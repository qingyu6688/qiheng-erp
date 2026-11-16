package cn.erp.workflow;

import java.util.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import cn.erp.identity.*;
import cn.erp.shared.*;

import static cn.erp.workflow.WorkflowDocuments.*;

@Component
class AssigneeResolver {
    private final JdbcTemplate jdbc;
    private final WorkflowDocuments documents;

    AssigneeResolver(JdbcTemplate jdbc, WorkflowDocuments documents) {
        this.jdbc = jdbc;
        this.documents = documents;
    }

    List<Long> resolve(
            Map<String, Object> instance,
            SimpleModel.Node node,
            Map<String, Object> summary,
            Map<String, List<Long>> selections) {
        long tenant = number(instance.get("tenant_id")),
                company = number(instance.get("company_id")),
                starter = number(instance.get("starter_id")),
                department = number(instance.get("starter_dept_id"));
        var a = node.assignee();
        var result = new LinkedHashSet<Long>();
        switch (a.strategy()) {
            case "users" -> result.addAll(a.userIds());
            case "starter" -> result.add(starter);
            case "starter_select" -> {
                var selected = selections.get(node.id());
                if (selected == null || selected.isEmpty() || selected.size() > 100)
                    throw new ApiException(
                            422, "WORKFLOW_ASSIGNEE_REQUIRED", "请为“" + node.name() + "”选择有效处理人");
                result.addAll(selected);
            }
            case "field_user" -> {
                long user = number(summary.get(a.field()));
                if (user > 0) result.add(user);
            }
            case "role" -> {
                for (String code : a.roleCodes())
                    result.addAll(
                            jdbc.queryForList(
                                    "SELECT ur.user_id FROM sys_user_role ur JOIN sys_role r ON"
                                        + " r.tenant_id=ur.tenant_id AND r.id=ur.role_id AND"
                                        + " r.status='active' WHERE ur.tenant_id=? AND r.code=?"
                                        + " ORDER BY ur.user_id",
                                    Long.class,
                                    tenant,
                                    code));
            }
            case "post" -> {
                for (long post : a.postIds())
                    result.addAll(
                            jdbc.queryForList(
                                    "SELECT up.user_id FROM org_user_post up JOIN org_post p ON"
                                        + " p.tenant_id=up.tenant_id AND p.id=up.post_id AND"
                                        + " p.status='active' WHERE up.tenant_id=? AND"
                                        + " up.company_id=? AND up.post_id=?"
                                            + (Boolean.TRUE.equals(a.starterDepartment())
                                                    ? " AND EXISTS(SELECT 1 FROM"
                                                          + " org_user_department d WHERE"
                                                          + " d.tenant_id=up.tenant_id AND"
                                                          + " d.company_id=up.company_id AND"
                                                          + " d.user_id=up.user_id AND"
                                                          + " d.department_id="
                                                            + department
                                                            + ")"
                                                    : "")
                                            + " ORDER BY up.user_id",
                                    Long.class,
                                    tenant,
                                    company,
                                    post));
            }
            case "dept_leader", "multi_level_leader" -> {
                int levels = a.level() == null ? 1 : a.level();
                long current = department;
                for (int level = 1; level <= levels && current > 0; level++) {
                    var rows =
                            jdbc.queryForList(
                                    "SELECT parent_id,leader_user_id FROM org_department WHERE"
                                        + " tenant_id=? AND company_id=? AND id=? AND"
                                        + " status='active'",
                                    tenant,
                                    company,
                                    current);
                    if (rows.isEmpty()) break;
                    var row = rows.getFirst();
                    if (a.strategy().equals("multi_level_leader") || level == levels) {
                        long leader = number(row.get("leader_user_id"));
                        if (leader > 0) result.add(leader);
                    }
                    current = number(row.get("parent_id"));
                }
            }
            case "starter_dept_members" -> {
                if (department > 0)
                    result.addAll(
                            jdbc.queryForList(
                                    "SELECT user_id FROM org_user_department WHERE tenant_id=? AND"
                                        + " company_id=? AND department_id=? ORDER BY user_id",
                                    Long.class,
                                    tenant,
                                    company,
                                    department));
            }
            default -> throw SimpleModel.invalid("审批人策略无效");
        }
        boolean review = node.type().equals("approver"),
                price = Boolean.TRUE.equals(node.priceApproval());
        String type = instance.get("business_type").toString();
        long business = number(instance.get("business_id"));
        var active =
                result.stream()
                        .filter(
                                user ->
                                        user != null
                                                && user > 0
                                                && documents.eligible(
                                                                tenant, company, user, type,
                                                                business, review, price)
                                                        != null)
                        .toList();
        if (a.strategy().equals("starter_select") && active.size() != result.size())
            throw new ApiException(
                    422, "WORKFLOW_ASSIGNEE_INVALID", "自选处理人已停用、无来源权限或不符合复核分离规则，请重新选择");
        if (!active.isEmpty()) return active;
        if ("admin".equals(a.emptyHandler())) {
            var admins =
                    jdbc.queryForList(
                            "SELECT DISTINCT ur.user_id FROM sys_user_role ur JOIN sys_role r ON"
                                + " r.tenant_id=ur.tenant_id AND r.id=ur.role_id AND"
                                + " r.status='active' JOIN sys_role_permission rp ON"
                                + " rp.tenant_id=r.tenant_id AND rp.role_id=r.id JOIN"
                                + " sys_permission p ON p.id=rp.permission_id WHERE ur.tenant_id=?"
                                + " AND p.code='workflow_instance:terminate' ORDER BY ur.user_id",
                            Long.class,
                            tenant);
            var fallback =
                    admins.stream()
                            .filter(
                                    user ->
                                            documents.eligible(
                                                            tenant, company, user, type, business,
                                                            review, price)
                                                    != null)
                            .toList();
            if (!fallback.isEmpty()) return fallback;
        }
        if (!review && "skip".equals(a.emptyHandler())) return List.of();
        throw new ApiException(
                422,
                "WORKFLOW_APPROVER_UNAVAILABLE",
                "“" + node.name() + "”没有符合权限和复核分离规则的处理人，请调整人员或流程");
    }

    List<Map<String, Object>> candidates(
            CompanyAccess actor, String type, long id, boolean review, boolean price) {
        return jdbc
                .queryForList(
                        "SELECT DISTINCT u.id,u.display_name FROM sys_user u JOIN"
                            + " sys_user_data_scope s ON s.tenant_id=u.tenant_id AND s.user_id=u.id"
                            + " AND s.company_id=? WHERE u.tenant_id=? AND u.status='active' ORDER"
                            + " BY u.id",
                        actor.companyId(),
                        actor.tenantId())
                .stream()
                .filter(
                        row ->
                                documents.eligible(
                                                actor.tenantId(),
                                                actor.companyId(),
                                                number(row.get("id")),
                                                type,
                                                id,
                                                review,
                                                price)
                                        != null)
                .map(
                        row ->
                                Map.<String, Object>of(
                                        "id",
                                        row.get("id").toString(),
                                        "name",
                                        row.get("display_name")))
                .toList();
    }

    List<Map<String, Object>> previewCandidates(
            CompanyAccess actor, String type, long id, java.time.LocalDate date, boolean review) {
        return jdbc
                .queryForList(
                        "SELECT DISTINCT u.id,u.display_name FROM sys_user u JOIN"
                            + " sys_user_data_scope s ON s.tenant_id=u.tenant_id AND s.user_id=u.id"
                            + " AND s.company_id=? WHERE u.tenant_id=? AND u.status='active' ORDER"
                            + " BY u.id",
                        actor.companyId(),
                        actor.tenantId())
                .stream()
                .filter(
                        row ->
                                documents.previewEligible(
                                                actor.tenantId(),
                                                actor.companyId(),
                                                number(row.get("id")),
                                                actor.userId(),
                                                type,
                                                id,
                                                date,
                                                review)
                                        != null)
                .map(
                        row ->
                                Map.<String, Object>of(
                                        "id",
                                        row.get("id").toString(),
                                        "name",
                                        row.get("display_name")))
                .toList();
    }
}
