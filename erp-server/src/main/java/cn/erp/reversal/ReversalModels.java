package cn.erp.reversal;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public final class ReversalModels {
    private ReversalModels() {}
    public record Input(@NotBlank(message="请选择来源类型") @Pattern(regexp="shipment|sales_return|goods_receipt|purchase_return|receipt|payment|account_transfer|stock_adjustment",message="来源类型不支持冲销") String sourceType,
        @NotNull(message="请选择来源单据") @Positive(message="来源单据无效") Long sourceId,@NotNull(message="请选择冲销日期") LocalDate businessDate,
        @NotBlank(message="请填写冲销原因") @Size(max=200,message="冲销原因最多200字") String reason) {}
    public record Version(@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号无效") Long version,@Size(max=200,message="审核意见最多200字") String reason) {}
}
