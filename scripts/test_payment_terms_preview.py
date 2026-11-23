"""到期日回填预览的只读边界与输出校验。"""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("preview", Path(__file__).with_name("preview-payment-terms.py"))
preview = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preview)


class PaymentTermsPreviewTest(unittest.TestCase):
    def test_query_only_reads_one_tenant_and_company(self):
        query = preview.preview_query(1, 2)
        for token in ["UPDATE ", "DELETE ", "INSERT ", "ALTER "]:
            self.assertNotIn(token, query.upper())
        self.assertEqual(query.count("r.tenant_id=1 AND r.company_id=2"), 2)
        self.assertIn("r.due_date IS NULL", query)

    def test_sql_keeps_transaction_open_for_review_and_preserves_existing_due_dates(self):
        row = {"fact": "receivable", "id": "3", "tenantId": "1", "companyId": "2",
               "version": 4, "dueDate": "2026-12-23"}
        sql = preview.render_sql([row, dict(row, id="5", dueDate=None)])
        self.assertEqual(sql.count("UPDATE "), 1)
        self.assertIn("tenant_id=1 AND company_id=2 AND id=3 AND version=4 AND due_date IS NULL", sql)
        self.assertNotIn("\nCOMMIT;", sql)

    def test_rejects_sql_injection_in_ids_and_dates(self):
        with self.assertRaises(ValueError):
            preview.positive("1 OR 1=1")
        with self.assertRaises(ValueError):
            preview.render_sql([{"fact": "receivable", "dueDate": "2026-01-01';DROP TABLE x"}])


if __name__ == "__main__":
    unittest.main()
