package cn.erp.notify;

import cn.erp.jobs.*;
import cn.erp.shared.*;
import java.util.*;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class BusinessNotificationEvents implements EventHandler {
    private static final Set<String> TYPES=Set.of("sales_order","purchase_order","purchase_requisition","shipment","receipt","payment","refund_request","sales_return","goods_receipt","purchase_return");
    private final OutboxService outbox;private final MessageService messages;private final org.springframework.context.ApplicationEventPublisher events;
    BusinessNotificationEvents(OutboxService outbox,MessageService messages,org.springframework.context.ApplicationEventPublisher events) {this.outbox=outbox;this.messages=messages;this.events=events;}
    @EventListener public void changed(BusinessChanged change) {
        String type=change.operation().split("\\.",2)[0];if(TYPES.contains(type)) {
            outbox.publish(change.tenantId(),"business_command",change.documentId(),0,"notify.refresh",Map.of("companyId",change.companyId()));
            // 在线页面提交后立即按权限读取收件箱；离线收件人与外部投递仍由Outbox补齐。
            events.publishEvent(new UserSignal(change.tenantId(),0,"business.changed",Map.of("category","todo")));
        }
    }
    @Override public String eventType() {return "notify.refresh";}
    @Override public String consumer() {return "notify.refresh";}
    @Override public void handle(OutboxService.Event event) {messages.refreshTenant(event.tenantId());}
}
