package cn.erp.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 浏览器回归在一分钟内会多次登录随机验收企业中的同一测试账号。 */
@Component
@Primary
@Profile("e2e")
class BrowserLoginLimiter extends LoginLimiter {
    private static final int BROWSER_ATTEMPTS_PER_MINUTE = 60;

    BrowserLoginLimiter(StringRedisTemplate redis,
            @Value("${spring.session.redis.namespace:erp:session:prod}") String namespace) {
        super(redis, namespace);
    }

    @Override
    int maxAttempts() {
        return BROWSER_ATTEMPTS_PER_MINUTE;
    }
}
