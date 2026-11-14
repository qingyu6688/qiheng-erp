package cn.erp.identity;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import cn.erp.shared.ApiException;

@Component
class LoginLimiter {
    private static final int MAX_ATTEMPTS = 5;
    // 计数与首次设置有效期必须原子执行，避免中途异常留下永久限流的键。
    private static final DefaultRedisScript<Long> ATTEMPT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], 60) end
            return count
            """, Long.class);
    private final StringRedisTemplate redis;
    private final String keyPrefix;

    LoginLimiter(StringRedisTemplate redis, @Value("${spring.session.redis.namespace:erp:session:prod}") String namespace) {
        this.redis = redis;
        this.keyPrefix = namespace + ":login:";
    }

    void check(String address, String tenant, String username) {
        String key = (address + "\n" + tenant + "\n" + username).toLowerCase(Locale.ROOT);
        String counter = keyPrefix + DigestUtils.md5DigestAsHex(key.getBytes(StandardCharsets.UTF_8));
        Long attempts = redis.execute(ATTEMPT, List.of(counter));
        if (attempts == null) {
            throw new ApiException(503, "SERVICE_UNAVAILABLE", "登录服务暂时不可用");
        }
        if (attempts > MAX_ATTEMPTS) {
            throw new ApiException(429, "TOO_MANY_REQUESTS", "登录操作过于频繁，请稍后再试");
        }
    }
}
