package cn.erp.ai.gateway;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import cn.erp.ai.common.AiException;
import cn.erp.ai.common.BoundedHttp;

/** 按已批准的端点创建 Spring AI 模型，统一封装可控的请求和响应。 */
final class HttpChatModel implements ChatModel {
    private static final Duration TIMEOUT = Duration.ofSeconds(42);
    private final ModelCatalog.Target target;
    private final ModelCatalog.Route route;
    private final String secret;
    private final BoundedHttp http;
    private final ObjectMapper json;

    HttpChatModel(ModelCatalog.Target target, ModelCatalog.Route route, String secret,
            BoundedHttp http, ObjectMapper json) {
        this.target=target;
        this.route=route;
        this.secret=secret;
        this.http=http;
        this.json=json;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        boolean claude = target.type().equals("anthropic");
        String path = claude ? "/v1/messages" : "/chat/completions";
        String system = prompt.getSystemMessage().getText();
        String user = prompt.getUserMessage().getText();
        var payload = new LinkedHashMap<String,Object>();
        payload.put("model",target.modelKey());
        payload.put("max_tokens",route.maxTokens());
        if (route.temperature() != null) payload.put("temperature",route.temperature());
        if (claude) {
            payload.put("system",system);
            payload.put("messages",List.of(Map.of("role","user","content",user)));
        } else {
            payload.put("messages",List.of(Map.of("role","system","content",system),Map.of("role","user","content",user)));
        }
        var request = HttpRequest.newBuilder(URI.create(target.endpoint() + path)).timeout(TIMEOUT)
                .header("Accept","application/json").header("Content-Type","application/json");
        if (claude) {
            request.header("anthropic-version","2023-06-01").header("x-api-key",secret);
        } else if (!secret.isBlank()) {
            request.header("Authorization","Bearer " + secret);
        }
        var response = http.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(payload))).build(),
                1_048_576,TIMEOUT);
        if (response.statusCode() != 200) throw error("AI_MODEL_UNAVAILABLE","模型服务调用失败");
        try {
            return parse(json.readTree(response.body()),claude,estimated(system) + estimated(user));
        } catch (AiException failure) {
            throw failure;
        } catch (RuntimeException malformed) {
            throw error("AI_MODEL_RESPONSE","模型返回格式无效");
        }
    }

    private ChatResponse parse(JsonNode body, boolean claude, int estimatedInput) {
        String text;
        var usage = body.path("usage");
        int input = usage.path(claude ? "input_tokens" : "prompt_tokens").asInt(-1);
        int output = usage.path(claude ? "output_tokens" : "completion_tokens").asInt(-1);
        if (claude) {
            var builder = new StringBuilder();
            for (var block : body.path("content")) {
                if ("text".equals(block.path("type").asString())) builder.append(block.path("text").asString());
            }
            text = builder.toString();
        } else {
            var message = body.path("choices").path(0).path("message");
            if (message.path("refusal").isString() || message.path("tool_calls").isArray())
                throw error("AI_MODEL_REFUSED","模型拒绝返回本次回答");
            text = message.path("content").asString("");
        }
        if (input < 0) input = estimatedInput;
        if (output < 0) output = Math.min(route.maxTokens(),estimated(text));
        if (input > 4_000_000 || output > 100_000) throw error("AI_MODEL_RESPONSE","模型用量返回无效");
        if (text.isBlank() || text.length() > 32000) {
            throw new ModelGateway.ModelFailure("AI_MODEL_REFUSED","模型未返回可用内容",input,output);
        }
        var metadata = ChatResponseMetadata.builder().model(target.modelKey())
                .usage(new DefaultUsage(input,output)).build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))),metadata);
    }

    static int estimated(String value) {
        // 字节数比字符数保守，中文与标点也预留足量令牌。
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private AiException error(String code, String message) { return new AiException(502,code,message); }
}
