package cn.erp.notify;

import org.springframework.stereotype.Component;

import cn.erp.jobs.*;
import cn.erp.shared.WorkflowNotice;

@Component
class WorkflowMessageDelivery implements EventHandler {
    private final MessageService messages;
    private final tools.jackson.databind.ObjectMapper json;

    WorkflowMessageDelivery(MessageService messages, tools.jackson.databind.ObjectMapper json) {
        this.messages = messages;
        this.json = json;
    }

    @Override
    public String eventType() {
        return "notify.workflow";
    }

    @Override
    public String consumer() {
        return "notify.workflow";
    }

    @Override
    public void handle(OutboxService.Event event) {
        var value =
                json.readValue(
                        event.payload(),
                        new tools.jackson.core.type.TypeReference<
                                java.util.Map<String, Object>>() {});
        long task = ((Number) value.get("taskId")).longValue();
        messages.materialize(
                new WorkflowNotice(
                        event.tenantId(),
                        ((Number) value.get("companyId")).longValue(),
                        ((Number) value.get("userId")).longValue(),
                        ((Number) value.get("instanceId")).longValue(),
                        task == 0 ? null : task,
                        value.get("kind").toString(),
                        value.get("eventKey").toString()));
    }
}
