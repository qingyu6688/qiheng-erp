"""用合成证据验证容量门槛，不连接数据库或 ERP。"""

import copy
import json
import subprocess
import sys
import unittest
from pathlib import Path, PureWindowsPath
from types import SimpleNamespace
from unittest.mock import patch

import gl_capacity as capacity
import local_test_storage as storage


def manifest(phase):
    periods = [{"id": str(2000 + month), "period": f"2026{month:02}"} for month in range(1, 13)]
    return {
        "dataset": "synthetic-gl-12",
        "sourceCommit": "a" * 40,
        "artifactSha256": "b" * 64,
        "environmentSha256": "c" * 64,
        "tenantId": "100",
        "companyId": "101",
        "tenantCode": "LOAD-000000000030-0",
        "phase": phase,
        "periods": periods[:1] if phase == "month01" else periods,
    }


def snapshot(value):
    return {
        "collectedAt": "2026-11-18T00:00:00+00:00",
        "identity": {"bookStatus": "active", "tenantCode": value["tenantCode"], "companyId": value["companyId"],
                     "databaseVersion": "8.0.46", "schemaVersion": 102, "flushLogAtCommit": 1, "syncBinlog": 1},
        "periods": [{**row, "businessPosted": 100000, "allPosted": 100000, "linkedBusinessPosted": 100000,
                     "voucherLines": 200000, "balanceRows": 200} for row in value["periods"]],
    }


def posting(phase):
    value = manifest(phase)
    start = 10000 if phase == "month01" else 20000
    samples = [{
        "voucherId": str(start + number), "elapsedMs": 10 if phase == "month01" else 11,
        "httpStatus": 200, "posted": True, "periodId": value["periods"][-1]["id"],
        "version": 2, "afterVersion": 3,
    } for number in range(100)]
    return {"kind": "posting", "manifest": value, "samples": samples,
            "before": snapshot(value), "after": snapshot(value)}


def evidence():
    first, last = posting("month01"), posting("month12")
    value = manifest("month12")
    queue = {
        "kind": "queue", "manifest": value, "before": snapshot(value), "after": snapshot(value),
        "intervalSeconds": 5, "steadySeconds": 60, "drainSeconds": 60,
        "samples": [{"phase": "steady" if number <= 12 else "drain", "elapsedSeconds": number * 5,
                     "created": min(number, 12) * 10, "done": min(number, 12) * 10,
                     "pending": 0, "processing": 0, "dead": 0, "oldestSeconds": 0} for number in range(25)],
    }
    reconciliation = {
        "kind": "reconciliation", "manifest": value, "before": snapshot(value), "after": snapshot(value),
        "checks": [{"type": kind, "id": str(900 + number), "status": "passed", "diffCount": 0, "differences": []}
                   for number, kind in enumerate(("gl_trial", "gl_operational"))],
    }
    return first, last, queue, reconciliation


class GlCapacityTests(unittest.TestCase):
    def assert_rejected(self, data, text):
        result = capacity.evaluate(*data, 2)
        self.assertFalse(result["passed"])
        self.assertTrue(any(text in error for error in result["errors"]), result["errors"])

    def test_complete_synthetic_evidence_passes_and_preserves_p95(self):
        result = capacity.evaluate(*evidence(), 2)
        self.assertTrue(result["passed"], result)
        self.assertEqual({"month01": 10, "month12": 11}, result["p95Ms"])

    def test_first_period_cannot_include_twelfth_period_history(self):
        data = evidence()
        data[0]["before"]["periods"].append(data[1]["before"]["periods"][-1])
        self.assert_rejected(data, "历史期间超过")

    def test_first_period_cannot_hide_future_manual_posting_history(self):
        data = evidence()
        future = copy.deepcopy(data[1]["before"]["periods"][-1])
        future.update({"businessPosted": 0, "allPosted": 100000, "linkedBusinessPosted": 0})
        data[0]["before"]["periods"].append(future)
        self.assert_rejected(data, "历史期间超过")

    def test_empty_or_unlinked_business_facts_fail(self):
        for field in ("businessPosted", "linkedBusinessPosted", "voucherLines", "balanceRows"):
            with self.subTest(field=field):
                data = evidence()
                data[1]["before"]["periods"][4][field] = 0
                self.assert_rejected(data, "样本不足")

    def test_thirteenth_period_or_skipped_month_is_rejected(self):
        data = evidence()
        data[1]["manifest"]["periods"][5]["period"] = "202607"
        with self.assertRaisesRegex(ValueError, "连续"):
            capacity.evaluate(*data, 2)

    def test_wrong_build_or_actual_durability_configuration_fails(self):
        data = evidence()
        data[1]["manifest"]["artifactSha256"] = "f" * 64
        self.assert_rejected(data, "身份不一致")
        data = evidence()
        data[2]["before"]["identity"]["flushLogAtCommit"] = 2
        self.assert_rejected(data, "持久化配置")

    def test_failed_post_stale_version_insufficient_samples_and_reuse_fail(self):
        variants = [("posted", False), ("httpStatus", 409), ("afterVersion", 2)]
        for key, value in variants:
            data = evidence()
            data[1]["samples"][0][key] = value
            self.assert_rejected(data, "采样不足")
        data = evidence()
        data[1]["samples"].pop()
        self.assert_rejected(data, "采样不足")
        data = evidence()
        data[1]["samples"][0]["voucherId"] = data[0]["samples"][0]["voucherId"]
        self.assert_rejected(data, "复用了")

    def test_p95_uses_raw_samples_and_enforces_growth_and_absolute_limit(self):
        data = evidence()
        for item in data[1]["samples"][-6:]:
            item["elapsedMs"] = 13
        self.assert_rejected(data, "P95")
        data = evidence()
        for phase in data[:2]:
            for item in phase["samples"]:
                item["elapsedMs"] = 1001
        self.assert_rejected(data, "1000")

    def test_empty_input_interruptions_and_backlog_cannot_pass(self):
        data = evidence()
        for item in data[2]["samples"]:
            item["created"] = item["done"] = 0
        self.assert_rejected(data, "实际连续")
        data = evidence()
        data[2]["samples"][4]["elapsedSeconds"] = 40
        self.assert_rejected(data, "采样存在中断")
        data = evidence()
        data[2]["samples"][-1]["pending"] = 1
        self.assert_rejected(data, "收尾")

    def test_persistent_growth_is_rejected_even_after_complete_drain(self):
        data = evidence()
        for number, sample in enumerate(data[2]["samples"][:13]):
            sample["pending"] = number // 2
            sample["oldestSeconds"] = number
        self.assert_rejected(data, "连续增长")

    def test_counter_reset_dead_letter_or_nonzero_reconciliation_fails(self):
        data = evidence()
        data[2]["samples"][8]["created"] = 0
        data[2]["samples"][8]["done"] = 0
        self.assert_rejected(data, "累计计数减少")
        data = evidence()
        data[2]["samples"][7]["dead"] = 1
        self.assert_rejected(data, "死信")
        data = evidence()
        data[3]["checks"][0]["diffCount"] = 1
        self.assert_rejected(data, "对账未通过")
        data = evidence()
        data[3]["after"]["periods"][0]["businessPosted"] += 1
        self.assert_rejected(data, "样本规模发生变化")

    def test_sql_uses_exact_scope_and_read_only_transaction_without_exposing_password(self):
        settings = {"DB_HOST": "127.0.0.1", "DB_PORT": "13300", "TEST_DB_USER": "readonly", "TEST_DB_PASSWORD": "private-test-value"}
        state = {"database": "erp_load_synthetic"}
        database = capacity.Database(settings, state, "mysql")
        self.assertNotIn(settings["TEST_DB_PASSWORD"], " ".join(database.command))
        self.assertEqual(settings["TEST_DB_PASSWORD"], database.environment["MYSQL_PWD"])
        with patch.object(capacity.subprocess, "run") as execute:
            execute.return_value.stdout = '{"created": 1, "done": 1}\n'
            result = database.rows(capacity.scoped_queue_sql(manifest("month12")))
            self.assertEqual([{"created": 1, "done": 1}], result)
            sql = execute.call_args.kwargs["input"]
            self.assertTrue(sql.startswith("SET SESSION TRANSACTION READ ONLY;"))
            self.assertEqual(2, sql.count("e.aggregate_type='gl_journal'"))
            self.assertIn("e.event_type='gl.journal.requested'", sql)
            self.assertIn("j.company_id=101", sql)
        with self.assertRaisesRegex(ValueError, "erp_load_"):
            capacity.Database(settings, {"database": "erp_test"}, "mysql")

    def test_file_overwrite_and_missing_manifest_fields_are_rejected(self):
        with storage.temporary_test_directory("gl-capacity-unit") as directory:
            target = Path(directory) / "evidence.json"
            capacity.write_json(target, {"value": 1})
            with self.assertRaises(FileExistsError):
                capacity.write_json(target, {"value": 2})
            self.assertEqual({"value": 1}, json.loads(target.read_text()))
        invalid = copy.deepcopy(manifest("month12"))
        invalid["tenantId"] = "100 OR 1=1"
        with self.assertRaisesRegex(ValueError, "十进制"):
            capacity.validate_manifest(invalid)

    def test_windows_test_storage_rejects_c_drive_after_resolving_directory_link(self):
        with patch.object(storage, "os", SimpleNamespace(name="nt", environ={})), patch.object(storage, "Path") as path:
            with self.assertRaisesRegex(ValueError, "ERP_TEST_ROOT"):
                storage.temporary_test_directory("gl-capacity-unit")
            path.return_value.resolve.return_value = PureWindowsPath("C:/output/evidence.json")
            with self.assertRaisesRegex(ValueError, "非 C 盘"):
                storage.checked_test_output("D:/linked/output/evidence.json")
            path.return_value.resolve.return_value = PureWindowsPath("D:/output/evidence.json")
            self.assertEqual(PureWindowsPath("D:/output/evidence.json"), storage.checked_test_output("D:/output/evidence.json"))

    def test_incomplete_queue_and_invalid_input_rate_are_rejected(self):
        data = evidence()
        del data[2]["samples"][0]["pending"]
        with self.assertRaisesRegex(ValueError, "队列证据字段"):
            capacity.evaluate(*data, 2)
        with self.assertRaisesRegex(ValueError, "大于0"):
            capacity.evaluate(*evidence(), 0)

    def test_collector_posts_original_versions_and_preserves_incomplete_request(self):
        value = manifest("month01")
        value["postVouchers"] = [{"id": str(3000 + i), "version": 2} for i in range(100)]
        api = FakeApi(value)
        raw = []
        result = capacity.post_samples(FakeDatabase(value), api, value, raw.append)
        self.assertEqual(100, len(result["samples"]))
        self.assertEqual(100, len(raw))
        self.assertTrue(all(row["posted"] and row["version"] == 2 and row["afterVersion"] == 3 for row in raw))
        self.assertEqual(100, len(api.idempotency_keys))
        self.assertEqual(100, len(set(api.idempotency_keys)))
        with patch.object(api, "request", side_effect=RuntimeError("此原始内容应保留在应用日志")):
            with self.assertRaises(RuntimeError):
                capacity.post_samples(FakeDatabase(value), api, value)
        api = FakeApi(value, fail_post=True)
        result = capacity.post_samples(FakeDatabase(value), api, value)
        self.assertEqual(1, len(result["samples"]))
        self.assertFalse(result["samples"][0]["posted"])
        self.assertEqual("SAMPLE_INCOMPLETE", result["samples"][0]["errorCode"])

    def test_collector_queue_records_scope_totals_and_full_drain(self):
        value = manifest("month12")
        database = FakeDatabase(value)
        times = iter([0, *range(0, 81, 5)])
        raw = []
        with patch.object(capacity.time, "monotonic", side_effect=lambda: next(times)), patch.object(capacity.time, "sleep"):
            result = capacity.watch_queue(database, value, 60, 15, 5, raw.append)
        self.assertEqual(16, len(result["samples"]))
        self.assertEqual(3, sum(item["phase"] == "drain" for item in raw))
        self.assertTrue(all("queryElapsedMs" in row for row in raw))
        self.assertEqual(7000, raw[0]["created"])
        self.assertEqual(120, raw[-1]["created"] - raw[0]["created"])
        self.assertTrue(all("j.company_id=101" in query for query in database.queue_queries))

    def test_reconciliation_collects_all_failed_difference_pages(self):
        class Api:
            def __init__(self):
                self.kind = None

            def request(self, method, path, value=None, key=None):
                if method == "POST":
                    self.kind = value["checkType"]
                    return {"status": 202, "data": {"id": "9901"}}
                if "/diffs" in path:
                    if self.kind == "gl_operational":
                        return {"status": 200, "data": {"items": [], "nextCursor": None}}
                    more = "&cursor=" not in path
                    return {"status": 200, "data": {
                        "items": [{"id": "909" if more else "908", "objectKey": "维度/金额差异"}],
                        "nextCursor": "908" if more else None,
                    }}
                return {"status": 200, "data": {
                    "status": "failed" if self.kind == "gl_trial" else "passed",
                    "diffCount": 2 if self.kind == "gl_trial" else 0,
                }}
        value = manifest("month12")
        raw = []
        result = capacity.reconcile(FakeDatabase(value), Api(), value, 10, raw.append)
        self.assertEqual(2, len(result["checks"]))
        self.assertEqual(2, len(result["checks"][0]["differences"]))
        self.assertEqual("failed", result["checks"][0]["status"])
        self.assertEqual(4, len(raw))

    def test_cli_preserves_success_and_failure_reports_without_database(self):
        with storage.temporary_test_directory("gl-capacity-unit") as directory:
            location = Path(directory)
            paths = [location / (key + ".json") for key in ("first", "twelfth", "queue", "reconciliation")]
            data = evidence()
            for path, value in zip(paths, data):
                capacity.write_json(path, value)
            command = [sys.executable, "-B", str(Path(capacity.__file__)), "evaluate", "--target-event-rate", "2"]
            for flag, path in zip(("--first", "--twelfth", "--queue", "--reconciliation"), paths):
                command.extend([flag, str(path)])
            passed = location / "passed.json"
            run = subprocess.run([*command, "--output", str(passed)], capture_output=True, check=False)
            self.assertEqual(0, run.returncode, run.stderr)
            self.assertEqual(4, len(capacity.read_json(passed)["evidenceSha256"]))
            data[0]["before"]["periods"] = []
            paths[0].write_text(json.dumps(data[0]), encoding="utf-8")
            failed = location / "failed.json"
            run = subprocess.run([*command, "--output", str(failed)], capture_output=True, check=False)
            self.assertEqual(1, run.returncode, run.stderr)
            self.assertFalse(capacity.read_json(failed)["passed"])


class FakeDatabase:
    def __init__(self, value):
        self.value = value
        self.sample = 0
        self.queue_queries = []

    def rows(self, statements):
        if "e.event_type=" in statements[0]:
            self.queue_queries.extend(statements)
            created = 7000 + min(self.sample, 12) * 10
            self.sample += 1
            return [{"created": created, "done": created},
                    {"pending": 0, "processing": 0, "dead": 0, "oldestSeconds": 0}]
        state = snapshot(self.value)
        return [state["identity"], *state["periods"]]


class FakeApi:
    def __init__(self, value, fail_post=False):
        self.value = value
        self.posted = set()
        self.fail_post = fail_post
        self.idempotency_keys = []

    def request(self, method, path, value=None, key=None):
        identifier = path.split("/")[3]
        if method == "POST":
            if self.fail_post:
                raise RuntimeError("网络中断，服务端是否已提交需要复核")
            self.posted.add(identifier)
            self.idempotency_keys.append(key)
            return {"status": 200, "data": {}, "errorCode": None}
        posted = identifier in self.posted
        return {"status": 200, "data": {
            "id": identifier, "source": "manual", "periodId": self.value["periods"][-1]["id"],
            "status": "posted" if posted else "approved", "version": 3 if posted else 2,
        }}


if __name__ == "__main__":
    unittest.main()
