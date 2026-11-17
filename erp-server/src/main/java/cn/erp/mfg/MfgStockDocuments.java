package cn.erp.mfg;

import cn.erp.identity.CompanyAccess;
import cn.erp.salesfinance.FinanceSupport;
import cn.erp.shared.ApiException;
import cn.erp.shared.AuditService;
import cn.erp.shared.BusinessCommands;
import cn.erp.shared.DocumentNumbers;
import cn.erp.shared.Ids;
import cn.erp.shared.ProductionCostPort;
import cn.erp.shared.WorkflowPort;
import cn.erp.shared.EnterpriseUpgradeMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static cn.erp.mfg.MfgStockModels.*;
import static cn.erp.salesfinance.FinanceSupport.*;

@Service
@Transactional
class MfgStockDocuments {
    private static final Set<String> OPERATIONS = Set.of("submit", "approve", "reject", "post", "cancel");
    private final JdbcTemplate jdbc;
    private final MfgOrders orders;
    private final MfgStockQueries queries;
    private final MfgStockLines lines;
    private final MfgStockPosting posting;
    private final FinanceSupport finance;
    private final ProductionCostPort productionCost;
    private final WorkflowPort workflow;
    private final EnterpriseUpgradeMode upgrade;
    private final DocumentNumbers numbers;
    private final BusinessCommands commands;
    private final AuditService audit;

    MfgStockDocuments(JdbcTemplate jdbc, MfgOrders orders, MfgStockQueries queries, MfgStockLines lines,
                      MfgStockPosting posting, FinanceSupport finance, ProductionCostPort productionCost,
                      WorkflowPort workflow, DocumentNumbers numbers, BusinessCommands commands, AuditService audit,
                      EnterpriseUpgradeMode upgrade) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.queries = queries;
        this.lines = lines;
        this.posting = posting;
        this.finance = finance;
        this.productionCost = productionCost;
        this.workflow = workflow;
        this.upgrade = upgrade;
        this.numbers = numbers;
        this.commands = commands;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    Map<String, Object> list(CompanyAccess actor, Type type, Filter filter) {
        require(actor, type, "view");
        return queries.list(actor, type, filter);
    }

    @Transactional(readOnly = true)
    Map<String, Object> detail(CompanyAccess actor, Type type, long document) {
        require(actor, type, "view");
        return queries.detail(actor, type, document);
    }

    Map<String, Object> create(CompanyAccess actor, Type type, String key, Draft input) {
        require(actor, type, "create");
        validate(type, input);
        orders.header(actor, input.workOrderId(), false);
        return execute(actor, type, "create", key, input, 201, () -> {
            var order = orders.header(actor, input.workOrderId(), true);
            validateOrder(actor, type, order, input.warehouseId(), input.direction());
            var document = Ids.next();
            insert(actor, type, document, order, input);
            lines.replace(actor, type, document, order, input);
            record(actor, type, document, "create", "draft", null);
            return document;
        });
    }

    Map<String, Object> update(CompanyAccess actor, Type type, long document, Draft input) {
        require(actor, type, "update");
        validate(type, input);
        var initial = queries.header(actor, type, document, false);
        var order = orders.header(actor, id(initial, "work_order_id"), true);
        var before = queries.header(actor, type, document, true);
        state(before, "draft");
        version(before, input.version());
        if (input.workOrderId() != id(order, "id") || type == Type.ISSUE && !input.direction().equals(before.get("direction")))
            throw invalid("工单和领退料方向在建单后不能修改");
        validateOrder(actor, type, order, input.warehouseId(), input.direction());
        jdbc.update("UPDATE " + type.head + " SET warehouse_id=?,business_date=?,remark=?,updated_by=?,version=version+1"
                        + " WHERE tenant_id=? AND company_id=? AND id=?", input.warehouseId(), input.businessDate(), input.remark(),
                actor.userId(), actor.tenantId(), actor.companyId(), document);
        lines.replace(actor, type, document, order, input);
        record(actor, type, document, "update", "draft", null);
        return queries.detail(actor, type, document);
    }

    Map<String, Object> command(CompanyAccess actor, Type type, long document, String action, String key, Revision input) {
        if (!OPERATIONS.contains(action) || input == null) throw invalid("生产单据操作无效");
        if (input.reason() != null && input.reason().length() > 500) throw invalid("操作说明不能超过 500 字");
        require(actor, type, action);
        queries.header(actor, type, document, false);
        return execute(actor, type, action, key, List.of(document, input), 200, () -> {
            workflow.guard(actor, type.head, document, action);
            var snapshot = queries.header(actor, type, document, false);
            var day = ((Date) snapshot.get("business_date")).toLocalDate();
            long period = period(actor, action, day);
            var order = orders.header(actor, id(snapshot, "work_order_id"), true);
            var before = queries.header(actor, type, document, true);
            version(before, input.version());
            if (!day.equals(((Date) before.get("business_date")).toLocalDate()))
                throw new ApiException(409, "VERSION_CONFLICT", "单据日期已变化，请刷新后重试");
            transition(actor, type, document, action, input, order, before, period, day);
            var status = queries.header(actor, type, document, false).get("status").toString();
            record(actor, type, document, action, status, input.reason());
            return document;
        });
    }

    void workflowDecide(CompanyAccess actor, Type type, long document, String outcome, String comment) {
        var snapshot = queries.header(actor, type, document, false);
        orders.header(actor, id(snapshot, "work_order_id"), true);
        var before = queries.header(actor, type, document, true);
        state(before, "pending");
        switch (outcome) {
            case "approved" -> review(actor, type, before, true, comment);
            case "rejected" -> review(actor, type, before, false, comment);
            case "cancelled", "terminated" -> setStatus(actor, type, document, "cancelled");
            default -> throw invalid("审批结果无效");
        }
        record(actor, type, document, outcome, outcome, comment);
    }

    private void transition(CompanyAccess actor, Type type, long document, String action, Revision input,
                            Map<String, Object> order, Map<String, Object> before, long period, LocalDate day) {
        switch (action) {
            case "submit" -> submit(actor, type, document, before, order, input);
            case "approve" -> review(actor, type, before, true, input.reason());
            case "reject" -> review(actor, type, before, false, input.reason());
            case "post" -> {
                state(before, "approved");
                validateOrder(actor, type, order, id(before, "warehouse_id"), before.get("direction"));
                posting.post(actor, type, before, order, queries.lines(actor, type, document, true), period, day);
            }
            case "cancel" -> {
                state(before, "draft", "rejected", "approved");
                setStatus(actor, type, document, "cancelled");
            }
            default -> throw invalid("生产单据操作无效");
        }
    }

    private long period(CompanyAccess actor, String action, LocalDate day) {
        if (!Set.of("submit", "post").contains(action)) return 0;
        long period = finance.period(actor, day);
        productionCost.requireStockPosting(actor, period);
        return period;
    }

    private void submit(CompanyAccess actor, Type type, long document, Map<String, Object> before,
                        Map<String, Object> order, Revision input) {
        state(before, "draft");
        validateOrder(actor, type, order, id(before, "warehouse_id"), before.get("direction"));
        var rows = queries.lines(actor, type, document, true);
        boolean over = type == Type.ISSUE && "issue".equals(before.get("direction"))
                && lines.overIssue(actor, id(order, "id"), rows);
        var params = new ArrayList<Object>(List.of(actor.userId(), actor.userId()));
        if (type == Type.ISSUE) params.add(over);
        params.addAll(List.of(actor.tenantId(), actor.companyId(), document));
        jdbc.update("UPDATE " + type.head + " SET status='pending',submitted_by=?,updated_by=?,version=version+1"
                        + (type == Type.ISSUE ? ",over_issue=?" : "") + " WHERE tenant_id=? AND company_id=? AND id=?", params.toArray());
        if (over || type == Type.RECEIPT || upgrade.active())
            workflow.start(actor, type.head, document, input.workflowAssignees());
    }

    private void review(CompanyAccess actor, Type type, Map<String, Object> document, boolean approve, String reason) {
        state(document, "pending");
        if (!MfgStockQueries.independentReviewer(actor, document))
            throw new ApiException(422, "SAME_OPERATOR_NOT_ALLOWED", "审核人不能是单据制单人或提交人");
        if (!approve && (reason == null || reason.isBlank() || reason.length() > 500)) throw invalid("请填写 500 字以内的驳回原因");
        if (approve && !actor.hasPermission(type.permission("approve"))) throw forbidden();
        jdbc.update("UPDATE " + type.head + " SET status=?,approved_by=?,approved_at=" + (approve ? "UTC_TIMESTAMP(3)" : "NULL")
                        + ",updated_by=?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",
                approve ? "approved" : "rejected", approve ? actor.userId() : null, actor.userId(),
                actor.tenantId(), actor.companyId(), id(document, "id"));
    }

    private void insert(CompanyAccess actor, Type type, long document, Map<String, Object> order, Draft input) {
        var values = new ArrayList<Object>(List.of(document, actor.tenantId(), actor.companyId(),
                numbers.next(actor, type.numberPrefix, input.businessDate()), input.workOrderId(), input.warehouseId(),
                input.businessDate(), order.get("owner_user_id"), order.get("owner_department_id"), actor.userId(), actor.userId()));
        values.add(input.remark());
        if (type == Type.ISSUE) values.add(input.direction());
        jdbc.update("INSERT INTO " + type.head + "(id,tenant_id,company_id,document_no,work_order_id,warehouse_id,business_date,"
                        + "owner_user_id,owner_department_id,created_by,updated_by,remark"
                        + (type == Type.ISSUE ? ",direction) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)" : ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?)"), values.toArray());
    }

    private void validate(Type type, Draft input) {
        if (input == null || input.workOrderId() == null || input.workOrderId() <= 0 || input.warehouseId() == null || input.warehouseId() <= 0)
            throw invalid("请选择生产工单和仓库");
        if (type == Type.ISSUE && (input.direction() == null || !Set.of("issue", "return").contains(input.direction())))
            throw invalid("请选择领料或退料方向");
        if (input.remark() != null && input.remark().length() > 500) throw invalid("备注不能超过 500 字");
        date(input.businessDate());
    }

    private void validateOrder(CompanyAccess actor, Type type, Map<String, Object> order, long warehouse, Object direction) {
        MfgStockPosting.orderState(type, direction, order);
        if (!actor.allowsWarehouse(warehouse) || jdbc.queryForList(
                "SELECT id FROM warehouse WHERE tenant_id=? AND company_id=? AND id=? AND status='active' FOR SHARE",
                Long.class, actor.tenantId(), actor.companyId(), warehouse).isEmpty()) throw missing();
        if (type == Type.RECEIPT && warehouse != id(order, "receive_warehouse_id")) throw invalid("完工仓必须与工单一致");
    }

    private void setStatus(CompanyAccess actor, Type type, long document, String status) {
        jdbc.update("UPDATE " + type.head + " SET status=?,updated_by=?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",
                status, actor.userId(), actor.tenantId(), actor.companyId(), document);
    }

    private void record(CompanyAccess actor, Type type, long document, String action, String status, String reason) {
        audit.record(actor, type.resource + "." + action, type.head, document, null,
                Map.of("status", status, "reason", reason == null ? "" : reason));
    }

    private Map<String, Object> execute(CompanyAccess actor, Type type, String action, String key, Object input,
                                        int status, Supplier<Long> command) {
        String result = commands.execute(actor, type.head + "." + action, key, input, status,
                () -> Long.toString(command.get()), String.class);
        return queries.detail(actor, type, Long.parseLong(result));
    }

    private static void require(CompanyAccess actor, Type type, String action) {
        if (!actor.hasPermission(type.permission(action)) || !actor.hasPermission(type.permission("view"))) throw forbidden();
    }

    private static ApiException forbidden() {
        return new ApiException(403, "FORBIDDEN", "没有当前生产单据的操作权限");
    }
}
