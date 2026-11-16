package cn.erp.shared;

/** 已从Redis收到的摘要，独立事件类型避免再次发布造成广播循环。 */
public record RemoteUserSignal(UserSignal signal) {}
