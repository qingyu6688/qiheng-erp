package cn.erp.gl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import cn.erp.accounting.LedgerService;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.*;

import static cn.erp.gl.GlTestData.key;
import static cn.erp.gl.GlSupport.number;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class GlIntegrationIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired GlBookService books;
    @Autowired GlMasterService master;
    @Autowired GlVoucherService vouchers;
    @Autowired GlQueryService queries;
    @Autowired GlSyncService sync;
    @Autowired LedgerService ledger;
    @Autowired GlClosingService closing;
    @Autowired GlAdministration administration;
    @Autowired GlBalances balances;
    @Autowired GlReconciliation reconciliation;
    @Autowired TransactionTemplate transactions;
    GlTestData d;

    @BeforeEach
    void setup() {
        d = new GlTestData(jdbc);
        books.create(d.actor(d.maker), key(), new GlModels.Book("enterprise", d.october, null, 0L));
    }

    @AfterEach
    void cleanup() {
        if (d != null) d.cleanup();
    }

    void activate() {
        var book = books.get(d.actor(d.maker));
        book =
                books.confirm(
                        d.actor(d.reviewer),
                        key(),
                        new GlModels.Confirmation(
                                GlSupport.number(book.get("version")), "本机核对科目、辅助、映射与报表样例"));
        books.activate(
                d.actor(d.maker),
                "activate",
                key(),
                new GlModels.Version(GlSupport.number(book.get("version")), null));
    }

    Map<String, Object> draft() {
        return expense(LocalDate.of(2026, 10, 15));
    }

    Map<String, Object> expense(LocalDate day) {
        return draft(day, List.of(
                line(d.subject("6602"), "D", "100.00", "0", GlModels.Aux.empty()),
                line(d.subject("2241"), "C", "100.00", "0", GlModels.Aux.empty())));
    }

    private Map<String, Object> draft(LocalDate day, List<GlModels.Line> lines) {
        return vouchers.save(d.actor(d.maker), null, key(),
                new GlModels.Voucher(d.word(), day, 0, lines, null, null));
    }

    private static GlModels.Line line(long subject, String direction, String amount, String quantity, GlModels.Aux aux) {
        return new GlModels.Line("增量余额验证", subject, direction, amount, quantity, null, aux, null);
    }

    Map<String, Object> change(Map<String, Object> v, long user, String action) {
        return vouchers.change(
                d.actor(user),
                GlSupport.number(v.get("id")),
                action,
                key(),
                new GlModels.Version(GlSupport.number(v.get("version")), "测试复核"));
    }

    @Test
    void defaultsAreCompanyCopiesAndRequireFinancialConfirmation() {
        assertThat(master.list(d.actor(d.maker), "subjects")).hasSizeGreaterThan(150);
        var other = new CompanyAccess(d.tenant, d.maker, d.other, true, Set.of(), d.permissions);
        assertThat(books.get(other)).containsEntry("exists", false);
        assertThatThrownBy(
                        () ->
                                books.activate(
                                        d.actor(d.maker),
                                        "activate",
                                        key(),
                                        new GlModels.Version(0L, null)))
                .hasMessageContaining("财务");
    }

    @Test
    void makerCannotApproveAndPostedVoucherCannotEdit() {
        activate();
        var draft = draft();
        var pending = change(draft, d.maker, "submit");
        assertThatThrownBy(() -> change(pending, d.maker, "approve")).hasMessageContaining("制单人");
        var approved = change(pending, d.reviewer, "approve");
        var posted = change(approved, d.maker, "post");
        assertThat(posted).containsEntry("status", "posted");
        assertThatThrownBy(() -> change(posted, d.maker, "delete")).hasMessageContaining("状态");
        assertThat(queries.ledger(d.actor(d.maker), "balance", d.query()).get("items"))
                .asList()
                .isNotEmpty();
    }

    @Test
    void redVoucherRequiresAnotherReviewAndKeepsOriginalFacts() {
        activate();
        var posted =
                change(
                        change(change(draft(), d.maker, "submit"), d.reviewer, "approve"),
                        d.maker,
                        "post");
        var red =
                vouchers.reverse(
                        d.actor(d.maker),
                        GlSupport.number(posted.get("id")),
                        key(),
                        new GlVoucherService.Reversal(
                                GlSupport.number(posted.get("version")),
                                LocalDate.of(2026, 10, 16),
                                "费用取消"));
        assertThat(red).containsEntry("status", "draft");
        assertThat(vouchers.get(d.actor(d.maker), GlSupport.number(posted.get("id"))))
                .containsEntry("status", "posted");
        var approved = change(change(red, d.maker, "submit"), d.reviewer, "approve");
        change(approved, d.maker, "post");
        assertThat(vouchers.get(d.actor(d.maker), GlSupport.number(posted.get("id"))))
                .containsEntry("status", "reversed");
        var items =
                (List<Map<String, Object>>)
                        queries.ledger(d.actor(d.maker), "balance", d.query()).get("items");
        assertThat(items)
                .allSatisfy(
                        row ->
                                assertThat(
                                                new BigDecimal(row.get("closingDebit").toString())
                                                        .add(
                                                                new BigDecimal(
                                                                        row.get("closingCredit")
                                                                                .toString())))
                                        .isZero());
    }

    @Test
    void configurationChangeInvalidatesConfirmation() {
        activate();
        master.save(d.actor(d.maker), "digests", null, key(), Map.of("content", "新增摘要"));
        assertThatThrownBy(this::draft).hasMessageContaining("确认");
    }

    @Test
    void unbalancedVoucherCanSaveButCannotSubmit() {
        activate();
        var input =
                new GlModels.Voucher(
                        d.word(),
                        LocalDate.of(2026, 10, 15),
                        0,
                        List.of(
                                new GlModels.Line(
                                        "费用", d.subject("6602"), "D", "100", "0", null, null, null),
                                new GlModels.Line(
                                        "应付", d.subject("2241"), "C", "99", "0", null, null, null)),
                        null,
                        null);
        var v = vouchers.save(d.actor(d.maker), null, key(), input);
        assertThatThrownBy(() -> change(v, d.maker, "submit")).hasMessageContaining("平衡");
    }

    @Test
    void auxiliaryIsRequiredAndCrossCompanySubjectRejected() {
        activate();
        var input =
                new GlModels.Voucher(
                        d.word(),
                        LocalDate.of(2026, 10, 15),
                        0,
                        List.of(
                                new GlModels.Line(
                                        "应收", d.subject("1122"), "D", "100", "0", null, null, null),
                                new GlModels.Line(
                                        "销售",
                                        d.subject("6001"),
                                        "C",
                                        "100",
                                        "0",
                                        null,
                                        null,
                                        null)),
                        null,
                        null);
        assertThatThrownBy(() -> vouchers.save(d.actor(d.maker), null, key(), input))
                .hasMessageContaining("客户");
        var other = new CompanyAccess(d.tenant, d.maker, d.other, true, Set.of(), d.permissions);
        assertThatThrownBy(() -> vouchers.get(other, d.subject("1122")))
                .isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"warehouse", "department", "cost", "permission"})
    void partialScopeCannotReadOrReplay(String restriction) {
        activate();
        String key = key();
        var input =
                new GlModels.Voucher(
                        d.word(),
                        LocalDate.of(2026, 10, 15),
                        0,
                        List.of(
                                new GlModels.Line(
                                        "费用", d.subject("6602"), "D", "10", "0", null, null, null),
                                new GlModels.Line(
                                        "应付", d.subject("2241"), "C", "10", "0", null, null, null)),
                        null,
                        null);
        vouchers.save(d.actor(d.maker), null, key, input);
        var permissions = new HashSet<>(d.permissions);
        if (restriction.equals("cost")) permissions.remove("cost:view");
        if (restriction.equals("permission")) {
            permissions.remove("gl_voucher:edit");
            permissions.remove("gl_ledger:view");
        }
        var limited =
                new CompanyAccess(
                        d.tenant,
                        d.maker,
                        d.company,
                        !restriction.equals("warehouse"),
                        Set.of(),
                        permissions,
                        restriction.equals("department")
                                ? OrgScope.restricted(Set.of(), Set.of(), 0)
                                : OrgScope.all());
        assertThatThrownBy(() -> vouchers.save(limited, null, key, input))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> reconciliation.check(limited, "gl_trial"))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(403));
    }

    @Test
    void duplicateConcurrentPostingCannotDoubleBalance() throws Exception {
        activate();
        var approved = change(change(draft(), d.maker, "submit"), d.reviewer, "approve");
        long id = GlSupport.number(approved.get("id")),
                version = GlSupport.number(approved.get("version"));
        String key = key();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var calls =
                    List.of(
                            pool.submit(
                                    () ->
                                            vouchers.change(
                                                    d.actor(d.maker),
                                                    id,
                                                    "post",
                                                    key,
                                                    new GlModels.Version(version, null))),
                            pool.submit(
                                    () ->
                                            vouchers.change(
                                                    d.actor(d.maker),
                                                    id,
                                                    "post",
                                                    key,
                                                    new GlModels.Version(version, null))));
            for (var call : calls)
                assertThat(call.get(20, TimeUnit.SECONDS)).containsEntry("status", "posted");
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT debit FROM gl_balance WHERE tenant_id=? AND subject_id=?"
                                        + " AND period_id=?",
                                BigDecimal.class,
                                d.tenant,
                                d.subject("6602"),
                                d.october))
                .isEqualByComparingTo("100.00");
    }

    @Test
    void outOfOrderPostingAndCrossPeriodReversalMatchFullRebuild() {
        var actor = d.actor(d.maker);
        long stock = d.subject("1405"), payable = d.subject("2241");
        var dimensions = stockDimensions(stock);
        var first = dimensions.getFirst();
        var second = dimensions.getLast();
        books.saveOpenings(actor, key(), new GlModels.Openings(number(books.get(actor).get("version")), List.of(
                new GlModels.OpeningLine(stock, first, "25", "0", "0", "0", "25", "0", "1.250000"),
                new GlModels.OpeningLine(payable, null, "0", "25", "0", "0", "0", "25", "0"))));
        activate();

        post(expense(LocalDate.of(2026, 11, 11)));
        long december = Ids.next();
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date)"
                + " VALUES(?,?,?,'202612','2026-12-01','2026-12-31')", december, d.tenant, d.company);
        transactions.executeWithoutResult(tx -> balances.refresh(actor, december));
        long expenseId = number(balanceRow(d.november, d.subject("6602"), 0).get("id"));
        assertAmounts(d.november, stock, first.customId(), "25", "0", "0", "1.25", "0", "0");

        var original = post(draft(LocalDate.of(2026, 10, 20), List.of(
                line(stock, "D", "12", "1.5", first),
                line(stock, "D", "8", "2", first),
                line(stock, "D", "4", "1", second),
                line(stock, "C", "4", "1", second),
                line(payable, "C", "20", "0", GlModels.Aux.empty()))));
        assertAmounts(d.october, stock, first.customId(), "25", "20", "0", "1.25", "3.5", "0");
        for (long period : List.of(d.november, december)) {
            assertAmounts(period, stock, first.customId(), "45", "0", "0", "4.75", "0", "0");
            assertAmounts(period, stock, second.customId(), "0", "0", "0", "0", "0", "0");
        }

        post(vouchers.reverse(actor, number(original.get("id")), key(), new GlVoucherService.Reversal(
                number(original.get("version")), LocalDate.of(2026, 11, 16), "更正上期入账")));
        assertThat(vouchers.get(actor, number(original.get("id")))).containsEntry("status", "reversed");
        assertAmounts(d.october, stock, first.customId(), "25", "20", "0", "1.25", "3.5", "0");
        assertAmounts(d.november, stock, first.customId(), "45", "-20", "0", "4.75", "-3.5", "0");
        assertAmounts(d.november, stock, second.customId(), "0", "-4", "-4", "0", "-1", "-1");
        assertAmounts(december, stock, first.customId(), "25", "0", "0", "1.25", "0", "0");
        assertThat(number(balanceRow(d.november, d.subject("6602"), 0).get("id"))).isEqualTo(expenseId);
        assertThat(reconciliation.check(actor, "gl_trial")).isEmpty();

        var incremental = balanceValues();
        var before = statements(d.november);
        transactions.executeWithoutResult(tx -> {
            for (long period : List.of(d.october, d.november, december)) balances.refresh(actor, period);
        });
        assertThat(balanceValues()).isEqualTo(incremental);
        assertThat(statements(d.november)).isEqualTo(before);

        jdbc.update("UPDATE gl_balance SET quantity_debit=quantity_debit+1 WHERE id=?",
                balanceRow(d.october, stock, first.customId()).get("id"));
        jdbc.update("DELETE FROM gl_balance WHERE id=?", balanceRow(december, stock, second.customId()).get("id"));
        assertThat(reconciliation.check(actor, "gl_trial"))
                .extracting(FinancialReconciliationPort.Difference::key)
                .containsExactly("202610/" + GlDimensions.key(stock, first) + "/quantity_debit",
                        "202612/" + GlDimensions.key(stock, second) + "/余额行");
    }

    @Test
    void concurrentFirstAndBackdatedPostingCarryEveryMovementOnce() throws Exception {
        activate();
        var earlier = approve(draft());
        var later = approve(expense(LocalDate.of(2026, 11, 11)));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var posts = new ArrayList<Future<Map<String, Object>>>();
            for (var approved : List.of(earlier, later)) {
                posts.add(pool.submit(() -> {
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return change(approved, d.maker, "post");
                }));
            }
            start.countDown();
            for (var result : posts) assertThat(result.get(20, TimeUnit.SECONDS)).containsEntry("status", "posted");
        }
        assertAmounts(d.november, d.subject("6602"), 0, "100", "100", "0", "0", "0", "0");
        assertThat(reconciliation.check(d.actor(d.maker), "gl_trial")).isEmpty();
    }

    private Map<String, Object> post(Map<String, Object> draft) {
        return change(approve(draft), d.maker, "post");
    }

    private Map<String, Object> approve(Map<String, Object> draft) {
        return change(change(draft, d.maker, "submit"), d.reviewer, "approve");
    }

    private Map<String, Object> balanceRow(long period, long subject, long custom) {
        return jdbc.queryForMap("SELECT * FROM gl_balance WHERE tenant_id=? AND company_id=?"
                + " AND period_id=? AND subject_id=? AND aux_custom_id=?", d.tenant, d.company, period, subject, custom);
    }

    private void assertAmounts(long period, long subject, long custom, String... values) {
        var row = balanceRow(period, subject, custom);
        var columns = List.of("opening_signed", "debit", "credit", "opening_quantity", "quantity_debit", "quantity_credit");
        for (int i = 0; i < columns.size(); i++) {
            String column = columns.get(i);
            assertThat(GlBalances.decimal(row, column)).as(column).isEqualByComparingTo(values[i]);
        }
    }

    private List<Map<String, Object>> balanceValues() {
        return jdbc.queryForList("SELECT period_id,subject_id," + GlSupport.AUX_COLUMNS
                + ",opening_signed,debit,credit,opening_quantity,quantity_debit,quantity_credit"
                + " FROM gl_balance WHERE tenant_id=? AND company_id=? ORDER BY period_id,subject_id," + GlSupport.AUX_COLUMNS,
                d.tenant, d.company);
    }

    private List<Map<String, Object>> statements(long period) {
        return List.of("balance_sheet", "income_statement", "cash_flow").stream()
                .map(type -> queries.statement(d.actor(d.maker), type, period)).toList();
    }

    private List<GlModels.Aux> stockDimensions(long subject) {
        long dept = Ids.next(), unit = Ids.next(), category = Ids.next(), product = Ids.next(), sku = Ids.next();
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(?,?,?,'supplier')",
                Ids.next(), d.tenant, d.customer);
        jdbc.update("INSERT INTO org_department(id,tenant_id,company_id,code,name,path,depth)"
                + " VALUES(?,?,?,'D','测试部门',?,1)", dept, d.tenant, d.company, "/" + dept + "/");
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件')", unit, d.tenant);
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'C','测试分类','/')",
                category, d.tenant);
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'P','测试商品',?)",
                product, d.tenant, category, unit);
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name,sale_price) VALUES(?,?,?,'S','测试规格',100)",
                sku, d.tenant, product);

        var actor = d.actor(d.maker);
        long projectType = master.list(actor, "aux-types").stream()
                .filter(row -> row.get("code").equals("project")).mapToLong(row -> number(row.get("id")))
                .findFirst().orElseThrow();
        long customType = number(master.save(actor, "aux-types", null, key(),
                Map.of("code", "BATCH", "name", "测试批次", "source", "custom")).get("id"));
        long project = auxItem(projectType, "P");
        long first = auxItem(customType, "A");
        long second = auxItem(customType, "B");
        jdbc.update("UPDATE gl_subject SET aux_types=JSON_ARRAY('customer','supplier','dept','employee','project','sku','custom'),"
                + " custom_aux_type_id=?,quantity_unit='件' WHERE tenant_id=? AND company_id=? AND id=?",
                customType, d.tenant, d.company, subject);
        return List.of(new GlModels.Aux(d.customer, d.customer, dept, d.maker, project, sku, first),
                new GlModels.Aux(d.customer, d.customer, dept, d.maker, project, sku, second));
    }

    private long auxItem(long type, String code) {
        return number(master.save(d.actor(d.maker), "aux-items", null, key(),
                Map.of("auxTypeId", type, "code", code, "name", "测试辅助" + code)).get("id"));
    }

    @Test
    void businessProjectionIsIdempotentAndReadOnly() {
        activate();
        long journal =
                ledger.post(
                        d.actor(d.maker),
                        new LedgerService.Posting(
                                "sales_invoice",
                                Ids.next(),
                                0,
                                d.october,
                                LocalDate.of(2026, 10, 15),
                                List.of(
                                        new LedgerService.Line(
                                                "AR",
                                                "D",
                                                new BigDecimal("100"),
                                                d.customer,
                                                null,
                                                null,
                                                null,
                                                null,
                                                "确认销售"),
                                        new LedgerService.Line(
                                                "SALES_REVENUE",
                                                "C",
                                                new BigDecimal("100"),
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                "确认销售"))));
        assertThat(administration.synchronize(d.actor(d.maker), key(), d.october))
                .containsEntry("generated", 1)
                .containsEntry("remaining", 0);
        long voucher = sync.project(d.tenant, d.company, journal);
        assertThat(sync.project(d.tenant, d.company, journal)).isEqualTo(voucher);
        var v = vouchers.get(d.actor(d.maker), voucher);
        assertThat(v).containsEntry("source", "business").containsEntry("status", "posted");
        assertThatThrownBy(() -> change(v, d.reviewer, "delete")).hasMessageContaining("只读");
        assertThat(reconciliation.check(d.actor(d.maker), "gl_trial")).isEmpty();
        assertThat(queries.statement(d.actor(d.maker), "balance_sheet", d.october))
                .containsEntry("passed", true);
    }

    @Test
    void tenManualVouchersCloseAndReopenPreserveProfit() {
        activate();
        for (int i = 0; i < 10; i++)
            change(
                    change(change(draft(), d.maker, "submit"), d.reviewer, "approve"),
                    d.maker,
                    "post");
        var before = queries.statement(d.actor(d.maker), "income_statement", d.october);
        closing.close(d.actor(d.reviewer), d.october);
        assertThat(queries.statement(d.actor(d.maker), "income_statement", d.october).get("rows"))
                .isEqualTo(before.get("rows"));
        assertThat(queries.statement(d.actor(d.maker), "balance_sheet", d.october))
                .containsEntry("passed", true);
        closing.reopen(d.actor(d.reviewer), d.october, "回补费用");
        assertThat(queries.statement(d.actor(d.maker), "income_statement", d.october).get("rows"))
                .isEqualTo(before.get("rows"));
        assertThat(reconciliation.check(d.actor(d.maker), "gl_trial")).isEmpty();
        jdbc.update("DELETE FROM gl_balance WHERE tenant_id=? AND company_id=? AND period_id=?",
                d.tenant, d.company, d.november);
        assertThat(reconciliation.check(d.actor(d.maker), "gl_trial"))
                .isNotEmpty()
                .allSatisfy(result -> assertThat(result.key()).startsWith("202611/").endsWith("/余额行"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void multiColumnCombinesVoucherLinesAndBalancesTaxWithinSameAuxiliary() {
        activate();
        var v =
                vouchers.save(
                        d.actor(d.maker),
                        null,
                        key(),
                        new GlModels.Voucher(
                                d.word(),
                                LocalDate.of(2026, 10, 15),
                                0,
                                List.of(
                                        new GlModels.Line(
                                                "进项税",
                                                d.subject("22210101"),
                                                "D",
                                                "100",
                                                "0",
                                                null,
                                                null,
                                                null),
                                        new GlModels.Line(
                                                "销项税",
                                                d.subject("22210102"),
                                                "C",
                                                "40",
                                                "0",
                                                null,
                                                null,
                                                null),
                                        new GlModels.Line(
                                                "应付结算",
                                                d.subject("2241"),
                                                "C",
                                                "60",
                                                "0",
                                                null,
                                                null,
                                                null)),
                                null,
                                null));
        change(change(change(v, d.maker, "submit"), d.reviewer, "approve"), d.maker, "post");
        var rows =
                (List<Map<String, Object>>)
                        queries.ledger(d.actor(d.maker), "multi_column", d.query()).get("items");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
                .containsEntry("debit", "100.00")
                .containsEntry("credit", "100.00");
        assertThat((Map<String, String>) rows.getFirst().get("columns"))
                .containsEntry("22210101", "100.00")
                .containsEntry("22210102", "-40.00");
        var report = (List<Map<String, Object>>) master.list(d.actor(d.maker), "report-templates");
        var template =
                report.stream()
                        .filter(r -> r.get("reportType").equals("balance_sheet"))
                        .findFirst()
                        .orElseThrow();
        master.save(
                d.actor(d.maker),
                "report-templates",
                GlSupport.number(template.get("id")),
                key(),
                Map.of(
                        "name",
                        "维度借贷核对",
                        "version",
                        template.get("version"),
                        "rows",
                        List.of(
                                new GlModels.FormulaRow("tax_d", "税金借余", "QM(2221,D)", false, 0),
                                new GlModels.FormulaRow("tax_c", "税金贷余", "QM(2221,C)", false, 0)),
                        "checks",
                        List.of()));
        var output =
                (List<Map<String, Object>>)
                        queries.statement(d.actor(d.maker), "balance_sheet", d.october).get("rows");
        assertThat(output.get(0)).containsEntry("amount", "60.00");
        assertThat(output.get(1)).containsEntry("amount", "0.00");
    }

    @Test
    void batchApprovalRollsBackAllWhenAnyVoucherHasWrongState() {
        activate();
        var one = change(draft(), d.maker, "submit");
        var two = draft();
        assertThatThrownBy(
                        () ->
                                vouchers.batch(
                                        d.actor(d.reviewer),
                                        "approve",
                                        key(),
                                        new GlModels.Batch(
                                                List.of(
                                                        new GlModels.BatchRow(
                                                                GlSupport.number(one.get("id")),
                                                                GlSupport.number(
                                                                        one.get("version"))),
                                                        new GlModels.BatchRow(
                                                                GlSupport.number(two.get("id")),
                                                                GlSupport.number(
                                                                        two.get("version")))),
                                                "批量复核")))
                .isInstanceOf(ApiException.class);
        assertThat(vouchers.get(d.actor(d.maker), GlSupport.number(one.get("id"))))
                .containsEntry("status", "pending");
    }

    @Test
    void usedVoucherWordCannotBeRenamed() {
        activate();
        change(change(change(draft(), d.maker, "submit"), d.reviewer, "approve"), d.maker, "post");
        assertThatThrownBy(
                        () ->
                                master.save(
                                        d.actor(d.maker),
                                        "words",
                                        d.word(),
                                        key(),
                                        Map.of(
                                                "code",
                                                "NEW",
                                                "printTitle",
                                                "新凭证字",
                                                "isDefault",
                                                true,
                                                "version",
                                                0)))
                .hasMessageContaining("已有凭证");
    }

    @Test
    void organizeKeepsPostedAndDeletedNumbersAndReordersOnlyUnpostedVouchers() {
        activate();
        var removed = draft();
        var fixed =
                change(
                        change(change(draft(), d.maker, "submit"), d.reviewer, "approve"),
                        d.maker,
                        "post");
        var later = draft();
        var early =
                vouchers.save(
                        d.actor(d.maker),
                        null,
                        key(),
                        new GlModels.Voucher(
                                d.word(),
                                LocalDate.of(2026, 10, 10),
                                0,
                                List.of(
                                        new GlModels.Line(
                                                "较早费用",
                                                d.subject("6602"),
                                                "D",
                                                "1",
                                                "0",
                                                null,
                                                null,
                                                null),
                                        new GlModels.Line(
                                                "较早应付",
                                                d.subject("2241"),
                                                "C",
                                                "1",
                                                "0",
                                                null,
                                                null,
                                                null)),
                                null,
                                null));
        change(removed, d.maker, "delete");
        String key = key();
        vouchers.organize(d.actor(d.maker), d.october, d.word(), key);
        vouchers.organize(d.actor(d.maker), d.october, d.word(), key);
        assertThat(
                        GlSupport.number(
                                vouchers.get(d.actor(d.maker), GlSupport.number(fixed.get("id")))
                                        .get("number")))
                .isEqualTo(2);
        assertThat(
                        GlSupport.number(
                                vouchers.get(d.actor(d.maker), GlSupport.number(early.get("id")))
                                        .get("number")))
                .isEqualTo(3);
        assertThat(
                        GlSupport.number(
                                vouchers.get(d.actor(d.maker), GlSupport.number(later.get("id")))
                                        .get("number")))
                .isEqualTo(4);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT number FROM gl_voucher WHERE tenant_id=? AND id=?",
                                Integer.class,
                                d.tenant,
                                removed.get("id")))
                .isEqualTo(1);
    }
}
