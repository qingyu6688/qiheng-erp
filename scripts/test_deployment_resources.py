import json
import shutil
import subprocess
import sys
import unittest
from pathlib import Path

from check_deployment_resources import ROOT, evaluate, linux_resources, read_inventory
from load.local_test_storage import temporary_test_directory


class DeploymentResourcesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.profiles = json.loads((ROOT / "deploy/resource-profiles.json").read_text())["profiles"]

    def test_compact_budget_requires_headroom_for_existing_services(self):
        inventory = {"cpuCores": 4, "totalMemoryMiB": 4096, "availableMemoryMiB": 2000, "freeDiskMiB": 50000}
        result = evaluate(self.profiles["compact-core"], inventory, 40000)
        self.assertFalse(result["budgetMet"])
        self.assertEqual([check["item"] for check in result["checks"] if not check["met"]], ["启动前可用内存 MiB"])

    def test_one_compact_host_does_not_satisfy_ha_search_budget(self):
        inventory = {"cpuCores": 2, "totalMemoryMiB": 4096, "availableMemoryMiB": 3072, "freeDiskMiB": 50000}
        self.assertTrue(evaluate(self.profiles["compact-core"], inventory, 40000)["budgetMet"])
        self.assertFalse(evaluate(self.profiles["ai-search"], inventory, 40000)["budgetMet"])

    def test_host_total_and_incremental_memory_are_checked_separately(self):
        inventory = {"cpuCores": 4, "totalMemoryMiB": 3700, "availableMemoryMiB": 3500, "freeDiskMiB": 50000}
        result = evaluate(self.profiles["compact-core"], inventory, 40000)
        self.assertFalse(result["budgetMet"])
        self.assertEqual([check["item"] for check in result["checks"] if not check["met"]], ["主机总内存 MiB"])

    def test_unknown_disk_budget_keeps_capacity_evidence_pending(self):
        inventory = {"cpuCores": 4, "totalMemoryMiB": 8192, "availableMemoryMiB": 7000, "freeDiskMiB": 50000}
        result = evaluate(self.profiles["modular"], inventory, None)
        self.assertTrue(result["budgetMet"])
        self.assertFalse(result["diskBudgetProvided"])
        self.assertIn("升级备份、恢复与对象仓的峰值空间预算", result["pending"])
        self.assertFalse(evaluate(self.profiles["modular"], inventory, 60000)["budgetMet"])

    def test_invalid_inventory_is_rejected(self):
        with temporary_test_directory("deployment-resources-unit") as temp:
            path = Path(temp) / "host.json"
            for memory in (float("nan"), float("inf"), True, -1, 9000):
                inventory = {"cpuCores": 4, "totalMemoryMiB": 8192, "availableMemoryMiB": memory, "freeDiskMiB": 50000}
                path.write_text(json.dumps(inventory), encoding="utf-8")
                with self.subTest(memory=memory), self.assertRaises(ValueError):
                    read_inventory(path)
            path.write_text("[]", encoding="utf-8")
            with self.assertRaises(ValueError):
                read_inventory(path)
            inventory["availableMemoryMiB"] = inventory["freeDiskMiB"] = 0
            path.write_text(json.dumps(inventory), encoding="utf-8")
            self.assertEqual(read_inventory(path)["availableMemoryMiB"], 0)

    def test_cgroup_limits_take_precedence_over_host_capacity(self):
        with temporary_test_directory("deployment-resources-unit") as temp:
            directory = Path(temp)
            files = {"cpu.max": "150000 100000", "cpuset.cpus.effective": "1-3,6",
                     "memory.max": str(4096 * 1024 * 1024), "memory.current": str(1536 * 1024 * 1024)}
            for name, value in files.items():
                (directory / name).write_text(value)
            self.assertEqual(linux_resources(16, 32768, 20000, directory), (1.5, 4096, 2560))

    def test_packaged_layout_runs_with_only_public_resources(self):
        with temporary_test_directory("deployment-resources-unit") as temp:
            target = Path(temp)
            (target / "scripts").mkdir()
            (target / "deploy").mkdir()
            shutil.copyfile(ROOT / "scripts/check_deployment_resources.py", target / "scripts/check_deployment_resources.py")
            shutil.copyfile(ROOT / "deploy/resource-profiles.json", target / "deploy/resource-profiles.json")
            inventory = {"cpuCores": 4, "totalMemoryMiB": 8192, "availableMemoryMiB": 7000, "freeDiskMiB": 100000}
            sample = target / "host.json"
            sample.write_text(json.dumps(inventory), encoding="utf-8")
            process = subprocess.run([
                sys.executable, "-B", "-X", "utf8", str(target / "scripts/check_deployment_resources.py"),
                "--profile", "modular", "--inventory", str(sample), "--required-free-disk-mib", "80000",
            ], capture_output=True, text=True, encoding="utf-8", check=False)
            self.assertEqual(process.returncode, 0, process.stderr)
            report = json.loads(process.stdout)
            self.assertTrue(report["budgetMet"])
            self.assertTrue(report["diskBudgetProvided"])
            self.assertNotIn(str(target), process.stdout)
