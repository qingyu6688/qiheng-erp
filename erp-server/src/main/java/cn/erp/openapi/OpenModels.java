package cn.erp.openapi;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import tools.jackson.databind.JsonNode;

final class OpenModels {
    private OpenModels() {}

    record SkuInput(@NotBlank(message="请选择商品") String productId,
            @Size(max=32,message="SKU 编码过长") String code,
            @NotBlank(message="请输入 SKU 名称") @Size(max=100,message="SKU 名称过长") String name,
            @Size(max=100,message="规格过长") String spec,JsonNode extJson) {}

    record PartnerInput(@Size(max=32,message="单位编码过长") String code,
            @NotBlank(message="请输入单位名") @Size(max=100,message="单位名称过长") String name,
            @Size(max=50,message="简称过长") String shortName,
            @NotEmpty(message="请选择客户或供应商角色") Set<@NotBlank(message="往来角色不能为空")
                    @Pattern(regexp="customer|supplier",message="往来角色无效") String> roles,JsonNode extJson) {}

    record OrderInput(@NotNull(message="请输入业务日期") LocalDate businessDate,LocalDate deliveryDate,
            String customerId,String supplierId,Boolean priceTaxInclusive,
            @Size(max=500,message="备注最多 500 字") String remark,
            @NotEmpty(message="请填写商品明细") @Size(max=500,message="商品明细最多 500 行") List<@Valid @NotNull Line> lines,
            JsonNode extJson) {}

    record Line(@NotBlank(message="请选择 SKU") String skuId,@NotBlank(message="请选择仓库") String warehouseId,
            @NotBlank(message="请选择单位") String unitId,@NotBlank(message="请填写数量") String qty,
            String unitPrice,String taxRate,JsonNode extJson) {}

    record Command(@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号无效") Long version,
            @Size(max=500,message="意见最多 500 字") String comment,@Size(max=200,message="原因最多 200 字") String reason) {}

    record FinanceDraft(String partnerId,@NotBlank(message="请选择资金账户") String cashAccountId,
            @NotBlank(message="请填写金额") String amount,@NotNull(message="请输入业务日期") LocalDate businessDate,
            @Size(max=200,message="备注最多 200 字") String remark) {}

    record Document(String id,String documentNo,String status,long version,String businessDate,String partnerId,String warehouseId,
            List<DocumentLine> lines) {}
    record DocumentLine(String id,String skuId,String unitId,String warehouseId,String qty,String unitPrice) {}
    record Page<T>(List<T> items,String nextCursor) {}
    record Stock(String skuId,String warehouseId,String availableQty) {}

    static Document document(Map<String,Object> row) {
        Object lines=row.get("lines");
        var items=lines instanceof List<?> list ? list.stream().filter(Map.class::isInstance)
                .map(item -> documentLine((Map<?,?>)item)).toList() : List.<DocumentLine>of();
        return new Document(text(row,"id"),first(row,"documentNo","orderNo","shipmentNo","receiptNo","paymentNo"),
                text(row,"status"),Long.parseLong(Objects.toString(row.get("version"),"0")),text(row,"businessDate"),
                first(row,"customerId","supplierId","partnerId"),text(row,"warehouseId"),items);
    }

    private static DocumentLine documentLine(Map<?,?> row) {
        return new DocumentLine(text(row,"id"),text(row,"skuId"),text(row,"unitId"),text(row,"warehouseId"),
                text(row,"qty"),text(row,"unitPrice"));
    }

    static String text(Map<?,?> row,String key) { return Objects.toString(row.get(key),null); }

    private static String first(Map<String,Object> row,String... keys) {
        for (String key:keys) if (row.containsKey(key) && row.get(key)!=null) return row.get(key).toString();
        return null;
    }

    @SuppressWarnings("unchecked")
    static <T> Page<T> page(Map<String,Object> page,java.util.function.Function<Map<String,Object>,T> mapper) {
        return new Page<>(((List<Map<String,Object>>)page.get("items")).stream().map(mapper).toList(),text(page,"nextCursor"));
    }
}
