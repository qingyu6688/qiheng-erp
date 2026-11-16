package cn.erp.shared;

import java.util.*;

import org.springframework.jdbc.core.JdbcTemplate;

/** 详情已由业务模块校验权限，此处只补充状态并隐藏原生流程审批入口。 */
public final class WorkflowViews {
    private WorkflowViews() {}

    public static void decorate(
            JdbcTemplate jdbc, ActorScope actor, String type, long id, Map<String, Object> result) {
        if (result.get("wfInstanceId") != null && result.get("wfInstanceId").toString().equals("0"))
            return;
        var rows =
                jdbc.queryForList(
                        "SELECT id,status FROM wf_instance WHERE tenant_id=? AND company_id=? AND"
                            + " business_type=? AND business_id=? ORDER BY id DESC LIMIT 1",
                        actor.tenantId(),
                        actor.companyId(),
                        type,
                        id);
        if (rows.isEmpty()) return;
        var row = rows.getFirst();
        result.put("workflowInstanceId", row.get("id").toString());
        result.put("workflowStatus", row.get("status"));
        if (row.get("status").equals("running")
                && result.get("allowedActions") instanceof List<?> actions)
            result.put(
                    "allowedActions",
                    actions.stream()
                            .filter(
                                    a ->
                                            !Set.of("approve", "reject", "withdraw", "cancel")
                                                    .contains(a.toString()))
                            .toList());
    }

    public static List<String> actions(Map<String, Object> row, List<String> actions) {
        Object instance = row.get("wf_instance_id");
        boolean running =
                instance instanceof Number value
                        && value.longValue() > 0
                        && Set.of("pending", "pending_review")
                                .contains(Objects.toString(row.get("status"), ""));
        return running
                ? actions.stream()
                        .filter(
                                a ->
                                        !Set.of("approve", "reject", "withdraw", "cancel", "void")
                                                .contains(a))
                        .toList()
                : actions;
    }
}
