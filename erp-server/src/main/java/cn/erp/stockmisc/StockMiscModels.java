package cn.erp.stockmisc;

import cn.erp.inventory.InventoryModels.StockSelection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;

final class StockMiscModels {
    private StockMiscModels() {}

    record Input(@Pattern(regexp="in|out",message="出入库方向无效") @NotBlank(message="请选择出入库方向") String direction,
                 @NotBlank(message="请选择业务用途") @Size(max=64,message="用途编码最多64字") String purpose,
                 @NotNull(message="请选择仓库") @Positive(message="仓库编号无效") Long warehouseId,
                 @Positive(message="往来单位编号无效") Long partnerId,
                 @NotNull(message="请选择收支项目") @Positive(message="收支项目编号无效") Long incomeExpenseItemId,
                 @NotNull(message="请选择业务日期") LocalDate businessDate,LocalDate expectedReturnDate,
                 @Positive(message="原借出单编号无效") Long originalLoanId,
                 @Size(max=500,message="备注最多500字") String remark,
                 @NotEmpty(message="请填写至少一行商品") @Size(max=200,message="每张单最多200行")
                 List<@NotNull(message="商品行不能为空") @Valid Line> lines,Long version) {}

    record Line(@NotNull(message="请选择商品") @Positive(message="商品编号无效") Long skuId,
                @NotNull(message="请选择单位") @Positive(message="单位编号无效") Long unitId,
                @NotBlank(message="请填写数量") String qty,String inputUnitCost,
                @Size(max=200,message="零成本原因最多200字") String zeroCostReason,
                @Positive(message="原借出行编号无效") Long originalLoanLineId,StockSelection stockSelection) {}

    record Command(@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号不能为负") Long version,
                   @Size(max=200,message="意见最多200字") String reason) {}
}
