package cn.erp.realtime;

import org.slf4j.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 实时提醒可以稍后恢复，Redis 订阅失败不能阻止原有业务组件启动。 */
@Component
class RealtimeSubscriber {
    private static final Logger LOG=LoggerFactory.getLogger(RealtimeSubscriber.class);
    private final RedisMessageListenerContainer listener;
    RealtimeSubscriber(@org.springframework.beans.factory.annotation.Qualifier("platformEvents") RedisMessageListenerContainer listener) {this.listener=listener;}
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelay=5000,initialDelay=5000)
    public synchronized void start() {if(!listener.isRunning()) try {listener.start();} catch(RuntimeException failure) {LOG.warn("实时订阅暂不可用，稍后重试，异常类型 {}",failure.getClass().getSimpleName());}}
}
