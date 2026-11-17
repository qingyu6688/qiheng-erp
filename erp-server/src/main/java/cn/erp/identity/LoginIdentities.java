package cn.erp.identity;

import cn.erp.shared.ApiException;
import cn.erp.shared.AuditService;
import cn.erp.shared.Ids;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class LoginIdentities {
    static final Set<String> METHODS = Set.of("sms", "email", "wechat", "oidc", "ldap", "wecom", "dingtalk", "feishu");
    private static final int BCRYPT_MAX_BYTES = 72;
    private static final String ELIGIBLE = """
            t.status='active' AND u.status='active' AND u.user_type='human' AND u.must_change_password=0
            AND (u.locked_until IS NULL OR u.locked_until <= UTC_TIMESTAMP(3))
            """;
    private static final String TARGET = "SELECT i.id,u.id," + AccessService.PASSWORD_REVISION + """
             FROM user_login_identity i
             JOIN sys_user u ON u.tenant_id=i.tenant_id AND u.id=i.user_id
             JOIN tenant t ON t.id=u.tenant_id
             WHERE i.tenant_id=? AND i.method=? AND i.subject=? AND
            """ + ELIGIBLE;
    private static final String CURRENT = """
            FROM sys_user u JOIN tenant t ON t.id=u.tenant_id
            WHERE u.tenant_id=? AND u.id=? AND
            """ + AccessService.PASSWORD_REVISION + "=? AND " + ELIGIBLE;

    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final PasswordEncoder passwords;
    private final LoginLimiter limiter;
    private final AuditService audit;

    LoginIdentities(JdbcTemplate jdbc, AccessService access, PasswordEncoder passwords, LoginLimiter limiter,
                    AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.passwords = passwords;
        this.limiter = limiter;
        this.audit = audit;
    }

    Long tenantId(String tenantCode) {
        return jdbc.queryForList("SELECT id FROM tenant WHERE code=? AND status='active'", Long.class, tenantCode)
                .stream().findFirst().orElse(null);
    }

    String tenantCode(SessionAccount actor) {
        return jdbc.queryForObject("SELECT code FROM tenant WHERE id=?", String.class, actor.tenantId());
    }

    Target target(long tenantId, String method, String subject) {
        return readTarget(tenantId, method, subject, false);
    }

    @Transactional
    public SessionAccount authenticate(long tenantId, String method, String subject) {
        return authenticateTarget(tenantId, method, subject, target(tenantId, method, subject));
    }

    @Transactional
    public SessionAccount authenticate(long tenantId, String method, String subject, Target expected) {
        return authenticateTarget(tenantId, method, subject, expected);
    }

    private SessionAccount authenticateTarget(long tenantId, String method, String subject, Target expected) {
        if (expected == null || !lockUser(tenantId, expected.userId(), expected.passwordRevision())) {
            throw authFailed();
        }
        Target current = readTarget(tenantId, method, subject, true);
        if (!expected.equals(current)) {
            throw authFailed();
        }
        return open(tenantId, current);
    }

    private Target readTarget(long tenantId, String method, String subject, boolean lock) {
        return jdbc.query(TARGET + (lock ? " FOR SHARE OF i" : ""),
                (row, index) -> new Target(row.getLong(1), row.getLong(2), row.getLong(3)), tenantId, method, subject)
                .stream().findFirst().orElse(null);
    }

    private SessionAccount open(long tenantId, Target current) {
        jdbc.update("""
                UPDATE sys_user SET failed_login_count=0,locked_until=NULL,last_login_at=UTC_TIMESTAMP(3)
                WHERE tenant_id=? AND id=?
                """, tenantId, current.userId());
        return new SessionAccount(tenantId, current.userId(), current.passwordRevision(), System.currentTimeMillis())
                .withIdentity(current.identityId(),0);
    }

    SessionAccount reauthenticate(HttpServletRequest request, String password) {
        SessionAccount actor = SessionAccount.read(request);
        limiter.check(request.getRemoteAddr(), Long.toString(actor.tenantId()), "reauth-" + actor.userId());
        var hashes = jdbc.queryForList("SELECT u.password_hash " + CURRENT, String.class,
                actor.tenantId(), actor.userId(), actor.passwordRevision());
        if (hashes.isEmpty() || password == null || password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES
                || !passwords.matches(password, hashes.getFirst())) {
            throw new ApiException(401, "AUTH_FAILED", "无法验证当前账号和密码");
        }
        return actor;
    }

    @Transactional
    public void bind(SessionAccount actor, String method, String subject) {
        requireMethod(method);
        lockCurrent(actor);
        var previous = jdbc.queryForList("""
                SELECT subject FROM user_login_identity WHERE tenant_id=? AND user_id=? AND method=?
                """, String.class, actor.tenantId(), actor.userId(), method);
        if (!previous.isEmpty() && previous.getFirst().equals(subject)) {
            return;
        }
        remove(actor, method);
        try {
            jdbc.update("""
                    INSERT INTO user_login_identity(id,tenant_id,user_id,method,subject) VALUES(?,?,?,?,?)
                    """, Ids.next(), actor.tenantId(), actor.userId(), method, subject);
        } catch (DuplicateKeyException conflict) {
            throw new ApiException(409, "LOGIN_IDENTITY_IN_USE", "该登录身份已绑定企业内其他账号");
        }
        audit.record(new CompanyAccess(actor.tenantId(), actor.userId(), 0, true, Set.of(), Set.of()),
                "login_identity.bind", "user", actor.userId(), null, Map.of("method", method));
    }

    @Transactional
    public void unbind(SessionAccount actor, String method) {
        requireMethod(method);
        lockCurrent(actor);
        if (remove(actor, method) > 0) {
            audit.record(new CompanyAccess(actor.tenantId(), actor.userId(), 0, true, Set.of(), Set.of()),
                    "login_identity.unbind", "user", actor.userId(), null, Map.of("method", method));
        }
    }

    Map<String,Object> bindings(HttpServletRequest request, SessionAccount actor) {
        access.current(request, actor);
        var result = new LinkedHashMap<String,Object>();
        result.put("sms", null);
        result.put("email", null);
        result.put("wechat", false);
        jdbc.queryForList("""
                SELECT method,subject FROM user_login_identity WHERE tenant_id=? AND user_id=?
                """, actor.tenantId(), actor.userId()).forEach(row -> {
            String method = (String) row.get("method");
            String subject = (String) row.get("subject");
            result.put(method, switch (method) {
                case "sms" -> subject.substring(0, 6) + "****" + subject.substring(subject.length() - 4);
                case "email" -> subject.substring(0, 1) + "***" + subject.substring(subject.indexOf('@'));
                default -> true;
            });
        });
        return result;
    }

    private void lockCurrent(SessionAccount actor) {
        if (!lockUser(actor.tenantId(), actor.userId(), actor.passwordRevision())) {
            throw SessionAccount.expired();
        }
    }

    private boolean lockUser(long tenantId, long userId, long revision) {
        // 登录与修改绑定均先锁用户，再读写绑定，避免同一账号上的反向加锁。
        return !jdbc.queryForList("SELECT u.id " + CURRENT + " FOR UPDATE OF u", Long.class,
                tenantId, userId, revision).isEmpty();
    }

    private int remove(SessionAccount actor, String method) {
        return jdbc.update("DELETE FROM user_login_identity WHERE tenant_id=? AND user_id=? AND method=?",
                actor.tenantId(), actor.userId(), method);
    }

    static void requireMethod(String method) {
        if (method == null || !METHODS.contains(method)) {
            throw new ApiException(400, "VALIDATION_FAILED", "登录方式无效");
        }
    }

    static ApiException authFailed() {
        return new ApiException(401, "AUTH_FAILED", "登录验证失败，请检查绑定状态后重试");
    }

    record Target(long identityId, long userId, long passwordRevision) {}
}
