package cn.erp.identity;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import cn.erp.shared.ApiException;
import cn.erp.shared.AuditService;

@Service
class SecurityPolicyService {
    static final String PERMISSION="tenant:security_policy";
    private static final TypeReference<List<String>> STRINGS=new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SecretProtector secrets;
    private final SsoSettings sso;
    private final AuditService audit;

    SecurityPolicyService(JdbcTemplate jdbc,ObjectMapper json,SecretProtector secrets,SsoSettings sso,AuditService audit) {
        this.jdbc=jdbc;
        this.json=json;
        this.secrets=secrets;
        this.sso=sso;
        this.audit=audit;
    }

    Policy forTenant(long tenant) {
        return jdbc.query("""
                SELECT mfa_required_roles,password_login_allowed,sso_auto_provision,sso_default_role_id,version
                FROM tenant_security_policy WHERE tenant_id=?
                """,(row,index) -> new Policy(json.readValue(row.getString(1),STRINGS),row.getBoolean(2),
                row.getBoolean(3),row.getString(4),row.getLong(5)),tenant).stream().findFirst()
                .orElse(new Policy(List.of(),true,false,null,0L));
    }

    Policy view(CompanyAccess actor) {
        PlatformAccess.require(jdbc,actor,PERMISSION);
        return forTenant(actor.tenantId());
    }

    @Transactional
    public Policy save(CompanyAccess actor,Policy input) {
        PlatformAccess.lock(jdbc,actor,PERMISSION);
        var current=forTenant(actor.tenantId());
        if (!current.version().equals(input.version())) throw new ApiException(409,"VERSION_CONFLICT","安全策略已修改，请重新加载");
        if (input.passwordLoginAllowed()==null || input.ssoAutoProvision()==null || input.mfaRequiredRoles()==null
                || input.mfaRequiredRoles().size()>100 || input.mfaRequiredRoles().stream().anyMatch(java.util.Objects::isNull))
            throw SsoSettings.invalid("安全策略参数不完整或超出角色数量上限");
        List<String> roles=input.mfaRequiredRoles().stream().distinct().sorted().toList();
        if (!roles.isEmpty() && !secrets.configured())
            throw SsoSettings.invalid("启用角色二次验证前，请在服务端配置登录加密密钥");
        for (String role:roles) requireRole(actor.tenantId(),role);
        Long defaultRole=input.ssoDefaultRoleId()==null?null:requireRole(actor.tenantId(),input.ssoDefaultRoleId());
        if (input.ssoAutoProvision() && defaultRole==null) throw SsoSettings.invalid("启用后需选择默认角色");
        if (!input.passwordLoginAllowed() && !hasLogin(actor))
            throw SsoSettings.invalid("关闭密码登录前，请先为当前管理员绑定可用单点登录方式");
        jdbc.update("""
                INSERT INTO tenant_security_policy(tenant_id,mfa_required_roles,password_login_allowed,sso_auto_provision,sso_default_role_id,version)
                VALUES(?,?,?,?,?,1) AS incoming
                ON DUPLICATE KEY UPDATE mfa_required_roles=incoming.mfa_required_roles,password_login_allowed=incoming.password_login_allowed,
                    sso_auto_provision=incoming.sso_auto_provision,sso_default_role_id=incoming.sso_default_role_id,
                    version=tenant_security_policy.version+1
                """,actor.tenantId(),json.writeValueAsString(roles),input.passwordLoginAllowed(),input.ssoAutoProvision(),defaultRole);
        audit.record(actor,"identity.policy_update","tenant",actor.tenantId(),null,Map.of("mfaRoles",roles,
                "passwordLoginAllowed",input.passwordLoginAllowed(),"ssoAutoProvision",input.ssoAutoProvision()));
        return forTenant(actor.tenantId());
    }

    private boolean hasLogin(CompanyAccess actor) {
        var methods=jdbc.queryForList("SELECT method FROM user_login_identity WHERE tenant_id=? AND user_id=?",
                String.class,actor.tenantId(),actor.userId());
        return sso.available(actor.tenantId()).stream().anyMatch(method -> methods.contains(method.id()));
    }

    private long requireRole(long tenant,String value) {
        long id=validId(value);
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM sys_role WHERE tenant_id=? AND id=? AND status='active')",
                Boolean.class,tenant,id))) throw SsoSettings.invalid("安全策略仅可引用本企业的可用角色");
        return id;
    }

    static long validId(String value) {
        if (value==null || !value.matches("[1-9][0-9]{0,18}")) throw SsoSettings.invalid("编号格式有误");
        try {return Long.parseLong(value);}
        catch (NumberFormatException failure) {throw SsoSettings.invalid("编号超出范围");}
    }

    record Policy(List<String> mfaRequiredRoles,Boolean passwordLoginAllowed,Boolean ssoAutoProvision,
                  String ssoDefaultRoleId,Long version) {}
}
