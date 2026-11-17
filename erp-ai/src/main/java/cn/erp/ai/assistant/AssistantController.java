package cn.erp.ai.assistant;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import cn.erp.ai.common.AiException;
import cn.erp.ai.common.AiRequest;
import cn.erp.ai.common.ApiResponse;

@RestController
@RequestMapping("/ai/v1")
class AssistantController {
    private static final System.Logger LOG=System.getLogger(AssistantController.class.getName());
    private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore concurrentAnswers=new Semaphore(12);
    private final AssistantService service;
    private final AssistantCatalog catalog;
    private final AiRequest requests;

    AssistantController(AssistantService service,AssistantCatalog catalog,AiRequest requests) {
        this.service=service;
        this.catalog=catalog;
        this.requests=requests;
    }

    record Create(String assistantCode) {}

    @GetMapping("/assistants")
    ApiResponse<?> assistants(HttpServletRequest request) {
        return ApiResponse.ok(catalog.visible(requests.actor(request)));
    }

    @GetMapping("/conversations")
    ApiResponse<?> list(HttpServletRequest request) {
        return ApiResponse.ok(service.conversations(requests.actor(request)));
    }

    @PostMapping("/conversations")
    ApiResponse<?> create(HttpServletRequest request,@RequestBody Create input) {
        return ApiResponse.ok(service.create(requests.actor(request),input.assistantCode()));
    }

    @GetMapping("/conversations/{id}/messages")
    ApiResponse<?> history(HttpServletRequest request,@PathVariable String id) {
        return ApiResponse.ok(service.history(requests.access(request),id));
    }

    @DeleteMapping("/conversations/{id}")
    ApiResponse<?> delete(HttpServletRequest request,@PathVariable String id) {
        service.delete(requests.actor(request),id);
        return ApiResponse.ok(Map.of("id",id));
    }

    @PostMapping("/messages/{id}/feedback")
    ApiResponse<?> feedback(HttpServletRequest request,@PathVariable String id,@RequestBody AssistantService.Feedback input) {
        service.feedback(requests.actor(request),id,input);
        return ApiResponse.ok(Map.of("id",id));
    }

    @PostMapping(path="/conversations/{id}/messages", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter send(HttpServletRequest request,@PathVariable String id,@RequestBody AssistantService.MessageInput input) {
        var access=requests.access(request);
        if (!concurrentAnswers.tryAcquire())
            throw new AiException(429,"AI_BUSY","AI 助手繁忙，请稍后再试");
        var emitter=new SseEmitter(120_000L);
        var completed=new AtomicBoolean(false);
        emitter.onCompletion(() -> completed.set(true));
        emitter.onTimeout(() -> completed.set(true));
        emitter.onError(error -> completed.set(true));
        executor.submit(() -> {
            try {
                var result=service.respond(access,id,input);
                if (completed.get()) return;
                var answer=result.answer();
                for (var citation : answer.citations()) event(emitter,"citation",citation);
                for (var tool : answer.tools()) event(emitter,"tool_result",tool);
                emitText(emitter,answer.text());
                event(emitter,"done",Map.of("messageId",result.id(),"refused",answer.refused()));
                emitter.complete();
            } catch (IOException disconnected) {
                emitter.completeWithError(disconnected);
            } catch (AiException error) {
                error(emitter,error.code(),error.getMessage());
            } catch (RuntimeException error) {
                LOG.log(System.Logger.Level.ERROR,"AI 回答失败，异常类型：{0}",error.getClass().getSimpleName());
                error(emitter,"AI_INTERNAL","AI 服务暂不可用，请稍后重试");
            } finally {
                concurrentAnswers.release();
            }
        });
        return emitter;
    }

    private void emitText(SseEmitter emitter,String text) throws IOException {
        int[] points=text.codePoints().toArray();
        for(int offset=0;offset<points.length;offset+=96) {
            int length=Math.min(96,points.length-offset);
            event(emitter,"token",Map.of("text",new String(points,offset,length)));
        }
    }

    private void event(SseEmitter emitter,String name,Object data) throws IOException {
        emitter.send(SseEmitter.event().name(name).data(data,MediaType.APPLICATION_JSON));
    }

    private void error(SseEmitter emitter,String code,String message) {
        try {
            event(emitter,"error",Map.of("code",code,"message",message));
            emitter.complete();
        } catch (IOException | IllegalStateException disconnected) {
            emitter.completeWithError(disconnected);
        }
    }

    @PreDestroy
    void close() { executor.shutdownNow(); }
}
