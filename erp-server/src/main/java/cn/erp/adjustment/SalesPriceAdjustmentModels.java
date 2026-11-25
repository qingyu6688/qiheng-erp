package cn.erp.adjustment;

import java.time.LocalDate;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class SalesPriceAdjustmentModels {
    private SalesPriceAdjustmentModels() {}
    public record LineInput(@Positive(message="请选择原发货行") long shipmentLineId, String newUnitPrice, String adjustmentAmount) {}
    public record Input(Long version, @Positive(message="请选择客户") long partnerId,
                        @Positive(message="请选择仓库") long warehouseId, @NotNull(message="请选择调价日期") LocalDate businessDate,
                        @NotBlank(message="请填写调价原因") @Size(max=500,message="调价原因最多500字") String reason,
                        @NotEmpty(message="请添加调价明细") @Size(max=100,message="单次最多调整100行") List<@Valid LineInput> lines) {}
}
