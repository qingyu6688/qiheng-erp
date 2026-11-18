import os
import unittest
from pathlib import Path
from unittest.mock import patch

from local_test_storage import temporary_test_directory
from s09_trade import Group, Observer, Paused, TYPES, isolated_directory, wait_postings


class Response:
    def __init__(self):
        self.seen = []
        self.fail_once = False

    def request(self, method, path, body=None, key=None):
        self.seen.append((path, key))
        if self.fail_once:
            self.fail_once = False
            raise TimeoutError("响应中断")
        return {"id": "88", "version": 0, "lines": []}


class TradeTest(unittest.TestCase):
    def setUp(self):
        self.temp = temporary_test_directory("s09-trade")
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)
        self.api = Response()
        self.now = 100.0

    def increase(self, delta):
        self.now += delta

    def group(self, who="独立公司"):
        return Group(self.path, {"company": who}, {"maker": self.api},
                     lambda: self.now, self.increase)

    def test_pace_and_resume_do_not_backfill_missed_slots(self):
        group = self.group()
        self.assertEqual(TYPES.count("sale"), 5)
        self.assertEqual(TYPES.count("purchase"), 1)
        for i in range(3):
            group.wait_slot(i)
        self.assertEqual(group.state["starts"], {"0": 100.0, "1": 110.0, "2": 120.0})
        self.increase(50)
        group = self.group()
        group.wait_slot(3)
        group.wait_slot(4)
        self.assertEqual(group.state["starts"]["3"], 170.0)
        self.assertEqual(group.state["starts"]["4"], 180.0)

    def test_lost_response_reuses_one_key_and_saved_response_skips_post(self):
        self.api.fail_once = True
        args = ("sale.create", "maker", "/sales-orders", {"version": 0})
        with self.assertRaises(TimeoutError):
            self.group().step(*args)
        self.group().step(*args)
        self.group().step(*args)
        self.assertEqual(len(self.api.seen), 2)
        self.assertEqual(self.api.seen[0], self.api.seen[1])
        with self.assertRaisesRegex(ValueError, "参数变化"):
            self.group().step(*args[:3], {"version": 1})

    def test_pause_precedes_next_write_and_wrong_company_refuses_resume(self):
        marker = self.path / "pause"
        marker.touch()
        with self.assertRaises(Paused):
            self.group().step("sale.submit", "maker", "/sales-orders/1/submit", {})
        self.assertEqual(self.api.seen, [])
        marker.unlink()
        self.group().step("sale.submit", "maker", "/sales-orders/1/submit", {})
        with self.assertRaisesRegex(ValueError, "身份不一致"):
            self.group("其他公司")

    def test_event_sources_cannot_be_shared_across_orders(self):
        orders = {str(i): {"type": kind, "cash": "7", "movement": "99"} for i,kind in enumerate(TYPES)}
        with self.assertRaisesRegex(ValueError,"唯一对应"):
            wait_postings(None, {}, orders)

    def test_root_accepts_only_existing_absolute_descendants(self):
        root = self.path / "isolated"
        folder = root / "evidence"
        folder.mkdir(parents=True)
        self.assertEqual(isolated_directory(root, folder, "证据目录"), folder.resolve())
        for invalid in (root, self.path, root / "missing", "relative"):
            with self.subTest(invalid=invalid):
                with self.assertRaises(ValueError):
                    isolated_directory(root, invalid, "证据目录")

    def test_database_scope_and_client_password_are_explicit(self):
        with patch.dict(os.environ, {"S09_DB_NAME":"erp_test_s09_unit","S09_DB_PORT":"3307",
                   "S09_OBSERVER_PASSWORD":"test-only","S09_DB_USER":"observer"}, clear=True):
            observer = Observer("E2E-123456789abc", self.path)
            with patch("s09_trade.subprocess.run") as command:
                command.return_value.returncode = 0
                command.return_value.stdout = b""
                command.return_value.stderr = b""
                observer.rows("SELECT 1")
                args, kwargs = command.call_args
                self.assertEqual(args[0][0], "mysql")
                self.assertIn("--user=observer", args[0])
                self.assertNotIn("test-only"," ".join(args[0]))
                self.assertEqual(kwargs["env"]["MYSQL_PWD"],"test-only")
                self.assertTrue(kwargs["input"].startswith(b"START TRANSACTION READ ONLY;"))
            del os.environ["S09_OBSERVER_PASSWORD"]
            with self.assertRaisesRegex(ValueError,"观察账号口令"):
                Observer("E2E-123456789abc", self.path)
            os.environ["S09_DB_NAME"]="erp_production"
            with self.assertRaisesRegex(ValueError,"测试库"):
                Observer("E2E-123456789abc", self.path)

    def test_database_identity_and_storage_are_checked_before_http_writes(self):
        data = self.path / "db"
        data.mkdir()
        row = ["1", "2", "3", "4", "5", "6", "7", "8", str(data), "9", "10", "3307", "erp_test_s09_unit"]
        with patch.dict(os.environ, {"S09_DB_NAME":row[-1], "S09_DB_PORT":"3307", "S09_OBSERVER_PASSWORD":"test-only"}):
            obs = Observer("E2E-123456789abc", self.path)
            with patch.object(obs, "rows", return_value=[row]):
                self.assertEqual(obs.fixture()["book"], "10")
                row[-2] = "3308"
                with self.assertRaisesRegex(ValueError, "隔离端口"):
                    obs.fixture()
                row[-2], row[8] = "3307", str(self.path.parent)
                with self.assertRaisesRegex(ValueError, "隔离根"):
                    obs.fixture()


if __name__ == "__main__":
    unittest.main()
