package cn.erp.identity;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.erp.shared.ApiException;

@Service
class CredentialsService {
    private static final int FAILURE_LIMIT = 5;
    private static final int BCRYPT_MAX_BYTES = 72;
    private static final int MIN_PASSWORD_LENGTH = 8;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final String dummyHash;

    CredentialsService(JdbcTemplate jdbc, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public SessionAccount authenticate(String tenant, String username, String rawPassword) {
        Optional<LoginRow> result = jdbc.query("""
                SELECT u.tenant_id, u.id, u.password_hash,
                  COALESCE(u.locked_until > UTC_TIMESTAMP(3), FALSE),
                  IF(u.locked_until IS NULL, u.failed_login_count, 0),
                """ + AccessService.PASSWORD_REVISION + """
                 FROM sys_user u JOIN tenant t ON t.id = u.tenant_id
                 WHERE t.code = ? AND u.username = ? AND u.status = 'active' AND t.status = 'active'
                 FOR UPDATE OF u
                """, (r, n) -> new LoginRow(r.getLong(1), r.getLong(2), r.getString(3),
                r.getBoolean(4), r.getInt(5), r.getLong(6)), tenant, username).stream().findFirst();
        if (result.isEmpty()) {
            matches(rawPassword, dummyHash);
            throw invalid();
        }
        LoginRow user = result.get();
        if (user.locked()) {
            throw locked();
        }
        if (!matches(rawPassword, user.hash())) {
            int failures = user.failures() + 1;
            jdbc.update("""
                    UPDATE sys_user SET failed_login_count = ?,
                      locked_until = IF(? >= ?, DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 15 MINUTE), NULL)
                    WHERE tenant_id = ? AND id = ?
                    """, failures, failures, FAILURE_LIMIT, user.tenantId(), user.id());
            // 登录失败是需要提交的业务结果，事务保留计数和锁定时间。
            throw failures >= FAILURE_LIMIT ? locked() : invalid();
        }
        jdbc.update("""
                UPDATE sys_user SET failed_login_count = 0, locked_until = NULL, last_login_at = UTC_TIMESTAMP(3)
                WHERE tenant_id = ? AND id = ?
                """, user.tenantId(), user.id());
        return new SessionAccount(user.tenantId(), user.id(), user.revision(), System.currentTimeMillis());
    }

    @Transactional
    public SessionAccount changePassword(SessionAccount actor, String oldPassword, String newPassword) {
        validateNewPassword(newPassword);
        var hashes = jdbc.queryForList("""
                SELECT u.password_hash FROM sys_user u JOIN tenant t ON t.id = u.tenant_id
                WHERE u.tenant_id = ? AND u.id = ? AND u.status = 'active' AND t.status = 'active' AND
                """ + AccessService.PASSWORD_REVISION + " = ? FOR UPDATE OF u", String.class,
                actor.tenantId(), actor.userId(), actor.passwordRevision());
        if (hashes.isEmpty()) {
            throw SessionAccount.expired();
        }
        if (!matches(oldPassword, hashes.getFirst())) {
            throw new ApiException(400, "VALIDATION_FAILED", "原密码错误");
        }
        if (oldPassword.equals(newPassword)) {
            throw new ApiException(400, "VALIDATION_FAILED", "新密码应与原密码不同");
        }
        // DATETIME(3) 只有毫秒精度，连续改密时也要让旧会话的密码版本失效。
        jdbc.update("""
                UPDATE sys_user SET password_hash = ?, must_change_password = 0,
                password_changed_at = GREATEST(UTC_TIMESTAMP(3),
                    COALESCE(DATE_ADD(password_changed_at, INTERVAL 1000 MICROSECOND), UTC_TIMESTAMP(3))),
                failed_login_count = 0, locked_until = NULL, version = version + 1
                WHERE tenant_id = ? AND id = ?
                """, passwords.encode(newPassword), actor.tenantId(), actor.userId());
        Long revision = jdbc.queryForObject("SELECT " + AccessService.PASSWORD_REVISION
                + " FROM sys_user u WHERE tenant_id = ? AND id = ?", Long.class, actor.tenantId(), actor.userId());
        return new SessionAccount(actor.tenantId(), actor.userId(), revision, actor.loginMillis());
    }

    static void validateNewPassword(String value) {
        if (value == null || value.isBlank() || value.length() < MIN_PASSWORD_LENGTH ||
                value.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw new ApiException(400, "VALIDATION_FAILED", "密码至少 8 个字符，UTF-8 编码后最多 72 字节");
        }
    }

    private boolean matches(String password, String hash) {
        return password.getBytes(StandardCharsets.UTF_8).length <= BCRYPT_MAX_BYTES && passwords.matches(password, hash);
    }

    private static ApiException invalid() {
        return new ApiException(401, "AUTH_FAILED", "用户名或密码错误");
    }

    private static ApiException locked() {
        return new ApiException(401, "ACCOUNT_LOCKED", "登录失败次数过多，请 15 分钟后再试");
    }

    private record LoginRow(long tenantId, long id, String hash, boolean locked, int failures, long revision) {}
}
