package cn.erp.shared;

/** 手工费用冲销先取得来源及库存屏障；随单费用由原收货统一冲销。 */
public interface PurchaseFeeReversalPort {
    void prepare(ActorScope actor,long document);
    void dependencies(ActorScope actor,long document);
}
