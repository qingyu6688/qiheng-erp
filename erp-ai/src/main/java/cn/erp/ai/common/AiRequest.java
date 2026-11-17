package cn.erp.ai.common;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class AiRequest {
    private static final String CONTEXT = "/api/v1/ai-tools/context";
    private static final String WAREHOUSES = "/api/v1/ai-tools/warehouses";
    private static final Duration ERP_TIMEOUT = Duration.ofSeconds(10);
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD");
    private final String origin;
    private final byte[] signingKey;
    private final BoundedHttp http;
    private final ObjectMapper json;

    public AiRequest(@Value("${erp.ai.erp-origin}") String origin,
            @Value("${erp.ai.signing-key}") String signingKey, BoundedHttp http, ObjectMapper json) {
        URI address = URI.create(origin);
        if (!Set.of("http", "https").contains(address.getScheme()) || address.getHost() == null
                || address.getUserInfo() != null || address.getQuery() != null || address.getFragment() != null
                || !Set.of("", "/").contains(address.getPath())) {
            throw new IllegalStateException("ERP 服务地址必须为固定的内部来源");
        }
        this.origin = origin.replaceFirst("/$", "");
        this.signingKey = signingKey.getBytes(StandardCharsets.UTF_8);
        this.http = http;
        this.json = json;
    }

    public AiActor actor(HttpServletRequest request) { return access(request).actor(); }

    public Access access(HttpServletRequest request) {
        String company = request.getHeader("X-Company-Id");
        AiInputs.id(company);
        String cookies = String.join("; ", Collections.list(request.getHeaders("Cookie")));
        if (cookies.isBlank() || cookies.length() > 8192)
            throw new AiException(401, "AI_LOGIN_REQUIRED", "请登录后使用 AI 助手");
        String csrf = request.getHeader("X-XSRF-TOKEN");
        boolean write = !READ_METHODS.contains(request.getMethod());
        if (write && (csrf == null || csrf.isBlank() || csrf.length() > 512))
            throw new AiException(403, "AI_CSRF_REQUIRED", "请求验证已失效，请刷新后重试");
        var credentials = new Credentials(company, cookies);
        return new Access(context(credentials, write ? csrf : null), credentials);
    }

    public AiActor refresh(Access access) {
        AiActor current = context(access.credentials(), null);
        if (!current.tenantId().equals(access.actor().tenantId()) || !current.userId().equals(access.actor().userId()))
            throw AiException.forbidden();
        return current;
    }

    public JsonNode warehouses(Access access) {
        return erp(access.credentials(), "GET", WAREHOUSES, null);
    }

    private AiActor context(Credentials credentials, String csrf) {
        var data = erp(credentials, csrf == null ? "GET" : "POST", CONTEXT, csrf);
        return new AiActor(data.path("tenantId").asString(), data.path("userId").asString(),
                credentials.company(), stringSet(data.path("permissions")), stringSet(data.path("roleIds")),
                stringSet(data.path("departmentIds")));
    }

    private static Set<String> stringSet(JsonNode array) {
        if (!array.isArray()) throw upstream();
        Set<String> values = new LinkedHashSet<>();
        for (var item : array) {
            if (!item.isString()) throw upstream();
            values.add(item.stringValue());
        }
        return Set.copyOf(values);
    }

    private JsonNode erp(Credentials credentials, String method, String path, String csrf) {
        if (signingKey.length < 32)
            throw new AiException(503, "AI_BRIDGE_DISABLED", "AI 服务尚未启用");
        String timestamp = Long.toString(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String signature = sign(signingKey, timestamp, nonce, method, path, credentials.company(), credentials.cookie);
        var request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(ERP_TIMEOUT)
                .header("Accept", "application/json").header("Cookie", credentials.cookie)
                .header("X-Company-Id", credentials.company())
                .header("X-Erp-Ai-Timestamp", timestamp).header("X-Erp-Ai-Nonce", nonce)
                .header("X-Erp-Ai-Signature", signature);
        if (csrf != null) request.header("X-XSRF-TOKEN", csrf);
        var response = http.send(request.method(method, HttpRequest.BodyPublishers.noBody()).build(), 131072, ERP_TIMEOUT);
        if (response.statusCode() == 401) throw new AiException(401, "AI_LOGIN_REQUIRED", "登录已失效，请重新登录");
        if (response.statusCode() == 403) throw AiException.forbidden();
        if (response.statusCode() == 404) throw AiException.missing();
        if (response.statusCode() != 200) throw upstream();
        var body = json.readTree(response.body());
        if (body.path("code").asInt() != 200 || !body.path("data").isObject()) throw upstream();
        var data = body.path("data");
        if (path.equals(CONTEXT) && !credentials.company().equals(data.path("companyId").asString())) throw upstream();
        return data;
    }

    private static AiException upstream() {
        return new AiException(502, "AI_ERP_RESPONSE", "业务服务未返回有效资料");
    }

    static String sign(byte[] key, String timestamp, String nonce, String method, String path, String company, String cookie) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cookie.getBytes(StandardCharsets.UTF_8)));
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            String target = String.join("\n", timestamp, nonce, method, path, company, hash);
            return HexFormat.of().formatHex(mac.doFinal(target.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("签名算法不可用", error);
        }
    }

    public static final class Credentials {
        private final String company;
        private final String cookie;
        private Credentials(String company, String cookie) { this.company = company; this.cookie = cookie; }
        public String company() { return company; }
    }

    public record Access(AiActor actor, Credentials credentials) {}
}
