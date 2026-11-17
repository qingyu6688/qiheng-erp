package cn.erp.aitools;

import cn.erp.shared.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
class AiBridgeSignature {
    private static final String TIMESTAMP_HEADER = "X-Erp-Ai-Timestamp";
    private static final String NONCE_HEADER = "X-Erp-Ai-Nonce";
    private static final String SIGNATURE_HEADER = "X-Erp-Ai-Signature";
    private static final Pattern TIMESTAMP = Pattern.compile("[1-9][0-9]{0,15}");
    private static final Pattern NONCE = Pattern.compile("[a-f0-9]{32}");
    private static final Pattern SIGNATURE = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern COMPANY = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Duration MAX_CLOCK_SKEW = Duration.ofSeconds(60);
    private static final Duration NONCE_LIFETIME = MAX_CLOCK_SKEW.multipliedBy(2);
    private static final int MIN_KEY_BYTES = 32;
    private static final int MAX_QUERY_BODY_BYTES = 8192;
    private static final String HMAC = "HmacSHA256";

    private final StringRedisTemplate redis;
    private final byte[] signingKey;
    private final String noncePrefix;

    AiBridgeSignature(StringRedisTemplate redis, @Value("${ERP_AI_SIGNING_KEY:}") String signingKey,
                      @Value("${spring.session.redis.namespace:erp:session:prod}") String sessionNamespace) {
        this.redis = redis;
        this.signingKey = signingKey.getBytes(StandardCharsets.UTF_8);
        this.noncePrefix = sessionNamespace + ":ai-bridge:nonce:";
    }

    void verify(HttpServletRequest request, String path, boolean allowsPost) {
        authenticate(request, path, allowsPost, null);
    }

    byte[] verifyQuery(HttpServletRequest request, String path) {
        byte[] body = queryBody(request);
        authenticate(request, path, true, body);
        return body;
    }

    private void authenticate(HttpServletRequest request, String path, boolean allowsPost, byte[] body) {
        if (signingKey.length < MIN_KEY_BYTES) {
            throw new ApiException(503, "AI_BRIDGE_UNAVAILABLE", "AI 服务尚未启用");
        }
        String method = request.getMethod();
        String timestamp = singleHeader(request, TIMESTAMP_HEADER);
        String nonce = singleHeader(request, NONCE_HEADER);
        String signature = singleHeader(request, SIGNATURE_HEADER);
        String company = singleHeader(request, "X-Company-Id");
        String cookie = singleHeader(request, "Cookie");
        boolean post = "POST".equals(method);
        if (!("GET".equals(method) || post && allowsPost) || !path.equals(request.getRequestURI())
                || !matches(TIMESTAMP, timestamp) || !matches(NONCE, nonce) || !matches(SIGNATURE, signature)
                || !matches(COMPANY, company) || cookie == null || cookie.isBlank()) {
            throw denied();
        }
        long signedAt = Long.parseLong(timestamp);
        long now = System.currentTimeMillis();
        if (Math.abs(now - signedAt) >= MAX_CLOCK_SKEW.toMillis()
                || !validSignature(timestamp, nonce, method, path, company, cookie, signature, body)) {
            throw denied();
        }
        if (post && singleHeader(request, "X-XSRF-TOKEN") == null) {
            throw denied();
        }
        if (body == null) verifyNoParameters(request);
        // Redis 的原子写入让同一签名在多实例部署中也只能使用一次。
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(noncePrefix + nonce, "1", NONCE_LIFETIME))) {
            throw denied();
        }
    }

    private boolean validSignature(String timestamp, String nonce, String method, String path,
                                   String company, String cookie, String signature, byte[] body) {
        try {
            var hex = HexFormat.of();
            var sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest(cookie.getBytes(StandardCharsets.UTF_8));
            String canonical = String.join("\n", timestamp, nonce, method, path, company, hex.formatHex(digest));
            if (body != null) canonical += "\n" + hex.formatHex(sha256.digest(body));
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(signingKey, HMAC));
            byte[] expected = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(expected, hex.parseHex(signature));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("运行环境缺少 AI 服务签名算法", error);
        }
    }

    private static byte[] queryBody(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod()) || request.getQueryString() != null
                || request.getContentLengthLong() > MAX_QUERY_BODY_BYTES) {
            throw new ApiException(400, "VALIDATION_FAILED", "查询仅接受有效长度的 JSON 请求体");
        }
        try {
            byte[] body = request.getInputStream().readNBytes(MAX_QUERY_BODY_BYTES + 1);
            if (body.length == 0 || body.length > MAX_QUERY_BODY_BYTES)
                throw new ApiException(400, "VALIDATION_FAILED", "查询参数为空或超出长度限制");
            return body;
        } catch (IOException error) {
            throw new ApiException(400, "VALIDATION_FAILED", "无法读取查询参数");
        }
    }

    private static void verifyNoParameters(HttpServletRequest request) {
        if (request.getQueryString() != null || request.getContentLengthLong() > 0
                || request.getHeader("Transfer-Encoding") != null) {
            throw invalidRequest();
        }
        try {
            if (request.getInputStream().read() != -1) {
                throw invalidRequest();
            }
        } catch (IOException error) {
            throw invalidRequest();
        }
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        var values = request.getHeaders(name);
        if (!values.hasMoreElements()) {
            return null;
        }
        String value = values.nextElement();
        return values.hasMoreElements() ? null : value;
    }

    private static boolean matches(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).matches();
    }

    private static ApiException denied() {
        return new ApiException(403, "FORBIDDEN", "AI 服务请求校验失败");
    }

    private static ApiException invalidRequest() {
        return new ApiException(400, "VALIDATION_FAILED", "该 AI 服务接口不接受额外参数");
    }
}
