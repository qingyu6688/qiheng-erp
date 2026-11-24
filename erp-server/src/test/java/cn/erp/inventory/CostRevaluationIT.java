package cn.erp.inventory;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.flowable.engine.ProcessEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import cn.erp.ErpApplication;
import cn.erp.accounting.LedgerService;
import cn.erp.fulfillment.ShipmentModels;
import cn.erp.fulfillment.ShipmentService;
import cn.erp.gl.GlBookService;
import cn.erp.gl.GlModels;
import cn.erp.identity.AccessService;
import cn.erp.identity.CompanyAccess;
import cn.erp.jobs.OutboxDelivery;
import cn.erp.jobs.OutboxService;
import cn.erp.purchasing.PurchaseOrderModels;
import cn.erp.purchasing.PurchaseOrderService;
import cn.erp.receiving.ReceivingModels;
import cn.erp.receiving.ReceivingService;
import cn.erp.reconciliation.ReconciliationChecks;
import cn.erp.reversal.ReversalModels;
import cn.erp.reversal.ReversalService;
import cn.erp.salesorder.SalesOrderModels;
import cn.erp.salesorder.SalesOrderService;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import cn.erp.workflow.WorkflowModels;
import cn.erp.workflow.WorkflowTasks;
import cn.erp.workflow.WorkflowTestData;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = ErpApplication.class)
@ActiveProfiles("test")
class CostRevaluationIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessService access;
    @Autowired SalesOrderService sales;
    @Autowired WorkflowModels models;
    @Autowired WorkflowTasks tasks;
    @Autowired ProcessEngine engine;
    @Autowired PurchaseOrderService orders;
    @Autowired ReceivingService receiving;
    @Autowired InventoryService inventory;
    @Autowired LedgerService ledger;
    @Autowired CostRevaluationService service;
    @Autowired ReconciliationChecks checks;
    @Autowired ShipmentService shipments;
    @Autowired ReversalService reversals;
    @Autowired tools.jackson.databind.ObjectMapper json;
    @Autowired GlBookService books;
    @Autowired OutboxService outbox;
    @Autowired OutboxDelivery delivery;
    private WorkflowTestData d;
    private long period;

    @BeforeEach void prepare() {
        d = new WorkflowTestData(jdbc, access, sales, models);
        jdbc.update("UPDATE inventory_balance SET on_hand_qty=0 WHERE tenant_id=?", d.tenant);
        jdbc.update("UPDATE inventory_cost SET qty=0,total_cost=0 WHERE tenant_id=?", d.tenant);
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role,term_type,term_value) VALUES(?,?,?,'supplier','days',30)",
                Ids.next(), d.tenant, d.customer);
        period = jdbc.queryForObject("SELECT id FROM accounting_period WHERE tenant_id=?", Long.class, d.tenant);
    }

    @AfterEach void cleanup() { if (d != null) d.cleanup(engine); }

    @Test void approvedLegacyRevaluationAppendsDifferencesAndRepeatedPostIsIdempotent() {
        long outbound = legacyMismatch();
        long id = create();
        assertThat(service.get(d.actor(d.maker), id)).containsEntry("stockDelta", "-20.00").containsEntry("afterCost", "120.00");
        command(id, "submit", d.maker);
        approve(id);
        long version = version(id);
        String requestKey = key();
        var input = new CostRevaluationModels.Command(version, null, null);
        assertThat(service.command(d.actor(d.maker), id, "post", requestKey, input)).containsEntry("status", "posted");
        assertThat(service.command(d.actor(d.maker), id, "post", requestKey, input)).containsEntry("status", "posted");
        assertThat(value("SELECT cost_delta FROM inventory_movement WHERE source_id=?", outbound)).isEqualByComparingTo("-60");
        assertThat(value("SELECT SUM(amount) FROM cost_adjustment WHERE source_type='cost_revaluation' AND source_id=?", id)).isEqualByComparingTo("-20");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal WHERE tenant_id=? AND source_type='cost_revaluation'", Long.class, d.tenant)).isEqualTo(1);
        assertThat(inventory.balance(d.actor(d.maker), new InventoryModels.Key(d.warehouse, d.sku)).cost()).isEqualByComparingTo("120");
        reconciled();
        long repeated = create();
        assertThat(service.get(d.actor(d.maker), repeated)).containsEntry("stockDelta", "0.00");
        assertCode(() -> command(repeated, "submit", d.maker), "VALIDATION_FAILED");
    }

    @Test void backdatedBusinessDateDoesNotRepriceEarlierActualIssue() {
        receive("10", "10", d.day);
        issue("6");
        receive("5", "20", d.day.minusDays(1));
        long id = create();
        assertThat(service.get(d.actor(d.maker), id)).containsEntry("stockDelta", "0.00").containsEntry("afterCost", "140.00");
        assertCode(() -> command(id, "submit", d.maker), "VALIDATION_FAILED");
        reconciled();
    }

    @Test void movementAfterApprovalInvalidatesPreviewAndRefreshRequiresNewApproval() {
        legacyMismatch();
        long id = create();
        command(id, "submit", d.maker);
        approve(id);
        issue("1");
        assertCode(() -> command(id, "post", d.maker), "COST_PREVIEW_CHANGED");
        assertThat(service.get(d.actor(d.maker), id)).containsEntry("status", "approved");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cost_adjustment WHERE source_type='cost_revaluation' AND source_id=?", Long.class, id)).isZero();
        command(id, "refresh", d.maker);
        assertThat(service.get(d.actor(d.maker), id)).containsEntry("status", "draft").containsEntry("approvedBy", null);
        assertCode(() -> command(id, "post", d.maker), "STATE_CONFLICT");
        command(id, "submit", d.maker);
        approve(id);
        command(id, "post", d.maker);
        reconciled();
    }

    @Test void closedPeriodPreventsPostingAndDoesNotWriteCorrections() {
        legacyMismatch();
        long id = create();
        command(id, "submit", d.maker);
        approve(id);
        jdbc.update("UPDATE accounting_period SET status='closed',closed_at=UTC_TIMESTAMP(3) WHERE id=?", period);
        assertCode(() -> command(id, "post", d.maker), "PERIOD_CLOSED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cost_adjustment WHERE source_type='cost_revaluation' AND source_id=?", Long.class, id)).isZero();
        assertThat(service.get(d.actor(d.maker), id)).containsEntry("status", "approved");
    }

    @Test void scopedOrCostlessActorsCannotReadOrCreateFinancialTask() {
        legacyMismatch();
        long id = create();
        var actor = d.actor(d.maker);
        var restricted = new CompanyAccess(d.tenant, d.maker, d.company, false, Set.of(d.warehouse), actor.permissions());
        var permissions = new HashSet<>(actor.permissions()); permissions.remove("cost:view");
        var costless = new CompanyAccess(d.tenant, d.maker, d.company, true, Set.of(), permissions);
        assertCode(() -> service.get(restricted, id), "FORBIDDEN");
        assertCode(() -> service.list(costless, null, null, 20), "FORBIDDEN");
        assertCode(() -> service.create(costless, key(), input()), "FORBIDDEN");
        var otherCompany = new CompanyAccess(d.tenant, d.maker, Ids.next(), true, Set.of(), actor.permissions());
        assertCode(() -> service.get(otherCompany, id), "NOT_FOUND");
    }

    @Test void mandatoryWorkflowExcludesMakerAndRejectsDirectApproval() {
        legacyMismatch();
        long id = create();
        command(id, "submit", d.maker);
        assertThat(jdbc.queryForList("SELECT t.assignee_id FROM wf_task_ext t JOIN wf_instance i ON i.id=t.instance_id AND i.tenant_id=t.tenant_id"
                + " WHERE i.tenant_id=? AND i.business_id=? AND i.business_type='cost_revaluation' AND t.status='pending'", Long.class, d.tenant, id))
                .doesNotContain(d.maker).contains(d.manager);
        assertCode(() -> command(id, "approve", d.manager), "WORKFLOW_IN_PROGRESS");
        approve(id);
        assertThat(service.get(d.actor(d.maker), id)).containsEntry("status", "approved");
    }

    @Test void competingApprovedTasksCannotApplyTheSameCostDifferenceTwice() throws Exception {
        legacyMismatch();
        long first = create(), second = create();
        for (long id : List.of(first, second)) { command(id, "submit", d.maker); approve(id); }
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> { start.await(); return attemptPost(first); });
            var two = pool.submit(() -> { start.await(); return attemptPost(second); });
            start.countDown();
            assertThat(List.of(one.get(30, java.util.concurrent.TimeUnit.SECONDS), two.get(30, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("posted", "COST_PREVIEW_CHANGED");
        }
        assertThat(inventory.balance(d.actor(d.maker), new InventoryModels.Key(d.warehouse, d.sku)).cost()).isEqualByComparingTo("120");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal WHERE tenant_id=? AND source_type='cost_revaluation'", Long.class, d.tenant)).isEqualTo(1);
        reconciled();
    }

    private String attemptPost(long id) {
        try { command(id, "post", d.maker); return "posted"; }
        catch (ApiException error) { return error.getErrorCode(); }
    }

    @Test void realShipmentReversalAlsoReversesItsRevaluationAndPreservesOriginalSnapshots() {
        long shipment = legacyShipmentMismatch();
        long id = create(); command(id, "submit", d.maker); approve(id); command(id, "post", d.maker);
        assertThat(inventory.balance(d.actor(d.maker), new InventoryModels.Key(d.warehouse, d.sku)).cost()).isEqualByComparingTo("120");
        long reversal = id(reversals.create(d.actor(d.maker), key(), new ReversalModels.Input("shipment", shipment, d.day.plusDays(3), "撤销错误发货")));
        reversals.command(d.actor(d.manager), reversal, "approve", key(), new ReversalModels.Version(0L, null));
        reversals.command(d.actor(d.maker), reversal, "post", key(), new ReversalModels.Version(1L, null));
        assertThat(value("SELECT cost_amount FROM shipment_line WHERE shipment_id=?", shipment)).isEqualByComparingTo("60");
        assertThat(value("SELECT SUM(amount) FROM cost_adjustment WHERE source_type='document_reversal' AND source_id=? AND adjust_type='revaluation'", reversal)).isEqualByComparingTo("20");
        assertThat(inventory.balance(d.actor(d.maker), new InventoryModels.Key(d.warehouse, d.sku)).cost()).isEqualByComparingTo("200");
        reconciled();
        assertThat(service.get(d.actor(d.maker), create())).containsEntry("stockDelta", "0.00");
    }

    @Test void revaluationAndOriginalShipmentReversalReachRealGeneralLedgerWithoutDifferences() {
        try {
            books.create(d.actor(d.maker), key(), new GlModels.Book("enterprise", period, null, 0L));
            var confirmed = books.confirm(d.actor(d.manager), key(), new GlModels.Confirmation(0L, "核对零期初及库存成本差异映射"));
            books.activate(d.actor(d.maker), "activate", key(), new GlModels.Version(((Number) confirmed.get("version")).longValue(), null));
            long shipment = legacyShipmentMismatch();
            long id = create(); command(id, "submit", d.maker); approve(id); command(id, "post", d.maker);
            dispatch();
            assertThat(glBalance("INVENTORY")).isEqualByComparingTo("120");
            assertThat(glBalance("COST_VARIANCE")).isEqualByComparingTo("20");
            assertThat(checks.check(d.actor(d.maker), "gl_trial", null)).isEmpty();
            assertThat(checks.check(d.actor(d.maker), "gl_operational", null)).isEmpty();
            long reversal = id(reversals.create(d.actor(d.maker), key(), new ReversalModels.Input("shipment", shipment, LocalDate.of(2026, 11, 24), "冲销重估后的发货")));
            reversals.command(d.actor(d.manager), reversal, "approve", key(), new ReversalModels.Version(0L, null));
            reversals.command(d.actor(d.maker), reversal, "post", key(), new ReversalModels.Version(1L, null));
            dispatch();
            assertThat(glBalance("INVENTORY")).isEqualByComparingTo("200");
            assertThat(glBalance("COST_VARIANCE")).isZero();
            assertThat(checks.check(d.actor(d.maker), "gl_trial", null)).isEmpty();
            assertThat(checks.check(d.actor(d.maker), "gl_operational", null)).isEmpty();
            reconciled();
        } finally {
            jdbc.update("UPDATE gl_voucher SET reversal_of_id=NULL WHERE tenant_id=?", d.tenant);
            jdbc.update("UPDATE gl_subject SET parent_id=NULL WHERE tenant_id=?", d.tenant);
            for (String table : List.of("gl_sync_error", "gl_period_close", "gl_balance", "gl_opening_balance", "gl_voucher_line", "gl_voucher", "gl_voucher_seq",
                    "gl_voucher_template", "gl_business_mapping", "gl_closing_scheme", "gl_subject", "gl_aux_item", "gl_aux_type", "gl_cash_flow_item", "gl_voucher_word",
                    "gl_digest", "gl_report_template", "gl_export_format", "gl_posting_lock", "gl_account_book", "ledger_projection_lock"))
                jdbc.update("DELETE FROM " + table + " WHERE tenant_id=?", d.tenant);
        }
    }

    private void dispatch() {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            var events = outbox.claim(100, d.tenant);
            if (events.isEmpty()) return;
            for (var event : events) assertThat(delivery.deliver(event)).as(event.type()).isTrue();
        }
        throw new AssertionError("成本重估总账事件未在测试时限内同步完成");
    }

    private BigDecimal glBalance(String account) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(IF(g.direction='D',g.amount,-g.amount)),0) FROM gl_voucher_line g"
                + " JOIN gl_voucher v ON v.tenant_id=g.tenant_id AND v.company_id=g.company_id AND v.id=g.voucher_id"
                + " JOIN journal_line l ON l.tenant_id=g.tenant_id AND l.company_id=g.company_id AND l.id=g.journal_line_id"
                + " WHERE g.tenant_id=? AND v.status IN('posted','reversed') AND l.account_code=?", BigDecimal.class, d.tenant, account);
    }

    private long legacyShipmentMismatch() {
        long first = receive("10", "10", d.day), shipment = ship("6"), second = receive("5", "20", d.day);
        LocalDateTime base = LocalDateTime.of(2026, 11, 16, 1, 0);
        posted(first, base); posted(second, base.plusSeconds(1));
        jdbc.update("UPDATE inventory_movement SET posted_at=? WHERE tenant_id=? AND source_id=? AND source_type='shipment_line'",
                Timestamp.valueOf(base.plusSeconds(2)), d.tenant, shipment);
        return shipment;
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="ERP_B06_CAPACITY", matches="true")
    void twentyThousandHistoryEventsPostWithinOneGigabyteAndLargerReplayIsRejected() throws Exception {
        legacyMismatch();
        historyPairs(10_000);
        long started = System.nanoTime();
        long id = create();
        double previewMs = (System.nanoTime() - started) / 1e6;
        var preview = service.get(d.actor(d.maker), id);
        assertThat(preview).containsEntry("stockDelta", "-20.00");
        assertThat(((Number) preview.get("eventCount")).intValue()).isEqualTo(20_003);
        long lines = jdbc.queryForObject("SELECT COUNT(*) FROM cost_revaluation_line WHERE revaluation_id=?", Long.class, id);
        assertThat(lines).isEqualTo(20_001);
        started = System.nanoTime();
        command(id, "submit", d.maker); approve(id); command(id, "post", d.maker);
        double postingMs = (System.nanoTime() - started) / 1e6;
        assertThat(inventory.balance(d.actor(d.maker), new InventoryModels.Key(d.warehouse, d.sku)).cost()).isEqualByComparingTo("120");
        reconciled();
        historyPairs(5_000);
        // 30,003条库存事件及20,001条追加差额共同超过50,000条，不得静默截断。
        assertCode(this::create, "COST_REPLAY_LIMIT");
        assertThat(Runtime.getRuntime().maxMemory()).isLessThanOrEqualTo(1_073_741_824L);
        var root = java.nio.file.Path.of(System.getenv("ERP_TEST_ROOT")).toRealPath();
        var evidence = java.nio.file.Path.of(System.getenv("ERP_CAPACITY_EVIDENCE_DIR")).toRealPath();
        if (!evidence.startsWith(root)) throw new IllegalStateException("容量证据目录必须位于隔离测试根目录");
        java.nio.file.Files.writeString(evidence.resolve("成本重估容量.json"), json.writeValueAsString(Map.of(
                "events", 20_003, "differenceLines", lines, "previewMs", previewMs, "submitApprovePostMs", postingMs,
                "maxHeapBytes", Runtime.getRuntime().maxMemory(), "stockDelta", "-20.00", "reconciliationDifferences", 0,
                "oversizedEvents", 50_004, "oversizedRejected", true)), java.nio.file.StandardOpenOption.CREATE_NEW);
    }

    private void historyPairs(int count) {
        var rows = new java.util.ArrayList<Object[]>();
        for (int index = 0; index < count; index++) {
            long movement = Ids.next(), reverse = Ids.next(), source = Ids.next();
            rows.add(new Object[]{movement, d.tenant, d.company, d.warehouse, d.sku, "post", source, source, -1, -10, null, d.day, period, d.maker});
            rows.add(new Object[]{reverse, d.tenant, d.company, d.warehouse, d.sku, "reverse", source, source, 1, 10, movement, d.day, period, d.maker});
        }
        // 成对导入的历史出库及冲销净额为零，保留真实购销原快照及原有库存、账簿。
        jdbc.batchUpdate("INSERT INTO inventory_movement(id,tenant_id,company_id,warehouse_id,sku_id,movement_type,posting_action,source_type,source_id,source_line_id,"
                + "on_hand_delta,cost_delta,reverses_movement_id,business_date,period_id,posted_at,created_by)"
                + " VALUES(?,?,?,?,?,'sales_out',?,'capacity_history',?,?,?,?,?,?,?,UTC_TIMESTAMP(3),?)", rows);
    }

    private long ship(String qty) {
        long order = id(sales.create(d.actor(d.maker), key(), new SalesOrderModels.Input(Long.toString(d.customer), d.day, null, true, "0",
                List.of(new SalesOrderModels.Line(null, Long.toString(d.sku), Long.toString(d.warehouse), Long.toString(d.unit), qty, "30", null, null, null, "0")),
                List.of(), null, null, null)));
        var submitted = sales.command(d.actor(d.maker), order, "submit", key(), new SalesOrderModels.Command(0L, null, null));
        sales.command(d.actor(d.manager), order, "approve", key(), new SalesOrderModels.Command(((Number) submitted.get("version")).longValue(), null, null));
        long line = jdbc.queryForObject("SELECT id FROM sales_order_line WHERE order_id=?", Long.class, order);
        long shipment = id(shipments.create(d.actor(d.maker), key(), new ShipmentModels.Input(d.customer, d.warehouse, d.day, "pickup", null,
                List.of(new ShipmentModels.InputLine(line, d.unit, qty)), null)));
        shipments.submit(d.actor(d.maker), shipment, key(), 0);
        shipments.post(d.actor(d.manager), shipment, key(), 1);
        return shipment;
    }

    private long legacyMismatch() {
        long first = receive("10", "10", d.day);
        long outbound = issue("6");
        long second = receive("5", "20", d.day);
        // 模拟旧系统导入的实际过账顺序；保留原出库成本及账簿金额，验证追加差额。
        LocalDateTime base = LocalDateTime.of(2026, 11, 16, 1, 0);
        posted(first, base); posted(second, base.plusSeconds(1));
        jdbc.update("UPDATE inventory_movement SET posted_at=? WHERE tenant_id=? AND source_id=? AND source_type='shipment_line'",
                Timestamp.valueOf(base.plusSeconds(2)), d.tenant, outbound);
        reconciled();
        return outbound;
    }

    private void posted(long source, LocalDateTime at) {
        jdbc.update("UPDATE inventory_movement SET posted_at=? WHERE tenant_id=? AND source_type='goods_receipt_line' AND source_line_id=?",
                Timestamp.valueOf(at), d.tenant, source);
    }

    private CostRevaluationModels.Input input() {
        return new CostRevaluationModels.Input(d.warehouse, d.sku, d.day.minusDays(1), LocalDate.of(2026, 11, 24), "核对旧系统成本差异");
    }
    private long create() { return id(service.create(d.actor(d.maker), key(), input())); }
    private long version(long id) { return ((Number) service.get(d.actor(d.maker), id).get("version")).longValue(); }
    private Map<String,Object> command(long id, String action, long user) {
        return service.command(d.actor(user), id, action, key(), new CostRevaluationModels.Command(version(id), null, null));
    }
    private void approve(long id) {
        var task = jdbc.queryForMap("SELECT t.id,t.version FROM wf_task_ext t JOIN wf_instance i ON i.id=t.instance_id AND i.tenant_id=t.tenant_id"
                + " WHERE i.tenant_id=? AND i.business_type='cost_revaluation' AND i.business_id=? AND t.status='pending' AND t.assignee_id=?",
                d.tenant, id, d.manager);
        tasks.decide(d.actor(d.manager), ((Number) task.get("id")).longValue(), "approve", key(),
                new WorkflowTasks.Decision(((Number) task.get("version")).longValue(), null, null, null, null, null, null));
    }
    private long receive(String qty, String price, LocalDate day) {
        var input = new PurchaseOrderModels.Input(Long.toString(d.customer), day, null, true, "0",
                List.of(new PurchaseOrderModels.Line(null, null, Long.toString(d.sku), Long.toString(d.warehouse), Long.toString(d.unit), qty, price, null, null, null, "0")),
                List.of(), null, null, null);
        long order = id(orders.create(d.actor(d.maker), key(), input));
        var submitted = orders.command(d.actor(d.maker), order, "submit", key(), new PurchaseOrderModels.Command(0L, null, null));
        orders.command(d.actor(d.manager), order, "approve", key(), new PurchaseOrderModels.Command(((Number) submitted.get("version")).longValue(), null, null));
        long line = jdbc.queryForObject("SELECT id FROM purchase_order_line WHERE order_id=?", Long.class, order);
        long receipt = id(receiving.createReceipt(d.actor(d.maker), key(), new ReceivingModels.ReceiptInput(d.customer, d.warehouse, day, null,
                List.of(new ReceivingModels.ReceiptLine(line, d.unit, qty)), null)));
        receiving.command(d.actor(d.maker), false, receipt, "submit", key(), 0L);
        receiving.command(d.actor(d.maker), false, receipt, "post", key(), 1L);
        return jdbc.queryForObject("SELECT id FROM goods_receipt_line WHERE receipt_id=?", Long.class, receipt);
    }
    private long issue(String qty) {
        long source = Ids.next();
        var value = inventory.issue(d.actor(d.maker), new InventoryModels.Posting(new InventoryModels.Key(d.warehouse, d.sku),
                new InventoryModels.Source("shipment_line", source, source), d.day, period, "sales_out"), new BigDecimal(qty), null);
        ledger.post(d.actor(d.maker), new LedgerService.Posting("shipment_post", source, 0, period, d.day, List.of(
                new LedgerService.Line("COGS", "D", value.movementCost(), null, null, null, null, source, "测试已出库成本"),
                new LedgerService.Line("INVENTORY", "C", value.movementCost(), null, null, d.warehouse, d.sku, source, "测试已出库成本"))));
        return source;
    }
    private void reconciled() {
        for (String type : List.of("inventory_balance", "inventory_cost", "journal_balance", "ledger_inventory"))
            assertThat(checks.check(d.actor(d.maker), type, null)).as(type).isEmpty();
    }
    private BigDecimal value(String sql, long id) { return jdbc.queryForObject(sql, BigDecimal.class, id); }
    private static String key() { return UUID.randomUUID().toString(); }
    private static long id(Map<String,Object> result) { return Long.parseLong(result.get("id").toString()); }
    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
}
