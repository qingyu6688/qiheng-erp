package cn.erp.mfg;

import java.time.LocalDate;
import java.util.List;

final class MfgPlanningModels {
    private MfgPlanningModels() {}

    record Revision(Long version) {}
    record Substitute(Long substituteSkuId,Long unitId,String ratio,Integer priority) {}
    record BomLine(Long componentSkuId,Long unitId,String qty,String scrapRate,Long issueWarehouseId,
                   Boolean phantom,String remark,List<Substitute> substitutes) {}
    record BomDraft(Long skuId,String versionNo,Long unitId,String baseQty,LocalDate validFrom,LocalDate validTo,
                    Boolean isDefault,Long version,String remark,List<BomLine> lines) {}
    record WorkOrderDraft(Long skuId,Long bomId,Long unitId,String planQty,Long issueWarehouseId,
                          Long receiveWarehouseId,LocalDate planStartDate,LocalDate planFinishDate,
                          String overReceivePct,Long version,String remark) {}
    record FromSalesOrder(Long salesOrderId,Long salesOrderLineId,Long bomId,Long issueWarehouseId,
                          Long receiveWarehouseId,LocalDate planStartDate,LocalDate planFinishDate,
                          String overReceivePct,String remark) {}
    record ShortCloseMaterial(String materialId,String consumedQty,String scrappedQty,String scrapReason) {}
    record ShortCloseDraft(Long version,LocalDate businessDate,String reason,String costDisposition,
                           List<ShortCloseMaterial> materials) {}
}
