package cn.erp.workflow;

import java.util.*;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import cn.erp.identity.*;
import cn.erp.shared.*;

import static cn.erp.workflow.WorkflowDocuments.*;

@Component
class WorkflowState {
    final JdbcTemplate jdbc;
    final ObjectMapper json;
    final WorkflowModels models;
    final WorkflowDocuments documents;
    final AccessService access;
    final ApplicationEventPublisher events;
    final AuditService audit;

    WorkflowState(
            JdbcTemplate jdbc,
            ObjectMapper json,
            WorkflowModels models,
            WorkflowDocuments documents,
            AccessService access,
            ApplicationEventPublisher events,
            AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.models = models;
        this.documents = documents;
        this.access = access;
        this.events = events;
        this.audit = audit;
    }

    Map<String, Object> instance(CompanyAccess actor, long id, boolean lock) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM wf_instance WHERE tenant_id=? AND company_id=? AND id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        actor.tenantId(),
                        actor.companyId(),
                        id);
        if (rows.isEmpty()) throw missing();
        var instance = rows.getFirst();
        if (!documents.visible(actor, instance)) throw missing();
        return instance;
    }

    Map<String, Object> raw(long tenant, long id, boolean lock) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM wf_instance WHERE tenant_id=? AND id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        tenant,
                        id);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    Map<String, Object> task(CompanyAccess actor, long id, boolean lock) {
        var ids =
                jdbc.queryForList(
                        "SELECT instance_id FROM wf_task_ext WHERE tenant_id=? AND company_id=? AND"
                            + " id=?",
                        Long.class,
                        actor.tenantId(),
                        actor.companyId(),
                        id);
        if (ids.isEmpty()) throw missing();
        var instance = instance(actor, ids.getFirst(), lock);
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM wf_task_ext WHERE tenant_id=? AND instance_id=? AND id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        actor.tenantId(),
                        instance.get("id"),
                        id);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    SimpleModel.Node node(Map<String, Object> instance, String key) {
        var tree =
                models.tree(
                        models.published(
                                number(instance.get("tenant_id")),
                                number(instance.get("model_id")),
                                ((Number) instance.get("model_version")).intValue()));
        var nodes = SimpleModel.validate(tree, instance.get("business_type").toString());
        var node = nodes.get(key);
        if (node == null) throw SimpleModel.invalid("运行节点未登记");
        return node;
    }

    Map<String, Object> summary(Map<String, Object> instance) {
        return json.readValue(
                instance.get("summary").toString(),
                new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    Map<String, List<Long>> selections(Map<String, Object> instance) {
        return json.readValue(
                instance.get("selections").toString(),
                new tools.jackson.core.type.TypeReference<Map<String, List<Long>>>() {});
    }

    void running(Map<String, Object> instance) {
        if (!instance.get("status").equals("running"))
            throw new ApiException(409, "WORKFLOW_FINISHED", "流程已结束，请刷新后查看");
    }

    void version(long expected, Map<String, Object> row) {
        if (expected != number(row.get("version")))
            throw new ApiException(409, "VERSION_CONFLICT", "审批状态已变化，请刷新后重试");
    }

    void action(
            Map<String, Object> instance,
            Long task,
            long actor,
            String action,
            String comment,
            Map<String, Object> details) {
        jdbc.update(
                "INSERT INTO"
                    + " wf_task_action(id,tenant_id,company_id,instance_id,task_id,actor_id,action,comment,details)"
                    + " VALUES(?,?,?,?,?,?,?,?,?)",
                Ids.next(),
                instance.get("tenant_id"),
                instance.get("company_id"),
                instance.get("id"),
                task,
                actor,
                action,
                comment,
                json.writeValueAsString(details == null ? Map.of() : details));
    }

    void signal(Map<String, Object> instance) {
        events.publishEvent(
                new UserSignal(
                        number(instance.get("tenant_id")),
                        0,
                        "business.changed",
                        Map.of("category", "todo")));
    }

    void notice(Map<String, Object> instance, long user, Long task, String kind, String key) {
        events.publishEvent(
                new WorkflowNotice(
                        number(instance.get("tenant_id")),
                        number(instance.get("company_id")),
                        user,
                        number(instance.get("id")),
                        task,
                        kind,
                        key));
    }

    void assigned(Map<String, Object> instance, Map<String, Object> task) {
        long id = number(task.get("id"));
        notice(
                instance,
                number(task.get("assignee_id")),
                id,
                "todo",
                "wf:task:" + id + ":" + number(task.get("version")));
    }

    void audit(CompanyAccess actor, String action, long id, Object details) {
        audit.record(actor, "workflow." + action, "wf_instance", id, null, details);
    }

    static void reason(String text) {
        if (text == null || text.isBlank() || text.length() > 1000)
            throw new ApiException(400, "VALIDATION_FAILED", "请填写1至1000字的处理意见");
    }
}
