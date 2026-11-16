package cn.erp.identity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;

import cn.erp.shared.ApiException;

@Service
public class AccessService {
    static final String PASSWORD_REVISION = "COALESCE(TIMESTAMPDIFF(MICROSECOND, '1970-01-01', u.password_changed_at), 0)";
    private static final String PROFILE = AccessService.class.getName() + ".profile";
    // 同一条查询读取有效账号、权限和公司范围，避免多次往返及不同查询时点的授权组合。
    private static final String PROFILE_SQL = """
            WITH active_account AS (
                SELECT u.tenant_id, u.id, u.username, u.display_name, u.must_change_password
                FROM sys_user u JOIN tenant t ON t.id = u.tenant_id
                WHERE u.tenant_id = ? AND u.id = ? AND u.status = 'active' AND t.status = 'active'
                  AND %s = ?
            )
            SELECT 0 AS row_kind, username, display_name, must_change_password, NULL AS permission_code,
                   NULL AS company_id, NULL AS company_code, NULL AS company_name,
                   NULL AS scope_type, NULL AS warehouse_id, NULL AS warehouse_key
            FROM active_account
            UNION ALL
            SELECT 1, NULL, NULL, NULL, p.code, NULL, NULL, NULL, NULL, NULL, NULL
            %s
            JOIN active_account a ON a.tenant_id = ur.tenant_id AND a.id = ur.user_id
            UNION ALL
            SELECT 2, NULL, NULL, NULL, NULL, c.id, c.code, c.name, s.scope_type, s.warehouse_id, s.warehouse_key
            FROM active_account a
            JOIN sys_user_data_scope s ON s.tenant_id = a.tenant_id AND s.user_id = a.id
            JOIN company c ON c.tenant_id = s.tenant_id AND c.id = s.company_id AND c.status = 'active'
            ORDER BY row_kind, company_id, warehouse_key
            """.formatted(PASSWORD_REVISION, UserAccessMapper.ROLE_PERMISSIONS);
    private final JdbcTemplate jdbc;
    private final OrgScopeResolver orgScopes;

    AccessService(JdbcTemplate jdbc,OrgScopeResolver orgScopes) {
        this.jdbc = jdbc;
        this.orgScopes=orgScopes;
    }

    /** 同一请求内复用校验结果，下一次请求重新检查账号、角色和数据范围。 */
    public CompanyAccess requireCompany(HttpServletRequest request, String permission) {
        SessionAccount actor = SessionAccount.read(request);
        UserView profile = current(request, actor);
        if (profile.mustChangePassword()) {
            throw new ApiException(403, "PASSWORD_CHANGE_REQUIRED", "请先修改密码");
        }
        String companyId = request.getHeader("X-Company-Id");
        if (companyId == null || !companyId.matches("[1-9][0-9]{0,18}")) {
            throw new ApiException(400, "VALIDATION_FAILED", "请选择有效的公司");
        }
        CompanyView company = profile.companies().stream().filter(c -> c.id().equals(companyId))
                .findFirst().orElseThrow(() -> new ApiException(403, "COMPANY_NOT_ALLOWED", "无权访问该公司"));
        if (permission != null && !profile.permissions().contains(permission)) {
            throw new ApiException(403, "FORBIDDEN", "没有执行此操作的权限");
        }
        Set<Long> warehouses = new LinkedHashSet<>();
        request.setAttribute("erp.actor.company",Long.parseLong(company.id()));
        company.warehouseIds().forEach(id -> warehouses.add(Long.valueOf(id)));
        return new CompanyAccess(actor.tenantId(), actor.userId(), Long.parseLong(company.id()),
                company.allWarehouses(), warehouses, profile.permissions(),orgScope(request,actor,Long.parseLong(company.id())));
    }

    /** 租户级资料沿用登录主体，权限仍在服务端逐次核对。 */
    public CompanyAccess requireUser(HttpServletRequest request) {
        SessionAccount actor=SessionAccount.read(request);
        UserView profile=current(request,actor);
        if(profile.mustChangePassword()) throw new ApiException(403,"PASSWORD_CHANGE_REQUIRED","请先修改密码");
        return new CompanyAccess(actor.tenantId(),actor.userId(),0,true,Set.of(),profile.permissions());
    }

    public CompanyAccess requireTenant(HttpServletRequest request, String permission) {
        SessionAccount actor = SessionAccount.read(request);
        UserView profile = current(request, actor);
        if (profile.mustChangePassword()) {
            throw new ApiException(403, "PASSWORD_CHANGE_REQUIRED", "请先修改密码");
        }
        if (!profile.permissions().contains(permission)) {
            throw new ApiException(403, "FORBIDDEN", "没有执行此操作的权限");
        }
        return new CompanyAccess(actor.tenantId(), actor.userId(), 0, true, Set.of(), profile.permissions());
    }

    /** 后台任务使用实时主库授权，与浏览器请求采用相同的公司、仓库和部门交集。 */
    public CompanyAccess forUser(long tenant,long user,long companyId) {
        var revisions=jdbc.queryForList("SELECT "+PASSWORD_REVISION+" FROM sys_user u WHERE tenant_id=? AND id=? AND status='active'",Long.class,tenant,user);
        if(revisions.isEmpty()) return null;
        UserView profile=jdbc.query(PROFILE_SQL,(ResultSetExtractor<UserView>) rows->readProfile(rows,user),tenant,user,revisions.getFirst());
        if(profile==null||profile.mustChangePassword()) return null;
        if(companyId==0) return new CompanyAccess(tenant,user,0,true,Set.of(),profile.permissions());
        var company=profile.companies().stream().filter(c->c.id().equals(Long.toString(companyId))).findFirst().orElse(null);if(company==null) return null;
        var warehouses=new LinkedHashSet<Long>();company.warehouseIds().forEach(id->warehouses.add(Long.parseLong(id)));
        return new CompanyAccess(tenant,user,companyId,company.allWarehouses(),warehouses,profile.permissions(),orgScopes.resolve(tenant,companyId,user));
    }

    private cn.erp.shared.OrgScope orgScope(HttpServletRequest request,SessionAccount actor,long company) {
        String key=PROFILE+".org."+company;
        if(request.getAttribute(key) instanceof cn.erp.shared.OrgScope scope) return scope;
        var scope=orgScopes.resolve(actor.tenantId(),company,actor.userId());request.setAttribute(key,scope);return scope;
    }

    UserView current(HttpServletRequest request, SessionAccount actor) {
        if (request.getAttribute(PROFILE) instanceof UserView profile) {
            return profile;
        }
        UserView profile = jdbc.query(PROFILE_SQL,
                (ResultSetExtractor<UserView>) rows -> readProfile(rows, actor.userId()),
                actor.tenantId(), actor.userId(), actor.passwordRevision());
        if (profile == null) {
            request.getSession(false).invalidate();
            throw SessionAccount.expired();
        }
        request.setAttribute(PROFILE, profile);
        return profile;
    }

    private UserView readProfile(ResultSet rows, long userId) throws SQLException {
        if (!rows.next()) {
            return null;
        }
        String username = rows.getString("username");
        String displayName = rows.getString("display_name");
        boolean mustChangePassword = rows.getBoolean("must_change_password");
        var permissions = new LinkedHashSet<String>();
        var companies = new LinkedHashMap<String, CompanyView>();
        while (rows.next()) {
            String permission = rows.getString("permission_code");
            if (permission != null) {
                permissions.add(permission);
                continue;
            }
            String id = rows.getString("company_id");
            CompanyView company = companies.get(id);
            if (company == null) {
                company = new CompanyView(id, rows.getString("company_code"), rows.getString("company_name"),
                        "company".equals(rows.getString("scope_type")), new LinkedHashSet<>());
                companies.put(id, company);
            }
            String warehouseId = rows.getString("warehouse_id");
            if (warehouseId != null) {
                company.warehouseIds().add(warehouseId);
            }
        }
        return new UserView(Long.toString(userId), username, displayName, mustChangePassword,
                Set.copyOf(permissions), List.copyOf(companies.values()));
    }

    record UserView(String id, String username, String displayName, boolean mustChangePassword,
                    Set<String> permissions, List<CompanyView> companies) {}
    record CompanyView(String id, String code, String name, boolean allWarehouses, Set<String> warehouseIds) {}
}
