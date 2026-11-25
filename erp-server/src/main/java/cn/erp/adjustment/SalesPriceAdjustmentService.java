package cn.erp.adjustment;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ActorScope;
import cn.erp.shared.ApiException;
import cn.erp.shared.AuditService;
import cn.erp.shared.BusinessCommands;
import cn.erp.shared.DocumentNumbers;
import cn.erp.shared.Ids;
import cn.erp.shared.OrgOwnership;
import cn.erp.shared.WorkflowBusiness;
import cn.erp.shared.WorkflowContext;
import cn.erp.shared.WorkflowPort;
import static cn.erp.adjustment.SalesPriceAdjustmentQueries.*;

@Service
@Transactional(readOnly = true)
public class SalesPriceAdjustmentService implements WorkflowBusiness {
    private final JdbcTemplate jdbc;
    private final SalesPriceAdjustmentQueries queries;
    private final SalesPriceAdjustmentWriter writer;
    private final SalesPriceAdjustmentPosting posting;
    private final BusinessCommands commands;
    private final DocumentNumbers numbers;
    private final AuditService audit;
    private WorkflowPort workflow;
    private final cn.erp.shared.SalesInvoiceLock invoiceLock;
    @Autowired void workflow(WorkflowPort workflow) { this.workflow = workflow; }

    SalesPriceAdjustmentService(JdbcTemplate jdbc, SalesPriceAdjustmentQueries queries, SalesPriceAdjustmentWriter writer,
                             SalesPriceAdjustmentPosting posting, BusinessCommands commands, DocumentNumbers numbers, AuditService audit, cn.erp.shared.SalesInvoiceLock invoiceLock) {
        this.jdbc = jdbc; this.queries = queries; this.writer = writer; this.posting = posting;
        this.commands = commands; this.numbers = numbers; this.audit = audit; this.invoiceLock = invoiceLock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> create(CompanyAccess actor, String key, SalesPriceAdjustmentModels.Input input) {
        require(actor, TYPE + ":create");
        require(actor, TYPE + ":view");
        return execute(actor, "create", key, input, 201, () -> {
            invoiceLock.lock(actor);
            writer.validate(actor, input);
            long id = Ids.next();
            jdbc.update("""
                    INSERT INTO price_adjustment(id,tenant_id,company_id,adjust_no,kind,partner_id,warehouse_id,business_date,
                      reason,owner_user_id,owner_department_id,created_by,updated_by) VALUES(?,?,?,?,'sales',?,?,?,?,?,?,?,?)
                    """, id, actor.tenantId(), actor.companyId(), numbers.next(actor, "SA", input.businessDate()), input.partnerId(), input.warehouseId(),
                    input.businessDate(), input.reason().trim(), actor.userId(), OrgOwnership.department(actor), actor.userId(), actor.userId());
            writer.save(actor, id, input);
            return audited(actor, id, "create", null);
        });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> update(CompanyAccess actor, long id, String key, SalesPriceAdjustmentModels.Input input) {
        require(actor, TYPE + ":update");
        require(actor, TYPE + ":view");
        queries.head(actor, id, false);
        return execute(actor, "update", key, Map.of("id", id, "input", input), 200, () -> {
            invoiceLock.lock(actor);
            var before = queries.head(actor, id, true);
            version(before, input.version()); state(before, "draft", "rejected");
            writer.validate(actor, input);
            jdbc.update("UPDATE price_adjustment SET partner_id=?,warehouse_id=?,business_date=?,reason=?,status='draft',approved_by=NULL,approved_at=NULL,version=version+1,updated_by=?"
                    + " WHERE tenant_id=? AND company_id=? AND id=?", input.partnerId(), input.warehouseId(), input.businessDate(), input.reason().trim(), actor.userId(), actor.tenantId(), actor.companyId(), id);
            writer.save(actor, id, input);
            return audited(actor, id, "update", before);
        });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> command(CompanyAccess actor, long id, String action, String key, PriceAdjustmentModels.Command input) {
        if (!Set.of("submit", "approve", "reject", "post", "void").contains(action)) throw invalid("调价操作无效");
        if (input == null || input.reason() != null && input.reason().length() > 500) throw invalid("操作意见最多500字");
        require(actor, TYPE + ":" + (action.equals("reject") ? "approve" : action));
        require(actor, TYPE + ":view");
        if (Set.of("approve", "reject", "post").contains(action)) requireFinancial(actor, TYPE + ":" + (action.equals("reject") ? "approve" : action));
        queries.head(actor, id, false);
        return execute(actor, action, key, Map.of("id", id, "input", input), 200, () -> {
            invoiceLock.lock(actor);
            workflow.guard(actor, TYPE, id, action);
            var before = queries.head(actor, id, true);
            version(before, input.version());
            var lines = queries.lines(actor, id);
            switch (action) {
                case "submit" -> submit(actor, before, lines, input.workflowAssignees());
                case "approve", "reject" -> review(actor, before, lines, action, input.reason());
                case "post" -> { state(before, "approved"); posting.post(actor, before, lines); }
                case "void" -> { state(before, "draft", "rejected", "approved"); changeStatus(actor, id, "voided"); }
                default -> throw invalid("调价操作无效");
            }
            return audited(actor, id, action, before, input.reason());
        });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> preview(CompanyAccess actor, long id) {
        require(actor, TYPE + ":view");
        var head = queries.head(actor, id, false);
        state(head, "draft", "rejected", "pending", "approved");
        return Map.of("items", posting.preview(actor, head, queries.lines(actor, id)), "notice", "预览按当前已签收未退货金额计算，过账前会重新核对数量和金额；已开票折让须先完成原票红字");
    }

    private void submit(CompanyAccess actor, Map<String, Object> header, List<Map<String, Object>> lines, Map<String,List<Long>> selections) {
        state(header, "draft", "rejected");
        writer.revalidate(actor, header, lines);
        changeStatus(actor, number(header, "id"), "pending");
        workflow.start(actor, TYPE, number(header, "id"), selections==null?Map.of():selections);
    }

    private void review(CompanyAccess actor, Map<String, Object> header, List<Map<String, Object>> lines, String action, String reason) {
        state(header, "pending");
        if (number(header, "created_by") == actor.userId()) throw new ApiException(422, "TWO_PERSON_REQUIRED", "调价审批人不能是制单人");
        if (action.equals("reject")) {
            if (reason == null || reason.isBlank() || reason.length() > 500) throw invalid("请填写驳回原因，最多500字");
            changeStatus(actor, number(header, "id"), "rejected");
            return;
        }
        writer.revalidate(actor, header, lines);
        jdbc.update("UPDATE price_adjustment SET status='approved',approved_by=?,approved_at=UTC_TIMESTAMP(3),version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",
                actor.userId(), actor.userId(), actor.tenantId(), actor.companyId(), header.get("id"));
    }

    private void changeStatus(CompanyAccess actor, long id, String status) {
        jdbc.update("UPDATE price_adjustment SET status=?,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",
                status, actor.userId(), actor.tenantId(), actor.companyId(), id);
    }

    private Map<String, Object> audited(CompanyAccess actor, long id, String action, Map<String, Object> before) {
        return audited(actor, id, action, before, null);
    }

    private Map<String, Object> audited(CompanyAccess actor, long id, String action, Map<String, Object> before, String reason) {
        var result = queries.get(actor, id);
        var after = new LinkedHashMap<>(result);
        if (reason != null && !reason.isBlank()) after.put("operationReason", reason.trim());
        audit.record(actor, TYPE + "." + action, TYPE, id, before, after);
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> execute(CompanyAccess actor, String action, String key, Object input, int status, Supplier<Map<String, Object>> work) {
        return commands.execute(actor, TYPE + "." + action, key, input, status, work, (Class) Map.class);
    }
    private void version(Map<String, Object> row, Long version) {
        if (version == null || version < 0) throw invalid("请提供有效版本号");
        if (version != number(row, "version")) throw new ApiException(409, "VERSION_CONFLICT", "调价单已被修改，请刷新后重试");
    }
    private void state(Map<String, Object> row, String... allowed) {
        if (!Set.of(allowed).contains(row.get("status"))) throw new ApiException(409, "STATE_CONFLICT", "当前调价单状态不允许此操作");
    }

    @Override public String businessType() { return TYPE; }
    @Override public Map<String, Object> snapshot(ActorScope scope, long id) {
        CompanyAccess actor = actor(scope);
        require(actor, TYPE + ":view");
        var header = queries.head(actor, id, false);
        var result = new LinkedHashMap<String, Object>();
        result.put("createdBy", number(header, "created_by")); result.put("ownerDeptId", number(header, "owner_department_id"));
        result.put("documentNo", header.get("adjust_no")); result.put("totalAmount", queries.lines(actor,id).stream().map(line -> amount(line,"amount").abs()).reduce(BigDecimal.ZERO,BigDecimal::add).toPlainString());
        result.put("title", "销售调价 " + header.get("adjust_no"));
        result.put("warehouseIds", List.of(header.get("warehouse_id").toString())); result.put("partnerId", header.get("partner_id").toString());
        return result;
    }
    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public void decide(ActorScope scope, long id, String outcome, String comment, String key) {
        if (!WorkflowContext.allows(scope, TYPE, id) || !Set.of("approved", "rejected", "cancelled", "terminated").contains(outcome))
            throw new ApiException(403, "FORBIDDEN", "调价审批必须通过当前有效的工作流处理");
        CompanyAccess actor = actor(scope);
        var header = queries.head(actor, id, false);
        if(Set.of("cancelled","terminated").contains(outcome)) {
            require(actor,TYPE+":view");
            var before=queries.head(actor,id,true);
            state(before,"pending");
            changeStatus(actor,id,outcome.equals("cancelled")?"rejected":"voided");
            audited(actor,id,outcome,before,comment);
            return;
        }
        command(actor, id, outcome.equals("approved") ? "approve" : "reject", key,
                new PriceAdjustmentModels.Command(number(header, "version"), comment == null || comment.isBlank() ? "流程未通过" : comment));
    }
    private CompanyAccess actor(ActorScope actor) {
        if (actor instanceof CompanyAccess access) return access;
        throw new ApiException(403, "FORBIDDEN", "缺少当前公司授权");
    }
}
