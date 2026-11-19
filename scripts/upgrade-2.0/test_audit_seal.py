import hashlib
import unittest

from audit_seal import GENESIS, verify
from engine import UpgradeError


class AuditSealTest(unittest.TestCase):
    def setUp(self):
        self.record_hash = "1" * 64
        self.first = self.batch(1, 9, GENESIS)
        self.second = self.batch(2, 3, self.first[3])

    def batch(self, number, row, previous):
        content = f"qiheng-audit-v1:12:{number}:{previous}\n{row}:{self.record_hash}\n"
        return [str(number), "1", previous, hashlib.sha256(content.encode()).hexdigest(),
                str(row), self.record_hash]

    def test_chain_commits_late_lower_record_and_checks_external_anchor(self):
        anchor = verify([self.first], 12)
        self.assertEqual(verify([self.first, self.second], 12, anchor)["batch"], 2)

    def test_rejects_changed_and_missing_rows(self):
        for digest in ("2" * 64, "NULL"):
            with self.subTest(digest=digest), self.assertRaises(UpgradeError):
                verify([[*self.first[:-1], digest]], 12)

    def test_rejects_deleted_tail_when_anchored(self):
        anchor = verify([self.first, self.second], 12)
        with self.assertRaises(UpgradeError):
            verify([self.first], 12, anchor)

    def test_rejects_gap_and_wrong_tenant(self):
        for rows, tenant in (([self.second], 12), ([self.first], 11)):
            with self.subTest(tenant=tenant), self.assertRaises(UpgradeError):
                verify(rows, tenant)
