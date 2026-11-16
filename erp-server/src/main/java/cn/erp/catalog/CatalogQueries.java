package cn.erp.catalog;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class CatalogQueries {
    private final JdbcTemplate jdbc;

    CatalogQueries(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    CatalogModels.Page list(CompanyAccess scope, String kind, String keyword, String status, int limit, String cursor) {
        if (limit < 1 || limit > 200 || (status != null && !List.of("active","disabled").contains(status))) {
            throw new ApiException(400, "VALIDATION_FAILED", "查询条件或每页数量无效");
        }
        var args = new ArrayList<Object>();
        args.add(scope.tenantId());
        String filter = " WHERE tenant_id=?";
        if (status != null) { filter += " AND status=?"; args.add(status); }
        if (keyword != null && !keyword.isBlank()) {
            if (keyword.length() > 100) { throw new ApiException(400, "VALIDATION_FAILED", "查询内容过长"); }
            filter += kind.equals("unit") ? " AND LOCATE(?,name)>0" : " AND (LOCATE(?,name)>0 OR LOCATE(?,code)>0)";
            args.add(keyword);
            if (!kind.equals("unit")) { args.add(keyword); }
        }
        if (cursor != null && !cursor.isBlank()) {
            try {
                long id = Long.parseLong(cursor);
                if (id <= 0) { throw new NumberFormatException(); }
                filter += " AND id<?";
                args.add(id);
            } catch (NumberFormatException ex) {
                throw new ApiException(400, "VALIDATION_FAILED", "分页游标无效");
            }
        }
        args.add(limit + 1);
        List<CatalogModels.Detail> all = jdbc.query("SELECT * FROM " + table(kind) + filter + " ORDER BY id DESC LIMIT ?",
                (rs, n) -> map(rs, scope, kind), args.toArray());
        boolean more = all.size() > limit;
        List<CatalogModels.Detail> items = all.subList(0, Math.min(limit, all.size()));
        return new CatalogModels.Page(List.copyOf(items), more ? items.getLast().id() : null);
    }

    CatalogModels.Detail find(CompanyAccess scope, String kind, long id, boolean lock) {
        var rows = jdbc.query("SELECT * FROM " + table(kind) + " WHERE tenant_id=? AND id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, n) -> map(rs, scope, kind), scope.tenantId(), id);
        if (rows.isEmpty()) { throw missing(); }
        return rows.getFirst();
    }

    CatalogModels.Detail detail(CompanyAccess scope, String kind, long id) {
        var value = find(scope, kind, id, false);
        if (kind.equals("product")) {
            List<CatalogModels.Detail> skus = jdbc.query("SELECT * FROM sku WHERE tenant_id=? AND product_id=? ORDER BY id",
                    (rs,n) -> map(rs,scope,"sku"), scope.tenantId(),id);
            return withChildren(value, null, skus);
        }
        if (kind.equals("sku")) {
            return copySku(value, units(scope,id), barcodes(scope,id));
        }
        return value;
    }

    CatalogModels.Page categories(CompanyAccess scope) {
        List<CatalogModels.Detail> rows = jdbc.query("SELECT * FROM product_category WHERE tenant_id=? ORDER BY sort_no,id LIMIT 10001",
                (rs,n) -> map(rs,scope,"product_category"), scope.tenantId());
        if (rows.size() > 10000) { throw new ApiException(422, "STATE_CONFLICT", "分类数量过多，请联系管理员"); }
        return new CatalogModels.Page(branch(rows,null), null);
    }

    private List<CatalogModels.Detail> branch(List<CatalogModels.Detail> rows, String parentId) {
        return rows.stream().filter(row -> java.util.Objects.equals(row.parentId(),parentId))
                .map(row -> withChildren(row,branch(rows,row.id()),null)).toList();
    }

    List<CatalogModels.UnitView> units(CompanyAccess scope, long skuId) {
        return jdbc.query("""
                SELECT c.unit_id,u.name,c.rate_to_base,c.is_base FROM unit_conversion c
                JOIN unit u ON u.tenant_id=c.tenant_id AND u.id=c.unit_id
                WHERE c.tenant_id=? AND c.sku_id=? ORDER BY c.is_base DESC,c.id
                """, (rs,n) -> new CatalogModels.UnitView(rs.getString(1),rs.getString(2),rs.getBigDecimal(3).toPlainString(),rs.getBoolean(4), referenced(scope,skuId,rs.getLong(1))),
                scope.tenantId(),skuId);
    }
    private boolean referenced(CompanyAccess scope,long sku,long unit) {
        for(String table:List.of("sales_order_line","shipment_line","sales_return_line","purchase_requisition_line","purchase_order_line","goods_receipt_line","purchase_return_line","stock_transfer_line","stock_adjustment_line")) {
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM "+table+" WHERE tenant_id=? AND sku_id=? AND unit_id=?)",Boolean.class,scope.tenantId(),sku,unit)))return true;
        }
        return false;
    }

    List<CatalogModels.BarcodeView> barcodes(CompanyAccess scope, long skuId) {
        return jdbc.query("SELECT barcode,unit_id FROM sku_barcode WHERE tenant_id=? AND sku_id=? ORDER BY id",
                (rs,n) -> new CatalogModels.BarcodeView(rs.getString(1),rs.getString(2)),scope.tenantId(),skuId);
    }

    CatalogModels.Scan scan(CompanyAccess scope, String barcode) {
        if (barcode.length() > 64) { throw new ApiException(400,"VALIDATION_FAILED","条码过长"); }
        var rows = jdbc.query("""
                SELECT b.sku_id,b.unit_id,u.name,c.rate_to_base FROM sku_barcode b
                JOIN unit_conversion c ON c.tenant_id=b.tenant_id AND c.sku_id=b.sku_id AND c.unit_id=b.unit_id
                JOIN unit u ON u.tenant_id=b.tenant_id AND u.id=b.unit_id
                JOIN sku s ON s.tenant_id=b.tenant_id AND s.id=b.sku_id
                JOIN product p ON p.tenant_id=s.tenant_id AND p.id=s.product_id
                JOIN product_category pc ON pc.tenant_id=p.tenant_id AND pc.id=p.category_id
                WHERE b.tenant_id=? AND b.barcode=? AND s.status='active' AND p.status='active'
                AND pc.status='active' AND u.status='active'
                """, (rs,n) -> new ScanRow(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getBigDecimal(4).toPlainString()),
                scope.tenantId(),barcode);
        if (rows.isEmpty()) { throw missing(); }
        var row=rows.getFirst();
        return new CatalogModels.Scan(detail(scope,"sku",row.sku()),row.unit(),row.name(),row.rate());
    }

    private CatalogModels.Detail map(ResultSet rs, CompanyAccess scope, String kind) throws SQLException {
        boolean category=kind.equals("product_category"), product=kind.equals("product"), sku=kind.equals("sku");
        String status=rs.getString("status");
        List<String> actions = scope.hasPermission(kind+":update") ? List.of("update",status.equals("active")?"disable":"enable") : List.of();
        return new CatalogModels.Detail(rs.getString("id"),kind.equals("unit")?null:rs.getString("code"),rs.getString("name"),status,rs.getLong("version"),
                category?rs.getString("parent_id"):null,category?rs.getInt("sort_no"):null,null,
                product?rs.getString("category_id"):null,product?rs.getString("item_type"):null,product?rs.getString("base_unit_id"):null,null,
                sku?rs.getString("product_id"):null,sku?rs.getString("spec"):null,sku?rs.getBigDecimal("default_tax_rate").toPlainString():null,
                sku && scope.hasPermission("cost:view")?decimal(rs,"purchase_price"):null,sku?decimal(rs,"sale_price"):null,null,null,actions,sku?decimal(rs,"min_sale_price"):null);
    }

    private static String decimal(ResultSet rs,String column) throws SQLException {
        var value=rs.getBigDecimal(column);
        return value==null?null:value.toPlainString();
    }

    private static CatalogModels.Detail withChildren(CatalogModels.Detail v,List<CatalogModels.Detail> children,List<CatalogModels.Detail> skus) {
        return new CatalogModels.Detail(v.id(),v.code(),v.name(),v.status(),v.version(),v.parentId(),v.sortNo(),children,
                v.categoryId(),v.itemType(),v.baseUnitId(),skus,v.productId(),v.spec(),v.defaultTaxRate(),v.purchasePrice(),v.salePrice(),
                v.units(),v.barcodes(),v.allowedActions(),v.minSalePrice());
    }

    private static CatalogModels.Detail copySku(CatalogModels.Detail v,List<CatalogModels.UnitView> units,List<CatalogModels.BarcodeView> barcodes) {
        return new CatalogModels.Detail(v.id(),v.code(),v.name(),v.status(),v.version(),v.parentId(),v.sortNo(),v.children(),
                v.categoryId(),v.itemType(),v.baseUnitId(),v.skus(),v.productId(),v.spec(),v.defaultTaxRate(),v.purchasePrice(),v.salePrice(),
                units,barcodes,v.allowedActions(),v.minSalePrice());
    }

    static String table(String kind) {
        return switch(kind) {
            case "unit" -> "unit";
            case "product_category" -> "product_category";
            case "product" -> "product";
            case "sku" -> "sku";
            default -> throw new IllegalArgumentException("商品资源类型无效");
        };
    }

    static ApiException missing() { return new ApiException(404,"NOT_FOUND","资料不存在或无权查看"); }
    private record ScanRow(long sku,String unit,String name,String rate) {}
}
