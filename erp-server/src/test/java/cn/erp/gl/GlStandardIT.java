package cn.erp.gl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import cn.erp.ar.BadDebtService;
import cn.erp.accounting.SettlementService;
import cn.erp.shared.Ids;

import static cn.erp.gl.GlTestData.key;
import static cn.erp.gl.GlSupport.number;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class GlStandardIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired GlBookService books;
    @Autowired GlQueryService queries;
    @Autowired BadDebtService badDebt;
    @Autowired SettlementService settlement;
    @Autowired GlSyncService sync;
    GlTestData d;

    @BeforeEach
    void setup() {
        d = new GlTestData(jdbc);
        books.create(
                d.actor(d.maker),
                key(),
                new GlModels.Book("small_enterprise", d.october, null, 0L));
        var b =
                books.confirm(
                        d.actor(d.reviewer),
                        key(),
                        new GlModels.Confirmation(0L, "小企业样例按实际损失核销，不计提减值准备"));
        books.activate(
                d.actor(d.maker),
                "activate",
                key(),
                new GlModels.Version(number(b.get("version")), null));
    }

    @AfterEach
    void cleanup() {
        if (d != null) d.cleanup();
    }

    @Test
    void smallEnterpriseRejectsProvisionAndWritesOffActualLoss() {
        LocalDate day = LocalDate.of(2026, 10, 31);
        assertThatThrownBy(
                        () ->
                                badDebt.create(
                                        d.actor(d.maker),
                                        key(),
                                        new BadDebtService.Input(
                                                d.customer,
                                                "provision",
                                                "individual",
                                                null,
                                                "10",
                                                day,
                                                "拟计提")))
                .hasMessageContaining("小企业");
        long id = Ids.next();
        jdbc.update(
                "INSERT INTO"
                    + " receivable(id,tenant_id,company_id,customer_id,kind,source_type,source_id,source_line_id,amount,untaxed_amount,tax_amount,business_date,period_id)"
                    + " VALUES(?,?,?,?,'charge','opening_entry',?,?,100,100,0,'2026-10-15',?)",
                id,
                d.tenant,
                d.company,
                d.customer,
                id,
                id,
                d.october);
        long item =
                settlement.open(
                        d.actor(d.maker),
                        new SettlementService.OpenItem(
                                "receivable",
                                id,
                                d.customer,
                                "D",
                                new BigDecimal("100"),
                                LocalDate.of(2026, 10, 15)));
        var row =
                badDebt.create(
                        d.actor(d.maker),
                        key(),
                        new BadDebtService.Input(
                                d.customer, "write_off", "actual", item, "30", day, "确认无法收回的实际损失"));
        row =
                badDebt.change(
                        d.actor(d.maker),
                        number(row.get("id")),
                        "submit",
                        key(),
                        new BadDebtService.Command(number(row.get("version")), null));
        row =
                badDebt.change(
                        d.actor(d.reviewer),
                        number(row.get("id")),
                        "approve",
                        key(),
                        new BadDebtService.Command(number(row.get("version")), "核对实际损失"));
        sync.project(d.tenant, d.company, number(row.get("journalId")));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT open_amount FROM partner_open_item WHERE id=?",
                                BigDecimal.class,
                                item))
                .isEqualByComparingTo("70");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM gl_voucher_line l JOIN gl_subject s ON"
                                    + " s.id=l.subject_id WHERE l.tenant_id=? AND s.code='5711' AND"
                                    + " l.direction='D' AND l.amount=30",
                                Long.class,
                                d.tenant))
                .isEqualTo(1);
    }

    @Test
    void emptyBookHasBalancedStatementsAndUndefinedRatios() {
        for (String type : List.of("balance_sheet", "income_statement", "cash_flow"))
            assertThat(queries.statement(d.actor(d.maker), type, d.october))
                    .containsEntry("passed", true);
        @SuppressWarnings("unchecked")
        var items =
                (List<Map<String, Object>>)
                        queries.indicators(d.actor(d.maker), d.october).get("items");
        assertThat(items)
                .hasSize(7)
                .allSatisfy(
                        r -> {
                            assertThat(r.get("value")).isNull();
                            assertThat(r.get("notice")).isEqualTo("分母为零，本期不计算");
                        });
    }
}
