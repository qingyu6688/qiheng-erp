package cn.erp.crm;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import java.util.Map;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** CRM 范围与租户、公司、部门和客户关系共同约束，由读写入口复用。 */
@Component
public class CrmScope {
    private final JdbcTemplate jdbc;

    public CrmScope(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> requireCustomer(CompanyAccess actor, long partnerId, boolean write, boolean lock) {
        var rows = jdbc.queryForList("""
                SELECT c.*, p.name AS partner_name, p.code AS partner_code, p.status AS partner_status,
                    EXISTS(SELECT 1 FROM partner_role r WHERE r.tenant_id=p.tenant_id
                        AND r.partner_id=p.id AND r.role='customer' AND r.status='active') AS active_customer_role,
                    (%s) AS can_write
                FROM crm_customer_profile c
                JOIN business_partner p ON p.tenant_id=c.tenant_id AND p.id=c.partner_id
                WHERE c.tenant_id=? AND c.company_id=? AND c.partner_id=? AND (%s)
                """.formatted(customerWritable(actor, "c"), customerFilter(actor, "c")) + (lock ? " FOR UPDATE" : ""),
                actor.tenantId(), actor.companyId(), partnerId);
        if (rows.isEmpty()) throw missing();
        var result = rows.getFirst();
        if (write && !flag(result.get("can_write"))) throw forbidden("没有维护该客户的权限");
        if (write && (!"active".equals(result.get("partner_status")) || !flag(result.get("active_customer_role")))) {
            throw new ApiException(409, "CRM_CUSTOMER_DISABLED", "客户或其往来角色已停用");
        }
        return result;
    }

    public String customerFilter(CompanyAccess actor, String alias) {
        String active = alias(alias);
        String privateScope = actor.orgScope().ownerFilter(active) + " AND (" + relationships(actor, active, false) + ")";
        String publicScope = actor.hasPermission("crm_pool:view") ? active + ".pool_entered_at IS NOT NULL" : "1=0";
        return "((" + active + ".pool_entered_at IS NULL AND " + privateScope + ") OR " + publicScope + ")";
    }

    String customerWritable(CompanyAccess actor, String alias) {
        return alias(alias) + ".pool_entered_at IS NULL AND " + actor.orgScope().ownerFilter(alias)
                + " AND (" + relationships(actor, alias, true) + ")";
    }

    String clueFilter(CompanyAccess actor, String alias) {
        String group = actor.hasPermission("crm_customer:manage_all") ? "1=1"
                : alias(alias) + ".owner_user_id=" + actor.userId() + " OR " + manager(actor, alias);
        return "(" + actor.orgScope().ownerFilter(alias) + " AND (" + group + "))";
    }

    Map<String, Object> requireClue(CompanyAccess actor, long id, boolean lock) {
        var rows = jdbc.queryForList("SELECT l.* FROM crm_clue l WHERE l.tenant_id=? AND l.company_id=? AND l.id=? AND "
                + clueFilter(actor, "l") + (lock ? " FOR UPDATE" : ""), actor.tenantId(), actor.companyId(), id);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    long memberDepartment(CompanyAccess actor, long user) {
        var departments = jdbc.queryForList("""
                SELECT COALESCE((SELECT d.id FROM org_user_department ud JOIN org_department d
                    ON d.tenant_id=ud.tenant_id AND d.company_id=ud.company_id AND d.id=ud.department_id AND d.status='active'
                    WHERE ud.tenant_id=u.tenant_id AND ud.company_id=? AND ud.user_id=u.id AND ud.is_primary=1),0)
                FROM sys_user u WHERE u.tenant_id=? AND u.id=? AND u.status='active' AND u.user_type='human'
                AND EXISTS(SELECT 1 FROM org_user_data_scope s WHERE s.tenant_id=u.tenant_id AND s.user_id=u.id AND s.company_id=?)
                """, Long.class, actor.companyId(), actor.tenantId(), user, actor.companyId());
        if (departments.isEmpty()) throw invalid("请选择当前公司的有效员工");
        return departments.getFirst();
    }

    List<Map<String, Object>> employeeOptions(CompanyAccess actor, String keyword) {
        var permissions = Set.of("crm_clue:view", "crm_customer:view", "crm_pool:assign", "crm_pool:manage", "crm_contract:view", "crm_business:view");
        if (permissions.stream().noneMatch(actor::hasPermission)) throw forbidden("没有查询员工范围的权限");
        String term = CrmCoreRows.trim(keyword);
        if (term.length() > 100) throw invalid("搜索词最多 100 字");
        var rows = jdbc.queryForList("""
                SELECT e.owner_user_id AS id,e.display_name,e.owner_department_id AS department_id FROM (
                    SELECT u.id AS owner_user_id,u.display_name,COALESCE(d.id,0) AS owner_department_id FROM sys_user u
                    LEFT JOIN org_user_department ud ON ud.tenant_id=u.tenant_id AND ud.user_id=u.id AND ud.company_id=? AND ud.is_primary=1
                    LEFT JOIN org_department d ON d.tenant_id=ud.tenant_id AND d.company_id=ud.company_id AND d.id=ud.department_id AND d.status='active'
                    WHERE u.tenant_id=? AND u.status='active' AND u.user_type='human' AND LOCATE(?,u.display_name)>0
                    AND EXISTS(SELECT 1 FROM org_user_data_scope s WHERE s.tenant_id=u.tenant_id AND s.user_id=u.id AND s.company_id=?)
                ) e WHERE %s ORDER BY e.display_name,e.owner_user_id LIMIT 200
                """.formatted(actor.orgScope().ownerFilter("e")), actor.companyId(), actor.tenantId(), term, actor.companyId());
        return rows.stream().map(CrmCoreRows::row).toList();
    }

    void requireAssignable(CompanyAccess actor, long user, long department) {
        if (!actor.orgScope().allows(department, user)) throw forbidden("员工不在当前部门授权范围内");
    }

    void lockCompany(CompanyAccess actor) {
        jdbc.queryForList("SELECT id FROM tenant WHERE id=? AND status='active' FOR SHARE", Long.class, actor.tenantId());
        var rows = jdbc.queryForList("SELECT id FROM company WHERE tenant_id=? AND id=? AND status='active' FOR UPDATE",
                Long.class, actor.tenantId(), actor.companyId());
        if (rows.isEmpty()) throw missing();
    }

    private String relationships(CompanyAccess actor, String alias, boolean write) {
        if (actor.hasPermission("crm_customer:manage_all") || (!write && actor.hasPermission("crm_customer:view_all"))) {
            return "1=1";
        }
        return alias + ".owner_user_id=" + actor.userId() + " OR " + manager(actor, alias) + " OR " + team(actor, alias, write);
    }

    private String manager(CompanyAccess actor, String alias) {
        return """
                EXISTS(SELECT 1 FROM org_department own JOIN org_department supervisor
                    ON supervisor.tenant_id=own.tenant_id AND supervisor.company_id=own.company_id
                    AND supervisor.status='active' AND own.path LIKE CONCAT(supervisor.path,'%%')
                    WHERE own.tenant_id=%1$s.tenant_id AND own.company_id=%1$s.company_id
                    AND own.id=%1$s.owner_department_id AND own.status='active' AND supervisor.leader_user_id=%2$d)
                """.formatted(alias, actor.userId());
    }

    private String team(CompanyAccess actor, String alias, boolean write) {
        return """
                EXISTS(SELECT 1 FROM crm_customer_team team WHERE team.tenant_id=%1$s.tenant_id
                    AND team.company_id=%1$s.company_id AND team.partner_id=%1$s.partner_id AND team.user_id=%2$d
                    AND (team.expires_at IS NULL OR team.expires_at>UTC_TIMESTAMP(3))%3$s)
                """.formatted(alias, actor.userId(), write ? " AND team.permission='write'" : "");
    }

    private String alias(String value) {
        if (!value.matches("[a-zA-Z_][a-zA-Z0-9_]*")) throw new IllegalArgumentException("客户查询别名无效");
        return value;
    }

    static void require(CompanyAccess actor, String permission) {
        if (!actor.hasPermission(permission)) throw forbidden("没有执行此操作的权限");
    }

    static long number(Map<String, Object> row, String field) {
        Object value = row.get(field);
        return value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
    }

    static boolean flag(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue() != 0;
    }

    static void version(Map<String, Object> row, Long version) {
        if (version == null || version < 0) throw invalid("请提供有效版本号");
        if (version != number(row, "version")) throw new ApiException(409, "VERSION_CONFLICT", "数据已更新，请刷新后重试");
    }

    static ApiException missing() {
        return new ApiException(404, "NOT_FOUND", "CRM 记录不存在或无权查看");
    }

    static ApiException forbidden(String message) {
        return new ApiException(403, "FORBIDDEN", message);
    }

    static ApiException invalid(String message) {
        return new ApiException(400, "VALIDATION_FAILED", message);
    }
}
