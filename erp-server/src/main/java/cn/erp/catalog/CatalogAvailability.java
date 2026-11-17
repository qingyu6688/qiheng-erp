package cn.erp.catalog;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 新单据取商品资料时的启用状态校验入口。 */
@Service
public class CatalogAvailability {
    private final JdbcTemplate jdbc;

    public CatalogAvailability(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    /** 校验 SKU、商品、分类和选用单位，调用方在保存单据的事务内使用。 */
    @Transactional(readOnly = true)
    public void requireActiveSku(CompanyAccess scope,long skuId,long unitId) {
        var rows=jdbc.query("""
                SELECT s.status,p.status,c.status,u.status FROM sku s
                JOIN product p ON p.tenant_id=s.tenant_id AND p.id=s.product_id
                JOIN product_category c ON c.tenant_id=p.tenant_id AND c.id=p.category_id
                JOIN unit_conversion cv ON cv.tenant_id=s.tenant_id AND cv.sku_id=s.id AND cv.unit_id=?
                JOIN unit u ON u.tenant_id=cv.tenant_id AND u.id=cv.unit_id
                WHERE s.tenant_id=? AND s.id=? FOR SHARE
                """,(rs,n)->rs.getString(1).equals("active")&&rs.getString(2).equals("active")
                        &&rs.getString(3).equals("active")&&rs.getString(4).equals("active"),unitId,scope.tenantId(),skuId);
        if(rows.isEmpty()) { throw new ApiException(404,"NOT_FOUND","商品或单位不存在"); }
        if(!rows.getFirst()) { throw new ApiException(422,"MASTER_DATA_DISABLED","商品、分类或单位已停用"); }
    }

    public record SkuUnit(long skuId,long unitId) {}

    /** 同一单据批量校验启用状态并读取换算、税率和参考价，资料共享锁持续到单据提交。 */
    @Transactional(readOnly=true)
    public Map<SkuUnit,Map<String,Object>> requireActiveSkus(CompanyAccess scope,Collection<SkuUnit> requested) {
        return activeSkus(scope,requested,true,true);
    }

    @Transactional(readOnly=true,noRollbackFor=ApiException.class)
    public void checkActiveSkus(CompanyAccess scope,Collection<SkuUnit> requested) {
        activeSkus(scope,requested,false,true);
    }

    @Transactional(readOnly=true,noRollbackFor=ApiException.class)
    public boolean hasActiveSku(CompanyAccess scope,Collection<SkuUnit> requested) {
        return !activeSkus(scope,requested,false,false).isEmpty();
    }

    private Map<SkuUnit,Map<String,Object>> activeSkus(CompanyAccess scope,Collection<SkuUnit> requested,boolean lock,boolean requireAll) {
        var keys=requested.stream().distinct().sorted(Comparator.comparingLong(SkuUnit::skuId).thenComparingLong(SkuUnit::unitId)).toList();
        if(keys.isEmpty()) {return Map.of();}
        if(keys.size()>2000) {throw new ApiException(400,"VALIDATION_FAILED","商品校验范围过大");}
        var args=new ArrayList<Object>();args.add(scope.tenantId());for(var key:keys) {args.add(key.skuId());args.add(key.unitId());}
        var result=new HashMap<SkuUnit,Map<String,Object>>();
        for(var row:jdbc.queryForList("""
            SELECT s.id AS sku_id,cv.unit_id,s.status AS sku_status,p.status AS product_status,c.status AS category_status,u.status AS unit_status,
              s.default_tax_rate,s.sale_price,s.purchase_price,cv.rate_to_base
            FROM sku s JOIN product p ON p.tenant_id=s.tenant_id AND p.id=s.product_id
            JOIN product_category c ON c.tenant_id=p.tenant_id AND c.id=p.category_id
            JOIN unit_conversion cv ON cv.tenant_id=s.tenant_id AND cv.sku_id=s.id
            JOIN unit u ON u.tenant_id=cv.tenant_id AND u.id=cv.unit_id
            WHERE s.tenant_id=? AND (s.id,cv.unit_id) IN (
            """+String.join(",",Collections.nCopies(keys.size(),"(?,?)"))+") ORDER BY s.id,cv.unit_id"+(lock?" FOR SHARE":""),args.toArray())) {
            if(!List.of("sku_status","product_status","category_status","unit_status").stream().allMatch(column->"active".equals(row.get(column)))) {
                if(requireAll) throw new ApiException(422,"MASTER_DATA_DISABLED","商品、分类或单位已停用");
                continue;
            }
            result.put(new SkuUnit(((Number)row.get("sku_id")).longValue(),((Number)row.get("unit_id")).longValue()),row);
        }
        if(requireAll&&result.size()!=keys.size()) {throw new ApiException(404,"NOT_FOUND","商品或单位不存在");}
        return result;
    }
}
