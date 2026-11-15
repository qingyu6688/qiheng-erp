package cn.erp.catalog;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.AuditService;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class SkuDetailsService {
    private static final List<String> DOCUMENT_LINES=List.of("sales_order_line","shipment_line","sales_return_line",
            "purchase_requisition_line","purchase_order_line","goods_receipt_line","purchase_return_line",
            "stock_transfer_line","stock_adjustment_line");
    private final JdbcTemplate jdbc;
    private final CatalogQueries queries;
    private final CatalogService catalog;
    private final AuditService audit;

    SkuDetailsService(JdbcTemplate jdbc,CatalogQueries queries,CatalogService catalog,AuditService audit) {
        this.jdbc=jdbc; this.queries=queries; this.catalog=catalog; this.audit=audit;
    }

    CatalogModels.Detail units(CompanyAccess scope,long id,CatalogModels.UnitsInput input) {
        var before=queries.find(scope,"sku",id,true);
        CatalogService.checkVersion(before,input.version());
        Object old=queries.detail(scope,"sku",id);
        var product=queries.find(scope,"product",Long.parseLong(before.productId()),true);
        Map<Long,CatalogModels.Conversion> desired=validateUnits(scope,input.units(),Long.parseLong(product.baseUnitId()));
        var current=jdbc.query("SELECT unit_id,rate_to_base,is_base FROM unit_conversion WHERE tenant_id=? AND sku_id=? ORDER BY unit_id",
                (rs,n) -> new CurrentUnit(rs.getLong(1),rs.getBigDecimal(2),rs.getBoolean(3)),scope.tenantId(),id);
        for (CurrentUnit row:current) {
            var next=desired.remove(row.unit());
            if (next==null) { removeUnit(scope,id,row.unit()); }
            else if (row.rate().compareTo(new BigDecimal(next.rateToBase()))!=0 || row.base()!=next.isBase()) {
                ensureUnused(scope,id,row.unit());
                jdbc.update("""
                        UPDATE unit_conversion SET rate_to_base=?,is_base=?,version=version+1,updated_by=?
                        WHERE tenant_id=? AND sku_id=? AND unit_id=?
                        """,next.rateToBase(),next.isBase(),scope.userId(),scope.tenantId(),id,row.unit());
            }
        }
        for (var entry:desired.values()) {
            jdbc.update("""
                    INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base,created_by,updated_by)
                    VALUES(?,?,?,?,?,?,?,?)
                    """,Ids.next(),scope.tenantId(),id,entry.unitId(),entry.rateToBase(),entry.isBase(),scope.userId(),scope.userId());
        }
        return changed(scope,id,"units",old,before);
    }

    private Map<Long,CatalogModels.Conversion> validateUnits(CompanyAccess scope,List<CatalogModels.Conversion> units,long baseId) {
        var desired=new LinkedHashMap<Long,CatalogModels.Conversion>();
        int baseCount=0;
        for (var unit:units) {
            BigDecimal rate=new BigDecimal(unit.rateToBase());
            if(rate.signum()<=0) { throw CatalogService.invalid("换算率必须大于零"); }
            if (desired.put(unit.unitId(),unit)!=null) { throw CatalogService.invalid("同一单位不能重复"); }
            catalog.active(scope,"unit",unit.unitId());
            if (unit.isBase()) {
                baseCount++;
                if (unit.unitId()!=baseId || rate.compareTo(BigDecimal.ONE)!=0) {
                    throw CatalogService.invalid("基础单位须与商品一致，换算率为 1");
                }
            } else if (unit.unitId()==baseId) { throw CatalogService.invalid("商品基础单位必须标记为基础单位"); }
        }
        if (baseCount!=1) { throw CatalogService.invalid("每个 SKU 必须且只能有一个基础单位"); }
        return desired;
    }

    private void removeUnit(CompanyAccess scope,long sku,long unit) {
        ensureUnused(scope,sku,unit);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM sku_barcode WHERE tenant_id=? AND sku_id=? AND unit_id=?",
                Long.class,scope.tenantId(),sku,unit)>0) {
            throw CatalogService.dependency("该单位已配置条码，请先调整条码");
        }
        jdbc.update("DELETE FROM unit_conversion WHERE tenant_id=? AND sku_id=? AND unit_id=?",scope.tenantId(),sku,unit);
    }

    private void ensureUnused(CompanyAccess scope,long sku,long unit) {
        for (String table:DOCUMENT_LINES) {
            Long exists=jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=? AND sku_id=? AND unit_id=?",
                    Long.class,scope.tenantId(),sku,unit);
            if (exists>0) { throw CatalogService.dependency("该换算率已被单据引用，不能修改或移除"); }
        }
    }

    CatalogModels.Detail barcodes(CompanyAccess scope,long id,CatalogModels.BarcodesInput input) {
        var before=queries.find(scope,"sku",id,true);
        CatalogService.checkVersion(before,input.version());
        var seen=new HashSet<String>();
        for (var entry:input.barcodes()) {
            if (!seen.add(entry.barcode().trim()) || entry.barcode().isBlank()) { throw CatalogService.invalid("条码不能为空或重复"); }
            Long exists=jdbc.queryForObject("SELECT COUNT(*) FROM unit_conversion WHERE tenant_id=? AND sku_id=? AND unit_id=?",
                    Long.class,scope.tenantId(),id,entry.unitId());
            if(exists!=1) { throw CatalogService.invalid("条码单位尚未配置换算率"); }
            catalog.active(scope,"unit",entry.unitId());
        }
        Object old=queries.detail(scope,"sku",id);
        jdbc.update("DELETE FROM sku_barcode WHERE tenant_id=? AND sku_id=?",scope.tenantId(),id);
        try {
            for (var entry:input.barcodes()) {
                jdbc.update("INSERT INTO sku_barcode(id,tenant_id,sku_id,unit_id,barcode,created_by) VALUES(?,?,?,?,?,?)",
                        Ids.next(),scope.tenantId(),id,entry.unitId(),entry.barcode().trim(),scope.userId());
            }
        } catch (DuplicateKeyException ex) { throw CatalogService.duplicate(); }
        return changed(scope,id,"barcodes",old,before);
    }

    private CatalogModels.Detail changed(CompanyAccess scope,long id,String action,Object old,CatalogModels.Detail before) {
        jdbc.update("UPDATE sku SET version=version+1,updated_by=? WHERE tenant_id=? AND id=? AND version=?",
                scope.userId(),scope.tenantId(),id,before.version());
        var result=queries.detail(scope,"sku",id);
        audit.record(scope,"sku."+action,"sku",id,CatalogVisibility.safeAudit(old),CatalogVisibility.withoutPurchasePrice(result));
        return result;
    }

    private record CurrentUnit(long unit,BigDecimal rate,boolean base) {}
}
