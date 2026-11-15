package cn.erp.accounting;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.jobs.OutboxDelivery;
import cn.erp.jobs.OutboxService;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
class AccountingProjectionIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired LedgerService ledger;
    @Autowired OutboxService outbox;
    @Autowired OutboxDelivery delivery;
    @Autowired PlatformTransactionManager transactions;
    private long tenant,company,period;
    private CompanyAccess scope;

    @BeforeEach
    void prepare() {
        tenant=Ids.next();company=Ids.next();period=Ids.next();
        scope=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),Set.of());
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,?)",tenant,"PROJ"+tenant,"投影测试");
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,?,?)",company,tenant,"A","公司A");
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,'202611','2026-11-01','2026-11-30')",period,tenant,company);
    }

    @AfterEach
    void cleanup() {
        for(String table:List.of("inbox_event","outbox_event","ledger_projection_lock","ledger_balance","journal_line","journal","accounting_period","company")) {
            jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);
        }
        jdbc.update("DELETE FROM tenant WHERE id=?",tenant);
    }

    @Test
    void realDeliveryProjectsEveryDimensionAndInboxReplayDoesNotDoubleAdd() {
        long journal=ledger.post(scope,posting("12.34"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_balance WHERE tenant_id=?",Long.class,tenant)).isZero();
        OutboxService.Event event=claimOwn();
        assertThat(event.aggregateId()).isEqualTo(journal);
        assertThat(delivery.deliver(event)).isTrue();
        assertBalances("12.34",journal);
        assertThat(delivery.deliver(event)).isFalse();
        // 模拟事件确认结果丢失后的再投递，Inbox 与余额副作用必须同事务去重。
        jdbc.update("UPDATE outbox_event SET status='pending',next_attempt_at='1000-01-01',lease_owner=NULL,lease_until=NULL WHERE tenant_id=? AND id=?",tenant,event.id());
        OutboxService.Event duplicate=claimOwn();
        assertThat(delivery.deliver(duplicate)).isTrue();
        assertBalances("12.34",journal);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbox_event WHERE tenant_id=? AND consumer='ledger_projection'",Long.class,tenant)).isEqualTo(1);
    }

    @Test
    void smallerJournalIdCommittedLaterIsStillProjectedAfterLargerId() throws Exception {
        var beforeCommit=new CountDownLatch(1);var allowCommit=new CountDownLatch(1);
        long[] earlyId=new long[1];
        try(var pool=Executors.newSingleThreadExecutor()) {
            Future<?> pending=pool.submit(()->new TransactionTemplate(transactions).executeWithoutResult(status->{
                earlyId[0]=ledger.post(scope,posting("7.00"));beforeCommit.countDown();
                try {if(!allowCommit.await(10,TimeUnit.SECONDS)) {throw new IllegalStateException("投影乱序测试等待超时");}}
                catch(InterruptedException error) {Thread.currentThread().interrupt();throw new IllegalStateException("投影乱序测试被中断",error);}
            }));
            try {
                assertThat(beforeCommit.await(5,TimeUnit.SECONDS)).isTrue();
                long laterId=ledger.post(scope,posting("3.00"));
                assertThat(laterId).isGreaterThan(earlyId[0]);
                OutboxService.Event later=claimOwn(laterId);
                assertThat(later.aggregateId()).isEqualTo(laterId);
                assertThat(delivery.deliver(later)).isTrue();
                assertBalances("3.00",laterId);
                allowCommit.countDown();pending.get(5,TimeUnit.SECONDS);
                OutboxService.Event early=claimOwn();
                assertThat(early.aggregateId()).isEqualTo(earlyId[0]);
                assertThat(delivery.deliver(early)).isTrue();
                assertBalances("10.00",laterId);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbox_event WHERE tenant_id=? AND consumer='ledger_projection'",Long.class,tenant)).isEqualTo(2);
            } finally {allowCommit.countDown();}
        }
    }

    @Test
    void earlierPeriodArrivingLateUpdatesLaterOpeningWithoutCountingPendingEvents() {
        long earlier=Ids.next();
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,'202610','2026-10-01','2026-10-31')",earlier,tenant,company);
        long november=ledger.post(scope,posting("3.00"));
        long october=ledger.post(scope,postingFor(earlier,LocalDate.of(2026,10,1),"7.00"));
        assertThat(delivery.deliver(claimOwn(november))).isTrue();
        assertPeriodBalance(period,"0.00","3.00","3.00");
        assertThat(delivery.deliver(claimOwn(october))).isTrue();
        assertPeriodBalance(earlier,"0.00","7.00","7.00");
        assertPeriodBalance(period,"7.00","3.00","10.00");

        long pendingOctober=ledger.post(scope,postingFor(earlier,LocalDate.of(2026,10,2),"2.00"));
        long moreNovember=ledger.post(scope,posting("5.00"));
        assertThat(delivery.deliver(claimOwn(moreNovember))).isTrue();
        assertPeriodBalance(period,"7.00","8.00","15.00");
        assertThat(delivery.deliver(claimOwn(pendingOctober))).isTrue();
        assertPeriodBalance(earlier,"0.00","9.00","9.00");
        assertPeriodBalance(period,"9.00","8.00","17.00");
    }

    private void assertPeriodBalance(long periodId,String opening,String debit,String closing) {
        var row=jdbc.queryForMap("SELECT opening_balance,debit_amount,closing_balance FROM ledger_balance WHERE tenant_id=? AND period_id=? AND account_code='OTHER_EXPENSE'",tenant,periodId);
        assertThat((BigDecimal)row.get("opening_balance")).isEqualByComparingTo(opening);
        assertThat((BigDecimal)row.get("debit_amount")).isEqualByComparingTo(debit);
        assertThat((BigDecimal)row.get("closing_balance")).isEqualByComparingTo(closing);
    }

    private OutboxService.Event claimOwn(long... journalIds) {
        // 优先领取本测试的事件；若有并发运行的任务抢先，外层事务断言会回滚其租约。
        return new TransactionTemplate(transactions).execute(status->{
            // 先读已提交事件的主键，再做精确更新，不能扫描并锁住刻意延迟提交的事件。
            var ids=journalIds.length==0
                    ? jdbc.queryForList("SELECT id FROM outbox_event WHERE tenant_id=? AND status='pending' ORDER BY id LIMIT 1",Long.class,tenant)
                    : jdbc.queryForList("SELECT id FROM outbox_event WHERE tenant_id=? AND aggregate_type='journal' AND aggregate_id=? AND status='pending'",Long.class,tenant,journalIds[0]);
            assertThat(ids).hasSize(1);
            jdbc.update("UPDATE outbox_event SET next_attempt_at='1000-01-01' WHERE id=?",ids.getFirst());
            var events=outbox.claim(1);
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().tenantId()).isEqualTo(tenant);
            return events.getFirst();
        });
    }

    private LedgerService.Posting posting(String amount) {
        return postingFor(period,LocalDate.of(2026,11,1),amount);
    }

    private LedgerService.Posting postingFor(long periodId,LocalDate date,String amount) {
        return new LedgerService.Posting("test_projection",Ids.next(),0,periodId,date,List.of(
                new LedgerService.Line("OTHER_EXPENSE","D",new BigDecimal(amount),null,null,null,null,null,null),
                new LedgerService.Line("OPENING_EQUITY","C",new BigDecimal(amount),null,null,null,null,null,null)));
    }

    private void assertBalances(String total,long watermark) {
        var expense=jdbc.queryForMap("SELECT debit_amount,credit_amount,closing_balance,projected_journal_id FROM ledger_balance WHERE tenant_id=? AND company_id=? AND period_id=? AND account_code='OTHER_EXPENSE' AND partner_key=0 AND cash_account_key=0 AND warehouse_key=0",tenant,company,period);
        assertThat((BigDecimal)expense.get("debit_amount")).isEqualByComparingTo(total);
        assertThat((BigDecimal)expense.get("credit_amount")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal)expense.get("closing_balance")).isEqualByComparingTo(total);
        assertThat(((Number)expense.get("projected_journal_id")).longValue()).isEqualTo(watermark);
        assertThat(jdbc.queryForObject("SELECT credit_amount FROM ledger_balance WHERE tenant_id=? AND account_code='OPENING_EQUITY'",BigDecimal.class,tenant)).isEqualByComparingTo(total);
    }
}
