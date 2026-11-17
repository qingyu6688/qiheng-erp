package cn.erp.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 登录主体、渠道和二次验证的授权读取共用一套条件。 */
@Component
class SessionChecks {
    static final String PRIMARY = """
             AND u.user_type='human' AND (
                (?=0 AND COALESCE((SELECT p.password_login_allowed FROM tenant_security_policy p WHERE p.tenant_id=u.tenant_id),TRUE))
                OR EXISTS(SELECT 1 FROM user_login_identity i JOIN tenant_login_provider p
                      ON p.tenant_id=i.tenant_id AND p.method=i.method AND p.enabled=1
                    WHERE i.id=? AND i.tenant_id=u.tenant_id AND i.user_id=u.id AND (?=0 OR p.version=?))
             )
            """;
    static final String ROLE_REQUIRED = """
              EXISTS(SELECT 1 FROM tenant_security_policy p
                JOIN sys_user_role ur ON ur.tenant_id=p.tenant_id AND ur.user_id=u.id
                JOIN sys_role r ON r.tenant_id=ur.tenant_id AND r.id=ur.role_id AND r.status='active'
                WHERE p.tenant_id=u.tenant_id AND JSON_CONTAINS(p.mfa_required_roles,JSON_QUOTE(CAST(ur.role_id AS CHAR))))
            """;
    static final String MFA = """
             AND (EXISTS(SELECT 1 FROM user_mfa m WHERE m.tenant_id=u.tenant_id AND m.user_id=u.id AND m.id=?)
               OR (?=0 AND NOT EXISTS(SELECT 1 FROM user_mfa m WHERE m.tenant_id=u.tenant_id AND m.user_id=u.id)
                   AND NOT %s))
            """.formatted(ROLE_REQUIRED);
    private static final String ACCOUNT_FROM = " FROM sys_user u JOIN tenant t ON t.id=u.tenant_id ";
    private static final String ACCOUNT_WHERE = """
            WHERE u.tenant_id=? AND u.id=? AND u.status='active' AND t.status='active' AND %s=?
            """.formatted(AccessService.PASSWORD_REVISION);
    private final JdbcTemplate jdbc;

    SessionChecks(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    static Object[] args(SessionAccount actor, boolean full) {
        if (!full) return new Object[]{actor.tenantId(),actor.userId(),actor.passwordRevision(),
                actor.identityId(),actor.identityId(),actor.providerVersion(),actor.providerVersion()};
        return new Object[]{actor.tenantId(),actor.userId(),actor.passwordRevision(),
                actor.identityId(),actor.identityId(),actor.providerVersion(),actor.providerVersion(),
                actor.mfaEnrollmentId(),actor.mfaEnrollmentId()};
    }

    boolean full(SessionAccount actor) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 " + ACCOUNT_FROM + ACCOUNT_WHERE + PRIMARY + MFA + ")",
                Boolean.class,args(actor,true)));
    }

    State state(SessionAccount actor, boolean lock) {
        var states=jdbc.query("SELECT u.username,m.id," + ROLE_REQUIRED + " AS mfa_required "
                        + ACCOUNT_FROM + "LEFT JOIN user_mfa m ON m.tenant_id=u.tenant_id AND m.user_id=u.id "
                        + ACCOUNT_WHERE + PRIMARY + (lock ? " FOR UPDATE OF u" : ""),
                (row,index) -> new State(row.getString(1),row.getLong(2),row.getBoolean(3)),args(actor,false));
        return states.stream().findFirst().orElseThrow(SessionAccount::expired);
    }

    record State(String username,long enrollmentId,boolean required) {}
}
