package cn.erp.portal;

import cn.erp.shared.ApiException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class PortalIntentClient {
    private static final String SIGNING_CONTEXT="portal-intent-v1";
    private static final Set<String> TOPICS=Set.of("orders","shipments","statements","invoices","handoff");
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json;
    private final URI endpoint;
    private final byte[] secret;

    PortalIntentClient(ObjectMapper json,@Value("${ERP_PORTAL_AI_ORIGIN:http://127.0.0.1:18081}") String origin,
                       @Value("${ERP_AI_SIGNING_KEY:}") String signingKey) {
        this.json=json;
        URI configured=URI.create(origin);
        if(configured.getHost()==null||!Set.of("http","https").contains(configured.getScheme())||configured.getRawUserInfo()!=null
                ||configured.getRawQuery()!=null||configured.getRawFragment()!=null||!Set.of("","/").contains(configured.getPath()))
            throw new IllegalArgumentException("门户 AI 仅接受服务源地址");
        this.endpoint=configured.resolve("/internal/portal-intent");
        this.secret=signingKey.getBytes(StandardCharsets.UTF_8);
    }

    boolean enabled() {return secret.length>=32;}

    String topic(PortalActor actor,String question) {
        if(!enabled()) return "handoff";
        String timestamp=Long.toString(Instant.now().getEpochSecond());
        String nonce=UUID.randomUUID().toString().replace("-", "");
        byte[] body=json.writeValueAsBytes(Map.of("tenantId",Long.toString(actor.tenantId()),"companyId",Long.toString(actor.companyId()),
                "userId",Long.toString(actor.userId()),"question",question));
        var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(12))
                .header("Content-Type","application/json").header("X-Portal-Intent-Time",timestamp)
                .header("X-Portal-Intent-Nonce",nonce).header("X-Portal-Intent-Signature",signature(timestamp,nonce,body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        try {
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var stream=response.body()) {
                if(response.statusCode()!=200) return "handoff";
                byte[] result=stream.readNBytes(4097);
                if(result.length>4096) return "handoff";
                String topic=json.readTree(new String(result,StandardCharsets.UTF_8)).path("data").path("topic").asString("");
                return TOPICS.contains(topic)?topic:"handoff";
            }
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ApiException(503,"PORTAL_ASSISTANT_INTERRUPTED","客服请求已中断，请重试");
        } catch(IOException | tools.jackson.core.JacksonException invalidResponse) {
            return "handoff";
        }
    }

    private String signature(String timestamp,String nonce,byte[] body) {
        try {
            var mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            mac.update((SIGNING_CONTEXT+"\n"+timestamp+"\n"+nonce+"\n").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch(GeneralSecurityException unsupported) {
            throw new IllegalStateException("运行环境缺少门户签名算法",unsupported);
        }
    }
}
