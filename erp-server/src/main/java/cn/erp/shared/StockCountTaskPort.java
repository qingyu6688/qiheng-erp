package cn.erp.shared;

/** 盘点单状态变化后同步库位作业，仍沿用原盘点流程。 */
public interface StockCountTaskPort {
    void started(ActorScope actor,long countId);
    void phase(ActorScope actor,long countId,String status);
}
