package cn.erp.inventory;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class OrderLineOccupancy {
    private final InventoryStore store;
    private final JdbcTemplate jdbc;
    public OrderLineOccupancy(InventoryStore store,JdbcTemplate jdbc) {this.store=store;this.jdbc=jdbc;}

    /** 发货单提交时调用，外层保存单据与本次占用共用事务。 */
    public void occupy(CompanyAccess scope,long lineId,BigDecimal quantity) {
        InventoryNumbers.quantity(quantity);
        store.lockOrderLine(scope,lineId);
        int changed=jdbc.update("""
                UPDATE sales_order_line SET pending_ship_base_qty=pending_ship_base_qty+?,version=version+1
                WHERE tenant_id=? AND company_id=? AND id=? AND base_qty-closed_base_qty-shipped_base_qty-pending_ship_base_qty>=?
                """,quantity,scope.tenantId(),scope.companyId(),lineId,quantity);
        if(changed!=1) {throw exceeded();}
    }

    public void release(CompanyAccess scope,long lineId,BigDecimal quantity) {
        change(scope,lineId,quantity,false);
    }

    /** 发货过账时把待发占用转为已发，外层事务同时调用库存出库。 */
    public void complete(CompanyAccess scope,long lineId,BigDecimal quantity) {
        change(scope,lineId,quantity,true);
    }

    private void change(CompanyAccess scope,long lineId,BigDecimal quantity,boolean complete) {
        InventoryNumbers.quantity(quantity);
        store.lockOrderLine(scope,lineId);
        int changed=jdbc.update("""
                UPDATE sales_order_line SET pending_ship_base_qty=pending_ship_base_qty-?,
                    shipped_base_qty=shipped_base_qty+?,version=version+1
                WHERE tenant_id=? AND company_id=? AND id=? AND pending_ship_base_qty>=?
                """,quantity,complete?quantity:BigDecimal.ZERO,scope.tenantId(),scope.companyId(),lineId,quantity);
        if(changed!=1) {throw exceeded();}
    }

    private static ApiException exceeded() {return new ApiException(422,"SHIP_QTY_EXCEEDED","发货数量超过订单行剩余数量");}
}
