package cn.erp.ai.assistant;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import cn.erp.ai.common.AiActor;
import cn.erp.ai.common.AiException;
import cn.erp.ai.common.AiInputs;
import cn.erp.ai.common.BusinessToolSpec;
import cn.erp.ai.common.DocumentExplainSpec;

@Service
public class AssistantCatalog {
    static final String WAREHOUSES = "list_warehouses";
    private static final String INTERNAL = "internal";
    private static final TypeReference<Set<String>> STRINGS = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    AssistantCatalog(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }

    public record Config(String id,String code,String name,String systemPrompt,Set<String> libraryIds,
                         Set<String> toolCodes,Set<String> roleIds,boolean enabled) {}
    public record View(String code,String name,Set<String> toolCodes) {}

    public List<View> visible(AiActor actor) {
        actor.require("ai:use");
        var configs = read(actor);
        if (configs.stream().noneMatch(c -> c.code().equals(INTERNAL))) {
            return java.util.stream.Stream.concat(java.util.stream.Stream.of(defaultConfig()),configs.stream())
                    .filter(c -> allowed(actor,c)).map(c -> view(actor,c)).toList();
        }
        return configs.stream().filter(c -> allowed(actor,c)).map(c -> view(actor,c)).toList();
    }

    public Config require(AiActor actor,String code) {
        actor.require("ai:use");
        var config = read(actor).stream().filter(row -> row.code().equals(code)).findFirst()
                .orElseGet(() -> INTERNAL.equals(code) ? defaultConfig() : null);
        if (config == null || !allowed(actor,config)) throw AiException.missing();
        return config;
    }

    boolean toolAllowed(AiActor actor,Config config,String code) {
        if (!config.toolCodes().contains(code)) return false;
        if (WAREHOUSES.equals(code)) return actor.has("warehouse:view");
        if (DocumentExplainSpec.CODE.equals(code)) return DocumentExplainSpec.allowed(actor);
        var tool=BusinessToolSpec.find(code);
        return tool!=null && tool.allowed(actor);
    }

    List<BusinessToolSpec> businessTools(AiActor actor,Config config) {
        return BusinessToolSpec.all().stream().filter(tool->toolAllowed(actor,config,tool.code())).toList();
    }

    public List<Config> adminList(AiActor actor) {
        actor.requireAdminView();
        return read(actor);
    }

    public Config save(AiActor actor,Config input) {
        actor.require("ai_admin:manage");
        String code=AiInputs.text(input.code(),40,"助手编码");
        if (!code.matches("[a-z][a-z0-9_-]{0,39}")) throw AiException.invalid("助手编码格式无效");
        String name=AiInputs.text(input.name(),100,"助手名称");
        String prompt=input.systemPrompt() == null || input.systemPrompt().isBlank()
                ? "" : AiInputs.text(input.systemPrompt(),4000,"助手补充要求");
        var roles=AiInputs.ids(input.roleIds(),100);
        var libraries=input.libraryIds() == null ? Set.<String>of() : Set.copyOf(input.libraryIds());
        if (libraries.size() > 100 || libraries.stream().anyMatch(id -> !id.matches("[A-Za-z0-9_-]{1,64}")))
            throw AiException.invalid("知识库编号或授权条目无效");
        for (String library : libraries) {
            if (jdbc.queryForList("SELECT id FROM kb_library WHERE tenant_id=? AND id=?",actor.tenantId(),library).isEmpty())
                throw AiException.missing();
        }
        var tools=input.toolCodes() == null ? Set.<String>of() : Set.copyOf(input.toolCodes());
        if (tools.stream().anyMatch(tool->!WAREHOUSES.equals(tool) && !DocumentExplainSpec.CODE.equals(tool)
                && BusinessToolSpec.find(tool)==null))
            throw AiException.invalid("工具不在只读白名单中");
        if (input.id() == null) {
            jdbc.update("""
                INSERT INTO ai_assistant(tenant_id,code,name,system_prompt,library_ids,role_ids,tool_codes,enabled)
                VALUES(?,?,?,?,?,?,?,?) AS staged
                ON DUPLICATE KEY UPDATE name=staged.name,system_prompt=staged.system_prompt,
                library_ids=staged.library_ids,role_ids=staged.role_ids,tool_codes=staged.tool_codes,
                enabled=staged.enabled,version=ai_assistant.version+1
                """, actor.tenantId(),code,name,prompt,json.writeValueAsString(libraries),json.writeValueAsString(roles),
                    json.writeValueAsString(tools),input.enabled());
        } else {
            long id=AiInputs.id(input.id());
            int changed=jdbc.update("""
                UPDATE ai_assistant SET name=?,system_prompt=?,library_ids=?,role_ids=?,tool_codes=?,enabled=?,version=version+1
                WHERE tenant_id=? AND id=? AND code=?
                """,name,prompt,json.writeValueAsString(libraries),json.writeValueAsString(roles),json.writeValueAsString(tools),
                input.enabled(),actor.tenantId(),id,code);
            if (changed==0) throw AiException.missing();
        }
        return read(actor).stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow(AiException::missing);
    }

    public List<Map<String,String>> tools(AiActor actor) {
        actor.requireAdminView();
        var tools=new ArrayList<Map<String,String>>();
        tools.add(Map.of("code",WAREHOUSES,"label","查看可访问仓库","description","读取当前公司的授权仓库编码和名称"));
        tools.add(Map.of("code",DocumentExplainSpec.CODE,"label",DocumentExplainSpec.LABEL,
                "description","解释当前销售订单、发货单和期间的业务校验结果"));
        for (var tool:BusinessToolSpec.all())
            tools.add(Map.of("code",tool.code(),"label",tool.label(),"description",tool.description()));
        return List.copyOf(tools);
    }

    private List<Config> read(AiActor actor) {
        return jdbc.query("""
            SELECT id,code,name,system_prompt,library_ids,tool_codes,role_ids,enabled
            FROM ai_assistant WHERE tenant_id=? ORDER BY id LIMIT 200
            """,(r,n) -> new Config(r.getString(1),r.getString(2),r.getString(3),r.getString(4),
                json.readValue(r.getString(5),STRINGS),json.readValue(r.getString(6),STRINGS),
                json.readValue(r.getString(7),STRINGS),r.getBoolean(8)),actor.tenantId());
    }

    private Config defaultConfig() {
        return new Config(null,INTERNAL,"内部知识助手","",null,Set.of(),Set.of(),true);
    }

    private boolean allowed(AiActor actor,Config config) {
        return config.enabled() && (config.roleIds().isEmpty() || !Collections.disjoint(config.roleIds(),actor.roleIds()));
    }

    private View view(AiActor actor,Config config) {
        var tools=new LinkedHashSet<String>();
        if (toolAllowed(actor,config,WAREHOUSES)) tools.add(WAREHOUSES);
        if (toolAllowed(actor,config,DocumentExplainSpec.CODE)) tools.add(DocumentExplainSpec.CODE);
        businessTools(actor,config).forEach(tool->tools.add(tool.code()));
        return new View(config.code(),config.name(),Collections.unmodifiableSet(tools));
    }
}
