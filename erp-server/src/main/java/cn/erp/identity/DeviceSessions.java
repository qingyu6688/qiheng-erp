package cn.erp.identity;

import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
class DeviceSessions {
    static final String CURRENT = DeviceSessions.class.getName()+".current";
    private static final Duration PAIR_LIFETIME = Duration.ofMinutes(5);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final StringRedisTemplate redis;
    private final SessionRepository<? extends Session> sessions;
    private final SessionAuthority authority;
    private final AccessService access;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final String prefix;

    DeviceSessions(StringRedisTemplate redis,SessionRepository<? extends Session> sessions,SessionAuthority authority,
                   AccessService access,JdbcTemplate jdbc,ObjectMapper json,
                   @Value("${spring.session.redis.namespace:erp:session:prod}") String namespace) {
        this.redis=redis;
        this.sessions=sessions;
        this.authority=authority;
        this.access=access;
        this.jdbc=jdbc;
        this.json=json;
        this.prefix=namespace+":device:";
    }

    record Pairing(String pairingCode,Instant expiresAt) {}
    record Issued(String id,String token,Instant expiresAt) {}
    private record Invitation(String parentId,String deviceName) {}
    record Credential(long id,String sessionId,String tokenHash,String deviceName) {}

    Pairing pair(HttpServletRequest request,String requestedName) {
        var account=SessionAccount.read(request);
        String parent=request.getSession(false).getId();
        requirePairingPermission(request,account);
        String name=requestedName==null ? "" : requestedName.strip();
        if (name.isEmpty() || name.length()>80 || name.codePoints().anyMatch(Character::isISOControl))
            throw new ApiException(422,"VALIDATION_FAILED","设备名称须为 1 到 80 个可见字符");
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(prefix+"throttle:"+digest(parent),"1",Duration.ofSeconds(3))))
            throw new ApiException(429,"DEVICE_PAIRING_LIMITED","请稍后重新生成配对码");
        authority.check(parent);
        Instant now=Instant.now(), expiry=now.plus(PAIR_LIFETIME);
        if (account.expiresAt().isBefore(expiry)) expiry=account.expiresAt();
        if (!expiry.isAfter(now)) throw SessionAccount.expired();
        String code=random("P-");
        redis.opsForValue().set(prefix+"pair:"+digest(code),json.writeValueAsString(new Invitation(parent,name)),Duration.between(now,expiry));
        return new Pairing(code,expiry);
    }

    @Transactional
    Issued activate(HttpServletRequest request,String code) {
        if (code==null || !code.matches("P-[A-Za-z0-9_-]{43}")) throw invalidPair();
        String stored=redis.opsForValue().getAndDelete(prefix+"pair:"+digest(code));
        if (stored==null) throw invalidPair();
        var invitation=json.readValue(stored,Invitation.class);
        authority.check(invitation.parentId());
        var parent=sessions.findById(invitation.parentId());
        if (parent==null || parent.isExpired() || Boolean.TRUE.equals(parent.getAttribute(SessionAccount.DEVICE_ATTRIBUTE)))
            throw invalidPair();
        SessionAccount account=parent.getAttribute(SessionAccount.class.getName());
        if (account==null || account.isExpired(Instant.now())) throw invalidPair();
        requirePairingPermission(request,account);
        return issue(sessions,account,parent,invitation.deviceName());
    }

    private <S extends Session> Issued issue(SessionRepository<S> repository,SessionAccount account,
                                             Session parent,String deviceName) {
        Instant now=Instant.now(), expiry=account.expiresAt();
        if (!expiry.isAfter(now)) throw invalidPair();
        authority.check(parent.getId());
        S session=repository.createSession();
        session.setMaxInactiveInterval(parent.getMaxInactiveInterval());
        session.setAttribute(SessionAccount.class.getName(),account);
        session.setAttribute(SessionAccount.DEVICE_ATTRIBUTE,Boolean.TRUE);
        long id=Ids.next();
        String token=random("PDA."), tokenHash=digest(token);
        jdbc.update("""
                INSERT INTO auth_device_token(id,tenant_id,user_id,token_hash,session_hash,device_name,expires_at,last_used_at)
                VALUES(?,?,?,?,?,?,?,UTC_TIMESTAMP(3))
                """,id,account.tenantId(),account.userId(),tokenHash,digest(session.getId()),deviceName,Timestamp.from(expiry));
        var credential=new Credential(id,session.getId(),tokenHash,deviceName);
        try {
            repository.save(session);
            redis.opsForValue().set(prefix+"token:"+tokenHash,json.writeValueAsString(credential),Duration.between(now,expiry));
        } catch (RuntimeException failure) {
            try { repository.deleteById(session.getId()); }
            catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
        return new Issued(Long.toString(id),token,expiry);
    }

    SessionAccount authenticate(HttpServletRequest request,String token) {
        if (!token.matches("PDA\\.[A-Za-z0-9_-]{43}")) throw SessionAccount.expired();
        String tokenHash=digest(token), stored=redis.opsForValue().get(prefix+"token:"+tokenHash);
        if (stored==null) throw SessionAccount.expired();
        var credential=json.readValue(stored,Credential.class);
        if (!tokenHash.equals(credential.tokenHash())) throw SessionAccount.expired();
        authority.check(credential.sessionId());
        return authenticate(sessions,request,credential);
    }

    private <S extends Session> SessionAccount authenticate(SessionRepository<S> repository,HttpServletRequest request,Credential credential) {
        S session=repository.findById(credential.sessionId());
        if (session==null || session.isExpired() || !Boolean.TRUE.equals(session.getAttribute(SessionAccount.DEVICE_ATTRIBUTE)))
            throw SessionAccount.expired();
        SessionAccount account=session.getAttribute(SessionAccount.class.getName());
        if (account==null || account.isExpired(Instant.now()) || !active(account,credential)) throw SessionAccount.expired();
        request.setAttribute(SessionAccount.DEVICE_ATTRIBUTE,account);
        if (access.current(request,account).mustChangePassword()) throw SessionAccount.expired();
        session.setLastAccessedTime(Instant.now());
        repository.save(session);
        authority.touch(credential.sessionId(),account,request.getRemoteAddr(),"PDA "+credential.deviceName());
        jdbc.update("""
                UPDATE auth_device_token SET last_used_at=UTC_TIMESTAMP(3) WHERE id=? AND tenant_id=? AND user_id=?
                AND revoked_at IS NULL AND last_used_at<UTC_TIMESTAMP(3)-INTERVAL 1 MINUTE
                """,credential.id(),account.tenantId(),account.userId());
        request.setAttribute(CURRENT,credential);
        return account;
    }

    void logout(HttpServletRequest request) {
        if (!(request.getAttribute(CURRENT) instanceof Credential credential)) throw SessionAccount.expired();
        var account=SessionAccount.read(request);
        authority.revoke(credential.sessionId(),account.tenantId(),account.userId());
        redis.delete(prefix+"token:"+credential.tokenHash());
    }

    private boolean active(SessionAccount account,Credential credential) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM auth_device_token WHERE id=? AND tenant_id=? AND user_id=?
                    AND token_hash=? AND session_hash=? AND revoked_at IS NULL AND expires_at>UTC_TIMESTAMP(3))
                """,Boolean.class,credential.id(),account.tenantId(),account.userId(),credential.tokenHash(),digest(credential.sessionId())));
    }

    private void requirePairingPermission(HttpServletRequest request,SessionAccount account) {
        var profile=access.current(request,account);
        if (profile.mustChangePassword() || !profile.permissions().contains("device:pair"))
            throw new ApiException(403,"FORBIDDEN","没有配对仓储作业设备的权限");
    }

    private static String random(String marker) {
        byte[] bytes=new byte[32];
        RANDOM.nextBytes(bytes);
        return marker+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String digest(String value) { return SessionAuthority.publicId(value); }
    private static ApiException invalidPair() { return new ApiException(401,"DEVICE_PAIRING_EXPIRED","设备配对码已失效，请重新生成"); }
}
