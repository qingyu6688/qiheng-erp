package cn.erp.shared;

/** 收货及冲销在业务事务中维护同一来源的上架作业。 */
public interface PutawayPort {
    Long receivingLocation(WarehouseScope actor,long warehouse);
    void createFromReceipt(WarehouseScope actor,long warehouse,String sourceType,long sourceId);
    void requireReversible(WarehouseScope actor,String sourceType,long sourceId);
    boolean hasConfirmedMovement(WarehouseScope actor,String sourceType,long sourceId,boolean lock);
    void cancelUntouched(WarehouseScope actor,String sourceType,long sourceId);
}
