package cn.erp.salesorder;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static cn.erp.salesorder.SalesOrderQueries.*;

@Service
public class SalesInventoryQueries {
    private final JdbcTemplate jdbc;
    public SalesInventoryQueries(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    public Map<String,Object> orderLines(CompanyAccess scope,String customerId,String warehouseId,boolean shippable,int limit) {
        if(limit<1||limit>200) {throw invalid("每页条数须在1到200之间");}
        var args=new ArrayList<Object>(List.of(scope.tenantId(),scope.companyId()));
        String filter=" WHERE l.tenant_id=? AND l.company_id=? AND l.is_deleted=0 AND h.is_deleted=0 AND h.status IN ('approved','closed')";
        if(customerId!=null) {filter+=" AND h.customer_id=?";args.add(identifier(customerId));}
        if(warehouseId!=null) {filter+=" AND l.warehouse_id=?";args.add(identifier(warehouseId));}
        if(shippable) {filter+=" AND l.base_qty-l.closed_base_qty-l.shipped_base_qty-l.pending_ship_base_qty>0";}
        if(!scope.allWarehouses()) {
            if(scope.warehouseIds().isEmpty()) {return page(List.of(),null);}
            filter+=" AND l.warehouse_id IN ("+String.join(",",Collections.nCopies(scope.warehouseIds().size(),"?"))+")";args.addAll(scope.warehouseIds());
        }
        args.add(limit);
        var rows=jdbc.queryForList("""
            SELECT l.id,l.order_id,h.order_no,h.customer_id,l.sku_id,l.warehouse_id,l.unit_id,l.unit_rate,l.qty,l.base_qty,
            s.code sku_code,s.name sku_name,u.name unit_name,
            l.base_qty-l.closed_base_qty-l.shipped_base_qty-l.pending_ship_base_qty shippable_qty
            FROM sales_order_line l JOIN sales_order h ON h.id=l.order_id AND h.tenant_id=l.tenant_id AND h.company_id=l.company_id
            JOIN sku s ON s.id=l.sku_id AND s.tenant_id=l.tenant_id JOIN unit u ON u.id=l.unit_id AND u.tenant_id=l.tenant_id
            """+filter+" ORDER BY h.id DESC,l.line_no LIMIT ?",args.toArray());
        return page(rows.stream().map(raw->{var row=camel(raw);row.put("label",raw.get("order_no")+" / "+raw.get("sku_name")+" / 可发 "+raw.get("shippable_qty"));return row;}).toList(),null);
    }

    public Map<String,Object> balances(CompanyAccess scope,String warehouse,String keyword,String cursor,int limit) {
        if(limit<1||limit>200||keyword.length()>100) {throw invalid("库存查询条件无效");}
        if(warehouse!=null&&!scope.allowsWarehouse(identifier(warehouse))) {throw missing();}
        var args=new ArrayList<Object>(List.of(scope.tenantId(),scope.companyId(),scope.tenantId(),scope.companyId()));
        String filter=" WHERE (LOCATE(?,s.code)>0 OR LOCATE(?,s.name)>0)";args.add(keyword);args.add(keyword);
        if(warehouse!=null) {filter+=" AND k.warehouse_id=?";args.add(identifier(warehouse));}
        if(!scope.allWarehouses()) {
            if(scope.warehouseIds().isEmpty()) {return page(List.of(),null);}
            filter+=" AND k.warehouse_id IN ("+String.join(",",Collections.nCopies(scope.warehouseIds().size(),"?"))+")";args.addAll(scope.warehouseIds());
        }
        if(cursor!=null&&!cursor.isBlank()) {
            String[] parts=cursor.split(":");if(parts.length!=2) {throw invalid("库存游标无效");}
            long lastWarehouse=identifier(parts[0]),lastSku=identifier(parts[1]);
            filter+=" AND (k.warehouse_id>? OR (k.warehouse_id=? AND k.sku_id>?))";
            args.add(lastWarehouse);args.add(lastWarehouse);args.add(lastSku);
        }
        args.add(limit+1);
        var rows=jdbc.queryForList("""
            WITH balances AS (SELECT warehouse_id,sku_id,SUM(on_hand_qty) on_hand_qty,SUM(reserved_qty) reserved_qty,SUM(blocked_qty) blocked_qty
              FROM inventory_balance WHERE tenant_id=? AND company_id=? GROUP BY warehouse_id,sku_id),
            demand AS (SELECT l.warehouse_id,l.sku_id,SUM(l.base_qty) pending_demand_qty FROM sales_order_line l
              JOIN sales_order h ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.order_id
              WHERE l.tenant_id=? AND l.company_id=? AND l.is_deleted=0 AND h.is_deleted=0 AND h.status='pending'
                AND l.warehouse_id IS NOT NULL GROUP BY l.warehouse_id,l.sku_id),
            k AS (SELECT warehouse_id,sku_id FROM balances UNION SELECT warehouse_id,sku_id FROM demand)
            SELECT k.warehouse_id,k.sku_id,w.code warehouse_code,w.name warehouse_name,s.code sku_code,s.name sku_name,
              COALESCE(b.on_hand_qty,0) on_hand_qty,COALESCE(b.reserved_qty,0) reserved_qty,COALESCE(b.blocked_qty,0) blocked_qty,
              COALESCE(b.on_hand_qty,0)-COALESCE(b.reserved_qty,0)-COALESCE(b.blocked_qty,0) available_qty,COALESCE(d.pending_demand_qty,0) pending_demand_qty
            FROM k LEFT JOIN balances b USING(warehouse_id,sku_id) LEFT JOIN demand d USING(warehouse_id,sku_id)
            JOIN warehouse w ON w.id=k.warehouse_id JOIN sku s ON s.id=k.sku_id
            """+filter+" ORDER BY k.warehouse_id,k.sku_id LIMIT ?",args.toArray());
        var items=rows.stream().limit(limit).map(raw->{var row=camel(raw);row.put("id",raw.get("warehouse_id")+":"+raw.get("sku_id"));return row;}).toList();
        return page(items,rows.size()>limit?items.getLast().get("id").toString():null);
    }
}
