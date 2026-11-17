package cn.erp.aitools;

import cn.erp.identity.CompanyAccess;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class AiBridgePrincipalRepository {
    // 同一查询时点读取权限和所属角色，防止撤权后用旧权限组合新角色的资料访问范围。
    private static final String PRINCIPAL_SQL = """
            WITH actor AS (
                SELECT u.tenant_id,u.id AS user_id,c.id AS company_id
                FROM sys_user u
                JOIN tenant t ON t.id=u.tenant_id AND t.status='active'
                JOIN company c ON c.tenant_id=u.tenant_id AND c.status='active'
                WHERE u.tenant_id=? AND u.id=? AND c.id=? AND u.status='active' AND u.must_change_password=0
                  AND EXISTS (SELECT 1 FROM sys_user_data_scope s
                              WHERE s.tenant_id=u.tenant_id AND s.user_id=u.id AND s.company_id=c.id)
            ), active_roles AS (
                SELECT r.tenant_id,r.id
                FROM actor a JOIN sys_user_role ur ON ur.tenant_id=a.tenant_id AND ur.user_id=a.user_id
                JOIN sys_role r ON r.tenant_id=ur.tenant_id AND r.id=ur.role_id AND r.status='active'
            )
            SELECT 0 AS fact_kind, CAST(id AS CHAR) AS fact_value FROM active_roles
            UNION
            SELECT 1,p.code FROM active_roles r
            JOIN sys_role_permission rp ON rp.tenant_id=r.tenant_id AND rp.role_id=r.id
            JOIN sys_permission p ON p.id=rp.permission_id
            UNION
            SELECT 2,CAST(d.id AS CHAR) FROM actor a
            JOIN org_user_department m ON m.tenant_id=a.tenant_id AND m.company_id=a.company_id AND m.user_id=a.user_id
            JOIN org_department d ON d.tenant_id=m.tenant_id AND d.company_id=m.company_id AND d.id=m.department_id
            WHERE d.status='active'
            ORDER BY fact_kind,fact_value
            """;

    private final JdbcTemplate jdbc;

    AiBridgePrincipalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Principal read(CompanyAccess actor) {
        return jdbc.query(PRINCIPAL_SQL, rows -> {
            var roles = new ArrayList<String>();
            var permissions = new ArrayList<String>();
            var departments = new ArrayList<String>();
            while (rows.next()) {
                String value = rows.getString("fact_value");
                switch (rows.getInt("fact_kind")) {
                    case 0 -> roles.add(value);
                    case 1 -> permissions.add(value);
                    case 2 -> departments.add(value);
                    default -> throw new IllegalStateException("AI 授权资料类型无效");
                }
            }
            return new Principal(Long.toString(actor.tenantId()), Long.toString(actor.userId()),
                    Long.toString(actor.companyId()), List.copyOf(permissions), List.copyOf(roles),
                    List.copyOf(departments));
        }, actor.tenantId(), actor.userId(), actor.companyId());
    }

    record Principal(String tenantId, String userId, String companyId, List<String> permissions,
                     List<String> roleIds, List<String> departmentIds) {}
}
