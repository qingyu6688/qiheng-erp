package cn.erp.pricing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;

/** 金额和数量在请求及响应中使用十进制字符串。 */
public final class PricingModels {
    private PricingModels() {}

    public record PreviewRequest(
            @NotNull(message="请选择单据类型") @Pattern(regexp="sales_order|purchase_order",message="单据类型无效") String documentType,
            @Pattern(regexp="[1-9][0-9]{0,18}",message="客户编号无效") String customerId,
            @Pattern(regexp="[1-9][0-9]{0,18}",message="供应商编号无效") String supplierId,
            Boolean priceTaxInclusive, String orderDiscountAmount,
            @NotEmpty(message="请至少填写一条商品明细") @Size(max=500,message="商品明细最多500行")
            List<@NotNull(message="商品行不能为空") @Valid PreviewLine> lines,
            @Size(max=50,message="费用最多50行") List<@NotNull(message="费用行不能为空") @Valid Fee> fees,
            LocalDate businessDate) {
        public PreviewRequest(String documentType, String customerId, String supplierId, Boolean priceTaxInclusive,
                              String orderDiscountAmount, List<PreviewLine> lines, List<Fee> fees) {
            this(documentType, customerId, supplierId, priceTaxInclusive, orderDiscountAmount, lines, fees, null);
        }
    }

    public record DefaultPrice(String unitPrice, boolean priceTaxInclusive, String source) {}

    public record PreviewLine(
            @NotBlank(message="请选择商品") @Pattern(regexp="[1-9][0-9]{0,18}",message="商品编号无效") String skuId,
            @NotBlank(message="请选择单位") @Pattern(regexp="[1-9][0-9]{0,18}",message="单位编号无效") String unitId,
            @Pattern(regexp="[1-9][0-9]{0,18}",message="仓库编号无效") String warehouseId,
            @NotBlank(message="请输入数量") String qty,
            String unitPrice, String amountInput, String lineDiscountAmount,
            String lineDiscountRatio, String taxRate) {}

    public record Calculation(boolean priceTaxInclusive, String orderDiscountAmount,
                              List<Line> lines, List<Fee> fees) {}

    public record Line(String skuId,String unitId,String warehouseId,String qty,String unitRate,
                       String unitPrice,String amountInput,String lineDiscountAmount,
                       String lineDiscountRatio,String taxRate) {}

    public record Fee(
            @NotNull(message="请输入费用类型") @Pattern(regexp="freight|other",message="费用类型无效") String feeType,
            @NotNull(message="请选择费用承担方") @Pattern(regexp="buyer|seller",message="费用承担方无效") String bearer,
            @NotBlank(message="请输入费用金额") String amount,
            String taxRate) {}

    public record Preview(int pricingVersion,boolean priceTaxInclusive,String currency,String goodsAmount,
                          String lineDiscountAmount,String orderDiscountAmount,String feeAmount,
                          String totalAmount,String untaxedAmount,String taxAmount,
                          List<LineResult> lines,List<FeeResult> fees,List<Warning> warnings) {}

    public record LineResult(int lineNo,boolean priceTaxInclusive,String skuId,String unitId,String warehouseId,
                             String qty,String unitRate,String baseQty,String unitPrice,
                             String amount,String lineDiscountAmount,String lineNet,
                             String orderDiscountAlloc,String receivableNet,String taxRate,
                             String untaxedAmount,String taxAmount,String totalAmount) {}

    public record FeeResult(int lineNo,boolean priceTaxInclusive,String feeType,String bearer,String amount,String taxRate,
                            String untaxedAmount,String taxAmount,String totalAmount,boolean included) {}

    public record Warning(int lineNo,String type,String message) {}

    public record Recognition(String totalAmount,String taxAmount,String untaxedAmount) {}
}
