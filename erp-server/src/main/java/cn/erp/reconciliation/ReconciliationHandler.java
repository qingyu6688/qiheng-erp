package cn.erp.reconciliation;

import cn.erp.jobs.*;
import org.springframework.stereotype.Component;

@Component
class ReconciliationHandler implements EventHandler {
    private final ReconciliationService service;
    ReconciliationHandler(ReconciliationService service) {this.service=service;}
    public String eventType() {return "reconciliation.requested";}
    public String consumer() {return "reconciliation";}
    public void handle(OutboxService.Event event) {service.perform(event.tenantId(),event.aggregateId(),true);}
    public void dead(OutboxService.Event event) {service.markError(event.tenantId(),event.aggregateId(),"任务重试达到上限，请按事件编号排查");}
}
