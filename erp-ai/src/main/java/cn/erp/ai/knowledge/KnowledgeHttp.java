package cn.erp.ai.knowledge;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

import org.springframework.stereotype.Component;

import cn.erp.ai.common.AiException;
import cn.erp.ai.common.BoundedHttp;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
final class KnowledgeHttp {
    private static final int MAX_RESPONSE = 8 * 1024 * 1024;
    private final BoundedHttp http;
    private final ObjectMapper json;

    KnowledgeHttp(BoundedHttp http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    Response send(String endpoint, String method, String authorization, Object body, int seconds) {
        URI uri = URI.create(endpoint);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("知识服务地址格式无效");
        }
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(seconds)).header("Accept", "application/json");
        if (!authorization.isBlank()) {
            request.header("Authorization", authorization);
        }
        var publisher = HttpRequest.BodyPublishers.noBody();
        if (body instanceof Ndjson ndjson) {
            request.header("Content-Type", "application/x-ndjson");
            publisher = HttpRequest.BodyPublishers.ofString(ndjson.data());
        } else if (body != null) {
            request.header("Content-Type", "application/json");
            publisher = HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body));
        }
        try {
            var response = http.send(request.method(method, publisher).build(), MAX_RESPONSE, Duration.ofSeconds(seconds));
            byte[] data = response.body();
            JsonNode value = data.length == 0 || response.statusCode() >= 500 ? null : json.readTree(data);
            return new Response(response.statusCode(), value);
        } catch (AiException | JacksonException error) {
            throw unavailable();
        }
    }

    static String bearer(String token) {
        return token.isBlank() ? "" : "Bearer " + token;
    }

    static AiException unavailable() {
        return new AiException(503, "KNOWLEDGE_SERVICE_UNAVAILABLE", "知识处理服务暂不可用");
    }

    record Response(int status, JsonNode data) {
        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    record Ndjson(String data) {}
}
