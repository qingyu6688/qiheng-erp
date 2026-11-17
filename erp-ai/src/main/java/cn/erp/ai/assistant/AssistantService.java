package cn.erp.ai.assistant;

import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import cn.erp.ai.common.AiActor;
import cn.erp.ai.common.AiException;
import cn.erp.ai.common.AiInputs;
import cn.erp.ai.common.AiRequest;
import cn.erp.ai.common.BusinessToolSpec;

@Service
class AssistantService {
    private static final String HIDDEN = "来源权限或版本已变化，此回答暂不可查看。";
    private static final String AUTO = "auto";
    private final AiRequest requests;
    private final AssistantCatalog catalog;
    private final AssistantRuntime runtime;
    private final ConversationStore store;
    private final BusinessToolPlanner planner;
    private final BusinessToolExecution business;
    private final ObjectMapper json;

    AssistantService(AiRequest requests,AssistantCatalog catalog,AssistantRuntime runtime,ConversationStore store,
                     BusinessToolPlanner planner,BusinessToolExecution business,ObjectMapper json) {
        this.requests=requests;
        this.catalog=catalog;
        this.runtime=runtime;
        this.store=store;
        this.planner=planner;
        this.business=business;
        this.json=json;
    }

    record MessageInput(String content,String toolCode,JsonNode toolArguments) {}
    record Feedback(String rating,String reason,String correction) {}
    record Message(String id,String role,String content,List<AssistantRuntime.Citation> citations,
                   List<AssistantRuntime.ToolResult> tools,String createdAt) {}
    record Completed(String id, AssistantRuntime.Answer answer) {}

    List<ConversationStore.Conversation> conversations(AiActor actor) {
        actor.require("ai:use");
        var codes=catalog.visible(actor).stream().map(AssistantCatalog.View::code).collect(java.util.stream.Collectors.toSet());
        return store.list(actor).stream().filter(c -> codes.contains(c.assistantCode())).toList();
    }

    ConversationStore.Conversation create(AiActor actor,String code) {
        var config=catalog.require(actor,AiInputs.text(code,40,"助手编码"));
        return store.create(actor,config.code());
    }

    List<Message> history(AiRequest.Access access,String id) {
        AiActor actor=access.actor();
        var conversation=store.own(actor,id);
        var config=catalog.require(actor,conversation.assistantCode());
        var messages=store.messages(actor,id,100).reversed();
        AssistantRuntime.ToolResult liveWarehouses=null;
        if (messages.stream().anyMatch(m -> m.tools().stream().anyMatch(tool->AssistantCatalog.WAREHOUSES.equals(tool.code())))
                && catalog.toolAllowed(actor,config,AssistantCatalog.WAREHOUSES)) {
            try {
                liveWarehouses=loadWarehouses(access);
            } catch (AiException denied) {
                if (denied.status()!=403 && denied.status()!=404) throw denied;
            }
        }
        var result=new ArrayList<Message>();
        for (var m : messages) {
            if (!runtime.receiptsValid(actor,config,m.receipts()) || !toolsVisible(m.tools(),liveWarehouses)) {
                result.add(new Message(m.id(),m.role(),HIDDEN,List.of(),List.of(),m.createdAt()));
            } else {
                result.add(new Message(m.id(),m.role(),m.content(),m.citations(),m.tools(),m.createdAt()));
            }
        }
        return result;
    }

    void delete(AiActor actor,String id) {
        actor.require("ai:use");
        store.delete(actor,id);
    }

    void feedback(AiActor actor,String id,Feedback feedback) {
        actor.require("ai:use");
        store.feedback(actor,id,feedback.rating(),optional(feedback.reason(),500),optional(feedback.correction(),2000));
    }

    Completed respond(AiRequest.Access access,String id,MessageInput input) {
        String question=AiInputs.text(input.content(),2000,"问题");
        AiActor actor=requests.refresh(access);
        var currentAccess=new AiRequest.Access(actor,access.credentials());
        var conversation=store.own(actor,id);
        var config=catalog.require(actor,conversation.assistantCode());
        String code=input.toolCode()==null||input.toolCode().isBlank()?null:AiInputs.text(input.toolCode(),40,"工具编码");
        boolean dataQuery=code!=null&&!AssistantCatalog.WAREHOUSES.equals(code);
        if ((!dataQuery||AUTO.equals(code)) && input.toolArguments()!=null && !input.toolArguments().isNull())
            throw AiException.invalid("当前模式不接受指定工具参数");
        String token=store.acquire(actor,id);
        try {
            if (dataQuery) return query(currentAccess,config,id,token,question,code,input.toolArguments());
            AssistantRuntime.Answer answer;
            if (code==null) {
                var recent=store.messages(actor,id,20).stream().filter(m -> m.role().equals("user"))
                        .limit(3).map(ConversationStore.Stored::content).toList().reversed();
                answer=runtime.answer(actor,config,question,recent,() -> requests.refresh(access));
            } else {
                if (!catalog.toolAllowed(actor,config,code)) throw AiException.forbidden();
                answer=AssistantRuntime.Answer.tool(loadWarehouses(access));
            }
            AiActor current=requests.refresh(access);
            var currentConfig=catalog.require(current,conversation.assistantCode());
            if (!runtime.receiptsValid(current,currentConfig,answer.receipts())) throw AiException.forbidden();
            if (!answer.tools().isEmpty() && (!catalog.toolAllowed(current,currentConfig,AssistantCatalog.WAREHOUSES)
                    || !toolsVisible(answer.tools(),loadWarehouses(access)))) throw AiException.forbidden();
            String message=store.complete(current,id,token,question,answer);
            if (answer.refused()) store.uncovered(current,question);
            return new Completed(message,answer);
        } finally {
            store.release(actor,id,token);
        }
    }

    private Completed query(AiRequest.Access access,AssistantCatalog.Config config,String id,String token,
                            String question,String code,JsonNode arguments) {
        var actor=access.actor();
        BusinessToolPlanner.Plan plan;
        if (AUTO.equals(code)) {
            plan=planner.plan(actor,config,question,()->reauthorize(access,config));
        } else {
            if (!catalog.toolAllowed(actor,config,code)) throw AiException.forbidden();
            var tool=BusinessToolSpec.require(code);
            tool.validate(arguments);
            tool.authorize(actor,arguments);
            plan=new BusinessToolPlanner.Plan(List.of(new BusinessToolPlanner.Call(code,arguments)),null,0);
        }
        var results=business.run(access,plan.calls());
        business.verify(access,results);
        var current=reauthorize(access,config);
        for (var call:plan.calls()) BusinessToolSpec.require(call.code()).authorize(current,call.arguments());
        var answer=AssistantRuntime.Answer.business(results.stream().map(BusinessToolExecution.Checked::result).toList(),
                plan.modelId(),plan.tokens());
        String message=store.complete(current,id,token,question,answer.businessHistory());
        return new Completed(message,answer);
    }

    private AiActor reauthorize(AiRequest.Access access,AssistantCatalog.Config config) {
        var current=requests.refresh(access);
        if (!config.equals(catalog.require(current,config.code())) || !current.equals(access.actor()))
            throw AiException.forbidden();
        return current;
    }

    private boolean toolsVisible(List<AssistantRuntime.ToolResult> saved, AssistantRuntime.ToolResult live) {
        return saved.isEmpty() || live != null
                && saved.stream().allMatch(t -> live.code().equals(t.code()) && live.rows().containsAll(t.rows()));
    }

    private AssistantRuntime.ToolResult loadWarehouses(AiRequest.Access access) {
        var data=requests.warehouses(access);
        JsonNode items=data.path("items");
        if (!items.isArray() || items.size()>20) throw new AiException(502,"AI_TOOL_RESPONSE","仓库查询结果格式无效");
        var rows=new ArrayList<JsonNode>();
        for (var row:items) {
            String id=row.path("id").asString();
            AiInputs.id(id);
            rows.add(json.valueToTree(Map.of("id",id,"code",row.path("code").asString(),"name",row.path("name").asString())));
        }
        return AssistantRuntime.ToolResult.warehouses(rows,data.path("hasMore").asBoolean());
    }

    private String optional(String value,int size) {
        return value == null || value.isBlank() ? null : AiInputs.text(value,size,"反馈");
    }
}
