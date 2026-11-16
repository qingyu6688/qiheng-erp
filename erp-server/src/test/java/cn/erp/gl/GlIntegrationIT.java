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

import cn.erp.accounting.LedgerService;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.*;

import static cn.erp.gl.GlTestData.key;
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
        return vouchers.save(
                d.actor(d.maker),
                null,
                key(),
                new GlModels.Voucher(
                        d.word(),
                        LocalDate.of(2026, 10, 15),
                        0,
                        List.of(
                                new GlModels.Line(
                                        "费用计提",
                                        d.subject("6602"),
                                        "D",
                                        "100.00",
                                        "0",
                                        null,
                                        GlModels.Aux.empty(),
                                        null),
                                new GlModels.Line(
                                        "其他应付",
                                        d.subject("2241"),
                                        "C",
                                        "100.00",
                                        "0",
                                        null,
                                        GlModels.Aux.empty(),
                                        null)),
                        null,
                        null));
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
        if (restriction.equals("permission")) permissions.remove("gl_voucher:edit");
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
