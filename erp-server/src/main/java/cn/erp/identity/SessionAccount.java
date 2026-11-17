package cn.erp.identity;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import cn.erp.shared.ApiException;

record SessionAccount(long tenantId, long userId, long passwordRevision, long loginMillis,
                      long identityId, long providerVersion, long mfaEnrollmentId)
        implements Serializable {
    private static final String ATTRIBUTE = SessionAccount.class.getName();
    private static final Duration ABSOLUTE_TIMEOUT = Duration.ofHours(12);

    SessionAccount(long tenantId, long userId, long passwordRevision, long loginMillis) {
        this(tenantId, userId, passwordRevision, loginMillis, 0, 0, 0);
    }

    SessionAccount withIdentity(long id, long version) {
        return new SessionAccount(tenantId, userId, passwordRevision, loginMillis, id, version, mfaEnrollmentId);
    }

    SessionAccount withMfa(long enrollment) {
        return new SessionAccount(tenantId, userId, passwordRevision, loginMillis, identityId, providerVersion, enrollment);
    }

    SessionAccount withPasswordRevision(long revision) {
        return new SessionAccount(tenantId, userId, revision, loginMillis, identityId, providerVersion, mfaEnrollmentId);
    }

    static SessionAccount read(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(ATTRIBUTE) instanceof SessionAccount account)) {
            throw expired();
        }
        if (account.isExpired(Instant.now())) {
            session.invalidate();
            throw expired();
        }
        return account;
    }

    boolean isExpired(Instant now) {
        return !now.isBefore(Instant.ofEpochMilli(loginMillis).plus(ABSOLUTE_TIMEOUT));
    }

    void save(HttpServletRequest request) {
        request.getSession(true).setAttribute(ATTRIBUTE, this);
    }

    static ApiException expired() {
        return new ApiException(401, "UNAUTHENTICATED", "请先登录");
    }
}
