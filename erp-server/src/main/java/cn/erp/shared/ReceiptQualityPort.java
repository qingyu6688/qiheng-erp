package cn.erp.shared;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 收货领域与质量模块共用的事务内入口。 */
public interface ReceiptQualityPort {
    record Hold(long sourceId,long lineId) {}

    boolean requires(ActorScope actor,long skuId,long supplierId);

    void received(ActorScope actor,long receiptId,long receiptLineId,long skuId,long supplierId,long warehouseId,
                  String lotKey,String locationKey,BigDecimal baseQty,LocalDate date);

    Hold beforePurchaseReturn(ActorScope actor,long returnId,long returnLineId,long receiptLineId,BigDecimal qty);

    Hold beforeScrap(ActorScope actor,long adjustmentId,long adjustmentLineId,BigDecimal qty);

    void beforeReceiptReversal(ActorScope actor,long receiptId);
}
