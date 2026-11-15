package cn.erp.jobs;

/** 消费者的数据库副作用与 Inbox 去重标记在同一事务提交。 */
public interface EventHandler {
    String eventType();
    String consumer();
    void handle(OutboxService.Event event);
    default void dead(OutboxService.Event event) {}
}
