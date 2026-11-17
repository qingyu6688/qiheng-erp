package cn.erp.openapi;

import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import cn.erp.jobs.EventHandler;
import cn.erp.jobs.OutboxService;

@Component
class OpenWorkflowEvents implements EventHandler {
    private final OpenWebhooks hooks;
    private final ObjectMapper json;

    OpenWorkflowEvents(OpenWebhooks hooks,ObjectMapper json) { this.hooks=hooks;this.json=json; }

    @Override public String eventType() { return "workflow.trigger"; }
    @Override public String consumer() { return "open.workflow.webhook"; }

    @Override public void handle(OutboxService.Event event) {
        var payload=json.readTree(event.payload());
        String kind=payload.path("event").asString();
        String instance=payload.path("instanceId").asString();
        String key=payload.path("nodeKey").asString();
        if (!Set.of("checkpoint","review_required").contains(kind) || !key.matches("[A-Za-z_][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalStateException("未注册的工作流触发事件");
        }
        OpenPolicy.id(instance);
        hooks.fanOut(event.tenantId(),event.id(),OpenPolicy.id(payload.path("companyId").asString()),eventType(),
                Map.of("event",kind,"instanceId",instance,"nodeKey",key));
    }
}
