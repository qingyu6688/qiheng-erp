package cn.erp.gl;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import cn.erp.ar.*;
import cn.erp.accounting.SettlementService;
import cn.erp.notify.MessageService;
import cn.erp.shared.*;

import static cn.erp.gl.GlTestData.key;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class ArFinanceIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired GlBookService books;
    @Autowired GlSyncService sync;
    @Autowired BadDebtService badDebt;
    @Autowired AgingService aging;
    @Autowired CollectionService collections;
    @Autowired SettlementService settlement;
    @Autowired MessageService messages;
    GlTestData d;

    @BeforeEach
    void setup() {
        d = new GlTestData(jdbc);
        books.create(d.actor(d.maker), key(), new GlModels.Book("enterprise", d.october, null, 0L));
        var book =
                books.confirm(
                        d.actor(d.reviewer), key(), new GlModels.Confirmation(0L, "本机核对财务配置"));
        books.activate(
                d.actor(d.maker),
                "activate",
                key(),
                new GlModels.Version(GlSupport.number(book.get("version")), null));
    }

    @AfterEach
    void cleanup() {
        if (d != null) {
            jdbc.update("DELETE FROM notify_send_log WHERE tenant_id=?", d.tenant);
            jdbc.update("DELETE FROM notify_message WHERE tenant_id=?", d.tenant);
            d.cleanup();
        }
    }

    void grantUsers() {
        int index = 0;
        for (long user : List.of(d.maker, d.reviewer)) {
            long role = Ids.next();
            jdbc.update(
                    "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,?,'财务回归角色')",
                    role,
                    d.tenant,
                    "FIN" + (index++));
            jdbc.update(
                    "INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",
                    Ids.next(),
                    d.tenant,
                    user,
                    role);
            for (long permission : jdbc.queryForList("SELECT id FROM sys_permission", Long.class))
                jdbc.update(
                        "INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)"
                                + " VALUES(?,?,?,?)",
                        Ids.next(),
                        d.tenant,
                        role,
                        permission);
        }
    }

    long receivable() {
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
        return settlement.open(
                d.actor(d.maker),
                new SettlementService.OpenItem(
                        "receivable",
                        id,
                        d.customer,
                        "D",
                        new BigDecimal("100"),
                        LocalDate.of(2026, 10, 15)));
    }

    Map<String, Object> decide(Map<String, Object> row, String action, long user) {
        return badDebt.change(
                d.actor(user),
                GlSupport.number(row.get("id")),
                action,
                key(),
                new BadDebtService.Command(GlSupport.number(row.get("version")), "独立核对认定依据"));
    }

    BadDebtService.Input input(String kind, String method, Long item, String amount) {
        return new BadDebtService.Input(
                d.customer, kind, method, item, amount, LocalDate.of(2026, 10, 31), "财务复核可回收性并认定");
    }

    @Test
    void writeOffUsesOriginalAllocationWithoutChangingReceivable() {
        long item = receivable();
        var pending =
                decide(
                        badDebt.create(
                                d.actor(d.maker),
                                key(),
                                input("provision", "individual", null, "50")),
                        "submit",
                        d.maker);
        assertThatThrownBy(() -> decide(pending, "approve", d.maker)).hasMessageContaining("另一人");
        var provision = decide(pending, "approve", d.reviewer);
        sync.project(d.tenant, d.company, GlSupport.number(provision.get("journalId")));
        var writeoff =
                decide(
                        decide(
                                badDebt.create(
                                        d.actor(d.maker),
                                        key(),
                                        input("write_off", "actual", item, "40")),
                                "submit",
                                d.maker),
                        "approve",
                        d.reviewer);
        sync.project(d.tenant, d.company, GlSupport.number(writeoff.get("journalId")));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT open_amount FROM partner_open_item WHERE tenant_id=? AND"
                                        + " id=?",
                                BigDecimal.class,
                                d.tenant,
                                item))
                .isEqualByComparingTo("60");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT amount FROM receivable WHERE tenant_id=?",
                                BigDecimal.class,
                                d.tenant))
                .isEqualByComparingTo("100");
        long allocation = GlSupport.number(writeoff.get("allocationId"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT allocation_type FROM settlement_allocation WHERE"
                                        + " tenant_id=? AND id=?",
                                String.class,
                                d.tenant,
                                allocation))
                .isEqualTo("bad_debt_write_off");
        assertThatThrownBy(() -> settlement.revoke(d.actor(d.reviewer), allocation, "尝试独立撤销"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void writeOffCannotExceedAllowance() {
        long item = receivable();
        var pending =
                decide(
                        badDebt.create(
                                d.actor(d.maker), key(), input("write_off", "actual", item, "1")),
                        "submit",
                        d.maker);
        assertThatThrownBy(() -> decide(pending, "approve", d.reviewer))
                .hasMessageContaining("准备不足");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT open_amount FROM partner_open_item WHERE tenant_id=? AND"
                                        + " id=?",
                                BigDecimal.class,
                                d.tenant,
                                item))
                .isEqualByComparingTo("100");
    }

    @Test
    void companyAgingEstimationAndIndividualRelease() {
        receivable();
        aging.save(
                d.actor(d.reviewer),
                key(),
                new AgingService.Config(0L, List.of(1, 10), List.of("0", "0.1", "0.5")));
        assertThat(badDebt.estimate(d.actor(d.maker), d.customer, LocalDate.of(2026, 10, 31)))
                .containsEntry("suggestedIncrease", "50.00");
        decide(
                decide(
                        badDebt.create(
                                d.actor(d.maker), key(), input("provision", "aging", null, "50")),
                        "submit",
                        d.maker),
                "approve",
                d.reviewer);
        decide(
                decide(
                        badDebt.create(
                                d.actor(d.maker),
                                key(),
                                input("release", "individual", null, "10")),
                        "submit",
                        d.maker),
                "approve",
                d.reviewer);
        assertThat(badDebt.estimate(d.actor(d.maker), d.customer, LocalDate.of(2026, 10, 31)))
                .containsEntry("existing", "40.00");
    }

    @Test
    void collectionReminderIsIdempotentAndFinishedPlanHidesMessage() {
        grantUsers();
        long item = receivable();
        var plan =
                collections.create(
                        d.actor(d.maker),
                        key(),
                        new CollectionService.Plan(
                                item,
                                d.reviewer,
                                LocalDate.now(ZoneId.of("Asia/Shanghai")),
                                "100"));
        assertThat(collections.remind(d.tenant)).isEqualTo(1);
        assertThat(collections.remind(d.tenant)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM notify_message WHERE tenant_id=? AND"
                                        + " source_type='ar_collection_plan'",
                                Long.class,
                                d.tenant))
                .isEqualTo(1);
        collections.follow(
                d.actor(d.reviewer),
                GlSupport.number(plan.get("id")),
                key(),
                new CollectionService.Followup(0L, "已沟通回款安排", null, null, "completed"));
        assertThat(collections.remind(d.tenant)).isZero();
        assertThat(messages.list(d.actor(d.reviewer), "alert", null, null, 20).get("items"))
                .asList()
                .isEmpty();
    }
}
