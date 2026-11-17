package cn.erp.shared;

import java.util.Set;

/** 预警只向消息中心发送事件编号及无业务明细的提示。 */
public interface AlertNotificationPort {
    void create(ActorScope actor,long event,int occurrence,Set<String> channels);
}
