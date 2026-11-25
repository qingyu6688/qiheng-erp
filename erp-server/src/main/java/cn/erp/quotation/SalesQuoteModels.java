package cn.erp.quotation;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import cn.erp.salesorder.SalesOrderModels;

final class SalesQuoteModels {
    private SalesQuoteModels() {}
    record Input(@NotNull(message="请填写报价内容") @Valid SalesOrderModels.Input order,
                 @NotNull(message="请选择有效期") LocalDate validTo,
                 @Positive(message="商机编号无效") Long opportunityId,Long version) {}
    record Command(@NotNull(message="请提供版本号") Long version,
                   @Size(max=500,message="操作意见最多500字") String reason,
                   LocalDate businessDate,
                   @Size(max=100,message="自选审批节点最多100个") Map<String,List<Long>> workflowAssignees) {}
}
