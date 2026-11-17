package cn.erp.shared;

/** 消息读取和后台投递复用预警中心的实时权限交集。 */
public interface AlertVisibilityPort {
    String eventCondition(ActorScope actor,String eventAlias);
}
