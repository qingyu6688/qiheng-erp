package cn.erp.ai.gateway;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import cn.erp.ai.common.AiActor;
import cn.erp.ai.common.AiException;
import cn.erp.ai.common.BoundedHttp;

@Service
public class ModelGateway {
    private static final Duration BUSINESS_PLAN_TIMEOUT = Duration.ofSeconds(20);
    private final ModelCatalog catalog;
    private final UsageLedger ledger;
    private final BoundedHttp http;
    private final ObjectMapper json;

    ModelGateway(ModelCatalog catalog, UsageLedger ledger, BoundedHttp http, ObjectMapper json) {
        this.catalog=catalog;
        this.ledger=ledger;
        this.http=http;
        this.json=json;
    }

    public ModelResult generate(AiActor actor, String scene, String system, String user) {
        return generate(actor,scene,system,user,() -> {},text -> false);
    }

    public ModelResult generate(AiActor actor,String scene,String system,String user,Runnable authorize,
            Predicate<String> refused) {
        return generate(actor,scene,system,user,authorize,refused,HttpChatModel.DEFAULT_TIMEOUT);
    }

    public ModelResult planBusinessQuery(AiActor actor,String scene,String system,String user,Runnable authorize) {
        return generate(actor,scene,system,user,authorize,text->false,BUSINESS_PLAN_TIMEOUT);
    }

    private ModelResult generate(AiActor actor,String scene,String system,String user,Runnable authorize,
                                 Predicate<String> refused,Duration timeout) {
        actor.require("wiki_maintain".equals(scene) ? "ai_admin:review" : "ai:use");
        var route = catalog.route(actor,scene);
        try {
            return call(actor,scene,system,user,route,route.model(),false,authorize,refused,timeout);
        } catch (AiException primary) {
            if (route.fallback() == null || primary.status() == 403 || primary.status() == 429
                    || "AI_INTERRUPTED".equals(primary.code())) throw primary;
            return call(actor,scene,system,user,route,route.fallback(),true,authorize,refused,timeout);
        }
    }

    private ModelResult call(AiActor actor,String scene,String system,String user,
            ModelCatalog.Route route,long model,boolean fallback,Runnable authorize,Predicate<String> refused,Duration timeout) {
        var target = catalog.target(actor,model);
        int inputLimit = HttpChatModel.estimated(system) + HttpChatModel.estimated(user) + 64;
        if (inputLimit + route.maxTokens() > target.contextWindow())
            throw AiException.invalid("资料超出模型上下文窗口，请缩小问题范围");
        String secret = catalog.secret(actor,target);
        ChatModel chat = new HttpChatModel(target,route,secret,http,json,timeout);
        authorize.run();
        var hold = ledger.reserve(actor,scene,target,inputLimit,route.maxTokens());
        long started = System.nanoTime();
        String text;
        int input, output;
        try {
            var response = chat.call(new Prompt(new SystemMessage(system),new UserMessage(user)));
            text = response.getResult().getOutput().getText();
            var usage = response.getMetadata().getUsage();
            input = usage.getPromptTokens();
            output = usage.getCompletionTokens();
            if (!fallback && route.fallback() != null && refused.test(text.strip())) {
                throw new ModelFailure("AI_MODEL_REFUSED","主模型未找到可引用的答案",input,output);
            }
        } catch (AiException error) {
            long failedInput = error instanceof ModelFailure failure ? failure.input : 0;
            long failedOutput = error instanceof ModelFailure failure ? failure.output : 0;
            ledger.finish(hold,failedInput,failedOutput,elapsed(started),fallback ? "fallback_failed" : "failed",error.code());
            throw error;
        } catch (RuntimeException error) {
            ledger.finish(hold,0,0,elapsed(started),fallback ? "fallback_failed" : "failed","AI_MODEL_RESPONSE");
            throw new AiException(502,"AI_MODEL_RESPONSE","模型返回格式无效");
        }
        ledger.finish(hold,input,output,elapsed(started),fallback ? "fallback" : "succeeded",null);
        return new ModelResult(text,Long.toString(model),input,output);
    }

    private static long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }

    static class ModelFailure extends AiException {
        private final long input;
        private final long output;
        ModelFailure(String code,String message,long input,long output) {
            super(502,code,message);
            this.input=input;
            this.output=output;
        }
    }
}
