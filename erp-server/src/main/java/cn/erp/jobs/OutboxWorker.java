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

    @Scheduled(fixedDelayString="${erp.worker.poll-millis:1000}")
    public void poll() {
        try {
            for(var event:events.claim(20)) {
                try {delivery.deliver(event);}
                catch(RuntimeException error) {
                    LOG.warn("事件 {} 消费失败，类型 {}，第 {} 次",event.id(),event.type(),event.attempts());
                    delivery.failed(event);
                }
            }
        } catch(RuntimeException error) {LOG.warn("后台任务暂不可用，将在下轮重试，原因 {}",error.getClass().getSimpleName());}
    }

    @Scheduled(fixedDelay=3_600_000,initialDelay=3_600_000)
    public void cleanCommands() {
        try {events.cleanExpiredCommands();}
        catch(RuntimeException error) {LOG.warn("过期命令清理失败，将在下轮重试");}
    }
}
