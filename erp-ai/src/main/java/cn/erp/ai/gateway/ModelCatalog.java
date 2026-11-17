package cn.erp.ai.gateway;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.erp.ai.common.AiActor;
import cn.erp.ai.common.AiException;
import cn.erp.ai.common.AiInputs;

@Service
public class ModelCatalog {
    private static final Set<String> SCENES = Set.of("kb_chat", "wiki_maintain");
    private static final Set<String> TYPES = Set.of("openai_compatible", "anthropic");
    private static final Set<String> PURPOSES = Set.of("chat", "vision", "embedding", "rerank");
    private static final Set<String> REGIONS = Set.of("domestic", "overseas");
    private final JdbcTemplate jdbc;
    private final ProviderCipher cipher;
    private final ProviderEndpoints endpoints;

    ModelCatalog(JdbcTemplate jdbc, ProviderCipher cipher, ProviderEndpoints endpoints) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.endpoints = endpoints;
    }

    public record ProviderInput(String id, String name, String type, String endpointKey, String dataRegion,
            boolean outboundConfirmed, String apiKey, boolean enabled) {
        @Override public String toString() { return "模型服务配置"; }
    }
    public record ModelInput(String id, String providerId, String name, String modelKey, String purpose,
            int contextWindow, BigDecimal priceIn, BigDecimal priceOut, boolean enabled) {}
    public record RouteInput(String scene, String modelId, String fallbackModelId, int maxTokens,
            BigDecimal temperature) {}
    record Route(long model, Long fallback, int maxTokens, BigDecimal temperature) {}
    record Target(long id, long provider, String modelKey, String type, URI endpoint, byte[] encryptedKey,
                  int contextWindow, BigDecimal priceIn, BigDecimal priceOut) {}

    public List<Map<String,String>> endpoints(AiActor actor) {
        actor.requireAdminView();
        return endpoints.list();
    }

    public List<Map<String,Object>> providers(AiActor actor) {
        actor.requireAdminView();
        return jdbc.query("""
            SELECT id,name,provider_type,endpoint_key,data_region,outbound_confirmed,
              api_key_cipher IS NOT NULL has_key,enabled
            FROM ai_provider WHERE tenant_id=? ORDER BY id LIMIT 200
            """, (r,n) -> Map.of("id",r.getString("id"),"name",r.getString("name"),
                "type",r.getString("provider_type"),"endpointKey",r.getString("endpoint_key"),
                "dataRegion",r.getString("data_region"),"outboundConfirmed",r.getBoolean("outbound_confirmed"),
                "hasKey",r.getBoolean("has_key"),"enabled",r.getBoolean("enabled")), actor.tenantId());
    }

    public List<Map<String,Object>> models(AiActor actor) {
        actor.requireAdminView();
        return jdbc.query("""
            SELECT id,provider_id,name,model_key,purpose,context_window,price_in,price_out,enabled
            FROM ai_model WHERE tenant_id=? ORDER BY id LIMIT 200
            """, (r,n) -> Map.of("id",r.getString("id"),"providerId",r.getString("provider_id"),
                "name",r.getString("name"),"modelKey",r.getString("model_key"),
                "purpose",r.getString("purpose"),"contextWindow",r.getInt("context_window"),
                "priceIn",r.getBigDecimal("price_in").toPlainString(),"priceOut",r.getBigDecimal("price_out").toPlainString(),
                "enabled",r.getBoolean("enabled")), actor.tenantId());
    }

    public List<RouteInput> routes(AiActor actor) {
        actor.requireAdminView();
        return jdbc.query("""
            SELECT scene,model_id,fallback_model_id,max_tokens,temperature
            FROM ai_route WHERE tenant_id=? ORDER BY scene
            """, (r,n) -> new RouteInput(r.getString(1),r.getString(2),r.getString(3),r.getInt(4),r.getBigDecimal(5)),
            actor.tenantId());
    }

    @Transactional
    public Map<String,String> saveProvider(AiActor actor, ProviderInput input) {
        actor.require("ai_admin:manage");
        String name = AiInputs.text(input.name(), 100, "模型服务名称");
        String type = oneOf(input.type(), TYPES, "服务类型");
        String region = oneOf(input.dataRegion(), REGIONS, "数据区域");
        String endpoint = AiInputs.text(input.endpointKey(), 64, "端点编号");
        if (input.enabled()) endpoints.require(endpoint, type, region, input.outboundConfirmed());
        String key = input.apiKey();
        if (key != null && (key.getBytes(StandardCharsets.UTF_8).length > 4096
                || key.chars().anyMatch(ch -> ch < 32 || ch > 126)))
            throw AiException.invalid("模型密钥格式无效");
        long id;
        if (input.id() == null) {
            id = insert("""
                INSERT INTO ai_provider(tenant_id,name,provider_type,endpoint_key,data_region,outbound_confirmed,enabled)
                VALUES (?,?,?,?,?,?,?)
                """, actor.tenantId(), name, type, endpoint, region, input.outboundConfirmed(), input.enabled());
            if (input.enabled() && type.equals("anthropic") && (key == null || key.isBlank()))
                throw AiException.invalid("请为 Claude 配置模型密钥");
        } else {
            id = AiInputs.id(input.id());
            var old = jdbc.query("""
                SELECT provider_type,endpoint_key,api_key_cipher IS NOT NULL has_key
                FROM ai_provider WHERE tenant_id=? AND id=? FOR UPDATE
                """, (row,n) -> Map.of("provider_type",row.getString(1),"endpoint_key",row.getString(2),
                    "has_key",row.getBoolean(3)), actor.tenantId(), id).stream().findFirst().orElseThrow(AiException::missing);
            if (key == null && Boolean.TRUE.equals(old.get("has_key"))
                    && (!type.equals(old.get("provider_type")) || !endpoint.equals(old.get("endpoint_key"))))
                throw AiException.invalid("切换服务地址时请重新设置或清除密钥");
            if (input.enabled() && type.equals("anthropic")
                    && ((key != null && key.isBlank()) || (key == null && !Boolean.TRUE.equals(old.get("has_key")))))
                throw AiException.invalid("请为 Claude 配置模型密钥");
            jdbc.update("""
                UPDATE ai_provider SET name=?,provider_type=?,endpoint_key=?,data_region=?,outbound_confirmed=?,enabled=?
                WHERE tenant_id=? AND id=?
                """, name,type,endpoint,region,input.outboundConfirmed(),input.enabled(),actor.tenantId(),id);
        }
        if (key != null)
            jdbc.update("UPDATE ai_provider SET api_key_cipher=? WHERE tenant_id=? AND id=?",
                    key.isBlank() ? null : cipher.seal(actor.tenantId(), id, key), actor.tenantId(), id);
        return Map.of("id", Long.toString(id));
    }

    public Map<String,String> saveModel(AiActor actor, ModelInput input) {
        actor.require("ai_admin:manage");
        long provider = AiInputs.id(input.providerId());
        if (jdbc.queryForList("SELECT id FROM ai_provider WHERE tenant_id=? AND id=?",actor.tenantId(),provider).isEmpty())
            throw AiException.missing();
        String name = AiInputs.text(input.name(), 100, "模型名称");
        String modelKey = AiInputs.text(input.modelKey(), 160, "模型编号");
        if (!modelKey.matches("[A-Za-z0-9_./:-]+")) throw AiException.invalid("模型编号格式无效");
        String purpose = oneOf(input.purpose(), PURPOSES, "模型用途");
        if (input.contextWindow() < 1024 || input.contextWindow() > 2_000_000)
            throw AiException.invalid("上下文窗口须介于1024和2000000之间");
        BigDecimal priceIn = amount(input.priceIn(), "输入单价", 10);
        BigDecimal priceOut = amount(input.priceOut(), "输出单价", 10);
        long id;
        if (input.id() == null) {
            id = insert("""
                INSERT INTO ai_model(tenant_id,provider_id,name,model_key,purpose,context_window,price_in,price_out,enabled)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,actor.tenantId(),provider,name,modelKey,purpose,input.contextWindow(),priceIn,priceOut,input.enabled());
        } else {
            id = AiInputs.id(input.id());
            int changed = jdbc.update("""
                UPDATE ai_model SET provider_id=?,name=?,model_key=?,purpose=?,context_window=?,price_in=?,price_out=?,enabled=?
                WHERE tenant_id=? AND id=?
                """,provider,name,modelKey,purpose,input.contextWindow(),priceIn,priceOut,input.enabled(),actor.tenantId(),id);
            if (changed == 0 && jdbc.queryForList("SELECT id FROM ai_model WHERE tenant_id=? AND id=?",actor.tenantId(),id).isEmpty())
                throw AiException.missing();
        }
        return Map.of("id", Long.toString(id));
    }

    public RouteInput saveRoute(AiActor actor, RouteInput input) {
        actor.require("ai_admin:manage");
        String scene = oneOf(input.scene(), SCENES, "路由场景");
        long model = AiInputs.id(input.modelId());
        Long fallback = input.fallbackModelId() == null || input.fallbackModelId().isBlank() ? null : AiInputs.id(input.fallbackModelId());
        if (fallback != null && fallback == model) throw AiException.invalid("备用模型须与主模型不同");
        if (input.maxTokens() < 64 || input.maxTokens() > 4096)
            throw AiException.invalid("输出上限须介于64和4096之间");
        BigDecimal temperature = input.temperature();
        if (temperature != null && (temperature.signum() < 0 || temperature.compareTo(BigDecimal.ONE) > 0 || temperature.scale() > 3))
            throw AiException.invalid("温度须介于0和1之间，最多三位小数");
        target(actor, model);
        if (fallback != null) target(actor, fallback);
        jdbc.update("""
            INSERT INTO ai_route(tenant_id,scene,model_id,fallback_model_id,max_tokens,temperature)
            VALUES(?,?,?,?,?,?) AS staged
            ON DUPLICATE KEY UPDATE model_id=staged.model_id,fallback_model_id=staged.fallback_model_id,
              max_tokens=staged.max_tokens,temperature=staged.temperature
            """,actor.tenantId(),scene,model,fallback,input.maxTokens(),temperature);
        return new RouteInput(scene,input.modelId(),fallback == null ? null : Long.toString(fallback),input.maxTokens(),temperature);
    }

    Route route(AiActor actor, String scene) {
        oneOf(scene, SCENES, "路由场景");
        return jdbc.query("""
            SELECT model_id,fallback_model_id,max_tokens,temperature FROM ai_route WHERE tenant_id=? AND scene=?
            """, (r,n) -> new Route(r.getLong(1),r.getObject(2,Long.class),r.getInt(3),r.getBigDecimal(4)),
                actor.tenantId(),scene).stream().findFirst().orElseThrow(() ->
                new AiException(503,"AI_ROUTE_MISSING","请先配置当前企业的模型路由"));
    }

    Target target(AiActor actor, long model) {
        return jdbc.query("""
            SELECT m.id,m.provider_id,m.model_key,m.context_window,m.price_in,m.price_out,p.provider_type,
              p.endpoint_key,p.data_region,p.outbound_confirmed,p.api_key_cipher
            FROM ai_model m JOIN ai_provider p ON p.tenant_id=m.tenant_id AND p.id=m.provider_id
            WHERE m.tenant_id=? AND m.id=? AND m.enabled=1 AND p.enabled=1 AND m.purpose='chat'
            """, (r,n) -> new Target(r.getLong("id"),r.getLong("provider_id"),r.getString("model_key"),
                r.getString("provider_type"),endpoints.require(r.getString("endpoint_key"),r.getString("provider_type"),
                    r.getString("data_region"),r.getBoolean("outbound_confirmed")),r.getBytes("api_key_cipher"),
                r.getInt("context_window"),r.getBigDecimal("price_in"),r.getBigDecimal("price_out")),
                actor.tenantId(),model).stream().findFirst().orElseThrow(() ->
                new AiException(503,"AI_MODEL_DISABLED","模型不存在或已停用"));
    }

    String secret(AiActor actor, Target target) { return cipher.open(actor.tenantId(), target.provider(), target.encryptedKey()); }

    public Map<String,Object> evaluationConfiguration(AiActor actor) {
        Route route = route(actor,"kb_chat");
        var settings = new LinkedHashMap<String,Object>();
        settings.put("modelId",Long.toString(route.model()));
        settings.put("fallbackModelId",route.fallback() == null ? null : Long.toString(route.fallback()));
        settings.put("maxTokens",route.maxTokens());
        settings.put("temperature",route.temperature());
        return settings;
    }

    private long insert(String query, Object... values) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(query,Statement.RETURN_GENERATED_KEYS);
            for (int i=0;i<values.length;i++) statement.setObject(i+1,values[i]);
            return statement;
        },key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    private static String oneOf(String value, Set<String> choices, String label) {
        if (value == null || !choices.contains(value)) throw AiException.invalid(label + "不受支持");
        return value;
    }

    static BigDecimal amount(BigDecimal input,String label,int integerDigits) {
        if (input == null || input.signum() < 0 || input.scale() > 6 || input.precision() - input.scale() > integerDigits)
            throw AiException.invalid(label + "格式无效");
        return input;
    }
}
