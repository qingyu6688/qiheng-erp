package cn.erp.period;

import cn.erp.ErpApplication;
import cn.erp.accounting.LedgerService;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryService;
import cn.erp.shared.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import static cn.erp.inventory.InventoryModels.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
class PeriodIT {
    static final LocalDate DAY=LocalDate.of(2026,11,15);
    @Autowired JdbcTemplate jdbc;
    @Autowired PeriodService periods;
    @Autowired LedgerService ledger;
    @Autowired InventoryService inventory;
    @Autowired cn.erp.opening.OpeningService opening;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    long tenant,company,warehouse,sku,unit,period,account;
    CompanyAccess scope;

    @BeforeEach void seed() {
        tenant=Ids.next();company=Ids.next();warehouse=Ids.next();sku=Ids.next();unit=Ids.next();period=Ids.next();account=Ids.next();
        scope=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),Set.of("period:view","period:close","period:reopen"));
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'关账验收')",tenant,"PERIOD"+tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','关账公司')",company,tenant);
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'W','关账仓库')",warehouse,tenant,company);
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件')",unit,tenant);
        long category=Ids.next(),product=Ids.next();
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'C','分类','/')",category,tenant);
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'P','商品',?)",product,tenant,category,unit);
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name) VALUES(?,?,?,'S','关账商品')",sku,tenant,product);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,sku,unit);
        jdbc.update("INSERT INTO cash_account(id,tenant_id,company_id,code,name,account_type) VALUES(?,?,?,'A','关账账户','cash')",account,tenant,company);
        jdbc.update("INSERT INTO cash_account_balance(id,tenant_id,company_id,cash_account_id) VALUES(?,?,?,?)",Ids.next(),tenant,company,account);
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,'202611','2026-11-01','2026-11-30')",period,tenant,company);
    }
    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM inbox_event WHERE event_id IN(SELECT id FROM outbox_event WHERE tenant_id=?)",tenant);
        for(String table:List.of("outbox_event","ledger_balance","ledger_projection_lock","journal_line","journal","cost_adjustment","inventory_movement","inventory_cost","inventory_balance",
            "stock_adjustment_line","stock_adjustment","receipt","income_expense_item","document_reversal","audit_log","idempotency_record","document_sequence","cash_account_balance","cash_account","unit_conversion","sku","product","product_category","unit","warehouse","accounting_period","company")) {
            jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);
        }
        jdbc.update("DELETE FROM tenant WHERE id=?",tenant);
    }

    @Test void closesAndReopensWithVersionReasonIdempotencyAndAudit() {
        assertThat(periods.closeCheck(scope,period)).containsEntry("passed",true);
        String key=key();var closed=periods.close(scope,period,key,new PeriodService.Version(0L,null));
        assertThat(closed).containsEntry("status","closed");
        var replay=periods.close(scope,period,key,new PeriodService.Version(0L,null));
        assertThat(replay).containsEntry("id",closed.get("id")).containsEntry("status","closed").containsEntry("closedAt",closed.get("closedAt"));
        assertThat(((Number)replay.get("version")).longValue()).isEqualTo(1);
        assertCode(()->periods.reopen(scope,period,key(),new PeriodService.Version(1L," ")),"VALIDATION_FAILED");
        assertThat(periods.reopen(scope,period,key(),new PeriodService.Version(1L,"核对补录"))).containsEntry("status","open").containsEntry("reopenCount",1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND action IN('period.close','period.reopen')",Long.class,tenant)).isEqualTo(2);
        assertCode(()->periods.close(scope,period,key(),new PeriodService.Version(0L,null)),"VERSION_CONFLICT");
    }

    @Test void closesInOrderAndOnlyReopensMostRecentClosedPeriod() {
        long previous=Ids.next();jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,'202610','2026-10-01','2026-10-31')",previous,tenant,company);
        assertCode(()->periods.close(scope,period,key(),new PeriodService.Version(0L,null)),"PERIOD_ORDER_INVALID");
        periods.close(scope,previous,key(),new PeriodService.Version(0L,null));periods.close(scope,period,key(),new PeriodService.Version(0L,null));
        assertCode(()->periods.reopen(scope,previous,key(),new PeriodService.Version(1L,"补录")),"PERIOD_ORDER_INVALID");
        periods.reopen(scope,period,key(),new PeriodService.Version(1L,"补录"));
        assertThat(periods.reopen(scope,previous,key(),new PeriodService.Version(1L,"补录"))).containsEntry("status","open");
    }

    @Test void B15UnpostedDocumentsBlockClose() {
        jdbc.update("INSERT INTO stock_adjustment(id,tenant_id,company_id,adjust_no,warehouse_id,adjust_type,reason,business_date,status) VALUES(?,?,?,'SA',?,'opening','待审',?,'pending')",Ids.next(),tenant,company,warehouse,DAY);
        assertFailed("unposted_documents");
    }
    @Test void B15PendingMoneyBlocksClose() {
        jdbc.update("INSERT INTO receipt(id,tenant_id,company_id,receipt_no,receipt_type,income_item_id,cash_account_id,amount,business_date,status) VALUES(?,?,?,'RC','other_income',?,?,1,?,'pending_confirm')",
            Ids.next(),tenant,company,income(),account,DAY);
        assertFailed("pending_money");
    }
    @Test void B15InventoryDifferenceBlocksClose() {
        jdbc.update("INSERT INTO inventory_balance(id,tenant_id,company_id,warehouse_id,sku_id,on_hand_qty) VALUES(?,?,?,?,?,1)",Ids.next(),tenant,company,warehouse,sku);
        assertFailed("inventory_reconciliation");
    }
    @Test void B15MoneyDifferenceBlocksClose() {
        jdbc.update("UPDATE cash_account_balance SET balance=1 WHERE cash_account_id=?",account);assertFailed("money_reconciliation");
    }
    @Test void B15MissingCostResidueBlocksClose() {
        for(var values:List.of(List.of("1","10"),List.of("-1","-9"))) {
            jdbc.update("INSERT INTO inventory_movement(id,tenant_id,company_id,warehouse_id,sku_id,movement_type,on_hand_delta,cost_delta,source_type,source_id,source_line_id,business_date,period_id,posted_at) VALUES(?,?,?,?,?,'adjust_in',?,?,'stock_adjustment',?,?,?, ?,UTC_TIMESTAMP(3))",
                Ids.next(),tenant,company,warehouse,sku,new BigDecimal(values.get(0)),new BigDecimal(values.get(1)),Ids.next(),Ids.next(),DAY,period);
        }
        assertFailed("cost_residue");
    }

    @Test void closingProjectsCommittedJournalsOnceAndReplayDoesNotDoubleBalances() {
        stockPosting();periods.close(scope,period,key(),new PeriodService.Version(0L,null));
        assertThat(jdbc.queryForObject("SELECT SUM(debit_amount-credit_amount) FROM ledger_balance WHERE tenant_id=? AND account_code='INVENTORY'",BigDecimal.class,tenant)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbox_event WHERE tenant_id=? AND consumer='ledger_projection'",Long.class,tenant)).isEqualTo(1);
        periods.reopen(scope,period,key(),new PeriodService.Version(1L,"复核"));periods.close(scope,period,key(),new PeriodService.Version(2L,null));
        assertThat(jdbc.queryForObject("SELECT SUM(debit_amount-credit_amount) FROM ledger_balance WHERE tenant_id=? AND account_code='INVENTORY'",BigDecimal.class,tenant)).isEqualByComparingTo("10");
    }

    @Test void T16ConcurrentPostingCannotCrossClosedPeriodBarrier() throws Exception {
        var posted=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Future<?> posting=pool.submit(()->new TransactionTemplate(transactions).executeWithoutResult(status->{stockPosting();posted.countDown();try {if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("并发测试等待超时");}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException("并发测试被中断",error);}}));
            assertThat(posted.await(10,TimeUnit.SECONDS)).isTrue();
            Future<?> closing=pool.submit(()->{try {periods.close(scope,period,key(),new PeriodService.Version(0L,null));}catch(ApiException error){assertThat(error.getErrorCode()).isEqualTo("PERIOD_CLOSE_CHECK_FAILED");}});
            Thread.sleep(150);assertThat(closing.isDone()).isFalse();release.countDown();posting.get(10,TimeUnit.SECONDS);closing.get(10,TimeUnit.SECONDS);
            if("open".equals(jdbc.queryForObject("SELECT status FROM accounting_period WHERE id=?",String.class,period))) {periods.close(scope,period,key(),new PeriodService.Version(0L,null));}
            assertCode(this::stockPosting,"PERIOD_CLOSED");
            assertThat(inventory.balance(scope,new Key(warehouse,sku)).onHand()).isEqualByComparingTo("1");
        } finally {release.countDown();}
    }

    @Test void companyScopeAndCrossCompanyIdentifiersAreRejected() {
        var limited=new CompanyAccess(tenant,scope.userId(),company,false,Set.of(warehouse),scope.permissions());
        assertCode(()->periods.closeCheck(limited,period),"FORBIDDEN");
        assertCode(()->periods.closeCheck(new CompanyAccess(tenant,scope.userId(),Ids.next(),true,Set.of(),scope.permissions()),period),"NOT_FOUND");
    }

    @Test void T16SubmittedDocumentsAlsoParticipateInPeriodBarrier() throws Exception {
        long first=Long.parseLong(opening.createStock(scope,key(),new cn.erp.opening.OpeningService.Stock("opening",warehouse,DAY,"关账提交屏障",List.of(new cn.erp.opening.OpeningService.StockLine(sku,unit,"1","10")),null)).get("id").toString());
        long second=Long.parseLong(opening.createStock(scope,key(),new cn.erp.opening.OpeningService.Stock("opening",warehouse,DAY,"关账后不能提交",List.of(new cn.erp.opening.OpeningService.StockLine(sku,unit,"1","10")),null)).get("id").toString());
        var submitted=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Future<?> submit=pool.submit(()->new TransactionTemplate(transactions).executeWithoutResult(status->{
                opening.command(scope,true,first,"submit",key(),new cn.erp.opening.OpeningService.Version(0L,null));submitted.countDown();
                try {if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("提交屏障测试等待超时");}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException("提交屏障测试被中断",error);}
            }));
            assertThat(submitted.await(10,TimeUnit.SECONDS)).isTrue();
            Future<?> close=pool.submit(()->assertCode(()->periods.close(scope,period,key(),new PeriodService.Version(0L,null)),"PERIOD_CLOSE_CHECK_FAILED"));
            Thread.sleep(150);assertThat(close.isDone()).isFalse();release.countDown();submit.get(10,TimeUnit.SECONDS);close.get(10,TimeUnit.SECONDS);
            opening.command(scope,true,first,"void",key(),new cn.erp.opening.OpeningService.Version(1L,null));
            periods.close(scope,period,key(),new PeriodService.Version(0L,null));
            assertCode(()->opening.command(scope,true,second,"submit",key(),new cn.erp.opening.OpeningService.Version(0L,null)),"PERIOD_CLOSED");
            assertThat(opening.get(scope,true,second)).containsEntry("status","draft");
        } finally {release.countDown();}
    }

    private void stockPosting() {new TransactionTemplate(transactions).executeWithoutResult(status->{
        long source=Ids.next();ledger.lockOpenPeriod(scope,period,DAY);
        inventory.receive(scope,new Posting(new Key(warehouse,sku),new Source("stock_adjustment",source,source),DAY,period,"opening_in"),BigDecimal.ONE,new BigDecimal("10"));
        ledger.post(scope,new LedgerService.Posting("stock_adjustment",source,0,period,DAY,List.of(
            new LedgerService.Line("INVENTORY","D",new BigDecimal("10"),null,null,warehouse,sku,source,"关账并发入库"),
            new LedgerService.Line("OPENING_EQUITY","C",new BigDecimal("10"),null,null,null,null,source,"关账并发入库"))));
    });}
    private long income() {long item=Ids.next();jdbc.update("INSERT INTO income_expense_item(id,tenant_id,code,name,item_type) VALUES(?,?,'I','其他收入','income')",item,tenant);return item;}
    @SuppressWarnings("unchecked") private void assertFailed(String check) {
        var report=periods.closeCheck(scope,period);assertThat(report).containsEntry("passed",false);
        var items=(List<Map<String,Object>>)report.get("items");assertThat(items).filteredOn(item->check.equals(item.get("check"))).allMatch(item->Boolean.FALSE.equals(item.get("passed")));
        if(check.equals("unposted_documents")) {
            var documents=(List<Map<String,Object>>)items.stream().filter(item->check.equals(item.get("check"))).findFirst().orElseThrow().get("documents");
            assertThat(documents).anySatisfy(document->assertThat(document).containsEntry("documentType","stock_adjustment").containsEntry("documentNo","SA"));
        }
        assertCode(()->periods.close(scope,period,key(),new PeriodService.Version(0L,null)),"PERIOD_CLOSE_CHECK_FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM accounting_period WHERE id=?",String.class,period)).isEqualTo("open");
    }
    private static String key() {return UUID.randomUUID().toString();}
    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable work,String code) {assertThatThrownBy(work).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getErrorCode()).isEqualTo(code));}
}
