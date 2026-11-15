package cn.erp.identity;

import cn.erp.shared.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
class LoginCodeTokens {
    private static final Duration CODE_LIFETIME = Duration.ofMinutes(5);
    private static final Duration RESEND_WAIT = Duration.ofSeconds(60);
    private static final Duration HOUR = Duration.ofHours(1);
    private static final int MAX_CODE_ERRORS = 5;
    private static final int CODE_RANGE = 1_000_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DefaultRedisScript<Long> RATE = script("""
            local attempts = redis.call('INCR', KEYS[1])
            if attempts == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return attempts
            """);
    private static final DefaultRedisScript<Long> ISSUE = script("""
            redis.call('HSET', KEYS[1], 'proof', ARGV[1], 'errors', 0)
            return redis.call('EXPIRE', KEYS[1], ARGV[2])
            """);
    private static final DefaultRedisScript<Long> CHECK = script("""
            local proof = redis.call('HGET', KEYS[1], 'proof')
            if not proof then return 0 end
            if proof == ARGV[1] then
              redis.call('DEL', KEYS[1])
              return 1
            end
            if redis.call('HINCRBY', KEYS[1], 'errors', 1) >= tonumber(ARGV[2]) then
              redis.call('DEL', KEYS[1])
            end
            return 0
            """);
    private static final DefaultRedisScript<Long> REMOVE = script("""
            if redis.call('HGET', KEYS[1], 'proof') == ARGV[1] then return redis.call('DEL', KEYS[1]) end
            return 0
            """);

    private final StringRedisTemplate redis;
    private final String namespace;
    private final SecretKeySpec key;

    LoginCodeTokens(StringRedisTemplate redis,
                    @Value("${spring.session.redis.namespace:erp:session:prod}") String namespace,
                    @Value("${ERP_LOGIN_SETTINGS_KEY:}") String rawKey) {
        this.redis = redis;
        this.namespace = namespace + ":login-code:";
        this.key = signingKey(rawKey);
    }

    void reserveSend(String address, long tenantId, String method, String destination) {
        limit("send-ip|" + address, Duration.ofMinutes(10), 60);
        limit("tenant|" + tenantId, HOUR, 200);
        String target = tenantId + "|" + method + "|" + destination;
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(redisKey("wait|" + target), "1", RESEND_WAIT))) {
            throw tooMany();
        }
        limit("send-target|" + target, HOUR, 5);
    }

    void limitVerification(String address, long tenantId, String method, String destination) {
        limit("verify-ip|" + address, Duration.ofMinutes(5), 90);
        limit("verify-target|" + tenantId + "|" + method + "|" + destination, CODE_LIFETIME, 15);
    }

    Issued issue(String scope) {
        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(CODE_RANGE));
        String proof = proof(scope, code);
        redis.execute(ISSUE, List.of(redisKey(scope)), proof, Long.toString(CODE_LIFETIME.toSeconds()));
        return new Issued(code, proof);
    }

    boolean consume(String scope, String code) {
        return Long.valueOf(1).equals(redis.execute(CHECK, List.of(redisKey(scope)), proof(scope, code),
                Integer.toString(MAX_CODE_ERRORS)));
    }

    void cancel(String scope, Issued issued) {
        redis.execute(REMOVE, List.of(redisKey(scope)), issued.proof());
    }

    private void limit(String scope, Duration window, int max) {
        Long attempts = redis.execute(RATE, List.of(redisKey("rate|" + scope)), Long.toString(window.toSeconds()));
        if (attempts == null || attempts > max) {
            throw tooMany();
        }
    }

    private String proof(String scope, String code) {
        return digest("proof|" + scope + "|" + code);
    }

    private String redisKey(String scope) {
        return namespace + digest("key|" + scope);
    }

    private String digest(String value) {
        if (key == null) {
            throw new ApiException(503, "LOGIN_CONFIGURATION_UNAVAILABLE", "验证码渠道暂不可用");
        }
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("无法计算登录验证码摘要", failure);
        }
    }

    private static SecretKeySpec signingKey(String rawKey) {
        try {
            byte[] decoded = Base64.getDecoder().decode(rawKey.strip());
            return decoded.length == 32 ? new SecretKeySpec(decoded, "HmacSHA256") : null;
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }

    private static DefaultRedisScript<Long> script(String source) {
        return new DefaultRedisScript<>(source, Long.class);
    }

    private static ApiException tooMany() {
        return new ApiException(429, "TOO_MANY_REQUESTS", "验证码操作过于频繁，请稍后再试");
    }

    record Issued(String code, String proof) {}
}
