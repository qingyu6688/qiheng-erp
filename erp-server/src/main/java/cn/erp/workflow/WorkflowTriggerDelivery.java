package cn.erp.workflow;

import java.util.Map;
import org.springframework.stereotype.Component;
import cn.erp.jobs.EventHandler;
import cn.erp.jobs.OutboxService;
import static cn.erp.workflow.WorkflowDocuments.number;

@Component
class WorkflowTriggerDelivery implements EventHandler {
    private final WorkflowState state;

    WorkflowTriggerDelivery(WorkflowState state) { this.state = state; }

    @Override public String eventType() { return "workflow.trigger"; }
    @Override public String consumer() { return "workflow-trigger-record"; }

    @Override public void handle(OutboxService.Event event) {
        var payload = state.json.readTree(event.payload());
        var instance = state.raw(event.tenantId(),Long.parseLong(payload.path("instanceId").asString()),false);
        if (!Long.toString(number(instance.get("company_id"))).equals(payload.path("companyId").asString()))
            throw new IllegalStateException("流程事件公司范围不一致");
        state.action(instance,null,0,"trigger_delivered",null,Map.of("eventId",Long.toString(event.id()),"nodeKey",payload.path("nodeKey").asString()));
        state.signal(instance);
    }
}
