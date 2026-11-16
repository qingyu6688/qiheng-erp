package cn.erp.salesorder;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;

public final class SalesOrderModels {
    private SalesOrderModels() {}

    public record Input(
        @NotBlank(message="请选择客户") @Pattern(regexp="[1-9][0-9]{0,18}",message="客户编号无效") String customerId,
        @NotNull(message="请选择业务日期") LocalDate businessDate, LocalDate deliveryDate,
        Boolean priceTaxInclusive, String orderDiscountAmount,
        @NotEmpty(message="请填写商品明细") @Size(max=500,message="明细最多500行") List<@NotNull @Valid Line> lines,
        @Size(max=50,message="费用最多50行") List<@NotNull @Valid Fee> fees,
        @Size(max=500,message="备注最多500字") String remark, Long version,
        @Size(max=200,message="原因最多200字") String reason, @Positive(message="业务员编号无效") Long salesUserId,@Positive(message="送货区划编号无效") Long deliveryAreaId,@Size(max=200,message="送货地址最多200字") String deliveryAddress) {
        public Input(String customerId,LocalDate businessDate,LocalDate deliveryDate,Boolean priceTaxInclusive,String orderDiscountAmount,List<Line> lines,List<Fee> fees,String remark,Long version,String reason,Long salesUserId) {this(customerId,businessDate,deliveryDate,priceTaxInclusive,orderDiscountAmount,lines,fees,remark,version,reason,salesUserId,null,null);}
        public Input(String customerId,LocalDate businessDate,LocalDate deliveryDate,Boolean priceTaxInclusive,String orderDiscountAmount,List<Line> lines,List<Fee> fees,String remark,Long version,String reason) {this(customerId,businessDate,deliveryDate,priceTaxInclusive,orderDiscountAmount,lines,fees,remark,version,reason,null,null,null);}
    }
    public record Line(String id,
        @NotBlank(message="请选择商品") String skuId, String warehouseId,
        @NotBlank(message="请选择单位") String unitId,
        @NotBlank(message="请输入数量") String qty, String unitPrice, String amountInput,
        String lineDiscountAmount,String lineDiscountRatio,String taxRate) {}
    public record Fee(String id,
        @NotBlank(message="请选择费用类型") String feeType,
        @NotBlank(message="请选择费用承担方") String bearer,
        @NotBlank(message="请输入费用金额") String amount,String taxRate) {}
    public record Command(@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号无效") Long version,
                          @Size(max=500,message="意见最多500字") String comment,
                          @Size(max=200,message="原因最多200字") String reason) {}
    public record CloseLine(@NotBlank(message="请选择订单行") String lineId,@NotBlank(message="请输入关闭数量") String closeQty) {}
    public record Close(@NotNull(message="请提供版本号") Long version,
                        @NotBlank(message="请填写关闭原因") @Size(max=200,message="原因最多200字") String reason,
                        @NotEmpty(message="请选择关闭明细") List<@NotNull @Valid CloseLine> lines) {}
}
