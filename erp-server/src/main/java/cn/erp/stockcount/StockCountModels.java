package cn.erp.stockcount;

import cn.erp.inventory.InventoryModels.StockSelection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;

public final class StockCountModels {
    private StockCountModels() {}
    public record Document(@NotNull(message="请选择仓库") Long warehouseId,
        @NotNull(message="请选择盘点日期") LocalDate businessDate,
        @NotBlank(message="请选择盘点方式") @Pattern(regexp="watermark",message="本期使用水位盘点，请选择流水水位方式") String countMode,
        @Size(max=500,message="备注最多500字") String remark,
        @NotEmpty(message="请选择盘点商品") @Size(max=200,message="每张盘点单最多200行") List<@NotNull(message="商品行不能为空") @Valid Item> lines,Long version) {}
    public record Item(@NotNull(message="请选择商品") Long skuId,@Valid StockSelection stockSelection) {
        public Item(Long skuId){this(skuId,null);}
    }
    public record Counts(@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号无效") Long version,
        @NotEmpty(message="请填写实盘数量") @Size(max=200,message="每张盘点单最多200行") List<@NotNull(message="实盘行不能为空") @Valid Count> lines) {}
    public record Count(@NotNull(message="请选择盘点行") Long lineId,
        @NotBlank(message="请填写实盘数量") String countedQty,String inputUnitCost,@Size(max=500,message="最多500个序列号") List<String> serials) {
        public Count(Long lineId,String countedQty,String inputUnitCost){this(lineId,countedQty,inputUnitCost,null);}
    }
    public record Version(@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号无效") Long version,
        @Size(max=200,message="意见最多200字") String reason) {}
}
