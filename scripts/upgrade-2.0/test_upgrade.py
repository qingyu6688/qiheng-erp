import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

from data import compare_scopes, normalize, permission_codes
from engine import UpgradeError, atomic_json, copy_objects, identifier, load_json, object_manifest
from upgrade import Upgrade


class UpgradeSafetyTest(unittest.TestCase):
    def test_object_path_and_digest_validation(self):
        with tempfile.TemporaryDirectory() as base:
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
        with tempfile.TemporaryDirectory() as folder:
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
        with tempfile.TemporaryDirectory() as folder:
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


if __name__ == "__main__":
    unittest.main()
