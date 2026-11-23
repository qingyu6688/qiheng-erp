"""仅用合成元数据验证清单门槛，不创建或冒充真实业务历史。"""

import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import unittest

import gl_history_manifest as manifests
from local_test_storage import temporary_test_directory


def generation(stage):
    months = range(1, 2) if stage == "first" else range(2, 13)
    start = 10_000 if stage == "first" else 20_000
    return {"stage": stage, "complete": True, "targetPerPeriod": 100000,
            "tenantId": "100", "companyId": "101", "tenantCode": "LOAD-000000000030-0",
            "periods": [{"id": 2000 + month, "period": f"2025{month:02d}",
                         "confirmedReceipts": 50000, "confirmedPayments": 50000,
                         "journals": 100000, "deliveredLedger": 100000, "deliveredGl": 100000,
                         "posted": 100000, "lines": 200000, "balances": 3,
                         "mismatchedLines": 0, "debit": "150000.00", "credit": "150000.00"}
                        for month in months],
            "postVouchers": [{"id": str(start + index), "version": 2} for index in range(100)]}


def expected():
    return {"dataset": "synthetic-manifest", "sourceCommit": "a" * 40, "artifactSha256": "b" * 64,
            "environmentSha256": "c" * 64, "tenantCode": "LOAD-000000000030-0", "startPeriod": "202501"}


class HistoryManifestTests(unittest.TestCase):
    @staticmethod
    def invoke(root, target):
        command = [sys.executable, "-B", str(Path(manifests.__file__)), "--phase", "month01", "--generation", str(root / "first.json"),
                   "--settings", str(root / "settings.env"), "--run-file", str(root / "run.json"),
                   "--environment-record", str(root / "environment.json"), "--artifact", str(root / "candidate.jar"), "--output", str(target)]
        return subprocess.run(command, capture_output=True, text=True, encoding="utf-8", check=False,
                              env={**os.environ, "PYTHONIOENCODING": "utf-8"})

    def test_first_and_last_preserve_exact_ids_and_current_phase_samples(self):
        first, later = generation("first"), generation("remaining")
        first["periods"][0]["id"] = 250572110819360768
        head = manifests.build_manifest(first, None, "month01", expected())
        full = manifests.build_manifest(later, first, "month12", expected())
        self.assertEqual("250572110819360768", head["periods"][0]["id"])
        self.assertEqual(12, len(full["periods"]))
        self.assertEqual(later["postVouchers"], full["postVouchers"])
        self.assertEqual(first["postVouchers"], head["postVouchers"])

    def test_partial_calibration_or_smoke_cannot_become_formal(self):
        for key, value in (("complete", False), ("complete", 1), ("targetPerPeriod", 2),
                           ("targetPerPeriod", 100000.0), ("stage", "smoke")):
            with self.subTest(key=key, value=value):
                data = generation("first")
                data[key] = value
                with self.assertRaises(ValueError):
                    manifests.build_manifest(data, None, "month01", expected())

    def test_missing_or_mismatched_business_consumer_and_gl_facts_are_rejected(self):
        for key, value in (("confirmedReceipts", 49999), ("confirmedPayments", 49999),
                           ("journals", 99999), ("deliveredLedger", 99999), ("deliveredGl", 99999),
                           ("posted", 99999), ("lines", 199999), ("balances", 0), ("balances", True),
                           ("mismatchedLines", 1), ("mismatchedLines", False), ("debit", "150000.01"),
                           ("credit", 150000.0), ("id", 250572110819360768.0)):
            with self.subTest(key=key, value=value):
                data = generation("first")
                data["periods"][0][key] = value
                with self.assertRaises(ValueError):
                    manifests.build_manifest(data, None, "month01", expected())

    def test_wrong_company_repeated_periods_and_nonconsecutive_months_are_rejected(self):
        for mutate in (lambda x: x.update(companyId="999"),
                       lambda x: x["periods"][0].update(id=2001),
                       lambda x: x["periods"][0].update(period="202504")):
            with self.subTest(mutate=mutate):
                later = generation("remaining")
                mutate(later)
                with self.assertRaises(ValueError):
                    manifests.build_manifest(later, generation("first"), "month12", expected())

    def test_formal_run_and_start_month_must_match(self):
        for field, value in (("tenantCode", "LOAD-111111111111-0"), ("startPeriod", "202502")):
            identity = expected()
            identity[field] = value
            with self.assertRaises(ValueError):
                manifests.build_manifest(generation("first"), None, "month01", identity)

    def test_posting_samples_need_one_hundred_unique_realistic_ids_and_versions(self):
        for mutate in (lambda x: x["postVouchers"].pop(),
                       lambda x: x["postVouchers"][1].update(id=x["postVouchers"][0]["id"]),
                       lambda x: x["postVouchers"][0].update(version=True),
                       lambda x: x["postVouchers"][0].update(id=1234)):
            data = generation("first")
            mutate(data)
            with self.assertRaises(ValueError):
                manifests.build_manifest(data, None, "month01", expected())

    def test_last_phase_cannot_reuse_first_posting_samples(self):
        first, later = generation("first"), generation("remaining")
        later["postVouchers"] = copy.deepcopy(first["postVouchers"])
        with self.assertRaisesRegex(ValueError, "复用"):
            manifests.build_manifest(later, first, "month12", expected())

    def test_phase_inputs_cannot_be_swapped_or_omit_first_generation(self):
        for current, first, phase in ((generation("remaining"), None, "month01"),
                                      (generation("first"), generation("first"), "month01"),
                                      (generation("remaining"), None, "month12")):
            with self.assertRaises(ValueError):
                manifests.build_manifest(current, first, phase, expected())

    def files(self):
        temp = temporary_test_directory("gl-history-manifest")
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        artifact = root / "candidate.jar"
        artifact.write_bytes(b"synthetic-test-candidate")
        sha = manifests.digest(artifact)
        environment = root / "environment.json"
        environment.write_text(json.dumps({"sourceCommit": "a" * 40, "artifactSha256": sha}), encoding="utf-8")
        env_sha = manifests.digest(environment)
        settings = {"DB_HOST": "127.0.0.1", "TEST_DB_NAME": "erp_load_o30_unit", "ERP_O30_RUN": "000000000030",
                    "ERP_O30_SOURCE": "a" * 40, "ERP_O30_DATASET": "synthetic-manifest", "ERP_O30_START": "2025-01",
                    "ERP_O30_ARTIFACT": sha, "ERP_O30_ENVIRONMENT": env_sha}
        (root / "settings.env").write_text("\n".join(f"{key}={value}" for key, value in settings.items()), encoding="utf-8")
        run = {"runId": "000000000030", "database": "erp_load_o30_unit", "artifactSha256": sha}
        (root / "run.json").write_text(json.dumps(run), encoding="utf-8")
        (root / "first.json").write_text(json.dumps(generation("first")), encoding="utf-8")
        return root, settings, run, json.loads(environment.read_text(encoding="utf-8")), sha, env_sha

    def test_real_file_hashes_run_database_and_source_must_match(self):
        root, settings, run, environment, sha, env_sha = self.files()
        self.assertEqual(expected()["sourceCommit"], manifests.identity(settings, run, environment, sha, env_sha)["sourceCommit"])
        for index in range(6):
            config, state, record = copy.deepcopy((settings, run, environment))
            values = [config, state, record, sha, env_sha]
            if index == 0:
                values[3] = "d" * 64
            elif index == 1:
                values[4] = "d" * 64
            elif index == 2:
                state["runId"] = "111111111111"
            elif index == 3:
                state["database"] = "erp_prod"
            elif index == 4:
                record["sourceCommit"] = "d" * 40
            else:
                config["TEST_DB_NAME"] = "erp_load_o30_other"
            with self.subTest(index=index), self.assertRaises(ValueError):
                manifests.identity(*values)

    def test_cli_creates_exclusively_and_records_input_hash_without_claiming_acceptance(self):
        root, *_ = self.files()
        target = root / "manifest.json"
        result = self.invoke(root, target)
        self.assertEqual(0, result.returncode, result.stderr)
        data = json.loads(target.read_text(encoding="utf-8"))
        self.assertEqual(manifests.digest(root / "first.json"), data["generationSha256"])
        self.assertNotIn("passed", data)
        original = target.read_bytes()
        repeated = self.invoke(root, target)
        self.assertEqual(2, repeated.returncode)
        self.assertEqual(original, target.read_bytes())

    def test_cli_refuses_partial_generation_without_leaving_output(self):
        root, *_ = self.files()
        data = generation("first")
        data["complete"] = False
        (root / "first.json").write_text(json.dumps(data), encoding="utf-8")
        target = root / "manifest.json"
        result = self.invoke(root, target)
        self.assertEqual(2, result.returncode)
        self.assertIn("十万", result.stderr)
        self.assertFalse(target.exists())


if __name__ == "__main__":
    unittest.main()
