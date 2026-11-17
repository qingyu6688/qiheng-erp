package cn.erp.shared;

import java.time.LocalDate;

/** 发货单与拣货、复核状态在同一事务内协调。 */
public interface ShipmentWorkPort {
    void submitted(WarehouseScope actor,long shipmentId);
    void requirePostReady(WarehouseScope actor,long shipmentId,long warehouseId);
    void posted(WarehouseScope actor,long shipmentId);
    void cancel(WarehouseScope actor,long shipmentId,LocalDate businessDate);
}
