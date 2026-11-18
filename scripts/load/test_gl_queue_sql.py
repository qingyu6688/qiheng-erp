"""使用内存夹具执行总账队列采集查询，验证事件范围和事务可见性。"""

import json
import sqlite3
import unittest
from uuid import uuid4

import gl_capacity as capacity


class QueueSqlTests(unittest.TestCase):
    def setUp(self):
        self.uri = f"file:gl_{uuid4().hex}?mode=memory&cache=shared"
        self.writer = self.connect()
        self.writer.executescript("""
            CREATE TABLE journal (id INTEGER, tenant_id INTEGER, company_id INTEGER);
            CREATE TABLE outbox_event (
                id INTEGER PRIMARY KEY, tenant_id INTEGER, aggregate_id INTEGER, aggregate_type TEXT,
                event_type TEXT, status TEXT, created_at INTEGER
            );
            INSERT INTO journal VALUES (10,7,31),(20,7,32),(30,8,31);
        """)
        self.reader = self.connect()
        self.reader.execute("PRAGMA query_only = ON")
        # 夹具时间均为微秒。查询保留 MySQL 的状态逻辑、时间运算和筛选条件。
        self.reader.create_function("UTC_TIMESTAMP", 1, lambda precision: 20_000_000)
        self.reader.create_function("TIMESTAMPDIFF", 3, lambda unit, start, end: float(end - start))
        self.reader.create_function("GREATEST", 2, max)
        self.reader.create_function("IF", 3, lambda condition, yes, no: yes if condition else no)
        self.scope = {"tenantId": 7, "companyId": 31}

    def connect(self):
        connection = sqlite3.connect(self.uri, uri=True, isolation_level=None)
        self.addCleanup(connection.close)
        return connection

    def event(self, identifier, *, journal=10, tenant=7, aggregate="gl_journal",
              event="gl.journal.requested", status="done", created_at=15_000_000, connection=None):
        (connection or self.writer).execute(
            "INSERT INTO outbox_event VALUES (?,?,?,?,?,?,?)",
            (identifier, tenant, journal, aggregate, event, status, created_at),
        )

    def observe(self, statements=None):
        queries = capacity.scoped_queue_sql(self.scope) if statements is None else statements
        # SQLite 要求把时间单位作为字符串传入函数。这个替换只适配 MySQL 的单位语法。
        return [json.loads(self.reader.execute(sql.replace(
            "TIMESTAMPDIFF(MICROSECOND,", "TIMESTAMPDIFF('MICROSECOND',")).fetchone()[0]) for sql in queries]

    def test_producer_and_ledger_events_are_counted_separately(self):
        self.event(1)
        self.event(2, status="processing", created_at=17_000_000)
        self.event(3, status="pending", created_at=18_000_000)
        self.event(4, status="dead")
        self.event(5, aggregate="journal", event="journal.posted", status="dead")
        self.event(6, aggregate="journal", status="dead")
        self.event(7, event="journal.posted", status="dead")
        totals, backlog = self.observe()
        self.assertEqual({"created": 4, "done": 1}, totals)
        self.assertEqual({"pending": 1, "processing": 1, "dead": 1, "oldestSeconds": 3}, backlog)

    def test_other_companies_tenants_and_orphaned_events_are_excluded(self):
        self.event(8, status="pending", created_at=19_750_000)
        self.event(9, journal=20, status="dead")
        self.event(10, journal=30, tenant=8, status="dead")
        self.event(11, journal=30, status="processing")
        self.event(12, journal=999, status="processing")
        totals, backlog = self.observe()
        self.assertEqual({"created": 1, "done": 0}, totals)
        self.assertEqual({"pending": 1, "processing": 0, "dead": 0, "oldestSeconds": .25}, backlog)

    def test_later_commit_with_earlier_id_contributes_to_observed_growth(self):
        late = self.connect()
        late.execute("BEGIN DEFERRED")
        earlier_id = 99
        self.event(101)
        self.event(200, journal=20)
        initial, _ = self.observe()
        self.event(earlier_id, status="pending", connection=late)
        late.execute("COMMIT")
        current, backlog = self.observe()
        self.assertEqual({"created": 1, "done": 1}, initial)
        self.assertEqual(1, current["created"] - initial["created"])
        self.assertEqual(initial["done"], current["done"])
        self.assertEqual(1, backlog["pending"])
        self.writer.execute("UPDATE outbox_event SET status='done' WHERE id=?", (earlier_id,))
        finished, drained = self.observe()
        self.assertEqual({"created": 2, "done": 2}, finished)
        self.assertEqual({"pending": 0, "processing": 0, "dead": 0, "oldestSeconds": 0}, drained)
