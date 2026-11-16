package cn.erp.identity;

import cn.erp.shared.OrgScope;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 只读主库授权资料；不缓存到会话或报表备库，部门移动和撤权在下一请求生效。 */
@Component
public class OrgScopeResolver {
    private final JdbcTemplate jdbc;
    public OrgScopeResolver(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Set<Long> administrativeCompanies(long tenant) {
        var rows=jdbc.queryForList("""
            SELECT DISTINCT u.id,s.company_id FROM sys_user u JOIN sys_user_data_scope s ON s.tenant_id=u.tenant_id AND s.user_id=u.id
            WHERE u.tenant_id=? AND u.status='active' AND EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.tenant_id=ur.tenant_id AND r.id=ur.role_id AND r.status='active'
            JOIN sys_role_permission rp ON rp.tenant_id=r.tenant_id AND rp.role_id=r.id JOIN sys_permission p ON p.id=rp.permission_id
            WHERE ur.tenant_id=u.tenant_id AND ur.user_id=u.id AND p.code='role:assign_permission')
            """,tenant);
        var companies=new HashSet<Long>();
        for(var row:rows) {long company=((Number)row.get("company_id")).longValue();if(resolve(tenant,company,((Number)row.get("id")).longValue()).allDepartments()) companies.add(company);}
        return Set.copyOf(companies);
    }
    public OrgScope resolve(long tenant,long company,long user) {
        var memberships=jdbc.queryForList("SELECT d.id,d.path,m.is_primary FROM org_user_department m JOIN org_department d ON d.tenant_id=m.tenant_id AND d.company_id=m.company_id AND d.id=m.department_id WHERE m.tenant_id=? AND m.company_id=? AND m.user_id=? AND d.status='active'",tenant,company,user);
        long primary=memberships.stream().filter(m->Boolean.TRUE.equals(m.get("is_primary")) || m.get("is_primary") instanceof Number n && n.intValue()==1).mapToLong(m->((Number)m.get("id")).longValue()).findFirst().orElse(0);
        var roles=jdbc.queryForList("SELECT r.id,COALESCE(s.scope_type,'all') scope_type FROM sys_user_role ur JOIN sys_role r ON r.tenant_id=ur.tenant_id AND r.id=ur.role_id AND r.status='active' LEFT JOIN org_role_data_scope s ON s.tenant_id=r.tenant_id AND s.role_id=r.id AND s.company_id=? WHERE ur.tenant_id=? AND ur.user_id=?",company,tenant,user);
        if(roles.stream().anyMatch(r->r.get("scope_type").equals("all"))) return new OrgScope(List.of(),primary);
        var departments=new HashSet<Long>();var users=new HashSet<Long>();
        for(var role:roles) switch(role.get("scope_type").toString()) {
            case "self" -> users.add(user);
            case "dept" -> memberships.forEach(m->departments.add(((Number)m.get("id")).longValue()));
            case "dept_and_children" -> {
                for(var membership:memberships) departments.addAll(jdbc.queryForList("SELECT id FROM org_department WHERE tenant_id=? AND company_id=? AND path LIKE ?",Long.class,tenant,company,membership.get("path")+"%"));
            }
            case "custom" -> departments.addAll(jdbc.queryForList("SELECT department_id FROM org_role_data_scope_dept WHERE tenant_id=? AND company_id=? AND role_id=?",Long.class,tenant,company,role.get("id")));
            default -> throw new IllegalStateException("角色部门范围配置无效");
        }
        return OrgScope.restricted(departments,users,primary);
    }
}
