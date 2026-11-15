package cn.erp.accounting;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
class AccountingIT {
    private static final LocalDate DATE=LocalDate.of(2026,11,1);
    @Autowired JdbcTemplate jdbc;
    @Autowired LedgerService ledger;
    @Autowired SettlementService settlement;
    @Autowired RefundService refunds;
    @Autowired PlatformTransactionManager transactions;
    private CompanyAccess scope;
    private long tenant,company,partner,account,period;

    @BeforeEach
    void prepare() {
        tenant=Ids.next();company=Ids.next();partner=Ids.next();account=Ids.next();period=Ids.next();
        scope=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),Set.of());
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,?)",tenant,"ACC"+tenant,"结算测试");
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,?,?)",company,tenant,"A","公司A");
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,?,?)",partner,tenant,"P","往来单位");
        jdbc.update("INSERT INTO cash_account(id,tenant_id,company_id,code,name,account_type) VALUES(?,?,?,?,?,'bank')",account,tenant,company,"BANK","银行账户");
        jdbc.update("INSERT INTO cash_account_balance(id,tenant_id,company_id,cash_account_id) VALUES(?,?,?,?)",Ids.next(),tenant,company,account);
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,'202611','2026-11-01','2026-11-30')",period,tenant,company);
    }

    @AfterEach
    void cleanup() {
        for(String table:List.of("inbox_event","outbox_event","ledger_balance","journal_line","settlement_allocation","partner_open_item","refund_request","receipt","payment","receivable","payable","sales_order","audit_log")) {
            if(table.equals("inbox_event")) {jdbc.update("DELETE FROM inbox_event WHERE event_id IN(SELECT id FROM outbox_event WHERE tenant_id=?)",tenant);}
            else {jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);}
        }
        jdbc.update("UPDATE journal SET reverses_journal_id=NULL,posting_action='post' WHERE tenant_id=?",tenant);
        jdbc.update("DELETE FROM journal WHERE tenant_id=?",tenant);
        for(String table:List.of("cash_account_balance","cash_account","accounting_period","company","business_partner")) {jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);}
        jdbc.update("DELETE FROM tenant WHERE id=?",tenant);
    }

    @Test
    void ledgerIsBalancedSourceIdempotentAndUpdatesFundsExactlyOnce() {
        var posting=posting(Ids.next(),"100.00");
        long id=ledger.post(scope,posting);
        assertThat(ledger.post(scope,posting)).isEqualTo(id);
        assertThat(balance()).isEqualByComparingTo("100.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal WHERE tenant_id=?",Long.class,tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE tenant_id=? AND event_type='journal.posted'",Long.class,tenant)).isEqualTo(1);
        var changed=new LedgerService.Posting(posting.sourceType(),posting.sourceId(),0,period,DATE,List.of(line("FUNDS","D","101.00",account),line("OPENING_EQUITY","C","101.00",null)));
        assertThatThrownBy(()->ledger.post(scope,changed)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getErrorCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void insufficientFundsAndOuterFailureRollbackJournalLinesCashAndOutbox() {
        assertThatThrownBy(()->ledger.post(scope,new LedgerService.Posting("payment",Ids.next(),0,period,DATE,List.of(line("OTHER_EXPENSE","D","1.00",null),line("FUNDS","C","1.00",account)))))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getErrorCode()).isEqualTo("CASH_BALANCE_INSUFFICIENT"));
        assertThatThrownBy(()->new TransactionTemplate(transactions).executeWithoutResult(status->{ledger.post(scope,posting(Ids.next(),"50.00"));throw new IllegalStateException("模拟业务失败");})).isInstanceOf(IllegalStateException.class);
        assertThat(balance()).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal WHERE tenant_id=?",Long.class,tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE tenant_id=?",Long.class,tenant)).isZero();
    }

    @Test
    void reversalsSwapDirectionsOnlyOnceAndClosedPeriodIsRejected() {
        long original=ledger.post(scope,posting(Ids.next(),"30.00"));long source=Ids.next();
        long reversal=ledger.reverse(scope,original,"document_reversal",source,0,period,DATE);
        assertThat(ledger.reverse(scope,original,"document_reversal",source,0,period,DATE)).isEqualTo(reversal);
        assertThatThrownBy(()->ledger.reverse(scope,original,"document_reversal",Ids.next(),0,period,DATE)).isInstanceOf(ApiException.class);
        assertThat(balance()).isEqualByComparingTo("0.00");
        jdbc.update("UPDATE accounting_period SET status='closed',closed_at=UTC_TIMESTAMP(3) WHERE id=?",period);
        assertThatThrownBy(()->ledger.post(scope,posting(Ids.next(),"1.00"))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getErrorCode()).isEqualTo("PERIOD_CLOSED"));
    }

    @Test
    void T07ConcurrentSettlementForTwentyRoundsNeverUsesCreditTwice() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            for(int round=0;round<20;round++) {
                long debit1=receivable("charge","10.00"),debit2=receivable("charge","10.00"),credit=receivable("credit","10.00");
                var latch=new CountDownLatch(1);
                List<Callable<Boolean>> tasks=List.of(()->attemptAllocation(latch,debit1,credit),()->attemptAllocation(latch,debit2,credit));
                var first=pool.submit(tasks.get(0));var second=pool.submit(tasks.get(1));latch.countDown();
                assertThat((first.get(10,TimeUnit.SECONDS)?1:0)+(second.get(10,TimeUnit.SECONDS)?1:0)).isEqualTo(1);
                assertThat(open(credit)).isEqualByComparingTo("0.00");
                assertThat(open(debit1).add(open(debit2))).isEqualByComparingTo("10.00");
            }
        }
    }

    @Test
    void revokeRestoresBothSidesAndRejectsCrossCompanyOrSide() {
        long debit=receivable("charge","10.00"),credit=receivable("credit","10.00");
        long allocation=settlement.allocate(scope,debit,credit,new BigDecimal("4.00"),"manual",null,null);
        settlement.revoke(scope,allocation,"录入更正");settlement.revoke(scope,allocation,"录入更正");
        assertThat(open(debit)).isEqualByComparingTo("10.00");assertThat(open(credit)).isEqualByComparingTo("10.00");
        assertThat(jdbc.queryForObject("SELECT status FROM settlement_allocation WHERE id=?",String.class,allocation)).isEqualTo("revoked");
        var wrong=new CompanyAccess(tenant,scope.userId(),Ids.next(),true,Set.of(),Set.of());
        assertThatThrownBy(()->settlement.allocate(wrong,debit,credit,new BigDecimal("1.00"),"manual",null,null)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(404));
        long payable=payableCredit();
        assertThatThrownBy(()->settlement.allocate(scope,debit,payable,new BigDecimal("1.00"),"manual",null,null)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getErrorCode()).isEqualTo("SETTLEMENT_NOT_ALLOWED"));
    }

    @Test
    void refundReservationReleaseAndSuccessfulPaymentPreserveBothCaps() {
        long receipt=receipt("100.00"),credit=receivable("credit","45.83");
        long refund=refund(receipt,"45.83");
        refunds.reserve(scope,refund,List.of(credit));
        assertThat(open(credit)).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("SELECT refund_pending_amount FROM receipt WHERE id=?",BigDecimal.class,receipt)).isEqualByComparingTo("45.83");
        long impossible=refund(receipt,"10.00");
        assertThatThrownBy(()->refunds.reserve(scope,impossible,List.of(credit))).isInstanceOf(ApiException.class);
        refunds.release(scope,refund);refunds.release(scope,refund);
        assertThat(open(credit)).isEqualByComparingTo("45.83");
        assertThat(jdbc.queryForObject("SELECT refund_pending_amount FROM receipt WHERE id=?",BigDecimal.class,receipt)).isEqualByComparingTo("0.00");
        long success=refund(receipt,"45.83");refunds.reserve(scope,success,List.of(credit));
        assertThatThrownBy(()->refunds.approve(scope,success)).isInstanceOf(ApiException.class);
        var reviewer=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),Set.of());refunds.approve(reviewer,success);
        long journal=refunds.confirm(reviewer,success,period,DATE,"BANK-REFUND");
        assertThat(refunds.confirm(reviewer,success,period,DATE,"BANK-REFUND")).isEqualTo(journal);
        assertThat(balance()).isEqualByComparingTo("54.17");
        assertThat(jdbc.queryForObject("SELECT refunded_amount FROM receipt WHERE id=?",BigDecimal.class,receipt)).isEqualByComparingTo("45.83");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement_allocation WHERE trigger_id=? AND status='active'",Long.class,success)).isEqualTo(1);
        long paidAllocation=jdbc.queryForObject("SELECT id FROM settlement_allocation WHERE trigger_id=? AND status='active'",Long.class,success);
        assertThatThrownBy(()->settlement.revoke(scope,paidAllocation,"误操作")).isInstanceOf(ApiException.class);
        assertThat(open(credit)).isEqualByComparingTo("0.00");
    }

    @Test
    void validatesPrecisionDimensionsAndUnbalancedEntriesBeforeWriting() {
        for(String amount:List.of("0","-1","1.001","10000000000000000000000")) {
            assertThatThrownBy(()->line("FUNDS","D",amount,account)).isInstanceOf(ApiException.class);
        }
        var invalid=new LedgerService.Posting("test",Ids.next(),0,period,DATE,List.of(line("FUNDS","D","1.00",account),line("OPENING_EQUITY","C","2.00",null)));
        assertThatThrownBy(()->ledger.post(scope,invalid)).isInstanceOf(ApiException.class);
        var missingDimension=new LedgerService.Posting("test",Ids.next(),0,period,DATE,List.of(line("FUNDS","D","1.00",null),line("OPENING_EQUITY","C","1.00",null)));
        assertThatThrownBy(()->ledger.post(scope,missingDimension)).isInstanceOf(ApiException.class);
    }

    @Test
    void relatedOrderPrepaymentsAreAllocatedWithoutConsumingUnrelatedReceipts() {
        long order=Ids.next();
        jdbc.update("INSERT INTO sales_order(id,tenant_id,company_id,order_no,customer_id,business_date,price_tax_inclusive) VALUES(?,?,?,?,?,?,1)",order,tenant,company,"SO"+order,partner,DATE);
        long related=receipt("40.00");receipt("20.00");
        jdbc.update("UPDATE receipt SET related_order_id=? WHERE id=?",order,related);
        long debit=receivable("charge","100.00");
        assertThat(settlement.autoAllocateOrder(scope,order,debit)).isEqualByComparingTo("40.00");
        assertThat(settlement.autoAllocateOrder(scope,order,debit)).isEqualByComparingTo("0.00");
        assertThat(open(debit)).isEqualByComparingTo("60.00");
    }

    @Test
    void T16SharedPeriodLockPreventsClosingUntilPostingTransactionFinishes() throws Exception {
        var acquired=new CountDownLatch(1);var finish=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Future<?> post=pool.submit(()->new TransactionTemplate(transactions).executeWithoutResult(status->{
                ledger.lockOpenPeriod(scope,period,DATE);acquired.countDown();
                try {if(!finish.await(5,TimeUnit.SECONDS)) {throw new IllegalStateException("期间锁测试超时");}}
                catch(InterruptedException error) {Thread.currentThread().interrupt();throw new IllegalStateException("期间锁测试被中断",error);}
                ledger.post(scope,posting(Ids.next(),"10.00"));
            }));
            assertThat(acquired.await(5,TimeUnit.SECONDS)).isTrue();
            Future<?> close=pool.submit(()->new TransactionTemplate(transactions).executeWithoutResult(status->{
                jdbc.queryForObject("SELECT id FROM accounting_period WHERE id=? FOR UPDATE",Long.class,period);
                jdbc.update("UPDATE accounting_period SET status='closed',closed_at=UTC_TIMESTAMP(3) WHERE id=?",period);
            }));
            try {assertThatThrownBy(()->close.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}
            finally {finish.countDown();}
            post.get(5,TimeUnit.SECONDS);close.get(5,TimeUnit.SECONDS);
        }
        assertThat(balance()).isEqualByComparingTo("10.00");
        assertThatThrownBy(()->ledger.post(scope,posting(Ids.next(),"1.00"))).isInstanceOf(ApiException.class);
    }

    @Test
    void refundConsumesCreditsInSelectedAgingOrderRatherThanIdOrder() {
        long original=receipt("100.00"),newer=receivable("credit","10.00"),older=receivable("credit","10.00");
        long refund=refund(original,"5.00");
        refunds.reserve(scope,refund,List.of(older,newer));
        assertThat(open(older)).isEqualByComparingTo("5.00");
        assertThat(open(newer)).isEqualByComparingTo("10.00");
        refunds.release(scope,refund);
        assertThat(open(older)).isEqualByComparingTo("10.00");
    }

    private boolean attemptAllocation(CountDownLatch latch,long debit,long credit) throws Exception {
        latch.await(5,TimeUnit.SECONDS);
        try {settlement.allocate(scope,debit,credit,new BigDecimal("10.00"),"manual",null,null);return true;}
        catch(ApiException error) {if(!error.getErrorCode().equals("SETTLEMENT_AMOUNT_EXCEEDED")) {throw error;}return false;}
    }

    private LedgerService.Line line(String code,String direction,String amount,Long cash) {return new LedgerService.Line(code,direction,new BigDecimal(amount),null,cash,null,null,null,null);}
    private LedgerService.Posting posting(long source,String amount) {return new LedgerService.Posting("opening_entry",source,0,period,DATE,List.of(line("FUNDS","D",amount,account),line("OPENING_EQUITY","C",amount,null)));}
    private BigDecimal balance() {return jdbc.queryForObject("SELECT balance FROM cash_account_balance WHERE cash_account_id=?",BigDecimal.class,account);}
    private BigDecimal open(long id) {return jdbc.queryForObject("SELECT open_amount FROM partner_open_item WHERE id=?",BigDecimal.class,id);}

    private long receivable(String kind,String amount) {
        long id=Ids.next();
        jdbc.update("""
                INSERT INTO receivable(id,tenant_id,company_id,customer_id,kind,source_type,source_id,source_line_id,amount,untaxed_amount,tax_amount,business_date,period_id)
                VALUES(?,?,?,?,?,'opening_entry',?,?,?,?,0,?,?)
                """,id,tenant,company,partner,kind,id,id,new BigDecimal(amount),new BigDecimal(amount),DATE,period);
        return settlement.open(scope,new SettlementService.OpenItem("receivable",id,partner,kind.equals("charge")?"D":"C",new BigDecimal(amount),DATE));
    }

    private long payableCredit() {
        long id=Ids.next();
        jdbc.update("""
                INSERT INTO payable(id,tenant_id,company_id,supplier_id,kind,source_type,source_id,source_line_id,amount,untaxed_amount,tax_amount,business_date,period_id)
                VALUES(?,?,?,?,'charge','opening_entry',?,?,10,10,0,?,?)
                """,id,tenant,company,partner,id,id,DATE,period);
        return settlement.open(scope,new SettlementService.OpenItem("payable",id,partner,"C",new BigDecimal("10.00"),DATE));
    }

    private long receipt(String amount) {
        long id=Ids.next();
        jdbc.update("""
                INSERT INTO receipt(id,tenant_id,company_id,receipt_no,receipt_type,partner_id,cash_account_id,amount,business_date,status,confirmed_at,period_id)
                VALUES(?,?,?,?,'customer',?,?,?,?,'confirmed',UTC_TIMESTAMP(3),?)
                """,id,tenant,company,"RC"+id,partner,account,new BigDecimal(amount),DATE,period);
        ledger.post(scope,new LedgerService.Posting("receipt",id,0,period,DATE,List.of(line("FUNDS","D",amount,account),new LedgerService.Line("AR","C",new BigDecimal(amount),partner,null,null,null,null,null))));
        settlement.open(scope,new SettlementService.OpenItem("receipt",id,partner,"C",new BigDecimal(amount),DATE));
        return id;
    }

    private long refund(long receipt,String amount) {
        long id=Ids.next();
        jdbc.update("""
                INSERT INTO refund_request(id,tenant_id,company_id,refund_no,customer_id,original_receipt_id,cash_account_id,amount,business_date,request_key,reason,created_by)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,tenant,company,"RF"+id,partner,receipt,account,new BigDecimal(amount),DATE,"KEY"+id,"退货退款",scope.userId());
        return id;
    }
}
