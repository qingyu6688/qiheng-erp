package cn.erp.shared;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 幂等结果同样遵守当前仓库范围，避免授权收窄后从历史响应读取单据。 */
@Component
public class CommandResultAccess {
    private final JdbcTemplate jdbc;
    public CommandResultAccess(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    public void verify(ActorScope actor,String operation,Object response) {
        if(!(actor instanceof WarehouseScope scope) || !(response instanceof Map<?,?> row)) {return;}
        if(java.util.Set.of("period.close","period.reopen").contains(operation)&&!scope.allWarehouses()) {throw new ApiException(403,"FORBIDDEN","期间操作结果需要整家公司数据范围");}
        if(operation.startsWith("reversal.")) {
            String type=String.valueOf(row.get("sourceType"));
            if(!(actor instanceof PermissionScope permissions)||!permissions.hasPermission(type+":view")) {throw new ApiException(403,"FORBIDDEN","没有查看冲销来源单据的权限");}
            if(!scope.allWarehouses()) {
                if(java.util.Set.of("shipment","sales_return","goods_receipt","purchase_return","stock_adjustment").contains(type)) {
                    var values=jdbc.queryForList("SELECT warehouse_id FROM "+type+" WHERE tenant_id=? AND company_id=? AND id=?",Long.class,scope.tenantId(),scope.companyId(),row.get("sourceId"));
                    if(values.isEmpty()) {throw missing();}warehouse(scope,values.getFirst());
                }
                if(row.get("warehouseIds") instanceof List<?> values) {for(Object value:values) {warehouse(scope,value);}}
            }
            return;
        }
        if(scope.allWarehouses()) {return;}
        Object id=row.get("id");
        if(id==null) {return;}
        if(operation.startsWith("sales_order.")||operation.startsWith("purchase_order.")) {
            String lineTable=operation.startsWith("sales_order.")?"sales_order_line":"purchase_order_line";
            var warehouses=jdbc.queryForList("SELECT warehouse_id FROM "+lineTable+" WHERE tenant_id=? AND company_id=? AND order_id=? AND is_deleted=0",Long.class,scope.tenantId(),scope.companyId(),id);
            if(warehouses.isEmpty() || warehouses.stream().anyMatch(w->w==null||!scope.allowsWarehouse(w))) {throw missing();}
            if(row.get("lines") instanceof List<?> lines) {
                for(Object line:lines) {if(line instanceof Map<?,?> fields) {warehouse(scope,fields.get("warehouseId"));}}
            }
        } else if(operation.startsWith("stock_transfer.")) {
            var values=jdbc.queryForList("SELECT from_warehouse_id,to_warehouse_id FROM stock_transfer WHERE tenant_id=? AND company_id=? AND id=?",scope.tenantId(),scope.companyId(),id);
            if(values.isEmpty()) {throw missing();}
            warehouse(scope,values.getFirst().get("from_warehouse_id"));warehouse(scope,values.getFirst().get("to_warehouse_id"));
            warehouse(scope,row.get("fromWarehouseId"));warehouse(scope,row.get("toWarehouseId"));
        } else if(operation.startsWith("shipment.") || operation.startsWith("sales_return.") || operation.startsWith("goods_receipt.") || operation.startsWith("purchase_return.") || operation.startsWith("stock_adjustment.") || operation.startsWith("stock_count.")) {
            String table=operation.substring(0,operation.indexOf('.'));
            var warehouses=jdbc.queryForList("SELECT warehouse_id FROM "+table+" WHERE tenant_id=? AND company_id=? AND id=?",Long.class,scope.tenantId(),scope.companyId(),id);
            if(warehouses.isEmpty()) {throw missing();}
            warehouse(scope,warehouses.getFirst());
            if(row.containsKey("warehouseId")) {warehouse(scope,row.get("warehouseId"));}
        }
    }

    private void warehouse(WarehouseScope scope,Object id) {
        if(id==null || !scope.allowsWarehouse(Long.parseLong(id.toString()))) {throw missing();}
    }
    private ApiException missing() {return new ApiException(404,"NOT_FOUND","单据不存在或无权查看");}
}
