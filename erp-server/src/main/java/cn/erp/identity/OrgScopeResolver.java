package cn.erp.identity;

import cn.erp.shared.OrgScope;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 只读主库授权资料；不缓存到会话或报表备库，部门移动和撤权在下一请求生效。 */
@Component
public class OrgScopeResolver {
    private static final String ALL_SCOPE = """
            SELECT COALESCE((SELECT m.department_id FROM org_user_department m
                       JOIN org_department d ON d.tenant_id=m.tenant_id AND d.company_id=m.company_id
                         AND d.id=m.department_id AND d.status='active'
                       WHERE m.tenant_id=? AND m.company_id=? AND m.user_id=? AND m.is_primary=1
                       ORDER BY m.department_id LIMIT 1),0) primary_id,
                   EXISTS(SELECT 1 FROM sys_user_role ur
                       JOIN sys_role r ON r.tenant_id=ur.tenant_id AND r.id=ur.role_id AND r.status='active'
                       LEFT JOIN org_role_data_scope s ON s.tenant_id=r.tenant_id AND s.role_id=r.id AND s.company_id=?
                       WHERE ur.tenant_id=? AND ur.user_id=? AND COALESCE(s.scope_type,'all')='all') all_scope
            """;

    private static final String RESOLVE = """
            WITH target AS (
                SELECT CAST(? AS SIGNED) tenant_id, CAST(? AS SIGNED) company_id, CAST(? AS SIGNED) user_id
            ), memberships AS (
                SELECT d.id department_id, d.path, m.is_primary
                FROM target t JOIN org_user_department m
                  ON m.tenant_id=t.tenant_id AND m.company_id=t.company_id AND m.user_id=t.user_id
                JOIN org_department d ON d.tenant_id=m.tenant_id AND d.company_id=m.company_id
                  AND d.id=m.department_id AND d.status='active'
            ), scope_roles AS (
                SELECT r.id, COALESCE(s.scope_type,'all') scope_type
                FROM target t JOIN sys_user_role ur ON ur.tenant_id=t.tenant_id AND ur.user_id=t.user_id
                JOIN sys_role r ON r.tenant_id=ur.tenant_id AND r.id=ur.role_id AND r.status='active'
                LEFT JOIN org_role_data_scope s ON s.tenant_id=r.tenant_id AND s.role_id=r.id AND s.company_id=t.company_id
            ), flags AS (
                SELECT COALESCE((SELECT MIN(department_id) FROM memberships WHERE is_primary=1),0) primary_id,
                       EXISTS(SELECT 1 FROM scope_roles WHERE scope_type='all') all_scope,
                       EXISTS(SELECT 1 FROM scope_roles WHERE scope_type='self') self_scope,
                       EXISTS(SELECT 1 FROM scope_roles WHERE scope_type='dept') member_scope,
                       EXISTS(SELECT 1 FROM scope_roles WHERE scope_type='dept_and_children') child_scope
            ), departments AS (
                SELECT m.department_id FROM memberships m JOIN flags f ON NOT f.all_scope AND f.member_scope
                UNION
                SELECT d.id FROM target t JOIN org_department d ON d.tenant_id=t.tenant_id AND d.company_id=t.company_id
                JOIN memberships m ON d.path LIKE CONCAT(m.path,'%')
                JOIN flags f ON NOT f.all_scope AND f.child_scope
                UNION
                SELECT x.department_id FROM target t JOIN org_role_data_scope_dept x
                  ON x.tenant_id=t.tenant_id AND x.company_id=t.company_id
                JOIN scope_roles r ON r.id=x.role_id AND r.scope_type='custom'
                JOIN flags f ON NOT f.all_scope
            )
            SELECT f.primary_id, f.all_scope, f.self_scope, d.department_id
            FROM flags f LEFT JOIN departments d ON NOT f.all_scope
            """;

    private final JdbcTemplate jdbc;
    public OrgScopeResolver(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Set<Long> administrativeCompanies(long tenant) {
        var rows=jdbc.queryForList("""
            SELECT DISTINCT u.id,s.company_id FROM sys_user u JOIN org_user_data_scope s ON s.tenant_id=u.tenant_id AND s.user_id=u.id
            WHERE u.tenant_id=? AND u.status='active' AND EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.tenant_id=ur.tenant_id AND r.id=ur.role_id AND r.status='active'
            JOIN sys_role_permission rp ON rp.tenant_id=r.tenant_id AND rp.role_id=r.id JOIN sys_permission p ON p.id=rp.permission_id
            WHERE ur.tenant_id=u.tenant_id AND ur.user_id=u.id AND p.code='role:assign_permission')
            """,tenant);
        var companies=new HashSet<Long>();
        for(var row:rows) {long company=((Number)row.get("company_id")).longValue();if(resolve(tenant,company,((Number)row.get("id")).longValue()).allDepartments()) companies.add(company);}
        return Set.copyOf(companies);
    }
    public OrgScope resolve(long tenant,long company,long user) {
        // 全部门角色只读取主部门和授权标识，避免在正常请求中构造部门候选集合。
        var all = jdbc.queryForObject(ALL_SCOPE, (row, index) -> row.getBoolean("all_scope")
                ? new OrgScope(List.of(), row.getLong("primary_id")) : null,tenant,company,user,company,tenant,user);
        if (all != null) return all;
        return jdbc.query(RESOLVE, rows -> {
            if (!rows.next()) throw new IllegalStateException("无法读取部门数据范围");
            long primary = rows.getLong("primary_id");
            if (rows.getBoolean("all_scope")) return new OrgScope(List.of(), primary);
            Set<Long> users = rows.getBoolean("self_scope") ? Set.of(user) : Set.of();
            var departments = new HashSet<Long>();
            do {
                long department = rows.getLong("department_id");
                if (!rows.wasNull()) departments.add(department);
            } while (rows.next());
            return OrgScope.restricted(departments, users, primary);
        },tenant,company,user);
    }
}
