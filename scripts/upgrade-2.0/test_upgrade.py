import contextlib
import io
import os
import tempfile
import unittest
from pathlib import Path, PureWindowsPath
from types import SimpleNamespace
from unittest.mock import Mock, patch

from data import compare_scopes, normalize, permission_codes
from engine import (UpgradeError, atomic_json, checked_test_output, configured_test_root, copy_objects,
                    identifier, load_json, object_manifest, sha256)
from upgrade import Upgrade


def temporary_test_directory():
    root = configured_test_root(required_on_windows=True)
    directory = checked_test_output(root / "upgrade-unit" / "tmp") if root is not None else None
    if directory is not None:
        directory.mkdir(parents=True, exist_ok=True)
    return tempfile.TemporaryDirectory(dir=directory)


class UpgradeTestStorageTest(unittest.TestCase):
    def test_rejects_relative_root(self):
        with patch.dict(os.environ, {"ERP_TEST_ROOT": "relative-path"}):
            with self.assertRaisesRegex(UpgradeError, "绝对路径"):
                configured_test_root()

    def test_checks_drive_after_resolving_directory_link(self):
        directory = Mock(spec=Path)
        with patch("engine.os", SimpleNamespace(name="nt")):
            directory.resolve.return_value = PureWindowsPath("C:/test-data")
            with self.assertRaisesRegex(UpgradeError, "非 C 盘"):
                checked_test_output(directory)
            directory.resolve.return_value = PureWindowsPath("D:/test-data")
            self.assertEqual(PureWindowsPath("D:/test-data"), checked_test_output(directory))


class UpgradeSafetyTest(unittest.TestCase):
    def test_object_path_and_digest_validation(self):
        with temporary_test_directory() as base:
            work = Path(base)
            source = work / "source"
            child = source / "company" / "source.pdf"
            child.parent.mkdir(parents=True)
            child.write_bytes(b"%PDF-1.4\n%%EOF")
            manifest = object_manifest(source)
            copy_objects(source, work / "restored", manifest)
            self.assertEqual(manifest, object_manifest(work / "restored"))
            with self.assertRaisesRegex(UpgradeError, "禁止覆盖"):
                copy_objects(source, work / "restored", manifest)
            with self.assertRaisesRegex(UpgradeError, "路径"):
                copy_objects(source, work / "outside", {"../source.pdf": next(iter(manifest.values()))})
            child.write_bytes(b"changed")
            with self.assertRaisesRegex(UpgradeError, "摘要"):
                copy_objects(source, work / "changed", manifest)

    def test_scope_compares_document_identity_as_well_as_count(self):
        scope = {"tenantId": "1", "companyId": "2", "userId": "3", "active": True,
                 "allWarehouses": False, "warehouseIds": ["4"], "departments": "5",
                 "documents": {"sales_order": {"count": 2, "idDigest": "before"}}}
        changed = {**scope, "documents": {"sales_order": {"count": 2, "idDigest": "after"}}}
        self.assertTrue(compare_scopes([scope], [changed])["differences"])
        self.assertFalse(compare_scopes([scope], [scope])["differences"])
        with self.assertRaisesRegex(UpgradeError, "公司授权"):
            compare_scopes([scope], [{**scope, "tenantId": "9"}])

    def test_permission_codes_merge_without_losing_grants(self):
        mapping = permission_codes()
        self.assertEqual(["crm_statistic:view", "sales_order:view"],
                         normalize(["crm_statistics:view", "sales_order:view", "crm_statistic:view"], mapping))
        self.assertEqual(["crm_statistic:view"], normalize(["crm_statistics:view"], mapping))
        self.assertEqual(["sales_order:view"], normalize(["sales_order:view"], mapping))

    def test_identifiers_and_atomic_state(self):
        with self.assertRaisesRegex(UpgradeError, "对象名"):
            identifier("tmp`; DROP DATABASE erp")
        with temporary_test_directory() as folder:
            state = Path(folder) / "state.json"
            atomic_json(state, {"phase": "restored"})
            atomic_json(state, {"phase": "ready", "result": "已核验"})
            self.assertEqual({"phase": "ready", "result": "已核验"}, load_json(state))
            self.assertFalse(state.with_name("state.json.partial").exists())

    def test_rollback_refuses_to_discard_live_writes(self):
        task = Upgrade.__new__(Upgrade)
        task.state = {"phase": "active"}
        task.mysql = Mock()
        with self.assertRaisesRegex(UpgradeError, "停写窗口"):
            task.rollback()
        task.mysql.assert_not_called()

    def test_cutover_requires_scope_review_and_unchanged_checkpoint(self):
        with temporary_test_directory() as folder:
            task = Upgrade.__new__(Upgrade)
            task.work = Path(folder)
            task.state = {"phase": "ready", "handoffSha256": "", "readyPosition": {}}
            task.args, task.mysql = Mock(approve_scopes=False), Mock()
            with self.assertRaisesRegex(UpgradeError, "审阅"):
                task.finish()
            task.args.approve_scopes = True
            task.require_frozen = Mock()
            atomic_json(task.work / "handoff.json", {"database": "wrong"})
            with self.assertRaisesRegex(UpgradeError, "切换目标"):
                task.finish()
            task.mysql.execute.assert_not_called()


class UpgradeTimingTest(unittest.TestCase):
    def setUp(self):
        folder = temporary_test_directory()
        self.addCleanup(folder.cleanup)
        self.task = Upgrade.__new__(Upgrade)
        self.task.work = Path(folder.name)
        self.task.state = {"runId": "test-run", "startedAt": "2026-11-18T02:00:00+00:00",
                           "frozenAt": "2026-11-18T02:00:01+00:00"}

    def test_failed_attempt_and_retry_are_kept_without_business_error(self):
        with patch("upgrade.time.monotonic_ns", side_effect=[0, 25_000_000, 39_000_000, 56_000_000]):
            with self.assertRaisesRegex(UpgradeError, "模拟失败"):
                with self.task.measure("restore"):
                    raise UpgradeError("模拟失败，内部数据不应出现在耗时报告")
            with self.task.measure("restore"):
                pass
        result = load_json(self.task.work / "upgrade-timing.json")
        self.assertEqual([("restore", "failed", 25), ("restore", "success", 17)],
                         [(row["step"], row["status"], row["elapsedMs"]) for row in result["steps"]])
        self.assertNotIn("内部数据", str(result))
        self.assertNotIn("elapsedMs", result["maintenanceWindow"])

    def test_maintenance_window_includes_wait_between_commands(self):
        with self.task.measure("verification"):
            pass
        self.task.state["activatedAt"] = "2026-11-18T02:01:30.125+00:00"
        with self.task.measure("activation"):
            pass
        result = load_json(self.task.work / "upgrade-timing.json")
        self.assertEqual(90_125, result["maintenanceWindow"]["elapsedMs"])
        self.assertEqual("2026-11-18T02:00:01+00:00", result["maintenanceWindow"]["mysqlFrozenAt"])

    def test_finish_after_rollback_records_activation_in_same_window(self):
        objects = self.task.work / "objects"
        objects.mkdir()
        handoff = self.task.work / "handoff.json"
        atomic_json(handoff, {"database": "erp_rollback", "objectRoot": str(objects), "apiMajor": 1})
        self.task.state_path = self.task.work / "state.json"
        self.task.state.update(phase="rollback-ready", handoffSha256=sha256(handoff),
                               readyPosition={"position": 100}, handoffObjects={})
        self.task.require_frozen = Mock()
        self.task.mysql = Mock()
        self.task.mysql.position.return_value = self.task.state["readyPosition"]
        self.task.hook = Mock()
        self.task.finish()
        self.task.mysql.execute.assert_called_once_with("SET PERSIST offline_mode=OFF;")
        self.task.hook.assert_called_once_with("activate", handoff)
        self.assertEqual("rollback-active", load_json(self.task.state_path)["phase"])
        report = load_json(self.task.work / "upgrade-timing.json")
        self.assertEqual("rollback-activation", report["steps"][0]["step"])
        self.assertEqual(self.task.state["activatedAt"], report["maintenanceWindow"]["activationCompletedAt"])

    def test_flyway_timing_includes_history_after_original_source_even_after_retry(self):
        self.task.mysql = Mock()
        atomic_json(self.task.work / "frozen-preflight.json", {"schema": {"version": 97}})
        self.task.mysql.query.return_value = [
            ["98", "98", "V98__first.sql", "150", "1"],
            ["99", "99", "V99__next.sql", "25", "0"],
        ]
        self.task.record_flyway_timing("erp_candidate")
        query, database = self.task.mysql.query.call_args.args
        self.assertIn("CAST(version AS UNSIGNED)>97", query)
        self.assertIn("ORDER BY installed_rank", query)
        self.assertEqual("erp_candidate", database)
        report = load_json(self.task.work / "flyway-timing.json")
        self.assertEqual(97, report["sourceVersion"])
        self.assertEqual([("98", "success", 150), ("99", "failed", 25)],
                         [(row["version"], row["status"], row["elapsedMs"]) for row in report["migrations"]])

    def test_metrics_failure_does_not_mask_original_migration_error(self):
        self.task.java = Mock(side_effect=UpgradeError("原迁移错误"))
        self.task.mysql = Mock()
        atomic_json(self.task.work / "frozen-preflight.json", {"schema": {"version": 87}})
        self.task.mysql.query.side_effect = UpgradeError("内部连接信息")
        warnings = io.StringIO()
        with contextlib.redirect_stderr(warnings), self.assertRaisesRegex(UpgradeError, "原迁移错误"):
            self.task.migrate("erp_candidate", self.task.work)
        self.assertIn("未能记录 Flyway 耗时", warnings.getvalue())
        self.assertNotIn("内部连接信息", warnings.getvalue())
        self.assertEqual("failed", load_json(self.task.work / "upgrade-timing.json")["steps"][0]["status"])


if __name__ == "__main__":
    unittest.main()
