package cn.erp.catalog;

final class CatalogVisibility {
    private CatalogVisibility() {}

    static Object safeAudit(Object value) {
        return value instanceof CatalogModels.Detail detail ? withoutPurchasePrice(detail) : value;
    }

    static CatalogModels.Detail withoutPurchasePrice(CatalogModels.Detail value) {
        if (value == null) {
            return null;
        }
        return new CatalogModels.Detail(
                value.id(), value.code(), value.name(), value.status(), value.version(),
                value.parentId(), value.sortNo(),
                value.children() == null ? null : value.children().stream().map(CatalogVisibility::withoutPurchasePrice).toList(),
                value.categoryId(), value.itemType(), value.baseUnitId(),
                value.skus() == null ? null : value.skus().stream().map(CatalogVisibility::withoutPurchasePrice).toList(),
                value.productId(), value.spec(), value.defaultTaxRate(), null, value.salePrice(),
                value.units(), value.barcodes(), value.allowedActions(),value.minSalePrice(),value.extJson());
    }
}
