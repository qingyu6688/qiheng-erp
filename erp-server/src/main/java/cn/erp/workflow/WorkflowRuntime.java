package cn.erp.workflow;

import java.util.*;

import org.flowable.engine.*;
import org.flowable.engine.delegate.*;
import org.flowable.task.service.delegate.DelegateTask;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.erp.identity.*;
import cn.erp.jobs.OutboxService;
import cn.erp.shared.*;

import static cn.erp.workflow.WorkflowDocuments.*;

@Service
@Transactional
public class WorkflowRuntime implements WorkflowPort {
    final WorkflowState state;
    final ProcessEngine engine;
    final AssigneeResolver assignees;
    private final OutboxService outbox;
    private final EnterpriseUpgradeMode upgrade;

    public WorkflowRuntime(WorkflowState state, ProcessEngine engine, AssigneeResolver assignees,
                           OutboxService outbox, EnterpriseUpgradeMode upgrade) {
        this.state = state;
        this.engine = engine;
        this.assignees = assignees;
        this.outbox = outbox;
        this.upgrade = upgrade;
    }

    private CreditPort credit=new CreditPort() {};
    @org.springframework.beans.factory.annotation.Autowired
    void credit(CreditPort credit) {this.credit=credit;}

    @Override
    public void start(ActorScope scope, String type, long id, Map<String, List<Long>> selections) {
        start((CompanyAccess) scope, type, id, selections, false);
    }

    public void resumeLegacy(CompanyAccess actor, String type, long id) {
        start(actor, type, id, Map.of(), true);
        if (state.jdbc.queryForObject("""
                SELECT COUNT(*) FROM wf_instance WHERE tenant_id=? AND company_id=? AND business_type=?
                    AND business_id=? AND status='running' AND flowable_instance_id IS NOT NULL
                """, Long.class, actor.tenantId(), actor.companyId(), type, id) != 1)
            throw SimpleModel.invalid("旧待审单据必须生成一个在途流程");
    }

    private void start(CompanyAccess actor, String type, long id, Map<String, List<Long>> selections,
                       boolean legacy) {
        var creditInfo=type.equals("sales_order")?credit.inspect(actor,id):Map.<String,Object>of();
        boolean needsCredit=Boolean.TRUE.equals(creditInfo.get("overCreditLimit"))&&"approval".equals(creditInfo.get("creditPolicy"));
        if (!SimpleModel.TYPES.contains(type)) throw SimpleModel.invalid("工作流业务类型无效");
        boolean generic = type.equals("wf_generic_request");
        boolean required = legacy || !generic && upgrade.active();
        boolean unbound = !generic && state.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM wf_business_binding WHERE tenant_id=? AND company_id"
                            + " IN(0,?) AND business_type=? AND enabled=1",
                        Long.class,
                        actor.tenantId(),
                        actor.companyId(),
                        type)
                == 0;
        if (unbound && required) state.models.installDefault(actor.tenantId(), actor.companyId(), type);
        if (unbound && !required) {
            if(needsCredit)throw new ApiException(422,"CREDIT_APPROVAL_REQUIRED","超额或超期须配置人工信用审批，当前没有匹配流程");
            if (type.equals("sales_order")
                    && state.jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sales_order_line l JOIN company_policy p"
                                        + " ON p.tenant_id=l.tenant_id AND"
                                        + " p.company_id=l.company_id WHERE l.tenant_id=? AND"
                                        + " l.company_id=? AND l.order_id=? AND l.is_deleted=0 AND"
                                        + " l.below_min_price=1 AND"
                                        + " p.below_min_price_policy='approval'",
                                    Long.class,
                                    actor.tenantId(),
                                    actor.companyId(),
                                    id)
                            > 0)
                throw new ApiException(422, "PRICE_APPROVAL_REQUIRED", "低于最低售价须配置人工价格审批，当前没有匹配流程");
            state.jdbc.update(
                    "UPDATE "
                            + table(type)
                            + " SET wf_instance_id=0 WHERE tenant_id=? AND company_id=? AND id=?",
                    actor.tenantId(),
                    actor.companyId(),
                    id);
            return;
        }
        var summary = new LinkedHashMap<>(state.documents.snapshot(actor, type, id));
        boolean needsPrice =
                Boolean.TRUE.equals(summary.get("belowMinPrice"))
                        && "approval".equals(summary.get("belowMinPricePolicy"));
        summary.put("priceApprovalRequired", needsPrice);
        summary.put("creditApprovalRequired", needsCredit);
        var binding = generic ? state.models.genericBinding(actor, number(summary.get("modelId")))
                : state.models.selected(actor, type, summary);
        if (required && binding == null) {
            state.models.installDefault(actor.tenantId(), actor.companyId(), type);
            binding = state.models.selected(actor, type, summary);
        }
        if (binding == null) {
            if (generic) throw SimpleModel.invalid("申请关联的流程未发布或已停用");
            if(needsCredit)throw new ApiException(422,"CREDIT_APPROVAL_REQUIRED","当前没有匹配的人工信用审批流程");
            if (needsPrice)
                throw new ApiException(
                        422, "PRICE_APPROVAL_REQUIRED", "低于最低售价须配置并完成人工价格审批，当前没有匹配的流程");
            state.jdbc.update(
                    "UPDATE "
                            + table(type)
                            + " SET wf_instance_id=0 WHERE tenant_id=? AND company_id=? AND id=?",
                    actor.tenantId(),
                    actor.companyId(),
                    id);
            return;
        }
        long model = number(binding.get("model_id"));
        int version = ((Number) binding.get("published_version")).intValue();
        var published = state.models.published(actor.tenantId(), model, version);
        var root = state.models.tree(published);
        var nodes = SimpleModel.validate(root, type);
        var selected = selections == null ? Map.<String, List<Long>>of() : selections;
        if (selected.size() > 100
                || selected.entrySet().stream()
                        .anyMatch(
                                e ->
                                        !nodes.containsKey(e.getKey())
                                                || nodes.get(e.getKey()).assignee() == null
                                                || !nodes.get(e.getKey())
                                                        .assignee()
                                                        .strategy()
                                                        .equals("starter_select")
                                                || e.getValue() == null
                                                || e.getValue().isEmpty()
                                                || e.getValue().size() > 100
                                                || e.getValue().stream()
                                                        .anyMatch(u -> u == null || u <= 0)))
            throw SimpleModel.invalid("自选人员内容无效或包含非自选节点");
        if (needsPrice && !pricePath(root, summary))
            throw new ApiException(422, "PRICE_APPROVAL_REQUIRED", "当前可达流程路径没有人工价格审批节点，请调整流程");
        if(needsCredit&&!reviewPath(root,summary,true))throw new ApiException(422,"CREDIT_APPROVAL_REQUIRED","当前可达流程路径没有人工信用审批节点");
        long instance = Ids.next();
        var departments =
                state.jdbc.queryForList(
                        "SELECT department_id FROM org_user_department WHERE tenant_id=? AND"
                            + " company_id=? AND user_id=? AND is_primary=1",
                        Long.class,
                        actor.tenantId(),
                        actor.companyId(),
                        actor.userId());
        state.jdbc.update(
                "INSERT INTO"
                    + " wf_instance(id,tenant_id,company_id,model_id,model_version,business_type,business_id,business_no,title,summary,starter_id,starter_dept_id,selections)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                instance,
                actor.tenantId(),
                actor.companyId(),
                model,
                version,
                type,
                id,
                summary.get("documentNo"),
                summary.get("title"),
                state.json.writeValueAsString(summary),
                actor.userId(),
                departments.isEmpty() ? 0 : departments.getFirst(),
                state.json.writeValueAsString(selected));
        state.jdbc.update(
                "UPDATE "
                        + table(type)
                            + " SET wf_instance_id=?" + (legacy ? ",updated_at=updated_at" : "")
                            + " WHERE tenant_id=? AND company_id=? AND id=?",
                instance,
                actor.tenantId(),
                actor.companyId(),
                id);
        var raw = state.raw(actor.tenantId(), instance, false);
        state.action(raw, null, actor.userId(), "start", null, Map.of("modelVersion", version));
        var process =
                engine.getRuntimeService()
                        .startProcessInstanceById(
                                published.get("flowable_definition_id").toString(),
                                "erp:" + actor.tenantId() + ":" + type + ":" + id,
                                Map.of("wfInstance", instance, "wfTenant", actor.tenantId()));
        state.jdbc.update(
                "UPDATE wf_instance SET flowable_instance_id=? WHERE tenant_id=? AND id=?",
                process.getId(),
                actor.tenantId(),
                instance);
        state.audit(
                actor,
                "start",
                instance,
                Map.of("businessType", type, "businessId", Long.toString(id)));
        state.signal(raw);
    }

    @Override
    public void guard(ActorScope actor, String type, long id, String action) {
        if (!Set.of("approve", "reject", "withdraw", "cancel", "void").contains(action)
                || WorkflowContext.allows(actor, type, id)) return;
        if (!state.jdbc
                .queryForList(
                        "SELECT id FROM wf_instance WHERE tenant_id=? AND company_id=? AND"
                            + " business_type=? AND business_id=? AND status='running'",
                        Long.class,
                        actor.tenantId(),
                        actor.companyId(),
                        type,
                        id)
                .isEmpty())
            throw new ApiException(409, "WORKFLOW_IN_PROGRESS", "单据正在流程审批中，请从审批中心处理");
    }

    public void prepare(DelegateExecution execution) {
        var instance =
                state.raw(
                        ((Number) execution.getVariable("wfTenant")).longValue(),
                        ((Number) execution.getVariable("wfInstance")).longValue(),
                        false);
        state.running(instance);
        String key = execution.getCurrentActivityId().substring("prepare_".length());
        var node = state.node(instance, key);
        List<Long> users;
        execution.setVariable("wfReject_" + key, false);
        try {
            users =
                    assignees.resolve(
                            instance, node, state.summary(instance), state.selections(instance));
        } catch (ApiException error) {
            if ("WORKFLOW_APPROVER_UNAVAILABLE".equals(error.getErrorCode())
                    && "reject".equals(node.assignee().emptyHandler())
                    && !node.type().equals("copy")) {
                execution.setVariable("wfReject_" + key, true);
                execution.setVariable("wfUsers_" + key, List.of());
                return;
            }
            throw error;
        }
        if (node.type().equals("copy")) {
            for (long user : users) {
                long id = Ids.next();
                int inserted =
                        state.jdbc.update(
                                "INSERT IGNORE INTO"
                                    + " wf_copy(id,tenant_id,company_id,instance_id,user_id,node_key,approval_epoch)"
                                    + " VALUES(?,?,?,?,?,?,?)",
                                id,
                                instance.get("tenant_id"),
                                instance.get("company_id"),
                                instance.get("id"),
                                user,
                                key,
                                instance.get("approval_epoch"));
                if (inserted == 1) state.notice(instance, user, null, "copy", "wf:copy:" + id);
            }
            return;
        }
        execution.setVariable("wfUsers_" + key, users.stream().map(String::valueOf).toList());
    }

    public void created(DelegateTask task) {
        long tenant = ((Number) task.getVariable("wfTenant")).longValue(),
                instanceId = ((Number) task.getVariable("wfInstance")).longValue();
        var instance = state.raw(tenant, instanceId, false);
        var node = state.node(instance, task.getTaskDefinitionKey());
        long id = Ids.next();
        var timeout = node.timeout();
        state.jdbc.update(
                "INSERT INTO"
                    + " wf_task_ext(id,tenant_id,company_id,instance_id,flowable_task_id,node_key,node_name,node_kind,assignee_id,approval_epoch,price_approval,timeout_action,repeat_hours,due_at,next_remind_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(3),INTERVAL ?"
                    + " HOUR),DATE_ADD(UTC_TIMESTAMP(3),INTERVAL ? HOUR))",
                id,
                tenant,
                instance.get("company_id"),
                instanceId,
                task.getId(),
                node.id(),
                node.name(),
                node.type(),
                Long.parseLong(task.getAssignee()),
                instance.get("approval_epoch"),
                Boolean.TRUE.equals(node.priceApproval()),
                timeout == null ? null : timeout.action(),
                timeout == null ? null : timeout.repeat() == null ? 12 : timeout.repeat(),
                timeout == null ? null : timeout.hours(),
                timeout == null ? null : timeout.hours());
        state.jdbc.update("UPDATE wf_task_ext SET credit_approval=? WHERE tenant_id=? AND id=?",Boolean.TRUE.equals(node.creditApproval()),tenant,id);
        state.jdbc.update(
                "UPDATE wf_instance SET flowable_instance_id=COALESCE(flowable_instance_id,?) WHERE"
                    + " tenant_id=? AND id=?",
                task.getProcessInstanceId(),
                tenant,
                instanceId);
        state.assigned(
                instance,
                Map.of("id", id, "assignee_id", Long.parseLong(task.getAssignee()), "version", 0));
    }

    public boolean condition(DelegateExecution execution, String key, int branch) {
        var instance =
                state.raw(
                        ((Number) execution.getVariable("wfTenant")).longValue(),
                        ((Number) execution.getVariable("wfInstance")).longValue(),
                        false);
        var node = state.node(instance, key);
        if (node.branches() == null || branch < 0 || branch >= node.branches().size())
            throw SimpleModel.invalid("条件分支未登记");
        return SimpleModel.matches(
                node.branches().get(branch).condition(), state.summary(instance));
    }

    public void scheduleDelay(DelegateExecution execution) {
        var instance = state.raw(number(execution.getVariable("wfTenant")),number(execution.getVariable("wfInstance")),false);
        var node = state.node(instance,execution.getCurrentActivityId());
        state.jdbc.update("""
                INSERT INTO wf_delay(execution_id,tenant_id,company_id,instance_id,node_key,due_at)
                VALUES(?,?,?,?,?,TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(3))) AS staged
                ON DUPLICATE KEY UPDATE node_key=staged.node_key,due_at=staged.due_at
                """,execution.getId(),instance.get("tenant_id"),instance.get("company_id"),instance.get("id"),node.id(),node.delaySeconds());
        state.action(instance,null,0,"delay_scheduled",null,Map.of("nodeKey",node.id()));
    }

    public void publishTrigger(DelegateExecution execution) {
        long tenant = number(execution.getVariable("wfTenant"));
        long id = number(execution.getVariable("wfInstance"));
        var instance = state.raw(tenant,id,false);
        var node = state.node(instance,execution.getCurrentActivityId());
        long event = outbox.publish(tenant,"workflow_trigger",Ids.next(),0,
                "workflow.trigger",Map.of("event",node.triggerEvent(),"nodeKey",node.id(),
                        "instanceId",Long.toString(id),"companyId",instance.get("company_id").toString()));
        state.action(instance,null,0,"trigger_enqueued",null,Map.of("nodeKey",node.id(),"eventId",Long.toString(event)));
    }

    public void finished(DelegateExecution execution) {
        long tenant = ((Number) execution.getVariable("wfTenant")).longValue(),
                id = ((Number) execution.getVariable("wfInstance")).longValue();
        var instance = state.raw(tenant, id, true);
        state.running(instance);
        String type = instance.get("business_type").toString();
        long business = number(instance.get("business_id"));
        var approvals =
                state.jdbc.queryForList(
                        "SELECT * FROM wf_task_ext WHERE tenant_id=? AND instance_id=? AND"
                            + " approval_epoch=? AND node_kind='approver' AND status='approved'"
                            + " ORDER BY ended_at DESC,id DESC",
                        tenant,
                        id,
                        instance.get("approval_epoch"));
        CompanyAccess reviewer = null;
        boolean price = false;
        boolean creditApproved=false;
        Map<String, Object> current = null;
        for (var task : approvals) {
            var actor =
                    state.documents.eligible(
                            tenant,
                            number(instance.get("company_id")),
                            number(task.get("assignee_id")),
                            type,
                            business,
                            true,
                            false,number(task.get("credit_approval"))==1);
            if (actor == null)
                throw new ApiException(422, "WORKFLOW_REVIEW_INVALID", "已有人工审批人的权限或任职已失效，请退回重审");
            if (reviewer == null) {
                reviewer = actor;
                current = state.documents.snapshot(actor, type, business);
            }
            if(number(task.get("credit_approval"))==1&&WorkflowDocuments.creditEligible(actor)&&Objects.equals(task.get("credit_fingerprint"),current.get("creditFingerprint")))creditApproved=true;
            if (Boolean.TRUE.equals(task.get("price_approval"))
                    || number(task.get("price_approval")) == 1) {
                if (actor.hasPermission("sales_order:price_approve")
                        && Objects.equals(
                                task.get("price_fingerprint"), current.get("priceFingerprint")))
                    price = true;
            }
        }
        if (reviewer == null)
            throw new ApiException(422, "WORKFLOW_REVIEW_REQUIRED", "流程完成前必须保留有效人工复核");
        try (var permit =
                WorkflowContext.open(
                        reviewer,
                        type,
                        business,
                        price ? Objects.toString(current.get("priceFingerprint"), null) : null,creditApproved?Objects.toString(current.get("creditFingerprint"),null):null)) {
            state.documents
                    .handler(type)
                    .decide(reviewer, business, "approved", "工作流人工审批通过", "wf-" + id + "-approved");
        }
        state.jdbc.update(
                "UPDATE wf_instance SET"
                    + " status='approved',ended_at=UTC_TIMESTAMP(3),version=version+1 WHERE"
                    + " tenant_id=? AND id=?",
                tenant,
                id);
        state.jdbc.update(
                "UPDATE wf_task_ext SET"
                    + " status='cancelled',ended_at=UTC_TIMESTAMP(3),version=version+1 WHERE"
                    + " tenant_id=? AND instance_id=? AND status IN('pending','waiting_sign')",
                tenant,
                id);
        state.action(
                instance, null, reviewer.userId(), "finish", null, Map.of("outcome", "approved"));
        state.notice(instance, number(instance.get("starter_id")), null, "done", "wf:finish:" + id);
        state.signal(instance);
    }

    void end(
            CompanyAccess actor,
            Map<String, Object> instance,
            String outcome,
            String reason,
            boolean system) {
        state.running(instance);
        String type = instance.get("business_type").toString();
        long business = number(instance.get("business_id")), id = number(instance.get("id"));
        if (instance.get("flowable_instance_id") != null)
            engine.getRuntimeService()
                    .deleteProcessInstance(
                            instance.get("flowable_instance_id").toString(), "审批流程结束");
        state.jdbc.update("DELETE FROM wf_delay WHERE tenant_id=? AND instance_id=?",actor.tenantId(),id);
        CompanyAccess callback =
                system ? systemActor(actor.tenantId(), actor.companyId(), type) : actor;
        try (var permit = WorkflowContext.open(callback, type, business)) {
            state.documents
                    .handler(type)
                    .decide(callback, business, outcome, reason, "wf-" + id + "-" + outcome);
        }
        state.jdbc.update(
                "UPDATE wf_instance SET status=?,ended_at=UTC_TIMESTAMP(3),version=version+1 WHERE"
                    + " tenant_id=? AND id=?",
                outcome,
                actor.tenantId(),
                id);
        state.jdbc.update(
                "UPDATE wf_task_ext SET"
                    + " status='cancelled',ended_at=UTC_TIMESTAMP(3),version=version+1 WHERE"
                    + " tenant_id=? AND instance_id=? AND status IN('pending','waiting_sign')",
                actor.tenantId(),
                id);
        state.notice(instance, number(instance.get("starter_id")), null, "done", "wf:finish:" + id);
        state.signal(instance);
    }

    static boolean pricePath(SimpleModel.Node node,Map<String,Object> summary) {return reviewPath(node,summary,false);}
    static boolean reviewPath(SimpleModel.Node node, Map<String, Object> summary,boolean credit) {
        return SimpleModel.reachable(node,summary).stream()
                .anyMatch(current -> Boolean.TRUE.equals(credit ? current.creditApproval() : current.priceApproval()));
    }

    public void autoRejected(DelegateExecution execution) {
        long tenant = ((Number) execution.getVariable("wfTenant")).longValue();
        var instance =
                state.raw(tenant, ((Number) execution.getVariable("wfInstance")).longValue(), true);
        state.running(instance);
        String type = instance.get("business_type").toString();
        state.jdbc.update("DELETE FROM wf_delay WHERE tenant_id=? AND instance_id=?",tenant,instance.get("id"));
        long business = number(instance.get("business_id"));
        var actor = systemActor(tenant, number(instance.get("company_id")), type);
        try (var permit = WorkflowContext.open(actor, type, business)) {
            state.documents
                    .handler(type)
                    .decide(
                            actor,
                            business,
                            "rejected",
                            "没有符合规则的处理人，流程自动驳回",
                            "wf-" + instance.get("id") + "-rejected");
        }
        state.jdbc.update(
                "UPDATE wf_instance SET"
                    + " status='rejected',ended_at=UTC_TIMESTAMP(3),version=version+1 WHERE"
                    + " tenant_id=? AND id=?",
                tenant,
                instance.get("id"));
        state.jdbc.update(
                "UPDATE wf_task_ext SET"
                    + " status='cancelled',ended_at=UTC_TIMESTAMP(3),version=version+1 WHERE"
                    + " tenant_id=? AND instance_id=? AND status IN('pending','waiting_sign')",
                tenant,
                instance.get("id"));
        state.action(instance, null, 0, "empty_reject", "没有符合规则的处理人，自动驳回", Map.of());
        state.notice(
                instance,
                number(instance.get("starter_id")),
                null,
                "done",
                "wf:finish:" + instance.get("id"));
    }

    private static CompanyAccess systemActor(long tenant, long company, String type) {
        var permissions =
                new HashSet<String>(Set.of(permission(type, "view"), permission(type, "reject")));
        for (String source :
                List.of(
                        "shipment",
                        "sales_return",
                        "goods_receipt",
                        "purchase_return",
                        "receipt",
                        "payment",
                        "account_transfer",
                        "stock_adjustment",
                        "stock_misc_doc", "mfg_issue_doc", "mfg_receipt_doc"))
            permissions.add(cn.erp.shared.ManufacturingReversalPort.viewPermission(source));
        return new CompanyAccess(tenant, 0, company, true, Set.of(), permissions);
    }
}
