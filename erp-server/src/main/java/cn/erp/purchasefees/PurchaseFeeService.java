package cn.erp.purchasefees;

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
import cn.erp.shared.FinancialScope;
import cn.erp.shared.Ids;
import cn.erp.shared.OrgOwnership;
import cn.erp.shared.WorkflowBusiness;
import cn.erp.shared.WorkflowContext;
import cn.erp.shared.WorkflowPort;
import static cn.erp.purchasefees.PurchaseFeeQueries.*;

@Service
@Transactional(readOnly = true)
public class PurchaseFeeService implements WorkflowBusiness {
    private final JdbcTemplate jdbc;
    private final PurchaseFeeQueries queries;
    private final PurchaseFeeWriter writer;
    private final PurchaseFeePosting posting;
    private final BusinessCommands commands;
    private final DocumentNumbers numbers;
    private final AuditService audit;
    private WorkflowPort workflow;
    @Autowired void workflow(WorkflowPort workflow) { this.workflow = workflow; }

    PurchaseFeeService(JdbcTemplate jdbc, PurchaseFeeQueries queries, PurchaseFeeWriter writer,
                             PurchaseFeePosting posting, BusinessCommands commands, DocumentNumbers numbers, AuditService audit) {
        this.jdbc = jdbc; this.queries = queries; this.writer = writer; this.posting = posting;
        this.commands = commands; this.numbers = numbers; this.audit = audit;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> create(CompanyAccess actor, String key, PurchaseFeeModels.Input input) {
        require(actor, TYPE + ":create");
        require(actor, TYPE + ":view");
        return execute(actor, "create", key, input, 201, () -> {
            boolean inclusive=writer.validate(actor, input);
            long id = Ids.next();
            jdbc.update("""
                    INSERT INTO purchase_fee_doc(id,tenant_id,company_id,document_no,partner_id,warehouse_id,business_date,
                      reason,cost_tax_inclusive,allocation_method,owner_user_id,owner_department_id,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, id, actor.tenantId(), actor.companyId(), numbers.next(actor, "PF", input.businessDate()), input.partnerId(), writer.primaryWarehouse(actor,input),
                    input.businessDate(), input.reason().trim(), inclusive, input.allocationMethod(), actor.userId(), OrgOwnership.department(actor), actor.userId(), actor.userId());
            writer.save(actor, id, input,inclusive);
            return audited(actor, id, "create", null);
        });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> update(CompanyAccess actor, long id, String key, PurchaseFeeModels.Input input) {
        require(actor, TYPE + ":update");
        require(actor, TYPE + ":view");
        queries.head(actor, id, false);
        return execute(actor, "update", key, Map.of("id", id, "input", input), 200, () -> {
            var before = queries.head(actor, id, true);
            manual(before); version(before, input.version()); state(before, "draft", "rejected");
            boolean inclusive=writer.validate(actor, input);
            jdbc.update("UPDATE purchase_fee_doc SET partner_id=?,warehouse_id=?,business_date=?,reason=?,cost_tax_inclusive=?,allocation_method=?,status='draft',approved_by=NULL,approved_at=NULL,term_type=NULL,term_value=NULL,due_date=NULL,version=version+1,updated_by=?"
                    + " WHERE tenant_id=? AND company_id=? AND id=?", input.partnerId(), writer.primaryWarehouse(actor,input), input.businessDate(), input.reason().trim(), inclusive,input.allocationMethod(), actor.userId(), actor.tenantId(), actor.companyId(), id);
            writer.save(actor, id, input,inclusive);
            return audited(actor, id, "update", before);
        });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> command(CompanyAccess actor, long id, String action, String key, PurchaseFeeModels.Command input) {
        if (!Set.of("submit", "approve", "reject", "post", "void").contains(action)) throw invalid("采购费用操作无效");
        if (input == null || input.reason() != null && input.reason().length() > 500) throw invalid("操作意见最多500字");
        require(actor, TYPE + ":" + (action.equals("reject") ? "approve" : action));
        require(actor, TYPE + ":view");
        if (Set.of("approve", "reject", "post").contains(action)) FinancialScope.require(actor, TYPE + ":" + (action.equals("reject") ? "approve" : action));
        queries.head(actor, id, false);
        return execute(actor, action, key, Map.of("id", id, "input", input), 200, () -> {
            workflow.guard(actor, TYPE, id, action);
            var before = queries.head(actor, id, true);
            version(before, input.version());
            manual(before);
            var lines = queries.allocations(actor, id);
            switch (action) {
                case "submit" -> submit(actor, before, lines, input.workflowAssignees());
                case "approve", "reject" -> review(actor, before, lines, action, input.reason());
                case "post" -> { state(before, "approved"); posting.post(actor, before, lines,queries.lines(actor,id)); }
                case "void" -> { state(before, "draft", "rejected", "approved"); changeStatus(actor, id, "voided"); }
                default -> throw invalid("采购费用操作无效");
            }
            return audited(actor, id, action, before, input.reason());
        });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> preview(CompanyAccess actor, long id) {
        require(actor, TYPE + ":view");
        var head = queries.head(actor, id, false);
        manual(head); state(head, "draft", "rejected", "pending", "approved");
        return Map.of("items", posting.preview(actor, head, queries.allocations(actor, id)), "notice", "成本按预览时库存计算，正式过账持锁重算");
    }

    private void submit(CompanyAccess actor, Map<String, Object> header, List<Map<String, Object>> lines, Map<String,List<Long>> selections) {
        state(header, "draft", "rejected");
        writer.revalidate(actor, header, lines);
        changeStatus(actor, number(header, "id"), "pending");
        workflow.start(actor, TYPE, number(header, "id"), selections==null?Map.of():selections);
    }

    private void review(CompanyAccess actor, Map<String, Object> header, List<Map<String, Object>> lines, String action, String reason) {
        state(header, "pending");
        if (number(header, "created_by") == actor.userId()) throw new ApiException(422, "TWO_PERSON_REQUIRED", "采购费用审批人不能是制单人");
        if (action.equals("reject")) {
            if (reason == null || reason.isBlank() || reason.length() > 500) throw invalid("请填写驳回原因，最多500字");
            changeStatus(actor, number(header, "id"), "rejected");
            return;
        }
        writer.revalidate(actor, header, lines);
        var term=cn.erp.shared.PaymentTerms.resolve(jdbc,actor,number(header,"partner_id"),"supplier");
        jdbc.update("UPDATE purchase_fee_doc SET status='approved',approved_by=?,approved_at=UTC_TIMESTAMP(3),term_type=?,term_value=?,due_date=?,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",
                actor.userId(),term.type(),term.value(),cn.erp.shared.PaymentTerms.dueDate(date(header,"business_date"),term), actor.userId(), actor.tenantId(), actor.companyId(), header.get("id"));
    }

    private void changeStatus(CompanyAccess actor, long id, String status) {
        jdbc.update("UPDATE purchase_fee_doc SET status=?,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",
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
        if (version != number(row, "version")) throw new ApiException(409, "VERSION_CONFLICT", "采购费用单已被修改，请刷新后重试");
    }
    private void state(Map<String, Object> row, String... allowed) {
        if (!Set.of(allowed).contains(row.get("status"))) throw new ApiException(409, "STATE_CONFLICT", "当前采购费用单状态不允许此操作");
    }

    private void manual(Map<String,Object> header) {
        if(!"manual".equals(header.get("source_kind"))) throw invalid("随单费用只能通过原收货单整体处理");
    }

    @Override public String businessType() { return TYPE; }
    @Override public Map<String, Object> snapshot(ActorScope scope, long id) {
        CompanyAccess actor = actor(scope);
        require(actor, TYPE + ":view");
        var header = queries.head(actor, id, false);
        manual(header);
        var result = new LinkedHashMap<String, Object>();
        result.put("createdBy", number(header, "created_by")); result.put("ownerDeptId", number(header, "owner_department_id"));
        result.put("documentNo", header.get("document_no")); result.put("totalAmount", amount(header, "amount").abs().toPlainString());
        result.put("title", "采购采购费用 " + header.get("document_no"));
        result.put("warehouseIds", queries.allocations(actor,id).stream().map(line->line.get("source_warehouse_id").toString()).distinct().toList()); result.put("partnerId", header.get("partner_id").toString());
        return result;
    }
    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public void decide(ActorScope scope, long id, String outcome, String comment, String key) {
        if (!WorkflowContext.allows(scope, TYPE, id) || !Set.of("approved", "rejected", "cancelled", "terminated").contains(outcome))
            throw new ApiException(403, "FORBIDDEN", "采购费用审批必须通过当前有效的工作流处理");
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
                new PurchaseFeeModels.Command(number(header, "version"), comment == null || comment.isBlank() ? "流程未通过" : comment));
    }
    private CompanyAccess actor(ActorScope actor) {
        if (actor instanceof CompanyAccess access) return access;
        throw new ApiException(403, "FORBIDDEN", "缺少当前公司授权");
    }
}
