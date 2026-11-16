package cn.erp.workflow;

import java.util.*;

import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.erp.identity.*;
import cn.erp.shared.*;

import static cn.erp.workflow.WorkflowDocuments.*;

@Service
@Transactional
public class WorkflowTasks {
    public record Decision(
            @NotNull(message = "请提供任务版本") @PositiveOrZero(message = "版本号无效") Long version,
            @Size(max = 1000, message = "意见最多1000字") String comment,
            Long userId,
            @Size(max = 20, message = "每次最多选择20项")
                    List<@Positive(message = "编号必须大于0") Long> userIds,
            @Size(max = 20, message = "每次最多选择20项")
                    List<@Positive(message = "编号必须大于0") Long> taskIds,
            @Pattern(regexp = "before|after", message = "请选择前加签或后加签") String position,
            String targetNodeKey) {}

    private final WorkflowRuntime runtime;
    private final WorkflowState state;
    private final BusinessCommands commands;
    private final org.flowable.engine.ProcessEngine engine;

    public WorkflowTasks(
            WorkflowRuntime runtime,
            WorkflowState state,
            BusinessCommands commands,
            org.flowable.engine.ProcessEngine engine) {
        this.runtime = runtime;
        this.state = state;
        this.commands = commands;
        this.engine = engine;
    }

    public Map<String, Object> decide(
            CompanyAccess actor, long id, String action, String key, Decision input) {
        if (!Set.of("approve", "reject", "transfer", "delegate", "add-sign", "remove-sign")
                .contains(action)) throw SimpleModel.invalid("审批操作无效");
        var initial = state.task(actor, id, true);
        assigned(actor, initial);
        var instance = state.raw(actor.tenantId(), number(initial.get("instance_id")), false);
        return execute(
                actor,
                "workflow_task." + action,
                key,
                List.of(id, input),
                () -> {
                    var task = state.task(actor, id, true);
                    state.running(instance);
                    state.version(input.version(), task);
                    assigned(actor, task);
                    if (!Set.of("pending", "waiting_sign").contains(task.get("status")))
                        throw new ApiException(409, "WORKFLOW_TASK_FINISHED", "任务已处理，请刷新");
                    if (task.get("status").equals("waiting_sign") && !action.equals("remove-sign"))
                        throw new ApiException(409, "WORKFLOW_SIGN_PENDING", "正在等待后加签，请先处理加签任务");
                    if (task.get("owner_id") != null
                            && !Set.of("approve", "reject").contains(action))
                        throw new ApiException(409, "WORKFLOW_DELEGATED", "被委派人只可反馈处理意见，不能转交或变更加签");
                    switch (action) {
                        case "approve" -> approve(actor, instance, task, input.comment());
                        case "reject" -> reject(actor, instance, task, input);
                        case "transfer", "delegate" -> move(actor, instance, task, action, input);
                        case "add-sign" -> add(actor, instance, task, input);
                        case "remove-sign" -> remove(actor, instance, task, input);
                        default -> throw SimpleModel.invalid("审批操作无效");
                    }
                    reconcile(instance);
                    state.audit(
                            actor,
                            action,
                            number(instance.get("id")),
                            Map.of("taskId", Long.toString(id)));
                    state.signal(instance);
                    return Map.of(
                            "id",
                            Long.toString(id),
                            "instanceId",
                            instance.get("id").toString(),
                            "status",
                            state.jdbc.queryForObject(
                                    "SELECT status FROM wf_task_ext WHERE tenant_id=? AND id=?",
                                    String.class,
                                    actor.tenantId(),
                                    id));
                });
    }

    private void assigned(CompanyAccess actor, Map<String, Object> task) {
        if (number(task.get("assignee_id")) != actor.userId())
            throw new ApiException(403, "WORKFLOW_TASK_NOT_ASSIGNED", "只有任务当前处理人可以操作");
        var instance = state.raw(actor.tenantId(), number(task.get("instance_id")), false);
        String type = instance.get("business_type").toString();
        if (state.documents.eligible(
                        actor.tenantId(),
                        actor.companyId(),
                        actor.userId(),
                        type,
                        number(instance.get("business_id")),
                        task.get("node_kind").equals("approver"),
                        number(task.get("price_approval")) == 1,number(task.get("credit_approval"))==1)
                == null)
            throw new ApiException(403, "WORKFLOW_TASK_NOT_ALLOWED", "当前权限、任职或经办复核规则不允许处理此任务");
    }

    private void approve(
            CompanyAccess actor,
            Map<String, Object> instance,
            Map<String, Object> task,
            String comment) {
        if (task.get("owner_id") != null) {
            resolve(actor, instance, task, comment);
            return;
        }
        long id = number(task.get("id"));
        Long before =
                state.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM wf_task_ext WHERE tenant_id=? AND parent_task_id=?"
                            + " AND status='pending' AND sign_position='before'",
                        Long.class,
                        actor.tenantId(),
                        id);
        if (before != 0) throw new ApiException(409, "WORKFLOW_SIGN_PENDING", "前加签尚未完成，请等待处理");
        var current =
                state.documents.snapshot(
                        actor,
                        instance.get("business_type").toString(),
                        number(instance.get("business_id")));
        if(number(task.get("credit_approval"))==1)state.jdbc.update("UPDATE wf_task_ext SET credit_fingerprint=? WHERE tenant_id=? AND id=?",Objects.toString(current.get("creditFingerprint"),null),actor.tenantId(),id);
        String fingerprint =
                number(task.get("price_approval")) == 1
                        ? Objects.toString(current.get("priceFingerprint"), null)
                        : null;
        state.action(
                instance,
                id,
                actor.userId(),
                task.get("node_kind").equals("approver") ? "approve" : "handle",
                comment,
                Map.of());
        Long after =
                state.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM wf_task_ext WHERE tenant_id=? AND parent_task_id=?"
                            + " AND status='pending' AND sign_position='after'",
                        Long.class,
                        actor.tenantId(),
                        id);
        state.jdbc.update(
                "UPDATE wf_task_ext SET"
                    + " status=?,comment=?,price_fingerprint=?,ended_at=UTC_TIMESTAMP(3),version=version+1"
                    + " WHERE tenant_id=? AND id=?",
                after > 0 ? "waiting_sign" : "approved",
                comment,
                fingerprint,
                actor.tenantId(),
                id);
        if (after == 0) complete(instance, task);
    }

    private void complete(Map<String, Object> instance, Map<String, Object> task) {
        engine.getTaskService().complete(task.get("flowable_task_id").toString());
        if (task.get("parent_task_id") != null) {
            long parent = number(task.get("parent_task_id"));
            var rows =
                    state.jdbc.queryForList(
                            "SELECT * FROM wf_task_ext WHERE tenant_id=? AND id=? FOR UPDATE",
                            instance.get("tenant_id"),
                            parent);
            if (!rows.isEmpty()
                    && rows.getFirst().get("status").equals("waiting_sign")
                    && state.jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM wf_task_ext WHERE tenant_id=? AND"
                                        + " parent_task_id=? AND status='pending'",
                                    Long.class,
                                    instance.get("tenant_id"),
                                    parent)
                            == 0) {
                state.jdbc.update(
                        "UPDATE wf_task_ext SET"
                            + " status='approved',ended_at=UTC_TIMESTAMP(3),version=version+1 WHERE"
                            + " tenant_id=? AND id=?",
                        instance.get("tenant_id"),
                        parent);
                engine.getTaskService()
                        .complete(rows.getFirst().get("flowable_task_id").toString());
            }
        }
    }

    private void resolve(
            CompanyAccess actor,
            Map<String, Object> instance,
            Map<String, Object> task,
            String comment) {
        long owner = number(task.get("owner_id"));
        if (state.documents.eligible(
                        actor.tenantId(),
                        actor.companyId(),
                        owner,
                        instance.get("business_type").toString(),
                        number(instance.get("business_id")),
                        task.get("node_kind").equals("approver"),
                        number(task.get("price_approval")) == 1,number(task.get("credit_approval"))==1)
                == null)
            throw new ApiException(422, "WORKFLOW_APPROVER_UNAVAILABLE", "原处理人已失去权限，请流程管理员处理");
        engine.getTaskService().resolveTask(task.get("flowable_task_id").toString());
        state.jdbc.update(
                "UPDATE wf_task_ext SET assignee_id=?,owner_id=NULL,version=version+1 WHERE"
                    + " tenant_id=? AND id=?",
                owner,
                actor.tenantId(),
                task.get("id"));
        state.action(
                instance,
                number(task.get("id")),
                actor.userId(),
                "resolve",
                comment,
                Map.of("returnedTo", Long.toString(owner)));
        var updated = new LinkedHashMap<>(task);
        updated.put("assignee_id", owner);
        updated.put("version", number(task.get("version")) + 1);
        state.assigned(instance, updated);
    }

    private void reject(
            CompanyAccess actor,
            Map<String, Object> instance,
            Map<String, Object> task,
            Decision input) {
        WorkflowState.reason(input.comment());
        if (task.get("owner_id") != null) {
            resolve(actor, instance, task, "委派反馈不同意：" + input.comment());
            return;
        }
        if (task.get("node_kind").equals("handler"))
            throw new ApiException(409, "WORKFLOW_HANDLER_ONLY", "办理节点不提供驳回，请提交办理结果或联系流程管理员");
        String target = input.targetNodeKey();
        state.action(
                instance,
                number(task.get("id")),
                actor.userId(),
                "reject",
                input.comment(),
                target == null ? Map.of() : Map.of("targetNodeKey", target));
        if (target == null || target.isBlank() || target.equals("starter")) {
            state.jdbc.update(
                    "UPDATE wf_task_ext SET"
                        + " status='rejected',comment=?,ended_at=UTC_TIMESTAMP(3),version=version+1"
                        + " WHERE tenant_id=? AND id=?",
                    input.comment(),
                    actor.tenantId(),
                    task.get("id"));
            runtime.end(actor, instance, "rejected", input.comment(), false);
            return;
        }
        var past =
                state.jdbc.queryForList(
                        "SELECT node_key FROM wf_task_ext WHERE tenant_id=? AND instance_id=? AND"
                            + " node_key=? AND status='approved' AND parent_task_id IS NULL",
                        String.class,
                        actor.tenantId(),
                        instance.get("id"),
                        target);
        if (past.isEmpty())
            throw new ApiException(422, "WORKFLOW_REJECT_TARGET_INVALID", "只能退回本实例中已经经过的审批或办理节点");
        state.node(instance, target);
        var activities =
                engine
                        .getRuntimeService()
                        .getActiveActivityIds(instance.get("flowable_instance_id").toString())
                        .stream()
                        .distinct()
                        .toList();
        state.jdbc.update(
                "UPDATE wf_task_ext SET"
                    + " status='cancelled',ended_at=UTC_TIMESTAMP(3),version=version+1 WHERE"
                    + " tenant_id=? AND instance_id=? AND status IN('pending','waiting_sign')",
                actor.tenantId(),
                instance.get("id"));
        state.jdbc.update(
                "UPDATE wf_instance SET approval_epoch=approval_epoch+1,version=version+1 WHERE"
                    + " tenant_id=? AND id=?",
                actor.tenantId(),
                instance.get("id"));
        engine.getRuntimeService()
                .createChangeActivityStateBuilder()
                .processInstanceId(instance.get("flowable_instance_id").toString())
                .moveActivityIdsToSingleActivityId(activities, "prepare_" + target)
                .changeState();
    }

    private void move(
            CompanyAccess actor,
            Map<String, Object> instance,
            Map<String, Object> task,
            String action,
            Decision input) {
        WorkflowState.reason(input.comment());
        if (input.userId() == null || input.userId() == actor.userId())
            throw SimpleModel.invalid("请选择其他有效处理人");
        eligible(actor, instance, task, input.userId());
        if (action.equals("delegate"))
            engine.getTaskService()
                    .delegateTask(
                            task.get("flowable_task_id").toString(), input.userId().toString());
        else
            engine.getTaskService()
                    .setAssignee(
                            task.get("flowable_task_id").toString(), input.userId().toString());
        state.jdbc.update(
                "UPDATE wf_task_ext SET assignee_id=?,owner_id=?,version=version+1 WHERE"
                    + " tenant_id=? AND id=?",
                input.userId(),
                action.equals("delegate") ? actor.userId() : null,
                actor.tenantId(),
                task.get("id"));
        state.action(
                instance,
                number(task.get("id")),
                actor.userId(),
                action,
                input.comment(),
                Map.of("userId", input.userId().toString()));
        var updated = new LinkedHashMap<>(task);
        updated.put("assignee_id", input.userId());
        updated.put("version", number(task.get("version")) + 1);
        state.assigned(instance, updated);
    }

    private void add(
            CompanyAccess actor,
            Map<String, Object> instance,
            Map<String, Object> parent,
            Decision input) {
        WorkflowState.reason(input.comment());
        if (parent.get("parent_task_id") != null
                || input.userIds() == null
                || input.userIds().isEmpty()
                || input.userIds().size() > 20) throw SimpleModel.invalid("仅原任务可以加签，每次最多20人");
        var users = new LinkedHashSet<>(input.userIds());
        if (users.stream().anyMatch(user -> user == null || user <= 0)
                || users.contains(actor.userId())
                || users.size() != input.userIds().size())
            throw SimpleModel.invalid("加签人员无效、重复或包含当前处理人");
        if (state.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM wf_task_ext WHERE tenant_id=? AND"
                                    + " parent_task_id=? AND status IN('pending','approved')",
                                Long.class,
                                actor.tenantId(),
                                parent.get("id"))
                        + users.size()
                > 20) throw SimpleModel.invalid("单任务累计有效加签最多20人");
        String position = input.position() == null ? "before" : input.position();
        for (long user : users) {
            eligible(actor, instance, parent, user);
            if (!state.jdbc
                    .queryForList(
                            "SELECT id FROM wf_task_ext WHERE tenant_id=? AND parent_task_id=? AND"
                                + " assignee_id=? AND status IN('pending','approved')",
                            Long.class,
                            actor.tenantId(),
                            parent.get("id"),
                            user)
                    .isEmpty()) throw SimpleModel.invalid("此人员已在该任务加签中");
            var task = engine.getTaskService().newTask();
            task.setName(parent.get("node_name") + "（加签）");
            task.setAssignee(Long.toString(user));
            task.setParentTaskId(parent.get("flowable_task_id").toString());
            task.setTenantId(Long.toString(actor.tenantId()));
            engine.getTaskService().saveTask(task);
            long id = Ids.next();
            state.jdbc.update(
                    "INSERT INTO"
                        + " wf_task_ext(id,tenant_id,company_id,instance_id,flowable_task_id,node_key,node_name,node_kind,assignee_id,parent_task_id,added_by,sign_position,approval_epoch,price_approval)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id,
                    actor.tenantId(),
                    actor.companyId(),
                    instance.get("id"),
                    task.getId(),
                    parent.get("node_key"),
                    task.getName(),
                    parent.get("node_kind"),
                    user,
                    parent.get("id"),
                    actor.userId(),
                    position,
                    instance.get("approval_epoch"),
                    parent.get("price_approval"));
            state.jdbc.update("UPDATE wf_task_ext SET credit_approval=? WHERE tenant_id=? AND id=?",parent.get("credit_approval"),actor.tenantId(),id);
            state.assigned(instance, Map.of("id", id, "assignee_id", user, "version", 0));
        }
        state.jdbc.update(
                "UPDATE wf_task_ext SET version=version+1 WHERE tenant_id=? AND id=?",
                actor.tenantId(),
                parent.get("id"));
        state.action(
                instance,
                number(parent.get("id")),
                actor.userId(),
                "add_sign",
                input.comment(),
                Map.of(
                        "userIds",
                        users.stream().map(String::valueOf).toList(),
                        "position",
                        position));
    }

    private void remove(
            CompanyAccess actor,
            Map<String, Object> instance,
            Map<String, Object> parent,
            Decision input) {
        WorkflowState.reason(input.comment());
        if (input.taskIds() == null
                || input.taskIds().isEmpty()
                || input.taskIds().stream().anyMatch(id -> id == null || id <= 0))
            throw SimpleModel.invalid("请选择需要减签的有效未处理任务");
        for (long id : new LinkedHashSet<>(input.taskIds())) {
            var rows =
                    state.jdbc.queryForList(
                            "SELECT * FROM wf_task_ext WHERE tenant_id=? AND parent_task_id=? AND"
                                + " id=? AND added_by=? AND status='pending' FOR UPDATE",
                            actor.tenantId(),
                            parent.get("id"),
                            id,
                            actor.userId());
            if (rows.isEmpty())
                throw new ApiException(
                        422, "WORKFLOW_SIGN_INVALID", "只能减少自己添加且未处理的加签任务，不能移除原人工审批环节");
            engine.getTaskService()
                    .deleteTask(rows.getFirst().get("flowable_task_id").toString(), "减签");
            state.jdbc.update(
                    "UPDATE wf_task_ext SET"
                        + " status='removed',ended_at=UTC_TIMESTAMP(3),version=version+1 WHERE"
                        + " tenant_id=? AND id=?",
                    actor.tenantId(),
                    id);
        }
        state.action(
                instance,
                number(parent.get("id")),
                actor.userId(),
                "remove_sign",
                input.comment(),
                Map.of("taskIds", input.taskIds().stream().map(String::valueOf).toList()));
        state.jdbc.update(
                "UPDATE wf_task_ext SET version=version+1 WHERE tenant_id=? AND id=?",
                actor.tenantId(),
                parent.get("id"));
        if (parent.get("status").equals("waiting_sign")
                && state.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM wf_task_ext WHERE tenant_id=? AND"
                                    + " parent_task_id=? AND status='pending'",
                                Long.class,
                                actor.tenantId(),
                                parent.get("id"))
                        == 0) {
            state.jdbc.update(
                    "UPDATE wf_task_ext SET status='approved' WHERE tenant_id=? AND id=?",
                    actor.tenantId(),
                    parent.get("id"));
            complete(instance, parent);
        }
    }

    private void eligible(
            CompanyAccess actor,
            Map<String, Object> instance,
            Map<String, Object> task,
            long user) {
        if (state.documents.eligible(
                        actor.tenantId(),
                        actor.companyId(),
                        user,
                        instance.get("business_type").toString(),
                        number(instance.get("business_id")),
                        task.get("node_kind").equals("approver"),
                        number(task.get("price_approval")) == 1,number(task.get("credit_approval"))==1)
                == null)
            throw new ApiException(422, "WORKFLOW_APPROVER_UNAVAILABLE", "目标人员没有来源单据权限或不符合复核分离规则");
    }

    void reconcile(Map<String, Object> instance) {
        var process = instance.get("flowable_instance_id");
        var active =
                process == null
                        ? Set.<String>of()
                        : new HashSet<>(
                                engine
                                        .getTaskService()
                                        .createTaskQuery()
                                        .processInstanceId(process.toString())
                                        .list()
                                        .stream()
                                        .map(org.flowable.task.api.Task::getId)
                                        .toList());
        for (var task :
                state.jdbc.queryForList(
                        "SELECT id,flowable_task_id,parent_task_id FROM wf_task_ext WHERE"
                            + " tenant_id=? AND instance_id=? AND status"
                            + " IN('pending','waiting_sign')",
                        instance.get("tenant_id"),
                        instance.get("id"))) {
            boolean exists =
                    task.get("parent_task_id") == null
                            ? active.contains(task.get("flowable_task_id").toString())
                            : state.jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM wf_task_ext WHERE tenant_id=? AND"
                                                + " id=? AND status IN('pending','waiting_sign')",
                                            Long.class,
                                            instance.get("tenant_id"),
                                            task.get("parent_task_id"))
                                    > 0;
            if (!exists) {
                if (task.get("parent_task_id") != null
                        && engine.getTaskService()
                                        .createTaskQuery()
                                        .taskId(task.get("flowable_task_id").toString())
                                        .count()
                                > 0)
                    engine.getTaskService()
                            .deleteTask(task.get("flowable_task_id").toString(), "原审批节点已结束");
                state.jdbc.update(
                        "UPDATE wf_task_ext SET"
                            + " status='cancelled',ended_at=UTC_TIMESTAMP(3),version=version+1"
                            + " WHERE tenant_id=? AND id=?",
                        instance.get("tenant_id"),
                        task.get("id"));
            }
        }
    }

    public Map<String, Object> end(
            CompanyAccess actor,
            long id,
            String action,
            String key,
            long expected,
            String comment) {
        if (!Set.of("cancel", "terminate").contains(action)) throw SimpleModel.invalid("流程结束操作无效");
        var instance = state.instance(actor, id, true);
        if (action.equals("cancel") && number(instance.get("starter_id")) != actor.userId())
            throw new ApiException(403, "FORBIDDEN", "只有发起人可以撤销自己的流程");
        if (action.equals("terminate") && !actor.hasPermission("workflow_instance:terminate"))
            throw new ApiException(403, "FORBIDDEN", "没有终止流程的权限");
        WorkflowState.reason(comment);
        return execute(
                actor,
                "workflow_instance." + action,
                key,
                Map.of("id", id, "version", expected, "comment", comment),
                () -> {
                    state.running(instance);
                    state.version(expected, instance);
                    runtime.end(
                            actor,
                            instance,
                            action.equals("cancel") ? "cancelled" : "terminated",
                            comment,
                            true);
                    state.action(instance, null, actor.userId(), action, comment, Map.of());
                    reconcile(instance);
                    state.audit(actor, action, id, Map.of("reason", comment));
                    return Map.of(
                            "id",
                            Long.toString(id),
                            "status",
                            action.equals("cancel") ? "cancelled" : "terminated");
                });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> execute(
            CompanyAccess actor,
            String operation,
            String key,
            Object input,
            java.util.function.Supplier<Map<String, Object>> work) {
        return commands.execute(actor, operation, key, input, 200, work, (Class) Map.class);
    }
}
