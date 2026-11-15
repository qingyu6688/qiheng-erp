package cn.erp.stocktransfer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;

public final class StockTransferModels {
    private StockTransferModels() {}
    public record Input(@NotNull(message="请选择调出仓库") Long fromWarehouseId,@NotNull(message="请选择调入仓库") Long toWarehouseId,
        @NotNull(message="请选择业务日期") LocalDate businessDate,@Size(max=500,message="备注最多500字") String remark,
        @NotEmpty(message="请填写调拨明细") @Size(max=200,message="调拨最多200行") List<@Valid Line> lines,Long version) {}
    public record Line(@NotNull(message="请选择商品") Long skuId,@NotNull(message="请选择单位") Long unitId,@NotBlank(message="请填写数量") String qty) {}
    public record Version(@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号无效") Long version,LocalDate businessDate) {}
    public record Receive(@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号无效") Long version,LocalDate businessDate,Boolean finalReceipt,
        @NotEmpty(message="请填写实收明细") @Size(max=200,message="实收最多200行") List<@Valid ReceivedLine> lines) {}
    public record ReceivedLine(@NotNull(message="请选择调拨行") Long lineId,@NotBlank(message="请填写实收基础数量") String qty) {}
    public record Difference(@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号无效") Long version,LocalDate businessDate,
        @NotBlank(message="请填写差异处理原因") @Size(max=200,message="原因最多200字") String reason,
        @NotEmpty(message="请选择差异行") @Size(max=200,message="差异最多200行") List<@Valid DifferenceLine> lines) {}
    public record DifferenceLine(@NotNull(message="请选择调拨行") Long lineId,
        @NotBlank(message="请选择差异处理方式") @Pattern(regexp="returned|lost",message="差异处理方式无效") String disposition) {}
}
