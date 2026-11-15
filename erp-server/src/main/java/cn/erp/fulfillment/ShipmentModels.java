package cn.erp.fulfillment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;

public final class ShipmentModels {
    private ShipmentModels() {}
    public record Input(
        @NotNull(message="请选择客户") @Positive(message="客户编号无效") Long customerId,
        @NotNull(message="请选择仓库") @Positive(message="仓库编号无效") Long warehouseId,
        @NotNull(message="请选择业务日期") LocalDate businessDate,
        @Pattern(regexp="delivery|pickup",message="交货方式无效") String deliveryMethod,
        @Size(max=500,message="备注最多500字") String remark,
        @NotEmpty(message="请至少选择一条订单行") @Size(max=500,message="发货明细最多500行") List<@NotNull @Valid InputLine> lines,
        Long version) {}
    public record InputLine(@NotNull(message="请选择订单行") @Positive(message="订单行编号无效") Long orderLineId,
        @NotNull(message="请选择单位") @Positive(message="单位编号无效") Long unitId,
        @NotBlank(message="请输入发货数量") String qty) {}
    public record Version(@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号不能为负数") Long version) {}
    public record Signed(@NotNull(message="请提供版本号") Long version,@NotNull(message="请选择签收日期") LocalDate signDate,
        @NotEmpty(message="请登记每一行的签收数量") List<@NotNull @Valid SignedLine> lines,
        @Size(max=20,message="签收附件最多20份") List<@Positive(message="附件编号无效") Long> attachmentIds) {}
    public record SignedLine(@NotNull(message="请选择发货行") Long lineId,@NotNull(message="请输入签收数量") String signedQty) {}
    public record Resolution(@NotNull(message="请提供版本号") Long version,@NotNull(message="请选择处理日期") LocalDate businessDate,
        @NotEmpty(message="请选择差异行") List<@NotNull @Valid ResolveLine> lines) {}
    public record ResolveLine(@NotNull(message="请选择发货行") Long lineId,
        @Pattern(regexp="returned|lost",message="请选择拒收退回或运输损失") @NotNull String disposition,
        @NotBlank(message="请输入处理原因") @Size(max=200,message="原因最多200字") String reason) {}
}
