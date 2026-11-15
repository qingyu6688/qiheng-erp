package cn.erp.invoicing;

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
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
class InvoiceIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired InvoiceService invoices;
    @Autowired CommandResults results;
    @Autowired BusinessCommands commands;
    private CompanyAccess scope;
    private long tenant,company,partner,period,ar;
    private static final LocalDate DATE=LocalDate.of(2026,11,1);

    @BeforeEach
    void prepare() {
        tenant=Ids.next();company=Ids.next();partner=Ids.next();period=Ids.next();ar=Ids.next();
        scope=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),Set.of("invoice_registration:void"));
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'开票测试')",tenant,"INVREG"+tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','公司A')",company,tenant);
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'P','测试客户')",partner,tenant);
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,'202611','2026-11-01','2026-11-30')",period,tenant,company);
        receivable(ar,"charge");
    }

    @AfterEach
    void cleanup() {
        for(String table:List.of("invoice_registration_line","invoice_registration","receivable","idempotency_record","audit_log","accounting_period","company","business_partner")) {
            jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);
        }
        jdbc.update("DELETE FROM tenant WHERE id=?",tenant);
    }

    @Test
    void partialInvoicesPreserveArTaxAndRecordDifferenceOnlyOnce() {
        var input=input("P1","40.00","4.61");
        var first=invoices.create(scope,"same-key",input);
        assertThat(invoices.create(scope,"same-key",input).get("id")).isEqualTo(first.get("id"));
        assertThat(first.get("taxDiffAmount")).isEqualTo("0.01");
        assertThat(first.get("partnerName")).isEqualTo("测试客户");
        assertThat(first.get("createdBy")).isEqualTo(Long.toString(scope.userId()));
        assertThat(invoiced()).isEqualByComparingTo("40");
        assertThat(jdbc.queryForObject("SELECT amount FROM receivable WHERE id=?",BigDecimal.class,ar)).isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("SELECT tax_amount FROM receivable WHERE id=?",BigDecimal.class,ar)).isEqualByComparingTo("11.5");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receivable WHERE tenant_id=?",Long.class,tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal WHERE tenant_id=?",Long.class,tenant)).isZero();
        assertThat(results.get(scope,"invoice_registration.create","same-key").get("status")).isEqualTo("succeeded");
        assertThat(invoices.create(scope,"rest",input("P2","60","6.90")).get("taxDiffAmount")).isEqualTo("0.00");
        assertThat(invoiced()).isEqualByComparingTo("100");
        assertError(()->invoices.create(scope,"extra",input("P3","0.01","0")),422);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invoice_registration WHERE tenant_id=?",Long.class,tenant)).isEqualTo(2);
    }

    @Test
    void voidReleasesInvoiceCapacityWithVersionAndIdempotencyProtection() {
        var first=invoices.create(scope,"create",input("P1","100","11.50"));
        long id=Long.parseLong(first.get("id").toString());
        assertError(()->invoices.voidInvoice(scope,id,1,"stale"),409);
        assertThat(invoiced()).isEqualByComparingTo("100");
        var voided=invoices.voidInvoice(scope,id,0,"void");
        assertThat(voided.get("status")).isEqualTo("voided");
        assertThat(invoices.voidInvoice(scope,id,0,"void").get("status")).isEqualTo("voided");
        assertThat(invoiced()).isEqualByComparingTo("0");
        assertError(()->invoices.voidInvoice(scope,id,1,"void-again"),409);
        invoices.create(scope,"replacement",input("P2","100","11.50"));
        assertThat(invoiced()).isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invoice_registration WHERE tenant_id=?",Long.class,tenant)).isEqualTo(2);
    }

    @Test
    void invalidSourcesAndAllocationTotalsRollbackAllChanges() {
        var mismatch=new InvoiceService.Input("sales",partner,"P1",DATE,"10","1",List.of(new InvoiceService.Line(ar,"11")),null);
        assertError(()->invoices.create(scope,"mismatch",mismatch),400);
        assertThat(invoiced()).isEqualByComparingTo("0");
        var duplicate=new InvoiceService.Input("sales",partner,"P1",DATE,"100","11.50",List.of(new InvoiceService.Line(ar,"50"),new InvoiceService.Line(ar,"50")),null);
        assertError(()->invoices.create(scope,"duplicate",duplicate),400);
        assertError(()->invoices.create(scope,"precision",input("P1","1.001","0")),400);
        assertError(()->invoices.create(scope,"tax",input("P1","1","2")),400);
        var wrongPartner=new InvoiceService.Input("sales",Ids.next(),"P1",DATE,"100","11.50",List.of(new InvoiceService.Line(ar,"100")),null);
        assertError(()->invoices.create(scope,"partner",wrongPartner),400);
        var outside=new CompanyAccess(tenant,scope.userId(),Ids.next(),true,Set.of(),Set.of());
        assertError(()->invoices.create(outside,"company",input("P1","100","11.50")),404);
        long credit=Ids.next();receivable(credit,"credit");
        var creditInput=new InvoiceService.Input("sales",partner,"P1",DATE,"100","11.50",List.of(new InvoiceService.Line(credit,"100")),null);
        assertError(()->invoices.create(scope,"credit",creditInput),400);
        assertThat(invoiced()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invoice_registration WHERE tenant_id=?",Long.class,tenant)).isZero();
    }

    @Test
    void invoiceQueriesEnforceCompanyAndOriginalOperator() {
        var first=invoices.create(scope,"own",input("P1","40","4.60"));
        long id=Long.parseLong(first.get("id").toString());
        var outside=new CompanyAccess(tenant,scope.userId(),Ids.next(),true,Set.of(),Set.of());
        assertError(()->invoices.get(outside,id),404);
        assertError(()->results.get(outside,"invoice_registration.create","own"),404);
        var other=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),Set.of());
        assertError(()->results.get(other,"invoice_registration.create","own"),403);
        assertError(()->invoices.create(scope,"own",input("P1","41","4.60")),409);
        assertThat(invoices.list(scope,null,1).get("items")).asList().hasSize(1);
        assertError(()->invoices.list(scope,null,0),400);
    }

    @Test
    void T14ConcurrentInvoicesCannotOverAllocateSameAr() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var ready=new CountDownLatch(1);
            var first=pool.submit(()->attempt(ready,"first"));
            var second=pool.submit(()->attempt(ready,"second"));
            ready.countDown();
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(invoiced()).isEqualByComparingTo("60");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invoice_registration WHERE tenant_id=?",Long.class,tenant)).isEqualTo(1);
    }

    private boolean attempt(CountDownLatch ready,String no) throws Exception {
        ready.await(5,TimeUnit.SECONDS);
        try {invoices.create(scope,no,input(no,"60","6.90"));return true;}
        catch(ApiException error) {if(!"INVOICE_AMOUNT_EXCEEDED".equals(error.getErrorCode())) {throw error;}return false;}
    }
    private InvoiceService.Input input(String no,String amount,String tax) {return new InvoiceService.Input("sales",partner,no,DATE,amount,tax,List.of(new InvoiceService.Line(ar,amount)),null);}
    private BigDecimal invoiced() {return jdbc.queryForObject("SELECT invoiced_amount FROM receivable WHERE id=?",BigDecimal.class,ar);}
    private void receivable(long id,String kind) {
        jdbc.update("""
            INSERT INTO receivable(id,tenant_id,company_id,customer_id,kind,source_type,source_id,source_line_id,amount,untaxed_amount,tax_amount,business_date,period_id)
            VALUES(?,?,?,?,?,'opening_entry',?,?,100,88.50,11.50,?,?)
            """,id,tenant,company,partner,kind,id,id,DATE,period);
    }
    private void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable work,int status) {assertThatThrownBy(work).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(status));}
}
