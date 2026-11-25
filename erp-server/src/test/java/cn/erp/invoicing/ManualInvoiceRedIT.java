package cn.erp.invoicing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.CommandResults;
import cn.erp.shared.Ids;
import cn.erp.shared.OrgScope;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = ErpApplication.class)
@ActiveProfiles("test")
class ManualInvoiceRedIT {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 1);
    private static final Set<String> PERMISSIONS = Set.of("invoice_registration:view", "invoice_registration:void",
            "invoice_red:view", "invoice_red:create", "invoice_red:confirm", "invoice_red:cancel");
    @Autowired JdbcTemplate jdbc;
    @Autowired InvoiceService invoices;
    @Autowired ManualInvoiceRedService reds;
    @Autowired CommandResults results;
    @Autowired tools.jackson.databind.ObjectMapper json;
    private long tenant, company, partner, period, ar, maker, reviewer;
    private CompanyAccess author, checker;

    @BeforeEach void setup() {
        tenant = Ids.next(); company = Ids.next(); partner = Ids.next(); period = Ids.next(); ar = Ids.next();
        maker = Ids.next(); reviewer = Ids.next();
        author = actor(maker); checker = actor(reviewer);
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'手工红字测试')", tenant, "RED" + tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','红字公司')", company, tenant);
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'P','红字客户')", partner, tenant);
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,'202611','2026-11-01','2026-11-30')", period, tenant, company);
        receivable(ar);
    }

    @AfterEach void cleanup() {
        for (String table : List.of("invoice_red_registration", "invoice_registration_line", "invoice_registration", "receivable", "payable", "shipment",
                "warehouse", "idempotency_record", "audit_log", "accounting_period", "company", "business_partner"))
            jdbc.update("DELETE FROM " + table + " WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM tenant WHERE id=?", tenant);
    }

    @Test void pendingDoesNotReleaseAndDifferentReviewerConfirmsExactlyOnceWithoutCostPermission() {
        long blue = blue("100", "11.50");
        var input = input(blue, 0L, "RED-ONE");
        var created = reds.create(author, "create-red", input);
        long red = id(created);
        assertThat(created).containsEntry("status", "pending").containsEntry("invoiceStatus", "registered");
        assertThat(reds.create(author, "create-red", input).get("id")).isEqualTo(created.get("id"));
        assertThat(invoiced()).isEqualByComparingTo("100");
        assertThat(invoices.get(author, blue).get("allowedActions")).asList().isEmpty();
        code(() -> reds.create(author, key(), input(blue, 0L, "RED-TWO")), "INVOICE_RED_EXISTS");
        code(() -> invoices.voidInvoice(author, blue, 0, key()), "INVOICE_RED_PENDING");
        code(() -> command(author, red, "confirm", 0, key()), "TWO_PERSON_REQUIRED");
        var confirmed = command(checker, red, "confirm", 0, "confirm-red");
        assertThat(confirmed).containsEntry("status", "confirmed").containsEntry("confirmedBy", Long.toString(reviewer));
        assertThat(confirmed.get("confirmedAt")).isNotNull();
        assertThat(json.readTree(json.writeValueAsString(command(checker, red, "confirm", 0, "confirm-red"))))
                .isEqualTo(json.readTree(json.writeValueAsString(confirmed)));
        assertThat(invoiced()).isZero();
        code(() -> command(checker, red, "confirm", 1, key()), "STATE_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invoice_red_registration WHERE tenant_id=?", Long.class, tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT amount FROM receivable WHERE id=?", BigDecimal.class, ar)).isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("SELECT tax_amount FROM receivable WHERE id=?", BigDecimal.class, ar)).isEqualByComparingTo("11.50");
        for (String table : List.of("journal", "inventory_movement", "settlement_allocation"))
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=?", Long.class, tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND action LIKE 'invoice_red.%'", Long.class, tenant)).isEqualTo(2);
    }

    @Test void confirmingPreviouslyVoidedBlueDoesNotReleaseAReplacementInvoice() {
        long old = blue("100", "11.50");
        invoices.voidInvoice(author, old, 0, key());
        blue("60", "6.90");
        long red = id(reds.create(author, key(), input(old, 1L, "RED-OLD-VOID")));
        command(checker, red, "confirm", 0, key());
        assertThat(invoiced()).isEqualByComparingTo("60");
        assertThat(jdbc.queryForObject("SELECT version FROM invoice_registration WHERE id=?", Long.class, old)).isEqualTo(1);
    }

    @Test void cancellationKeepsCapacityAndHistoryAndAllowsACorrectedRegistration() {
        long blue = blue("100", "11.50");
        long first = id(reds.create(author, key(), input(blue, 0L, "RED-CANCEL")));
        code(() -> command(checker, first, "cancel", 0, key()), "FORBIDDEN");
        assertThat(command(author, first, "cancel", 0, "cancel-red")).containsEntry("status", "cancelled");
        assertThat(invoiced()).isEqualByComparingTo("100");
        long second = id(reds.create(author, key(), input(blue, 0L, "RED-CORRECT")));
        command(checker, second, "confirm", 0, key());
        assertThat(invoiced()).isZero();
        var page = reds.list(author, blue, null, null, 1);
        assertThat(page.get("items")).asList().hasSize(1);
        assertThat(page.get("nextCursor")).isEqualTo(Long.toString(second));
        assertThat(reds.list(author, blue, null, second, 1).get("items")).asList().hasSize(1);
        assertThat(reds.list(author, blue, "cancelled", null, 20).get("items")).asList().hasSize(1);
        code(() -> command(author, second, "cancel", 1, key()), "STATE_CONFLICT");
    }

    @Test void staleVersionDatesAndPartialRedAmountsCannotChangeTheOriginalBlue() {
        long blue = blue("100", "11.50");
        code(() -> reds.create(author, key(), input(blue, 1L, "STALE")), "VERSION_CONFLICT");
        for (var input : List.of(
                new ManualInvoiceRedService.Input(blue, 0L, "PARTIAL", DAY, "99", "11.50", "部分红字"),
                new ManualInvoiceRedService.Input(blue, 0L, "TAX", DAY, "100", "11.51", "税额不同"),
                new ManualInvoiceRedService.Input(blue, 0L, "EARLY", DAY.minusDays(1), "100", "11.50", "早于原票"),
                new ManualInvoiceRedService.Input(blue, 0L, "FUTURE", LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1), "100", "11.50", "晚于今天"),
                new ManualInvoiceRedService.Input(blue, 0L, "PRECISION", DAY, "100.001", "11.50", "精度无效"),
                new ManualInvoiceRedService.Input(blue, 0L, "NEGATIVE", DAY, "-100", "11.50", "金额无效"),
                new ManualInvoiceRedService.Input(blue, 0L, "REASON", DAY, "100", "11.50", " "),
                new ManualInvoiceRedService.Input(blue, 0L, invoices.get(author, blue).get("invoiceNo").toString(), DAY, "100", "11.50", "重复票号")))
            code(() -> reds.create(author, key(), input), "VALIDATION_FAILED");
        assertThat(invoiced()).isEqualByComparingTo("100");
        assertThat(reds.list(author, null, null, null, 20).get("items")).asList().isEmpty();
    }

    @Test void oneRedConfirmsTheWholeMultiSourceBlueAtomically() {
        long second = Ids.next(); receivable(second);
        long blue = id(invoices.create(author, key(), new InvoiceService.Input("sales", partner, "MULTI", DAY, "100", "11.50",
                List.of(new InvoiceService.Line(ar, "40"), new InvoiceService.Line(second, "60")), null)));
        long red = id(reds.create(author, key(), input(blue, 0L, "RED-MULTI")));
        assertThat(reds.get(author, red).get("lines")).asList().hasSize(2);
        command(checker, red, "confirm", 0, key());
        assertThat(invoiced()).isZero();
        assertThat(jdbc.queryForObject("SELECT invoiced_amount FROM receivable WHERE id=?", BigDecimal.class, second)).isZero();
    }

    @Test void concurrentConfirmationsReleaseOnlyOnce() throws Exception {
        long red = id(reds.create(author, key(), input(blue("100", "11.50"), 0L, "RED-RACE")));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return attempt(() -> command(checker, red, "confirm", 0, key())); });
            var second = pool.submit(() -> { start.await(); return attempt(() -> command(actor(Ids.next()), red, "confirm", 0, key())); });
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .contains("ok").anyMatch(value -> Set.of("VERSION_CONFLICT", "STATE_CONFLICT").contains(value));
        }
        assertThat(invoiced()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invoice_red_registration WHERE tenant_id=? AND status='confirmed'", Long.class, tenant)).isEqualTo(1);
    }

    @Test void inconsistentSecondSourceRollsBackTheWholeRedConfirmation() {
        long second = Ids.next(); receivable(second);
        long blue = id(invoices.create(author, key(), new InvoiceService.Input("sales", partner, "ATOMIC", DAY, "100", "11.50",
                List.of(new InvoiceService.Line(ar, "40"), new InvoiceService.Line(second, "60")), null)));
        long red = id(reds.create(author, key(), input(blue, 0L, "RED-ATOMIC")));
        jdbc.update("UPDATE receivable SET invoiced_amount=59 WHERE id=?", second);
        code(() -> command(checker, red, "confirm", 0, key()), "STATE_CONFLICT");
        assertThat(invoiced()).isEqualByComparingTo("40");
        assertThat(reds.get(author, red)).containsEntry("status", "pending").containsEntry("invoiceStatus", "registered");
        jdbc.update("UPDATE receivable SET invoiced_amount=60 WHERE id=?", second);
        command(checker, red, "confirm", 0, key());
        assertThat(invoiced()).isZero();
    }

    @Test void twoHundredSourceBlueCanBeReviewedAndReleasedAsOneWholeInvoice() {
        var sources = new java.util.ArrayList<InvoiceService.Line>();
        for (int index = 0; index < 200; index++) {
            long source = index == 0 ? ar : Ids.next();
            if (index > 0) receivable(source);
            jdbc.update("UPDATE receivable SET untaxed_amount=amount,tax_amount=0 WHERE id=?", source);
            sources.add(new InvoiceService.Line(source, "1"));
        }
        long blue = id(invoices.create(author, key(), new InvoiceService.Input("sales", partner, "MAX-SOURCES", DAY, "200", "0", sources, null)));
        long red = id(reds.create(author, key(), new ManualInvoiceRedService.Input(blue, 0L, "RED-MAX-SOURCES", DAY, "200", "0", "整票二百来源确认")));
        assertThat(reds.get(checker, red).get("lines")).asList().hasSize(200);
        assertThat(command(checker, red, "confirm", 0, key())).containsEntry("status", "confirmed");
        assertThat(jdbc.queryForObject("SELECT SUM(invoiced_amount) FROM receivable WHERE tenant_id=?", BigDecimal.class, tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invoice_registration_line WHERE tenant_id=?", Long.class, tenant)).isEqualTo(200);
    }

    @Test void normalVoidAndNewRedCannotPassEachOtherUsingTheSameBlueVersion() throws Exception {
        long blue = blue("100", "11.50");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var red = pool.submit(() -> { start.await(); return attempt(() -> reds.create(author, key(), input(blue, 0L, "RED-VOID-RACE"))); });
            var normalVoid = pool.submit(() -> { start.await(); return attempt(() -> invoices.voidInvoice(checker, blue, 0, key())); });
            start.countDown();
            var outcomes = List.of(red.get(15, TimeUnit.SECONDS), normalVoid.get(15, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter("ok"::equals)).hasSize(1);
            assertThat(outcomes).anyMatch(value -> Set.of("INVOICE_RED_PENDING", "VERSION_CONFLICT").contains(value));
        }
        if ("registered".equals(invoices.get(author, blue).get("status"))) {
            assertThat(invoiced()).isEqualByComparingTo("100");
            assertThat(reds.list(author, blue, "pending", null, 20).get("items")).asList().hasSize(1);
        } else {
            assertThat(invoiced()).isZero();
            assertThat(reds.list(author, blue, "pending", null, 20).get("items")).asList().isEmpty();
        }
    }

    @Test void concurrentRegistrationsCannotClaimTheSameBlueTwice() throws Exception {
        long blue = blue("100", "11.50");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return attempt(() -> reds.create(author, key(), input(blue, 0L, "RACE-A"))); });
            var second = pool.submit(() -> { start.await(); return attempt(() -> reds.create(checker, key(), input(blue, 0L, "RACE-B"))); });
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder("ok", "INVOICE_RED_EXISTS");
        }
        assertThat(invoiced()).isEqualByComparingTo("100");
    }

    @Test void sourceWarehouseAndDepartmentAreCheckedForListsAndOldIdempotentResponses() {
        long blue = blue("100", "11.50");
        var input = input(blue, 0L, "RED-SCOPE");
        long red = id(reds.create(author, "scoped-red", input));
        long warehouse = Ids.next(), shipment = Ids.next();
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'W','来源仓')", warehouse, tenant, company);
        jdbc.update("INSERT INTO shipment(id,tenant_id,company_id,shipment_no,customer_id,warehouse_id,business_date) VALUES(?,?,?,'SCOPE',?,?,?)",
                shipment, tenant, company, partner, warehouse, DAY);
        jdbc.update("UPDATE receivable SET source_type='shipment_line',source_id=? WHERE id=?", shipment, ar);
        var outside = new CompanyAccess(tenant, maker, company, false, Set.of(Ids.next()), PERMISSIONS);
        code(() -> reds.get(outside, red), "NOT_FOUND");
        code(() -> reds.create(outside, "scoped-red", input), "NOT_FOUND");
        code(() -> results.get(outside, "invoice_red.create", "scoped-red"), "NOT_FOUND");
        assertThat(reds.list(outside, null, null, null, 20).get("items")).asList().isEmpty();
        code(() -> invoices.get(outside, blue), "NOT_FOUND");
        assertThat(invoices.list(outside, null, 20).get("items")).asList().isEmpty();
        jdbc.update("UPDATE receivable SET source_type='sales_order_fee' WHERE id=?", ar);
        code(() -> reds.get(outside, red), "NOT_FOUND");
        assertThat(reds.list(outside, null, null, null, 20).get("items")).asList().isEmpty();
        var visible = new CompanyAccess(tenant, reviewer, company, false, Set.of(warehouse), PERMISSIONS);
        assertThat(reds.list(visible, null, null, null, 20).get("items")).asList().hasSize(1);
        code(() -> command(visible, red, "confirm", 0, key()), "FORBIDDEN");
        var wrongDepartment = new CompanyAccess(tenant, maker, company, true, Set.of(), PERMISSIONS, OrgScope.restricted(Set.of(Ids.next()), Set.of(), 0));
        code(() -> reds.get(wrongDepartment, red), "NOT_FOUND");
        assertThat(reds.list(wrongDepartment, null, null, null, 20).get("items")).asList().isEmpty();
    }

    @Test void companyAndPermissionChangesInvalidateCachedResults() {
        long blue = blue("100", "11.50");
        var input = input(blue, 0L, "RED-PERMISSION");
        long red = id(reds.create(author, "permission-red", input));
        var outside = new CompanyAccess(tenant, maker, Ids.next(), true, Set.of(), PERMISSIONS);
        code(() -> reds.get(outside, red), "NOT_FOUND");
        var revoked = new CompanyAccess(tenant, maker, company, true, Set.of(), Set.of("invoice_red:view", "invoice_registration:view"));
        code(() -> reds.create(revoked, "permission-red", input), "FORBIDDEN");
        code(() -> results.get(revoked, "invoice_red.create", "permission-red"), "FORBIDDEN");
        var changed = new ManualInvoiceRedService.Input(blue, 0L, "CHANGED", DAY, "100", "11.50", "改变原请求");
        code(() -> reds.create(author, "permission-red", changed), "IDEMPOTENCY_KEY_REUSED");
    }

    @Test void oldPurchaseRegistrationRemainsReadableButCannotBecomeAManualSalesRed() {
        long payable = Ids.next(), blue = Ids.next();
        jdbc.update("INSERT INTO payable(id,tenant_id,company_id,supplier_id,kind,source_type,source_id,source_line_id,amount,untaxed_amount,tax_amount,business_date,period_id) VALUES(?,?,?,?,'charge','opening_entry',?,?,100,88.50,11.50,?,?)",
                payable, tenant, company, partner, payable, payable, DAY, period);
        jdbc.update("INSERT INTO invoice_registration(id,tenant_id,company_id,invoice_type,partner_id,invoice_no,invoice_date,amount,tax_amount,status,version) VALUES(?,?,?,'purchase',?,'OLD-PURCHASE',?,100,11.50,'voided',1)",
                blue, tenant, company, partner, DAY);
        jdbc.update("INSERT INTO invoice_registration_line(id,tenant_id,company_id,invoice_id,payable_id,amount) VALUES(?,?,?,?,?,100)",
                Ids.next(), tenant, company, blue, payable);
        assertThat(invoices.get(author, blue)).containsEntry("invoiceType", "purchase").containsEntry("status", "voided");
        assertThat(invoices.list(author, null, 20).get("items")).asList().hasSize(1);
        code(() -> reds.create(author, key(), input(blue, 1L, "NO-PURCHASE-RED")), "MANUAL_INVOICE_REQUIRED");
    }

    private CompanyAccess actor(long user) { return new CompanyAccess(tenant, user, company, true, Set.of(), PERMISSIONS); }
    private Map<String, Object> command(CompanyAccess actor, long id, String action, long version, String key) {
        return reds.command(actor, id, action, key, new ManualInvoiceRedService.Revision(version));
    }
    private ManualInvoiceRedService.Input input(long blue, Long version, String no) {
        return new ManualInvoiceRedService.Input(blue, version, no, DAY, "100", "11.50", "结算折让，整票红字后重新开具");
    }
    private long blue(String amount, String tax) {
        return id(invoices.create(author, key(), new InvoiceService.Input("sales", partner, key(), DAY, amount, tax,
                List.of(new InvoiceService.Line(ar, amount)), null)));
    }
    private void receivable(long id) {
        jdbc.update("INSERT INTO receivable(id,tenant_id,company_id,customer_id,kind,source_type,source_id,source_line_id,amount,untaxed_amount,tax_amount,business_date,period_id) VALUES(?,?,?,?,'charge','opening_entry',?,?,100,88.50,11.50,?,?)",
                id, tenant, company, partner, id, id, DAY, period);
    }
    private BigDecimal invoiced() { return jdbc.queryForObject("SELECT invoiced_amount FROM receivable WHERE id=?", BigDecimal.class, ar); }
    private static String attempt(java.util.function.Supplier<?> work) {
        try { work.get(); return "ok"; } catch (ApiException error) { return error.getErrorCode(); }
    }
    private static long id(Map<String, Object> row) { return Long.parseLong(row.get("id").toString()); }
    private static String key() { return UUID.randomUUID().toString(); }
    private static void code(org.assertj.core.api.ThrowableAssert.ThrowingCallable work, String code) {
        assertThatThrownBy(work).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
}
