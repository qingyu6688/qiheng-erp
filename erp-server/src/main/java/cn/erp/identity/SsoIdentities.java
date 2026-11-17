package cn.erp.identity;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.erp.shared.ApiException;
import cn.erp.shared.AuditService;
import cn.erp.shared.Ids;

@Service
class SsoIdentities {
    private final JdbcTemplate jdbc;
    private final LoginIdentities identities;
    private final SsoSettings settings;
    private final SecurityPolicyService policies;
    private final PasswordEncoder passwords;
    private final AuditService audit;

    SsoIdentities(JdbcTemplate jdbc,LoginIdentities identities,SsoSettings settings,
                  SecurityPolicyService policies,PasswordEncoder passwords,AuditService audit) {
        this.jdbc=jdbc;
        this.identities=identities;
        this.settings=settings;
        this.policies=policies;
        this.passwords=passwords;
        this.audit=audit;
    }

    @Transactional(noRollbackFor=AwaitingApproval.class)
    public SessionAccount authenticate(long tenant,SsoSettings.Provider prior,SsoWebClient.Identity remote) {
        if (jdbc.queryForList("SELECT id FROM tenant WHERE id=? AND status='active' FOR UPDATE",Long.class,tenant).isEmpty())
            throw LoginIdentities.authFailed();
        var provider=settings.provider(tenant,prior.type(),true);
        if (provider.version()!=prior.version()) throw SsoSettings.disabled();
        String subject=subject(provider,remote.subject());
        var mapped=identities.target(tenant,provider.type(),subject);
        if (mapped!=null) return identities.authenticate(tenant,provider.type(),subject,mapped).withIdentity(mapped.identityId(),provider.version());
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM user_login_identity WHERE tenant_id=? AND method=? AND subject=?)
                """,Boolean.class,tenant,provider.type(),subject))) throw new AwaitingApproval();
        var policy=policies.forTenant(tenant);
        if (!policy.ssoAutoProvision()) throw unbound();
        var verifiedEmail="oidc".equals(provider.type())?remote.verifiedEmail():null;
        var existing=verifiedEmail==null?null:byEmail(tenant,verifiedEmail);
        if (existing!=null) {
            long link=add(tenant,existing.userId(),provider.type(),subject);
            var next=identities.authenticate(tenant,provider.type(),subject).withIdentity(link,provider.version());
            record(next,"login_identity.sso_link",provider.type());
            return next;
        }
        provision(tenant,policy,provider.type(),subject);
        throw new AwaitingApproval();
    }

    private ExistingUser byEmail(long tenant,String email) {
        return jdbc.query("""
                SELECT u.id FROM user_login_identity mail JOIN sys_user u ON u.tenant_id=mail.tenant_id AND u.id=mail.user_id
                WHERE mail.tenant_id=? AND mail.method='email' AND mail.subject=? AND u.user_type='human'
                AND u.status='active' AND u.must_change_password=0 FOR UPDATE OF u
                """,(row,index) -> new ExistingUser(row.getLong(1)),tenant,email.toLowerCase(Locale.ROOT)).stream().findFirst().orElse(null);
    }

    private void provision(long tenant,SecurityPolicyService.Policy policy,String type,String subject) {
        if (policy.ssoDefaultRoleId()==null) throw unbound();
        long role=SecurityPolicyService.validId(policy.ssoDefaultRoleId());
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM sys_role WHERE tenant_id=? AND id=? AND status='active')",
                Boolean.class,tenant,role))) throw unbound();
        long user=Ids.next();
        jdbc.update("""
                INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash,status)
                VALUES(?,?,?,?,?,'disabled')
                """,user,tenant,"sso-"+user,"待启用账号 "+user,passwords.encode(UUID.randomUUID().toString()+UUID.randomUUID()));
        jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",Ids.next(),tenant,user,role);
        add(tenant,user,type,subject);
        audit.record(actor(tenant,user),"login_identity.sso_provision","sys_user",user,null,Map.of("method",type));
    }

    private long add(long tenant,long user,String method,String subject) {
        long id=Ids.next();
        try {
            jdbc.update("INSERT INTO user_login_identity(id,tenant_id,user_id,method,subject) VALUES(?,?,?,?,?)",
                    id,tenant,user,method,subject);
        } catch (DuplicateKeyException duplicate) {
            throw new ApiException(409,"SSO_IDENTITY_CONFLICT","外部身份或本地用户已有关联，请联系管理员核对");
        }
        return id;
    }

    @Transactional
    public void bind(CompanyAccess actor,long user,String type,String raw) {
        PlatformAccess.lock(jdbc,actor,SecurityPolicyService.PERMISSION);
        requireHuman(actor.tenantId(),user);
        var provider=settings.provider(actor.tenantId(),type,false);
        String subject=subject(provider,raw);
        String current=jdbc.queryForList("SELECT subject FROM user_login_identity WHERE tenant_id=? AND user_id=? AND method=?",
                String.class,actor.tenantId(),user,type).stream().findFirst().orElse(null);
        if (Objects.equals(current,subject)) return;
        remove(actor,user,type);
        add(actor.tenantId(),user,type,subject);
        audit.record(actor,"login_identity.admin_bind","sys_user",user,null,Map.of("method",type));
    }

    @Transactional
    public void unbind(CompanyAccess actor,long user,String type) {
        SsoSettings.requireMethod(type);
        PlatformAccess.lock(jdbc,actor,SecurityPolicyService.PERMISSION);
        requireHuman(actor.tenantId(),user);
        if (actor.userId()==user && !policies.forTenant(actor.tenantId()).passwordLoginAllowed()) {
            var allowed=settings.available(actor.tenantId()).stream().map(SsoSettings.Method::id).toList();
            var remaining=jdbc.queryForList("SELECT method FROM user_login_identity WHERE tenant_id=? AND user_id=? AND method<>?",
                    String.class,actor.tenantId(),user,type);
            if (remaining.stream().noneMatch(allowed::contains)) throw SsoSettings.invalid("请保留当前管理员的一个可用登录渠道");
        }
        if (remove(actor,user,type)!=0) audit.record(actor,"login_identity.admin_unbind","sys_user",user,null,Map.of("method",type));
    }

    private void requireHuman(long tenant,long user) {
        if (jdbc.queryForList("SELECT id FROM sys_user WHERE tenant_id=? AND id=? AND user_type='human' FOR UPDATE",Long.class,tenant,user).isEmpty())
            throw new ApiException(404,"NOT_FOUND","用户不存在");
    }

    private int remove(CompanyAccess actor,long user,String method) {
        return jdbc.update("DELETE FROM user_login_identity WHERE tenant_id=? AND user_id=? AND method=?",actor.tenantId(),user,method);
    }

    private static String subject(SsoSettings.Provider provider,String supplied) {
        if (supplied==null || supplied.isBlank() || supplied.length()>600 || supplied.chars().anyMatch(Character::isISOControl))
            throw SsoSettings.invalid("外部唯一标识长度或格式有误");
        if ("ldap".equals(provider.type())) {
            String dn=SsoSettings.ldapName(supplied);
            try {
                if (!new LdapName(dn).startsWith(new LdapName(provider.config().get("baseDn"))))
                    throw SsoSettings.invalid("目录身份必须位于当前登录方式的 Base DN 下");
            } catch (InvalidNameException failure) {
                throw SsoSettings.invalid("目录身份 DN 格式有误");
            }
            return dn;
        }
        String key=switch(provider.type()) {case "oidc" -> "issuer"; case "wecom" -> "corpId"; default -> "clientId";};
        String prefix=provider.config().get(key)+"|";
        String value=supplied.strip();
        String identifier=value.startsWith(prefix)?value.substring(prefix.length()):value;
        if (identifier.isBlank() || identifier.length()>255 || identifier.contains("|")
                || !identifier.chars().allMatch(c -> c>=33&&c<=126) || prefix.length()+identifier.length()>600)
            throw SsoSettings.invalid("请提供当前登录方式下的外部唯一标识");
        return prefix+identifier;
    }

    private void record(SessionAccount account,String operation,String type) {
        audit.record(actor(account.tenantId(),account.userId()),operation,"sys_user",account.userId(),null,Map.of("method",type));
    }
    private static CompanyAccess actor(long tenant,long user) {return new CompanyAccess(tenant,user,0,true,Set.of(),Set.of());}
    private static ApiException unbound() {return new ApiException(403,"SSO_NOT_BOUND","该外部身份尚未绑定，请联系管理员");}
    private record ExistingUser(long userId) {}
    private static final class AwaitingApproval extends ApiException {
        private AwaitingApproval() {super(403,"SSO_PENDING_APPROVAL","外部账号等待管理员核验与启用");}
    }
}
