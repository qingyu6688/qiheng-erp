package cn.erp.receiving;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;

public final class ReceivingModels {
    private ReceivingModels() {}
    public record ReceiptInput(@NotNull(message="请选择供应商") @Positive(message="供应商编号无效") Long supplierId,
        @NotNull(message="请选择收货仓库") @Positive(message="仓库编号无效") Long warehouseId,
        @NotNull(message="请选择业务日期") LocalDate businessDate,@Size(max=500,message="备注最多500字") String remark,
        @NotEmpty(message="请填写收货明细") @Size(max=500,message="明细最多500行") List<@NotNull(message="收货行不能为空") @Valid ReceiptLine> lines,Long version) {}
    public record ReceiptLine(@NotNull(message="请选择采购订单行") @Positive(message="采购行编号无效") Long orderLineId,
        @NotNull(message="请选择收货单位") @Positive(message="单位编号无效") Long unitId,@NotBlank(message="请输入收货数量") String qty) {}
    public record ReturnInput(@NotNull(message="请选择供应商") @Positive(message="供应商编号无效") Long supplierId,
        @NotNull(message="请选择退货仓库") @Positive(message="仓库编号无效") Long warehouseId,
        @NotNull(message="请选择业务日期") LocalDate businessDate,@NotBlank(message="请输入退货原因") @Size(max=200,message="退货原因最多200字") String reason,
        @NotEmpty(message="请填写退货明细") @Size(max=500,message="明细最多500行") List<@NotNull(message="退货行不能为空") @Valid ReturnLine> lines,Long version) {}
    public record ReturnLine(@NotNull(message="请选择原收货行") @Positive(message="收货行编号无效") Long receiptLineId,
        @NotNull(message="请选择退货单位") @Positive(message="单位编号无效") Long unitId,@NotBlank(message="请输入退货数量") String qty) {}
    public record Version(@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号不能为负") Long version) {}
}
