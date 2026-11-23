package cn.erp.shared;

/** 销售调价冲销恢复货款基准，不涉及库存成本。 */
public interface SalesPriceAdjustmentReversalPort {
    void prepare(ActorScope actor,long id);
    void dependencies(ActorScope actor,long id);
    void restore(ActorScope actor,long id);
}
