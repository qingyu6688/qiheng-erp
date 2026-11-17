package cn.erp.ai.assistant;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import cn.erp.ai.common.AiException;
import cn.erp.ai.common.AiRequest;
import cn.erp.ai.common.DocumentExplainSpec;
import cn.erp.ai.gateway.ModelGateway;

@Service
public class DocumentExplanationService {
    private static final String SCENE="kb_chat";
    private static final int MAX_REASONS=100;
    private static final int MAX_LINKS=50;
    private static final Pattern CODE=Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,79}");
    private static final String SYSTEM="""
            你是柒衡 ERP 单据解释助手。为员工选择应当优先处理的一项业务阻挡原因。
            action 是待办动作，reasonCodes 是 ERP 已确认且允许使用的原因码。
            只返回 JSON 对象 {"firstReason":"原因码"}，值必须来自 reasonCodes。
            优先选择能直接解释动作为什么不能完成的原因。只排序，不推测、不执行任何操作。
            """;
    private final AiRequest requests;
    private final AssistantCatalog catalog;
    private final ModelGateway model;
    private final ObjectMapper json;

    DocumentExplanationService(AiRequest requests,AssistantCatalog catalog,ModelGateway model,ObjectMapper json) {
        this.requests=requests;
        this.catalog=catalog;
        this.model=model;
        this.json=json;
    }

    public record Explanation(String text,AssistantRuntime.ToolResult tool,String modelId,long tokens,String firstReasonCode) {}
    private record Priority(String reasonCode,String modelId,long tokens) {}

    public Explanation explain(AiRequest.Access access,AssistantCatalog.Config config,JsonNode arguments) {
        var subject=DocumentExplainSpec.read(arguments);
        authorize(access,config,subject);
        var facts=requests.explainDocument(access,arguments);
        String fingerprint=AiRequest.scopeFingerprint(facts);
        var result=read(subject,facts);
        var action="accounting_period".equals(subject.documentType())?"关账":"发货";
        Runnable verify=()->verify(access,config,subject,arguments,fingerprint);
        var priority=result.rows().isEmpty()?null:prioritize(access,action,result.rows(),verify);
        verify.run();
        if (priority==null)
            return new Explanation("当前"+action+"检查已通过。执行时仍会按最新业务状态校验。",result,null,0,null);
        var reason=result.rows().stream().filter(row->priority.reasonCode().equals(row.path("code").asString())).findFirst()
                .orElseThrow(DocumentExplanationService::invalidResponse);
        return new Explanation("当前不能"+action+"。建议先处理这项原因："+reason.path("message").asString(),
                result,priority.modelId(),priority.tokens(),priority.reasonCode());
    }

    private Priority prioritize(AiRequest.Access access,String action,List<JsonNode> reasons,Runnable verify) {
        var reasonCodes=reasons.stream().map(row->row.path("code").asString()).distinct().toList();
        var fallback=new Priority(reasonCodes.getFirst(),null,0);
        try {
            var response=model.prioritizeExplanation(access.actor(),SCENE,SYSTEM,
                    json.writeValueAsString(Map.of("action",action,"reasonCodes",reasonCodes)),()->{
                        try { verify.run(); }
                        catch (AiException denied) { throw AiException.forbidden(); }
                    });
            String first=firstReason(response.text(),reasonCodes);
            return first==null?fallback:new Priority(first,response.modelId(),response.inputTokens()+response.outputTokens());
        } catch (AiException unavailable) {
            if ("AI_FORBIDDEN".equals(unavailable.code()) || "AI_INTERRUPTED".equals(unavailable.code())) throw unavailable;
            return fallback;
        }
    }

    private String firstReason(String output,List<String> reasonCodes) {
        try {
            var data=json.readerFor(JsonNode.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .<JsonNode>readValue(output);
            if (data==null || !data.isObject() || data.size()!=1 || !data.path("firstReason").isString()) return null;
            String first=data.path("firstReason").stringValue();
            return reasonCodes.contains(first)?first:null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private void authorize(AiRequest.Access access,AssistantCatalog.Config config,DocumentExplainSpec subject) {
        var current=requests.refresh(access);
        if (!current.equals(access.actor()) || !config.equals(catalog.require(current,config.code()))
                || !catalog.toolAllowed(current,config,DocumentExplainSpec.CODE)) throw AiException.forbidden();
        subject.authorize(current);
    }

    private void verify(AiRequest.Access access,AssistantCatalog.Config config,DocumentExplainSpec subject,
                        JsonNode arguments,String fingerprint) {
        authorize(access,config,subject);
        if (!fingerprint.equals(requests.documentScope(access,arguments))) throw AiException.forbidden();
    }

    private AssistantRuntime.ToolResult read(DocumentExplainSpec subject,JsonNode data) {
        if (!"ok".equals(data.path("status").asString())) throw invalidResponse();
        String asOf=requiredText(data.path("asOf"),80);
        try { Instant.parse(asOf); }
        catch (DateTimeParseException invalid) { throw invalidResponse(); }
        var summary=readSummary(subject,data.path("summary"));
        var items=data.path("items");
        if (!items.isArray() || items.size()>MAX_REASONS || summary.path("checkPassed").asBoolean()!=items.isEmpty())
            throw invalidResponse();
        var rows=new ArrayList<JsonNode>();
        for (var item:items) rows.add(readReason(item));
        return new AssistantRuntime.ToolResult(DocumentExplainSpec.CODE,DocumentExplainSpec.LABEL,List.copyOf(rows),
                data.path("hasMore").asBoolean(),"ok",asOf,summary,BusinessToolExecution.reportLink(data.path("detailUrl")),null);
    }

    private ObjectNode readSummary(DocumentExplainSpec subject,JsonNode summary) {
        var passed=summary.path("checkPassed");
        var actions=summary.path("allowedActions");
        if (!summary.isObject() || !subject.documentType().equals(summary.path("documentType").asString())
                || !passed.isBoolean() || !actions.isArray() || actions.size()>MAX_REASONS) throw invalidResponse();
        var allowed=json.createArrayNode();
        for (var action:actions) allowed.add(requiredText(action,80));
        return json.createObjectNode().put("documentType",subject.documentType())
                .put("documentNo",requiredText(summary.path("documentNo"),150))
                .put("documentStatus",requiredText(summary.path("documentStatus"),80))
                .put("checkPassed",passed.asBoolean()).set("allowedActions",allowed);
    }

    private ObjectNode readReason(JsonNode row) {
        String code=requiredText(row.path("code"),80);
        if (!CODE.matcher(code).matches()) throw invalidResponse();
        var item=json.createObjectNode().put("code",code).put("message",requiredText(row.path("message"),500));
        if (row.hasNonNull("label")) item.put("label",requiredText(row.path("label"),120));
        String direct=BusinessToolExecution.reportLink(row.path("detailUrl"));
        if (direct!=null) item.put("detailUrl",direct);
        var links=row.path("links");
        if (!links.isMissingNode() && !links.isNull()) {
            if (!links.isArray() || links.size()>MAX_LINKS) throw invalidResponse();
            var related=item.putArray("links");
            for (var link:links) {
                String href=BusinessToolExecution.reportLink(link.path("detailUrl"));
                if (href==null) throw invalidResponse();
                related.addObject().put("label",requiredText(link.path("label"),150)).put("detailUrl",href);
            }
        }
        return item;
    }

    private static String requiredText(JsonNode value,int max) {
        String text=value.isString()?value.stringValue():null;
        if (text==null || text.isBlank() || text.length()>max || text.codePoints().anyMatch(Character::isISOControl))
            throw invalidResponse();
        return text;
    }

    private static AiException invalidResponse() {
        return new AiException(502,"AI_EXPLANATION_RESPONSE","业务校验结果格式无效，请重新打开单据后重试");
    }
}
