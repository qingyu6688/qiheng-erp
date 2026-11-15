package cn.erp.jobs;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxDelivery {
    private final JdbcTemplate jdbc;
    private final List<EventHandler> handlers;
    private final OutboxService events;

    public OutboxDelivery(JdbcTemplate jdbc,List<EventHandler> handlers,OutboxService events) {
        this.jdbc=jdbc;this.handlers=List.copyOf(handlers);this.events=events;
        var consumers=new java.util.HashSet<String>();
        for(var handler:handlers) {
            if(!consumers.add(handler.consumer()) || handler.consumer()==null || handler.consumer().isBlank() || handler.consumer().length()>64) {
                throw new IllegalStateException("消费者标识须唯一且长度不超过64字");
            }
        }
    }

    @Transactional
    public boolean deliver(OutboxService.Event event) {
        var states=jdbc.queryForList("SELECT id FROM outbox_event WHERE id=? AND tenant_id=? AND status='processing' AND lease_owner=? AND lease_until>=UTC_TIMESTAMP(3) FOR UPDATE",
                Long.class,event.id(),event.tenantId(),event.owner());
        if(states.isEmpty()) {return false;}
        var consumers=handlers.stream().filter(h->h.eventType().equals(event.type())).toList();
        if(consumers.isEmpty()) {throw new IllegalStateException("未注册此事件的消费者");}
        for(var consumer:consumers) {
            var processed=jdbc.queryForList("SELECT tenant_id FROM inbox_event WHERE consumer=? AND event_id=? FOR UPDATE",
                    Long.class,consumer.consumer(),event.id());
            if(processed.isEmpty()) {
                jdbc.update("INSERT INTO inbox_event(id,tenant_id,consumer,event_id) VALUES(?,?,?,?)",
                        cn.erp.shared.Ids.next(),event.tenantId(),consumer.consumer(),event.id());
                consumer.handle(event);
            } else if(processed.getFirst()!=event.tenantId()) {
                throw new IllegalStateException("事件租户与收件记录不一致");
            }
        }
        jdbc.update("UPDATE outbox_event SET status='done',lease_owner=NULL,lease_until=NULL,last_error=NULL WHERE id=? AND lease_owner=?",event.id(),event.owner());
        return true;
    }

    @Transactional
    public void failed(OutboxService.Event event) {
        if(events.failed(event)) {
            handlers.stream().filter(handler->handler.eventType().equals(event.type())).forEach(handler->handler.dead(event));
        }
    }
}
