package cn.erp.shared;

import java.util.Map;

/** 工作台只读取消息中心收件箱，消息存储实现不进入业务查询模块。 */
public interface TodoInbox {Map<String,Object> summary(ActorScope actor);}
