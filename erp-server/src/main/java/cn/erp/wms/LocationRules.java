package cn.erp.wms;

import cn.erp.identity.CompanyAccess;
import cn.erp.salesfinance.FinanceSupport;
import cn.erp.shared.ApiRows;
import cn.erp.shared.AuditService;
import cn.erp.shared.Ids;
import cn.erp.shared.OrgOwnership;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static cn.erp.wms.WmsScopes.invalid;
import static cn.erp.wms.WmsScopes.missing;

@Service
class LocationRules {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final WmsScopes scopes;

    LocationRules(JdbcTemplate jdbc,AuditService audit,WmsScopes scopes) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.scopes = scopes;
    }

    record Input(Long id,Long version,Long warehouseId,Long skuId,Long categoryId,Long zoneId,
                 Long fixedLocationId,BigDecimal minQty,BigDecimal maxQty) {}

    record Target(Long fixedLocationId,Long zoneId) {}

    @Transactional(readOnly = true)
    List<Map<String,Object>> list(CompanyAccess actor,long warehouse) {
        scopes.warehouse(actor,warehouse,false);
        return jdbc.queryForList("""
                SELECT r.id,r.warehouse_id,r.sku_id,r.category_id,r.zone_id,r.fixed_location_id,r.min_qty,r.max_qty,r.version,
                  sku.code sku_code,category.name category_name,z.name zone_name,l.code fixed_location_code
                FROM wms_location_rule r
                LEFT JOIN sku ON sku.tenant_id=r.tenant_id AND sku.id=r.sku_id
                LEFT JOIN product_category category ON category.tenant_id=r.tenant_id AND category.id=r.category_id
                LEFT JOIN wms_zone z ON z.tenant_id=r.tenant_id AND z.company_id=r.company_id AND z.warehouse_id=r.warehouse_id AND z.id=r.zone_id
                LEFT JOIN wms_location l ON l.tenant_id=r.tenant_id AND l.company_id=r.company_id
                  AND l.warehouse_id=r.warehouse_id AND l.id=r.fixed_location_id
                WHERE r.tenant_id=? AND r.company_id=? AND r.warehouse_id=? ORDER BY r.id
                """,actor.tenantId(),actor.companyId(),warehouse).stream().map(ApiRows::row).toList();
    }

    @Transactional
    Map<String,Object> save(CompanyAccess actor,Input input) {
        scopes.fullDepartment(actor);
        if (input.warehouseId()==null || (input.skuId()==null)==(input.categoryId()==null))
            throw invalid("请选择仓库，并且仅选择商品或分类中的一项");
        long warehouse = input.warehouseId();
        scopes.warehouse(actor,warehouse,true);
        validate(actor,input);
        long id = input.id()==null ? Ids.next() : input.id();
        var before = input.id()==null ? null : get(actor,warehouse,id,true);
        try {
            if (before==null) {
                jdbc.update("""
                        INSERT INTO wms_location_rule(id,tenant_id,company_id,warehouse_id,sku_id,category_id,zone_id,
                            fixed_location_id,min_qty,max_qty,owner_user_id,owner_department_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                        """,id,actor.tenantId(),actor.companyId(),warehouse,input.skuId(),input.categoryId(),input.zoneId(),
                        input.fixedLocationId(),input.minQty(),input.maxQty(),actor.userId(),OrgOwnership.department(actor));
            } else {
                FinanceSupport.version(before,input.version());
                jdbc.update("""
                        UPDATE wms_location_rule SET sku_id=?,category_id=?,zone_id=?,fixed_location_id=?,min_qty=?,max_qty=?,version=version+1
                        WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND id=?
                        """,input.skuId(),input.categoryId(),input.zoneId(),input.fixedLocationId(),input.minQty(),input.maxQty(),
                        actor.tenantId(),actor.companyId(),warehouse,id);
            }
        } catch (DuplicateKeyException duplicate) {
            throw WmsScopes.conflict("该仓库已设置相同商品或分类的规则");
        }
        var result = ApiRows.row(get(actor,warehouse,id,false));
        audit.record(actor,"wms_rule.save","wms_location_rule",id,before==null?null:ApiRows.row(before),result);
        return result;
    }

    Target target(CompanyAccess actor,long warehouse,long sku) {
        return jdbc.query("""
                SELECT r.fixed_location_id,r.zone_id FROM wms_location_rule r
                JOIN sku s ON s.tenant_id=r.tenant_id AND s.id=?
                JOIN product p ON p.tenant_id=s.tenant_id AND p.id=s.product_id
                WHERE r.tenant_id=? AND r.company_id=? AND r.warehouse_id=? AND (r.sku_id=s.id OR r.category_id=p.category_id)
                ORDER BY r.sku_id IS NULL,r.id LIMIT 1
                """,(row,index)->new Target(row.getObject(1,Long.class),row.getObject(2,Long.class)),
                sku,actor.tenantId(),actor.companyId(),warehouse).stream().findFirst().orElse(new Target(null,null));
    }

    List<Map<String,Object>> replenishmentRules(CompanyAccess actor,long warehouse) {
        return jdbc.queryForList("""
                SELECT r.* FROM wms_location_rule r WHERE r.tenant_id=? AND r.company_id=? AND r.warehouse_id=?
                  AND r.min_qty IS NOT NULL ORDER BY r.id FOR UPDATE
                """,actor.tenantId(),actor.companyId(),warehouse);
    }

    private Map<String,Object> get(CompanyAccess actor,long warehouse,long id,boolean lock) {
        return jdbc.queryForList("""
                SELECT id,warehouse_id,sku_id,category_id,zone_id,fixed_location_id,min_qty,max_qty,version
                FROM wms_location_rule WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND id=?
                """+(lock?" FOR UPDATE":""),actor.tenantId(),actor.companyId(),warehouse,id).stream()
                .findFirst().orElseThrow(WmsScopes::missing);
    }

    private void validate(CompanyAccess actor,Input input) {
        if (input.skuId()!=null && jdbc.queryForList("SELECT id FROM sku WHERE tenant_id=? AND id=? AND status='active'",
                Long.class,actor.tenantId(),input.skuId()).isEmpty()) throw missing();
        if (input.categoryId()!=null && jdbc.queryForList("SELECT id FROM product_category WHERE tenant_id=? AND id=? AND status='active'",
                Long.class,actor.tenantId(),input.categoryId()).isEmpty()) throw missing();
        if (input.fixedLocationId()==null && input.zoneId()==null) throw invalid("请选择固定库位或存储库区");
        if (input.zoneId()!=null && jdbc.queryForList("""
                SELECT id FROM wms_zone WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND id=? AND status='active' AND type='storage'
                """,Long.class,actor.tenantId(),actor.companyId(),input.warehouseId(),input.zoneId()).isEmpty()) throw missing();
        if (input.fixedLocationId()!=null) validateFixed(actor,input);
        if (input.minQty()!=null || input.maxQty()!=null) {
            if (input.skuId()==null || input.fixedLocationId()==null || !validThreshold(input.minQty())
                    || !validThreshold(input.maxQty()) || input.maxQty().compareTo(input.minQty()) <= 0)
                throw invalid("补货须指定商品和固定库位，上限须大于非负下限，最多保留六位小数");
        }
    }

    private void validateFixed(CompanyAccess actor,Input input) {
        var zones = jdbc.queryForList("""
                SELECT z.id FROM wms_location l
                JOIN wms_zone z ON z.tenant_id=l.tenant_id AND z.company_id=l.company_id AND z.warehouse_id=l.warehouse_id AND z.id=l.zone_id
                WHERE l.tenant_id=? AND l.company_id=? AND l.warehouse_id=? AND l.id=?
                  AND l.status='active' AND z.status='active' AND z.type='storage'
                """,Long.class,actor.tenantId(),actor.companyId(),input.warehouseId(),input.fixedLocationId());
        if (zones.isEmpty()) throw missing();
        if (input.zoneId()!=null && !Objects.equals(input.zoneId(),zones.getFirst())) throw invalid("固定库位应位于所选库区");
    }

    private boolean validThreshold(BigDecimal value) {
        return value!=null && value.signum()>=0 && value.scale()<=6 && value.precision()-value.scale()<=18;
    }
}
