package cn.erp.shared;

/** 统一冲销取得原收货屏障并恢复退货计价基准，运营账务仍由原冲销服务追加。 */
public interface PriceAdjustmentReversalPort {
    void prepare(ActorScope actor, long adjustmentId);
    void dependencies(ActorScope actor, long adjustmentId);
    void restore(ActorScope actor, long adjustmentId);
}
