package cn.erp.ai.assistant;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import cn.erp.ai.common.AiException;
import cn.erp.ai.common.AiRequest;
import cn.erp.ai.common.BusinessToolSpec;

@Service
class BusinessToolExecution {
    private static final int MAX_ROWS=100;
    private static final int MAX_CANDIDATES=20;
    private static final int ROUND_TIMEOUT_SECONDS=12;
    private final AiRequest requests;
    private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();

    BusinessToolExecution(AiRequest requests) {this.requests=requests;}

    record Checked(String scopeFingerprint,AssistantRuntime.ToolResult result) {}

    List<Checked> run(AiRequest.Access access,List<BusinessToolPlanner.Call> calls) {
        return parallel(calls.stream().<Callable<Checked>>map(call->()->{
            var tool=BusinessToolSpec.require(call.code());
            var data=requests.businessTool(access,tool.code(),call.arguments());
            return new Checked(AiRequest.scopeFingerprint(data),read(tool,data));
        }).toList());
    }

    void verify(AiRequest.Access access,List<Checked> results) {
        parallel(results.stream().<Callable<Boolean>>map(result->()->{
            if (!result.scopeFingerprint().equals(requests.businessScope(access,result.result().code())))
                throw AiException.forbidden();
            return true;
        }).toList());
    }

    private <T> List<T> parallel(List<Callable<T>> calls) {
        try {
            var values=new ArrayList<T>();
            for (var future:executor.invokeAll(calls,ROUND_TIMEOUT_SECONDS,TimeUnit.SECONDS)) {
                values.add(future.get());
            }
            return List.copyOf(values);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AiException(503,"AI_INTERRUPTED","查询已中断，请重新查询");
        } catch (CancellationException timeout) {
            throw new AiException(504,"AI_TOOL_TIMEOUT","业务查询超时，请缩小范围后重试");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof AiException expected) throw expected;
            throw invalidResponse();
        }
    }

    private AssistantRuntime.ToolResult read(BusinessToolSpec tool,JsonNode data) {
        String status=data.path("status").asString("");
        if (!Set.of("ok","ambiguous").contains(status)) throw invalidResponse();
        String asOf=data.path("asOf").asString("");
        try { Instant.parse(asOf); }
        catch (DateTimeParseException invalid) { throw invalidResponse(); }
        var rows=objects(data.path("items"),MAX_ROWS);
        var candidates=data.path("candidates");
        var summary=data.path("summary");
        if ("ambiguous".equals(status) && (!rows.isEmpty() || !candidates.isArray() || candidates.isEmpty()))
            throw invalidResponse();
        if (!summary.isMissingNode() && !summary.isNull() && !summary.isObject()) throw invalidResponse();
        return new AssistantRuntime.ToolResult(tool.code(),tool.label(),rows,data.path("hasMore").asBoolean(),status,asOf,
                summary.isObject()?summary:null,reportLink(data.path("detailUrl")),
                candidates.isArray()?objects(candidates,MAX_CANDIDATES):null);
    }

    private List<JsonNode> objects(JsonNode input,int limit) {
        if (!input.isArray() || input.size()>limit) throw invalidResponse();
        var values=new ArrayList<JsonNode>();
        for (var row:input) {
            if (!row.isObject()) throw invalidResponse();
            values.add(row);
        }
        return List.copyOf(values);
    }

    private String reportLink(JsonNode link) {
        if (link.isMissingNode() || link.isNull()) return null;
        if (!link.isString()) throw invalidResponse();
        String value=link.stringValue();
        if (value.isBlank()) return null;
        if (!value.startsWith("/") || value.startsWith("//") || value.contains("\\") || value.length()>2000)
            throw invalidResponse();
        try {
            URI uri=URI.create(value);
            if (uri.isAbsolute() || uri.getRawAuthority()!=null || !uri.normalize().equals(uri)) throw invalidResponse();
        } catch (IllegalArgumentException invalid) { throw invalidResponse(); }
        return value;
    }

    private AiException invalidResponse() {
        return new AiException(502,"AI_TOOL_RESPONSE","业务查询结果格式无效");
    }

    @PreDestroy
    void close() {executor.shutdownNow();}
}
