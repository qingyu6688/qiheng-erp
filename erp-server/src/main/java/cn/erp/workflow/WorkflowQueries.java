package cn.erp.workflow;

import java.time.*;
import java.util.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.erp.identity.*;
import cn.erp.shared.*;

import static cn.erp.workflow.WorkflowDocuments.*;

@Service
@Transactional(readOnly = true)
public class WorkflowQueries {
    public record Filter(
            String businessType,
            String status,
            Long starterId,
            LocalDate fromDate,
            LocalDate toDate) {}

    private final WorkflowState state;
    private final WorkflowAccess visibility;
    private final AssigneeResolver assignees;

    public WorkflowQueries(
            WorkflowState state, WorkflowAccess visibility, AssigneeResolver assignees) {
        this.state = state;
        this.visibility = visibility;
        this.assignees = assignees;
    }

    public Map<String, Object> list(
            CompanyAccess actor, String kind, Long cursor, int limit, Filter filter) {
        if (limit < 1 || limit > 200 || cursor != null && cursor <= 0)
            throw SimpleModel.invalid("分页条件无效");
        if (!Set.of("todo", "done", "mine", "copies", "all").contains(kind))
            throw SimpleModel.invalid("审批列表类型无效");
        if (kind.equals("all") && !actor.hasPermission("workflow_instance:view_all"))
            throw new ApiException(403, "FORBIDDEN", "没有查看全部流程的权限");
        String join = "",
                fields = "i.id entry_id,i.started_at item_created_at",
                owner = "",
                key = "i.id";
        switch (kind) {
            case "todo" -> {
                join = " JOIN wf_task_ext t ON t.tenant_id=i.tenant_id AND t.instance_id=i.id";
                fields =
                        "t.id entry_id,t.node_name,t.status task_status,t.version"
                                + " task_version,t.owner_id,t.created_at item_created_at";
                owner = " AND " + visibility.task(actor, "t", "i");
                key = "t.id";
            }
            case "done" -> {
                join = " JOIN wf_task_action a ON a.tenant_id=i.tenant_id AND a.instance_id=i.id";
                fields = "a.id entry_id,a.action,a.comment,a.created_at item_created_at";
                owner = " AND a.actor_id=? AND a.task_id IS NOT NULL";
                key = "a.id";
            }
            case "mine" -> owner = " AND i.starter_id=?";
            case "copies" -> {
                join = " JOIN wf_copy c ON c.tenant_id=i.tenant_id AND c.instance_id=i.id";
                fields = "c.id entry_id,c.read_at,c.created_at item_created_at";
                owner = " AND c.user_id=?";
                key = "c.id";
            }
            default -> {}
        }
        String base =
                "SELECT i.*,"
                        + fields
                        + " FROM wf_instance i"
                        + join
                        + " WHERE i.tenant_id=? AND i.company_id=?"
                        + owner
                        + " AND "
                        + visibility.business(actor, "i", false);
        var args = new ArrayList<Object>(List.of(actor.tenantId(), actor.companyId()));
        if (Set.of("done", "mine", "copies").contains(kind)) args.add(actor.userId());
        if (filter.businessType() != null && !filter.businessType().isBlank()) {
            if (!SimpleModel.TYPES.contains(filter.businessType()))
                throw SimpleModel.invalid("业务类型无效");
            base += " AND i.business_type=?";
            args.add(filter.businessType());
        }
        if (filter.status() != null && !filter.status().isBlank()) {
            if (!Set.of("running", "approved", "rejected", "cancelled", "terminated")
                    .contains(filter.status())) throw SimpleModel.invalid("流程状态无效");
            base += " AND i.status=?";
            args.add(filter.status());
        }
        if (filter.starterId() != null) {
            if (filter.starterId() <= 0) throw SimpleModel.invalid("发起人编号无效");
            base += " AND i.starter_id=?";
            args.add(filter.starterId());
        }
        if (filter.fromDate() != null) {
            base += " AND i.started_at>=?";
            args.add(
                    java.sql.Timestamp.from(
                            filter.fromDate()
                                    .atStartOfDay(ZoneId.of("Asia/Shanghai"))
                                    .toInstant()));
        }
        if (filter.toDate() != null) {
            base += " AND i.started_at<?";
            args.add(
                    java.sql.Timestamp.from(
                            filter.toDate()
                                    .plusDays(1)
                                    .atStartOfDay(ZoneId.of("Asia/Shanghai"))
                                    .toInstant()));
        }
        long before = cursor == null ? Long.MAX_VALUE : cursor;
        var visible = new ArrayList<Map<String, Object>>();
        while (visible.size() <= limit) {
            var bound = new ArrayList<>(args);
            bound.add(before);
            var rows =
                    state.jdbc.queryForList(
                            base + " AND " + key + "<? ORDER BY " + key + " DESC LIMIT 100",
                            bound.toArray());
            if (rows.isEmpty()) break;
            for (var row : rows) {
                before = number(row.get("entry_id"));
                if (state.documents.visible(actor, row)) {
                    var result = new LinkedHashMap<String, Object>();
                    result.put("id", row.get("entry_id").toString());
                    result.put("instanceId", row.get("id").toString());
                    for (String name :
                            List.of(
                                    "title",
                                    "business_type",
                                    "business_no",
                                    "status",
                                    "node_name",
                                    "task_status",
                                    "task_version",
                                    "owner_id",
                                    "read_at",
                                    "action",
                                    "comment",
                                    "item_created_at"))
                        if (row.containsKey(name)) result.put(name, row.get(name));
                    visible.add(WorkflowModels.view(result));
                    if (visible.size() > limit) break;
                }
            }
            if (rows.size() < 100) break;
        }
        var page = new LinkedHashMap<String, Object>();
        page.put("items", visible.stream().limit(limit).toList());
        page.put("nextCursor", visible.size() > limit ? visible.get(limit - 1).get("id") : null);
        return page;
    }

    public long count(CompanyAccess actor) {
        return state.jdbc.queryForObject(
                "SELECT COUNT(*) FROM wf_task_ext t JOIN wf_instance i ON i.tenant_id=t.tenant_id"
                        + " AND i.id=t.instance_id WHERE "
                        + visibility.task(actor, "t", "i"),
                Long.class);
    }

    public Map<String, Object> workbench(CompanyAccess actor) {
        long count = count(actor);
        if (count == 0) return Map.of("count", 0L, "items", List.of());
        var page = list(actor, "todo", null, 10, new Filter(null, null, null, null, null));
        var items = new ArrayList<Map<String, Object>>();
        if (page.get("items") instanceof List<?> rows)
            for (Object value : rows)
                if (value instanceof Map<?, ?> row)
                    items.add(
                            Map.of(
                                    "id",
                                    row.get("id"),
                                    "label",
                                    Objects.toString(row.get("nodeName"), "审批待办"),
                                    "documentNo",
                                    row.get("businessNo"),
                                    "url",
                                    "/workflow/instances/" + row.get("instanceId")));
        return Map.of("count", count, "items", items);
    }

    public Map<String, Object> detail(CompanyAccess actor, long id) {
        var instance = state.instance(actor, id, false);
        if (!participant(actor, instance)) throw missing();
        var result = WorkflowModels.view(instance);
        result.remove("selections");
        var summary = state.summary(instance);
        summary.remove("priceFingerprint");
        summary.remove("creditFingerprint");
        if(!WorkflowDocuments.creditVisible(actor)) {summary.remove("creditExposure");summary.remove("creditLimit");summary.remove("creditOverdueDays");}
        summary.remove("belowMinPriceLineIds");
        if (!actor.hasPermission("cost:view")
                && (instance.get("business_type").equals("stock_adjustment")
                        || instance.get("business_type").equals("document_reversal")
                                && Set.of("stock_adjustment", "goods_receipt", "purchase_return")
                                        .contains(Objects.toString(summary.get("sourceType"), ""))))
            summary.remove("totalAmount");
        result.put("summary", summary);
        result.put(
                "tasks",
                state
                        .jdbc
                        .queryForList(
                                "SELECT t.*,u.display_name assignee_name FROM wf_task_ext t LEFT"
                                    + " JOIN sys_user u ON u.tenant_id=t.tenant_id AND"
                                    + " u.id=t.assignee_id WHERE t.tenant_id=? AND t.instance_id=?"
                                    + " ORDER BY t.id",
                                actor.tenantId(),
                                id)
                        .stream()
                        .map(WorkflowModels::view)
                        .toList());
        result.put(
                "actions",
                state
                        .jdbc
                        .queryForList(
                                "SELECT a.*,COALESCE(u.display_name,'系统') actor_name FROM"
                                        + " wf_task_action a LEFT JOIN sys_user u ON"
                                        + " u.tenant_id=a.tenant_id AND u.id=a.actor_id WHERE"
                                        + " a.tenant_id=? AND a.instance_id=? ORDER BY a.id",
                                actor.tenantId(),
                                id)
                        .stream()
                        .map(WorkflowModels::view)
                        .toList());
        result.put(
                "canCancel",
                instance.get("status").equals("running")
                        && number(instance.get("starter_id")) == actor.userId());
        result.put(
                "canTerminate",
                instance.get("status").equals("running")
                        && actor.hasPermission("workflow_instance:terminate"));
        return result;
    }

    public Map<String, Object> progress(CompanyAccess actor, String type, long id) {
        state.documents.snapshot(actor, type, id);
        var history =
                state.jdbc.queryForList(
                        "SELECT id,status,model_version,started_at,ended_at FROM wf_instance WHERE"
                            + " tenant_id=? AND company_id=? AND business_type=? AND business_id=?"
                            + " ORDER BY id DESC",
                        actor.tenantId(),
                        actor.companyId(),
                        type,
                        id);
        var result = new LinkedHashMap<String, Object>();
        result.put("instances", history.stream().map(WorkflowModels::view).toList());
        if (!history.isEmpty())
            result.put(
                    "tasks",
                    state
                            .jdbc
                            .queryForList(
                                    "SELECT"
                                        + " t.id,t.node_key,t.node_name,t.node_kind,t.assignee_id,u.display_name"
                                        + " assignee_name,t.status,t.comment,t.created_at,t.ended_at,t.due_at"
                                        + " FROM wf_task_ext t LEFT JOIN sys_user u ON"
                                        + " u.tenant_id=t.tenant_id AND u.id=t.assignee_id WHERE"
                                        + " t.tenant_id=? AND t.instance_id=? ORDER BY t.id",
                                    actor.tenantId(),
                                    history.getFirst().get("id"))
                            .stream()
                            .map(WorkflowModels::view)
                            .toList());
        else result.put("tasks", List.of());
        result.put(
                "canOpen",
                !history.isEmpty()
                        && participant(
                                actor,
                                state.raw(
                                        actor.tenantId(),
                                        number(history.getFirst().get("id")),
                                        false)));
        return result;
    }

    public List<Map<String, Object>> candidates(CompanyAccess actor, long task) {
        var row = state.task(actor, task, false);
        if (number(row.get("assignee_id")) != actor.userId())
            throw new ApiException(403, "WORKFLOW_TASK_NOT_ASSIGNED", "只有任务当前处理人可以选择转交人员");
        var instance = state.instance(actor, number(row.get("instance_id")), false);
        return assignees.candidates(
                actor,
                instance.get("business_type").toString(),
                number(instance.get("business_id")),
                row.get("node_kind").equals("approver"),
                number(row.get("price_approval")) == 1);
    }

    public Map<String, Object> startOptions(CompanyAccess actor, String type, long id) {
        var summary = state.documents.snapshot(actor, type, id);
        var binding = state.models.selected(actor, type, summary);
        if (binding == null) return Map.of("nodes", List.of());
        var tree =
                state.models.tree(
                        state.models.published(
                                actor.tenantId(),
                                number(binding.get("model_id")),
                                ((Number) binding.get("published_version")).intValue()));
        var nodes = SimpleModel.validate(tree, type);
        var options = new ArrayList<Map<String, Object>>();
        for (var node : SimpleModel.reachable(tree, summary))
            if (node.assignee() != null && node.assignee().strategy().equals("starter_select"))
                options.add(
                        Map.of(
                                "id",
                                node.id(),
                                "name",
                                node.name(),
                                "candidates",
                                assignees.candidates(
                                        actor,
                                        type,
                                        id,
                                        node.type().equals("approver"),
                                        Boolean.TRUE.equals(node.priceApproval()),Boolean.TRUE.equals(node.creditApproval()))));
        return Map.of("nodes", options);
    }

    public Map<String, Object> reversalStartOptions(
            CompanyAccess actor, String sourceType, long sourceId, LocalDate date) {
        if (!actor.hasPermission("reversal:create"))
            throw new ApiException(403, "FORBIDDEN", "没有发起冲销的权限");
        var summary = state.documents.preview(actor, sourceType, sourceId, date);
        var binding = state.models.selected(actor, "document_reversal", summary);
        if (binding == null) return Map.of("nodes", List.of());
        var tree =
                state.models.tree(
                        state.models.published(
                                actor.tenantId(),
                                number(binding.get("model_id")),
                                ((Number) binding.get("published_version")).intValue()));
        SimpleModel.validate(tree, "document_reversal");
        var options = new ArrayList<Map<String, Object>>();
        for (var node : SimpleModel.reachable(tree, summary))
            if (node.assignee() != null && node.assignee().strategy().equals("starter_select"))
                options.add(
                        Map.of(
                                "id",
                                node.id(),
                                "name",
                                node.name(),
                                "candidates",
                                assignees.previewCandidates(
                                        actor,
                                        sourceType,
                                        sourceId,
                                        date,
                                        node.type().equals("approver"))));
        return Map.of("nodes", options);
    }

    @Transactional
    public Map<String, Object> readCopy(CompanyAccess actor, long id) {
        var rows =
                state.jdbc.queryForList(
                        "SELECT instance_id FROM wf_copy WHERE tenant_id=? AND company_id=? AND"
                                + " user_id=? AND id=?",
                        Long.class,
                        actor.tenantId(),
                        actor.companyId(),
                        actor.userId(),
                        id);
        if (rows.isEmpty()) throw missing();
        var instance = state.instance(actor, rows.getFirst(), false);
        state.jdbc.update(
                "UPDATE wf_copy SET read_at=COALESCE(read_at,UTC_TIMESTAMP(3)) WHERE tenant_id=?"
                        + " AND user_id=? AND id=?",
                actor.tenantId(),
                actor.userId(),
                id);
        state.events.publishEvent(
                new WorkflowNotice(
                        actor.tenantId(),
                        actor.companyId(),
                        actor.userId(),
                        id,
                        null,
                        "copy_read",
                        "wf:copy:read:" + id));
        return Map.of("id", Long.toString(id), "read", true);
    }

    private boolean participant(CompanyAccess actor, Map<String, Object> instance) {
        if (actor.hasPermission("workflow_instance:view_all")
                || number(instance.get("starter_id")) == actor.userId()) return true;
        return state.jdbc.queryForObject(
                        "SELECT (SELECT COUNT(*) FROM wf_task_ext WHERE tenant_id=? AND"
                            + " instance_id=? AND (assignee_id=? OR owner_id=?))+(SELECT COUNT(*)"
                            + " FROM wf_copy WHERE tenant_id=? AND instance_id=? AND"
                            + " user_id=?)+(SELECT COUNT(*) FROM wf_task_action WHERE tenant_id=?"
                            + " AND instance_id=? AND actor_id=? AND task_id IS NOT NULL)",
                        Long.class,
                        actor.tenantId(),
                        instance.get("id"),
                        actor.userId(),
                        actor.userId(),
                        actor.tenantId(),
                        instance.get("id"),
                        actor.userId(),
                        actor.tenantId(),
                        instance.get("id"),
                        actor.userId())
                > 0;
    }
}
