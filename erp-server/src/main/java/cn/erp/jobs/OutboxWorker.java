package cn.erp.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="erp.worker.enabled",havingValue="true",matchIfMissing=true)
public class OutboxWorker {
    private static final Logger LOG=LoggerFactory.getLogger(OutboxWorker.class);
    private final OutboxService events;
    private final OutboxDelivery delivery;

    public OutboxWorker(OutboxService events,OutboxDelivery delivery) {this.events=events;this.delivery=delivery;}

    @org.springframework.beans.factory.annotation.Autowired
    void managed(cn.erp.shared.ManagedJobs jobs) {jobs.register("outbox.poll",this::pollTenant);jobs.register("commands.cleanup",tenant->events.cleanExpiredCommands(tenant));}
    public void poll() {
        try {pollTenant(null);} catch(RuntimeException error) {LOG.warn("后台任务暂不可用，将在下轮重试，原因 {}",error.getClass().getSimpleName());}
    }
    private void pollTenant(Long tenant) {
            for(var event:events.claim(20,tenant)) {
                try {delivery.deliver(event);}
                catch(RuntimeException error) {
                    LOG.warn("事件 {} 消费失败，类型 {}，第 {} 次",event.id(),event.type(),event.attempts());
                    delivery.failed(event);
                }
            }
    }

    public void cleanCommands() {
        try {events.cleanExpiredCommands();}
        catch(RuntimeException error) {LOG.warn("过期命令清理失败，将在下轮重试");}
    }
}
