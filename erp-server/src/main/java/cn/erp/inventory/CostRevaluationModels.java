package cn.erp.inventory;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

final class CostRevaluationModels {
    private CostRevaluationModels() {}
    record Input(@Positive(message="请选择仓库") long warehouseId, @Positive(message="请选择商品") long skuId,
                 @NotNull(message="请选择起算日") LocalDate startDate,
                 @NotNull(message="请选择调整入账日") LocalDate businessDate,
                 @NotBlank(message="请填写重估原因") @Size(max=500,message="重估原因最多500字") String reason) {}
    record Command(@NotNull(message="请提供版本号") Long version,
                   @Size(max=500,message="操作意见最多500字") String reason,
                   @Size(max=100,message="自选节点最多100个") Map<String,List<Long>> workflowAssignees) {}
}
