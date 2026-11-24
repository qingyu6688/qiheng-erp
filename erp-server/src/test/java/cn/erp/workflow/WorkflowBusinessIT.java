package cn.erp.workflow;

import java.util.*;

import org.flowable.engine.ProcessEngine;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import cn.erp.identity.*;
import cn.erp.opening.OpeningService;
import cn.erp.purchasing.*;
import cn.erp.reversal.*;
import cn.erp.salesfinance.*;
import cn.erp.salesorder.*;
import cn.erp.shared.*;

import static cn.erp.workflow.WorkflowTestData.*;
import static org.assertj.core.api.Assertions.*;

/** 五类适配器均调用真实业务命令，验证审核结束与过账仍是两个步骤。 */
@SpringBootTest
@ActiveProfiles("test")
class WorkflowBusinessIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessService access;
    @Autowired SalesOrderService sales;
    @Autowired WorkflowModels models;
    @Autowired WorkflowTasks tasks;
    @Autowired WorkflowQueries queries;
    @Autowired ProcessEngine engine;
    @Autowired PurchaseOrderService purchases;
    @Autowired RefundApplicationService refunds;
    @Autowired ReceiptService receipts;
    @Autowired OpeningService opening;
    @Autowired ReversalService reversals;
    WorkflowTestData d;

    @BeforeEach
    void prepare() {
        d = new WorkflowTestData(jdbc, access, sales, models);
    }

    @AfterEach
    void cleanup() {
        if (d != null) d.cleanup(engine);
    }

    long id(Map<String, Object> row) {
        return Long.parseLong(row.get("id").toString());
    }

    long version(String type, long id) {
        return jdbc.queryForObject(
                "SELECT version FROM " + type + " WHERE tenant_id=? AND id=?",
                Long.class,
                d.tenant,
                id);
    }

    String status(String type, long id) {
        return jdbc.queryForObject(
                "SELECT status FROM " + type + " WHERE tenant_id=? AND id=?",
                String.class,
                d.tenant,
                id);
    }

    long instance(String type, long id) {
        return jdbc.queryForObject(
                "SELECT wf_instance_id FROM " + type + " WHERE tenant_id=? AND id=?",
                Long.class,
                d.tenant,
                id);
    }

    long task(long instance) {
        return jdbc.queryForObject(
                "SELECT id FROM wf_task_ext WHERE tenant_id=? AND instance_id=? AND"
                    + " status='pending'",
                Long.class,
                d.tenant,
                instance);
    }

    void finish(long instance) {
        long task = task(instance);
        tasks.decide(
                d.actor(d.manager),
                task,
                "approve",
                key(),
                new WorkflowTasks.Decision(0L, "同意", null, null, null, null, null));
    }

    long stock() {
        return id(
                opening.createStock(
                        d.actor(d.maker),
                        key(),
                        new OpeningService.Stock(
                                "gain",
                                d.warehouse,
                                d.day,
                                "盘盈核实",
                                List.of(new OpeningService.StockLine(d.sku, d.unit, "1", "20")),
                                null)));
    }

    long postedStock() {
        long id = stock();
        opening.command(
                d.actor(d.maker), true, id, "submit", key(), new OpeningService.Version(0L, null));
        opening.command(
                d.actor(d.manager),
                true,
                id,
                "approve",
                key(),
                new OpeningService.Version(1L, null));
        opening.command(
                d.actor(d.financeA), true, id, "post", key(), new OpeningService.Version(2L, null));
        return id;
    }

    long refund() {
        long account = Ids.next();
        jdbc.update(
                "INSERT INTO cash_account(id,tenant_id,company_id,code,name,account_type)"
                    + " VALUES(?,?,?,'BANK','验收银行','bank')",
                account,
                d.tenant,
                d.company);
        jdbc.update(
                "INSERT INTO cash_account_balance(id,tenant_id,company_id,cash_account_id)"
                    + " VALUES(?,?,?,?)",
                Ids.next(),
                d.tenant,
                d.company,
                account);
        long receipt =
                id(
                        receipts.create(
                                d.actor(d.maker),
                                key(),
                                new FinanceModels.Receipt(
                                        "customer",
                                        d.customer,
                                        null,
                                        account,
                                        "100",
                                        d.day,
                                        null,
                                        "WF" + key(),
                                        null,
                                        null)));
        receipts.command(
                d.actor(d.maker),
                receipt,
                "submit",
                key(),
                new FinanceModels.Version(0L, null, null));
        receipts.command(
                d.actor(d.manager),
                receipt,
                "confirm",
                key(),
                new FinanceModels.Version(1L, null, null));
        return id(
                refunds.create(
                        d.actor(d.maker),
                        key(),
                        new FinanceModels.Refund(
                                d.customer, receipt, account, "30", d.day, "客户退款", null, null)));
    }

    @Test
    void purchaseUsesSelectedReviewerAndKeepsOriginalPendingState() {
        jdbc.update(
                "INSERT INTO partner_role(id,tenant_id,partner_id,role,price_tax_inclusive)"
                    + " VALUES(?,?,?,'supplier',1)",
                Ids.next(),
                d.tenant,
                d.customer);
        var reviewer =
                new SimpleModel.Node(
                        "select",
                        "approver",
                        "选择采购复核人",
                        new SimpleModel.Assignee(
                                "starter_select", null, null, null, null, null, null, "reject"),
                        "and",
                        null,
                        null,
                        null,
                        false);
        d.bind(root(reviewer), "purchase_order");
        long id =
                id(
                        purchases.create(
                                d.actor(d.maker),
                                key(),
                                new PurchaseOrderModels.Input(
                                        "" + d.customer,
                                        d.day,
                                        d.day.plusDays(1),
                                        true,
                                        "0",
                                        List.of(
                                                new PurchaseOrderModels.Line(
                                                        null,
                                                        null,
                                                        "" + d.sku,
                                                        "" + d.warehouse,
                                                        "" + d.unit,
                                                        "2",
                                                        "100",
                                                        null,
                                                        "0",
                                                        null,
                                                        null)),
                                        List.of(),
                                        null,
                                        null,
                                        null)));
        assertThat(queries.startOptions(d.actor(d.maker), "purchase_order", id).get("nodes"))
                .asList()
                .hasSize(1);
        purchases.command(
                d.actor(d.maker),
                id,
                "submit",
                key(),
                new PurchaseOrderModels.Command(
                        0L, null, null, Map.of("select", List.of(d.manager))));
        assertThat(status("purchase_order", id)).isEqualTo("pending");
        assertThatThrownBy(
                        () ->
                                purchases.command(
                                        d.actor(d.manager),
                                        id,
                                        "approve",
                                        key(),
                                        new PurchaseOrderModels.Command(1L, null, null)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(409));
        finish(instance("purchase_order", id));
        assertThat(status("purchase_order", id)).isEqualTo("approved");
    }

    @Test
    void unboundReceiptRefundKeepsExistingSingleApproval() {
        long id = refund();
        refunds.command(
                d.actor(d.maker), id, "submit", key(), new FinanceModels.Version(0L, null, null));
        assertThat(instance("refund_request", id)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wf_business_binding WHERE tenant_id=? AND business_type='refund_request'",
                Long.class, d.tenant)).isZero();
        refunds.command(
                d.actor(d.manager), id, "approve", key(), new FinanceModels.Version(1L, null, null));
        assertThat(status("refund_request", id)).isEqualTo("pending_payment");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM journal WHERE tenant_id=? AND source_type='refund_request'",
                Long.class, d.tenant)).isZero();
    }

    @Test
    void refundRetainsCreditReservationAndWaitsForPaymentAfterApproval() {
        d.bind(root(approve("review", List.of(d.manager), "and", null)), "refund_request");
        long id = refund();
        refunds.command(
                d.actor(d.maker), id, "submit", key(), new FinanceModels.Version(0L, null, null));
        assertThat(status("refund_request", id)).isEqualTo("pending_review");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM settlement_allocation WHERE tenant_id=? AND"
                                    + " status='reserved'",
                                Long.class,
                                d.tenant))
                .isEqualTo(1);
        finish(instance("refund_request", id));
        assertThat(status("refund_request", id)).isEqualTo("pending_payment");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM journal WHERE tenant_id=? AND"
                                    + " source_type='refund_request'",
                                Long.class,
                                d.tenant))
                .isZero();
    }

    @Test
    void refundRejectionReleasesCreditInSameTransaction() {
        d.bind(root(approve("review", List.of(d.manager), "and", null)), "refund_request");
        long id = refund();
        refunds.command(
                d.actor(d.maker), id, "submit", key(), new FinanceModels.Version(0L, null, null));
        long task = task(instance("refund_request", id));
        tasks.decide(
                d.actor(d.manager),
                task,
                "reject",
                key(),
                new WorkflowTasks.Decision(0L, "原路退回资料不全", null, null, null, null, "starter"));
        assertThat(status("refund_request", id)).isEqualTo("draft");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM settlement_allocation WHERE tenant_id=? AND"
                                    + " status='reserved'",
                                Long.class,
                                d.tenant))
                .isZero();
    }

    @Test
    void adjustmentApprovalDoesNotPostAndClosingPeriodStillBlocksPosting() {
        d.bind(root(approve("review", List.of(d.manager), "and", null)), "stock_adjustment");
        long id = stock();
        opening.command(
                d.actor(d.maker), true, id, "submit", key(), new OpeningService.Version(0L, null));
        finish(instance("stock_adjustment", id));
        assertThat(status("stock_adjustment", id)).isEqualTo("approved");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM inventory_movement WHERE tenant_id=?",
                                Long.class,
                                d.tenant))
                .isZero();
        jdbc.update(
                "UPDATE accounting_period SET status='closed',closed_at=UTC_TIMESTAMP(3) WHERE"
                    + " tenant_id=?",
                d.tenant);
        assertThatThrownBy(
                        () ->
                                opening.command(
                                        d.actor(d.financeA),
                                        true,
                                        id,
                                        "post",
                                        key(),
                                        new OpeningService.Version(2L, null)))
                .isInstanceOf(ApiException.class);
        assertThat(status("stock_adjustment", id)).isEqualTo("approved");
    }

    @Test
    void reversalStartsOnCreateSupportsPreviewAndOnlyApproves() {
        long source = postedStock();
        d.bind(root(approve("review", List.of(d.manager), "and", null)), "document_reversal");
        assertThat(
                        queries.reversalStartOptions(
                                        d.actor(d.maker), "stock_adjustment", source, d.day)
                                .get("nodes"))
                .asList()
                .isEmpty();
        long id =
                id(
                        reversals.create(
                                d.actor(d.maker),
                                key(),
                                new ReversalModels.Input(
                                        "stock_adjustment", source, d.day, "重复盘盈", null)));
        assertThat(status("document_reversal", id)).isEqualTo("pending");
        finish(instance("document_reversal", id));
        assertThat(status("document_reversal", id)).isEqualTo("approved");
        assertThat(status("stock_adjustment", source)).isEqualTo("posted");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM journal WHERE tenant_id=? AND"
                                    + " posting_action='reverse'",
                                Long.class,
                                d.tenant))
                .isZero();
    }

    @Test
    void unboundAdjustmentAndReversalKeepExistingSingleApproval() {
        long source = postedStock();
        long id =
                id(
                        reversals.create(
                                d.actor(d.maker),
                                key(),
                                new ReversalModels.Input(
                                        "stock_adjustment", source, d.day, "重复盘盈")));
        assertThat(instance("document_reversal", id)).isZero();
        reversals.command(
                d.actor(d.manager), id, "approve", key(), new ReversalModels.Version(0L, null));
        assertThat(status("document_reversal", id)).isEqualTo("approved");
    }

    @Test
    void minimumPriceApprovalCannotFallBackToSingleApproval() {
        jdbc.update("UPDATE sku SET min_sale_price=101 WHERE tenant_id=?", d.tenant);
        jdbc.update(
                "UPDATE company_policy SET below_min_price_policy='approval' WHERE tenant_id=?",
                d.tenant);
        long order = d.order("1", "100");
        assertThatThrownBy(() -> d.submit(order))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("PRICE_APPROVAL_REQUIRED"));
        assertThat(d.status(order)).isEqualTo("draft");
    }

    @Test
    void unreachableSelectionIsNotRequiredAndVersionSnapshotIsImmutable() {
        var branch =
                new SimpleModel.Node(
                        "amount",
                        "condition_branch",
                        "金额",
                        null,
                        null,
                        null,
                        List.of(
                                new SimpleModel.Branch(
                                        "大额",
                                        new SimpleModel.Condition(
                                                "totalAmount", ">", "50000", null, null),
                                        false,
                                        new SimpleModel.Node(
                                                "select",
                                                "approver",
                                                "大额选人",
                                                new SimpleModel.Assignee(
                                                        "starter_select",
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        "reject"),
                                                "and",
                                                null,
                                                null,
                                                null,
                                                false)),
                                new SimpleModel.Branch("其他", null, true, null)),
                        null,
                        false);
        long model = d.bind(root(approve("review", List.of(d.manager), "and", branch)));
        assertThat(
                        queries.startOptions(d.actor(d.maker), "sales_order", d.order("1", "100"))
                                .get("nodes"))
                .asList()
                .isEmpty();
        assertThat(models.version(d.actor(d.maker), model, 1).get("simple"))
                .isEqualTo(root(approve("review", List.of(d.manager), "and", branch)));
    }
}
