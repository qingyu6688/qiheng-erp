package cn.erp.inventory;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import java.util.*;
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

    public void occupyAll(CompanyAccess scope,Map<Long,BigDecimal> quantities) {changeAll(scope,quantities,false);}
    public void completeAll(CompanyAccess scope,Map<Long,BigDecimal> quantities) {changeAll(scope,quantities,true);}

    private void changeAll(CompanyAccess scope,Map<Long,BigDecimal> quantities,boolean complete) {
        if(quantities.isEmpty() || quantities.size()>500) {throw new ApiException(400,"VALIDATION_FAILED","发货占用明细无效");}
        quantities.values().forEach(InventoryNumbers::quantity);
        var args=new ArrayList<Object>(List.of(scope.tenantId(),scope.companyId()));args.addAll(new TreeSet<>(quantities.keySet()));
        var lines=jdbc.queryForList("SELECT id,warehouse_id,base_qty,closed_base_qty,shipped_base_qty,pending_ship_base_qty FROM sales_order_line FORCE INDEX(uk_sales_order_line_scope) WHERE tenant_id=? AND company_id=? AND id IN ("+String.join(",",Collections.nCopies(quantities.size(),"?"))+") ORDER BY id FOR UPDATE",args.toArray());
        if(lines.size()!=quantities.size() || lines.stream().anyMatch(row->row.get("warehouse_id")==null || !scope.allowsWarehouse(((Number)row.get("warehouse_id")).longValue()))) {throw InventoryStore.missing();}
        for(var line:lines) checkShipmentQuantity(line,quantities.get(((Number)line.get("id")).longValue()),complete);
        String sql=complete?"UPDATE sales_order_line SET pending_ship_base_qty=pending_ship_base_qty-?,shipped_base_qty=shipped_base_qty+?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=? AND pending_ship_base_qty>=?":"UPDATE sales_order_line SET pending_ship_base_qty=pending_ship_base_qty+?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=? AND base_qty-closed_base_qty-shipped_base_qty-pending_ship_base_qty>=?";
        var updates=new ArrayList<Object[]>();
        new TreeMap<>(quantities).forEach((id,qty)->updates.add(complete?new Object[]{qty,qty,scope.tenantId(),scope.companyId(),id,qty}:new Object[]{qty,scope.tenantId(),scope.companyId(),id,qty}));
        for(int changed:jdbc.batchUpdate(sql,updates)) {if(changed!=1) {throw exceeded();}}
    }

    public static BigDecimal remaining(Map<String,Object> line) {
        return InventoryStore.decimal(line,"base_qty").subtract(InventoryStore.decimal(line,"closed_base_qty"))
                .subtract(InventoryStore.decimal(line,"shipped_base_qty")).subtract(InventoryStore.decimal(line,"pending_ship_base_qty"));
    }

    public static void checkShipmentQuantity(Map<String,Object> line,BigDecimal quantity,boolean complete) {
        var available=complete?InventoryStore.decimal(line,"pending_ship_base_qty"):remaining(line);
        if(available.compareTo(quantity)<0) throw exceeded();
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
