package cn.erp.shared;

/** 缓存结果与来源入口重新检查当前报价范围，不通过缓存恢复已经失效的授权。 */
public interface QuotationScopePort {
    void requireVisible(ActorScope actor,long quotationId);
    String condition(ActorScope actor,String alias);
}
