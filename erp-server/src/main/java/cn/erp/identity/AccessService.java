package cn.erp.identity;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import cn.erp.shared.ApiException;

@Service
public class AccessService {
    static final String PASSWORD_REVISION = "COALESCE(TIMESTAMPDIFF(MICROSECOND, '1970-01-01', u.password_changed_at), 0)";
    private static final String PROFILE = AccessService.class.getName() + ".profile";
    private final JdbcTemplate jdbc;
    private final UserAccessMapper mapper;

    AccessService(JdbcTemplate jdbc, UserAccessMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
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
        if (!profile.permissions().contains(permission)) {
            throw new ApiException(403, "FORBIDDEN", "没有执行此操作的权限");
        }
        Set<Long> warehouses = new LinkedHashSet<>();
        company.warehouseIds().forEach(id -> warehouses.add(Long.valueOf(id)));
        return new CompanyAccess(actor.tenantId(), actor.userId(), Long.parseLong(company.id()),
                company.allWarehouses(), warehouses, profile.permissions());
    }

    UserView current(HttpServletRequest request, SessionAccount actor) {
        if (request.getAttribute(PROFILE) instanceof UserView profile) {
            return profile;
        }
        var rows = jdbc.query("""
                SELECT u.username, u.display_name, u.must_change_password
                FROM sys_user u JOIN tenant t ON t.id = u.tenant_id
                WHERE u.tenant_id = ? AND u.id = ? AND u.status = 'active' AND t.status = 'active' AND
                """ + PASSWORD_REVISION + " = ?", (rs, n) -> new UserInfo(rs.getString(1), rs.getString(2),
                rs.getBoolean(3)), actor.tenantId(), actor.userId(), actor.passwordRevision());
        if (rows.isEmpty()) {
            request.getSession(false).invalidate();
            throw SessionAccount.expired();
        }
        UserInfo user = rows.getFirst();
        UserView profile = new UserView(Long.toString(actor.userId()), user.username(), user.displayName(),
                user.mustChangePassword(), Set.copyOf(mapper.permissions(actor.tenantId(), actor.userId())), companies(actor));
        request.setAttribute(PROFILE, profile);
        return profile;
    }

    private List<CompanyView> companies(SessionAccount actor) {
        var companies = new LinkedHashMap<String, CompanyView>();
        jdbc.query("""
                SELECT c.id, c.code, c.name, s.scope_type, s.warehouse_id FROM sys_user_data_scope s
                JOIN company c ON c.tenant_id = s.tenant_id AND c.id = s.company_id AND c.status = 'active'
                WHERE s.tenant_id = ? AND s.user_id = ? ORDER BY c.id, s.warehouse_key
                """, (RowCallbackHandler) rs -> {
            String id = rs.getString(1);
            CompanyView company = companies.get(id);
            if (company == null) {
                company = new CompanyView(id, rs.getString(2), rs.getString(3),
                        "company".equals(rs.getString(4)), new LinkedHashSet<>());
                companies.put(id, company);
            }
            String warehouseId = rs.getString(5);
            if (warehouseId != null) {
                company.warehouseIds().add(warehouseId);
            }
        }, actor.tenantId(), actor.userId());
        return List.copyOf(companies.values());
    }

    private record UserInfo(String username, String displayName, boolean mustChangePassword) {}
    record UserView(String id, String username, String displayName, boolean mustChangePassword,
                    Set<String> permissions, List<CompanyView> companies) {}
    record CompanyView(String id, String code, String name, boolean allWarehouses, Set<String> warehouseIds) {}
}
