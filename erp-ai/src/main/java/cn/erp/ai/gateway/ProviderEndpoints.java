package cn.erp.ai.gateway;

import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import cn.erp.ai.common.AiException;

@Component
class ProviderEndpoints {
    private final Map<String, URI> entries;
    private final boolean overseas;

    ProviderEndpoints(@Value("${erp.ai.provider-endpoints}") String configured,
            @Value("${erp.ai.allow-overseas}") boolean overseas) {
        var endpoints = new TreeMap<String, URI>();
        for (var entry : configured.split(",")) {
            if (entry.isBlank()) continue;
            var pair = entry.strip().split("=", 2);
            if (pair.length != 2 || !pair[0].matches("[a-z][a-z0-9_-]{0,63}"))
                throw new IllegalStateException("模型服务白名单格式无效");
            URI uri = URI.create(pair[1].replaceFirst("/+$", ""));
            if (!allowed(uri) || endpoints.putIfAbsent(pair[0], uri) != null)
                throw new IllegalStateException("模型服务地址或白名单编号无效");
        }
        this.entries = Collections.unmodifiableMap(endpoints);
        this.overseas = overseas;
    }

    List<Map<String,String>> list() {
        return entries.keySet().stream().map(id -> Map.of("id", id, "name", id)).toList();
    }

    URI require(String key, String type, String region, boolean confirmed) {
        var uri = entries.get(key);
        if (uri == null) throw AiException.invalid("请选择部署配置中允许的模型服务");
        boolean officialClaude = "api.anthropic.com".equalsIgnoreCase(uri.getHost());
        if (officialClaude != "anthropic".equals(type)
                || officialClaude && (!"https://api.anthropic.com".equals(uri.toString()) || !"overseas".equals(region)))
            throw AiException.invalid("Claude 仅可选择已批准的官方服务地址与境外路由");
        if ("overseas".equals(region) && (!overseas || !confirmed))
            throw new AiException(403, "AI_OUTBOUND_FORBIDDEN", "境外模型需启用出境许可并由管理员确认");
        return uri;
    }

    private boolean allowed(URI uri) {
        String host = uri.getHost();
        String path = uri.getRawPath();
        return host != null && !"api.openai.com".equalsIgnoreCase(host)
                && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                && path != null && path.matches("(?:/[A-Za-z0-9_-]+)*")
                && (!"api.anthropic.com".equalsIgnoreCase(host) || "https".equals(uri.getScheme()));
    }
}
