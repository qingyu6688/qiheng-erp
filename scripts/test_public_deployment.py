import unittest
from pathlib import Path
from unittest.mock import patch

from check_public_deployment import contains_public_address, has_private_versions, historical_addresses


class PublicDeploymentTest(unittest.TestCase):
    def test_single_node_template_leaves_optional_report_replica_disabled(self):
        template = Path(__file__).resolve().parents[1] / "deploy" / "production.env.example"
        settings = dict(
            line.split("=", 1)
            for line in template.read_text(encoding="utf-8").splitlines()
            if line.startswith("ERP_REPORT_")
        )
        for name in ("ERP_REPORT_JDBC_URL", "ERP_REPORT_DB_USER", "ERP_REPORT_DB_PASSWORD"):
            with self.subTest(name=name):
                self.assertEqual(settings[name], "")

    def test_private_paths_in_index_or_previous_commits_are_rejected(self):
        for output, expected in [([".local/pda-report.json\0"], True), (["", "fe83e13\n"], True), (["", ""], False)]:
            with self.subTest(output=output), patch("check_public_deployment.git", side_effect=output) as git:
                self.assertEqual(has_private_versions(), expected)
                self.assertIn(":(glob)**/.local/**", git.call_args_list[0].args)
                self.assertIn(":(exclude,glob)**/.env.example", git.call_args_list[0].args)

    def test_loopback_and_documentation_samples_are_allowed(self):
        for line in ["127.0.0.1:8080", "203.0.113.10", "http://[2001:db8::1]", "内网 192.168.1.1"]:
            with self.subTest(line=line):
                self.assertFalse(contains_public_address(line))

    def test_global_addresses_are_rejected(self):
        for line in ["服务地址8.8.8.8", "https://[2001:4860:4860::8888]:443"]:
            with self.subTest(line=line):
                self.assertTrue(contains_public_address(line))

    def test_flowable_schema_version_does_not_hide_an_address(self):
        self.assertFalse(contains_public_address("引擎schema.version=8.0.0.0"))
        self.assertTrue(contains_public_address("schema.version=8.0.0.0，服务地址 8.8.8.8"))

    def test_history_reports_location_without_reporting_address(self):
        lines = [
            "commit:12d1c712d1c712d1c712d1c712d1c712d1c712d1",
            "diff --git a/docs/部署.md b/docs/部署.md",
            "+++ b/docs/部署.md",
            "@@ -4,0 +5,2 @@",
            "+++ b/示例中引用的补丁",
            "+服务地址：8.8.8.8",
        ]
        self.assertEqual(
            list(historical_addresses(lines)),
            ["12d1c712d1c7 docs/部署.md:6 历史提交中包含公网地址"],
        )
