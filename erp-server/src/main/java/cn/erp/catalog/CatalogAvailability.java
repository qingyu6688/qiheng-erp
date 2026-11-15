package cn.erp.catalog;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
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
}
