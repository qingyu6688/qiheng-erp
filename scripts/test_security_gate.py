import json
import unittest
from unittest.mock import Mock

from security_gate import code_findings, dependency_findings


class SecurityGateTest(unittest.TestCase):
    def report(self, body):
        file = Mock(name="安全报告")
        file.name = "report.json"
        file.read_text.return_value = json.dumps(body)
        return [file]

    def test_requires_scanned_packages(self):
        with self.assertRaises(ValueError):
            list(dependency_findings(self.report({"Results": [{}]})))

    def test_dependency_severity(self):
        item = {"VulnerabilityID": "TEST-1", "PkgName": "fixture", "InstalledVersion": "1.0"}
        def report(severity):
            return self.report({"Results": [{"Packages": [{"Name": "fixture"}],
                                              "Vulnerabilities": [{**item, "Severity": severity}]}]})
        self.assertEqual(list(dependency_findings(report("MEDIUM"))), [])
        self.assertEqual(len(list(dependency_findings(report("HIGH")))), 1)

    def test_code_scanning_high_and_medium(self):
        rules = [{"id": "high", "properties": {"security-severity": "7.0"}},
                 {"id": "medium", "properties": {"security-severity": "6.9"}}]
        runs = [{"tool": {"driver": {}, "extensions": [{"rules": rules}]},
                 "results": [{"ruleId": "high"}, {"ruleId": "medium"}]}]
        self.assertEqual(list(code_findings(self.report({"runs": runs}))), ["report.json: high"])
