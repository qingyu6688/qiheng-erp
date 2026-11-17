package cn.erp.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

final class CatalogModels {
    private CatalogModels() {}

    record UnitInput(
            @NotBlank(message = "请输入计量单位") @Size(max = 16, message = "单位名称最多 16 个字符") String name,
            @Pattern(regexp = "active|disabled", message = "单位状态无效") String status, Long version) {}
    record CategoryInput(
            @NotBlank(message = "请输入分类编码") @Size(max = 32, message = "分类编码过长") String code,
            @NotBlank(message = "请输入分类名称") @Size(max = 64, message = "分类名称过长") String name,
            Long parentId, Integer sortNo,
            @Pattern(regexp = "active|disabled", message = "分类状态无效") String status, Long version) {}
    record ProductInput(
            @NotNull(message = "请选择分类") Long categoryId,
            @NotBlank(message = "请输入商品编码") @Size(max = 32, message = "商品编码过长") String code,
            @NotBlank(message = "请输入商品名称") @Size(max = 100, message = "商品名称过长") String name,
            @Pattern(regexp = "goods|material|finished", message = "商品类型无效") String itemType,
            @NotNull(message = "请选择基础单位") Long baseUnitId,
            @Pattern(regexp = "active|disabled", message = "商品状态无效") String status, Long version) {}
    record SkuInput(
            @NotNull(message = "请选择商品") Long productId,
            @Size(max = 32, message = "SKU 编码过长") String code,
            @NotBlank(message = "请输入 SKU 名称") @Size(max = 100, message = "SKU 名称过长") String name,
            @Size(max = 100, message = "规格最多 100 个字符") String spec,
            @Pattern(regexp = "0(\\.[0-9]{1,6})?", message = "税率须小于 1，最多六位小数") String defaultTaxRate,
            @Pattern(regexp = "[0-9]{1,16}(\\.[0-9]{1,8})?", message = "采购价格式有误，最多八位小数") String purchasePrice,
            @Pattern(regexp = "[0-9]{1,16}(\\.[0-9]{1,8})?", message = "销售价格式有误，最多八位小数") String salePrice,
            @Pattern(regexp = "active|disabled", message = "SKU 状态无效") String status, Long version,
            @Pattern(regexp="[0-9]{1,16}(\\.[0-9]{1,8})?",message="最低售价须为非负数，最多八位小数") String minSalePrice, @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) JsonNode extJson) {
        public SkuInput(Long productId,String code,String name,String spec,String defaultTaxRate,String purchasePrice,String salePrice,String status,Long version,String minSalePrice) {this(productId,code,name,spec,defaultTaxRate,purchasePrice,salePrice,status,version,minSalePrice,null);}
        public SkuInput(Long productId,String code,String name,String spec,String defaultTaxRate,String purchasePrice,String salePrice,String status,Long version) {this(productId,code,name,spec,defaultTaxRate,purchasePrice,salePrice,status,version,null);}
    }
    record Version(@NotNull(message = "请提供版本号") @PositiveOrZero(message = "版本号无效") Long version) {}
    record Conversion(
            @NotNull(message = "请选择单位") Long unitId,
            @NotBlank(message = "请输入换算率")
            @Pattern(regexp = "[0-9]{1,12}(\\.[0-9]{1,6})?", message = "换算率格式有误，最多六位小数") String rateToBase,
            boolean isBase) {}
    record UnitsInput(@NotNull(message = "请提供版本号") Long version,
                      @NotEmpty(message = "至少保留基础单位") @Size(max = 50, message = "单位数量过多")
                      List<@Valid Conversion> units) {}
    record Barcode(@NotBlank(message = "请输入条码") @Size(max = 64, message = "条码最多 64 个字符") String barcode,
                   @NotNull(message = "请选择条码对应单位") Long unitId) {}
    record BarcodesInput(@NotNull(message = "请提供版本号") Long version,
                         @NotNull(message = "请提供条码列表") @Size(max = 100, message = "条码数量过多")
                         List<@Valid Barcode> barcodes) {}
    record Page(List<Detail> items, String nextCursor) {}
    record Detail(String id, String code, String name, String status, long version,
                  String parentId, Integer sortNo, List<Detail> children,
                  String categoryId, String itemType, String baseUnitId, List<Detail> skus,
                  String productId, String spec, String defaultTaxRate,
                  @JsonInclude(JsonInclude.Include.NON_NULL) String purchasePrice, String salePrice,
                  List<UnitView> units, List<BarcodeView> barcodes, List<String> allowedActions,String minSalePrice,Map<String,Object> extJson) {}
    record UnitView(String unitId, String unitName, String rateToBase, boolean isBase, boolean referenced) {}
    record BarcodeView(String barcode, String unitId) {}
    record Scan(Detail sku, String unitId, String unitName, String rateToBase) {}
}
