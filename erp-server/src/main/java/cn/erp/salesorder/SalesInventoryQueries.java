package cn.erp.salesorder;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static cn.erp.salesorder.SalesOrderQueries.*;

@Service
@org.springframework.transaction.annotation.Transactional(readOnly=true)
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
        filter+=cn.erp.shared.OrgOwnership.filter(scope,"h");args.add(limit);
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
        return balances(scope,warehouse,keyword,cursor,limit,false,false);
    }
    public Map<String,Object> balances(CompanyAccess scope,String warehouse,String keyword,String cursor,int limit,boolean shortageOnly,boolean hideZero) {
        if(limit<1||limit>200||keyword.length()>100)throw invalid("库存查询条件无效");
        if(warehouse!=null&&!scope.allowsWarehouse(identifier(warehouse)))throw missing();
        var args=new LinkedHashMap<String,Object>();args.put("t",scope.tenantId());args.put("c",scope.companyId());args.put("k",keyword);
        String filter=" WHERE (LOCATE(:k,s.code)>0 OR LOCATE(:k,s.name)>0 OR EXISTS(SELECT 1 FROM sku_barcode bc WHERE bc.tenant_id=:t AND bc.sku_id=s.id AND bc.barcode=:k))";
        if(warehouse!=null){filter+=" AND v.warehouse_id=:w";args.put("w",identifier(warehouse));}
        if(!scope.allWarehouses()) {
            filter+=" AND v.warehouse_id IN (:warehouses)";args.put("warehouses",scope.warehouseIds().isEmpty()?List.of(-1L):scope.warehouseIds());
        }
        if(shortageOnly)filter+=" AND v.pending_demand_qty>v.available_qty";
        if(hideZero)filter+=" AND (v.on_hand_qty<>0 OR v.reserved_qty<>0 OR v.blocked_qty<>0 OR v.pending_demand_qty<>0)";
        String base=BALANCES.formatted(cn.erp.shared.OrgOwnership.filter(scope,"h"))+" FROM v JOIN warehouse w ON w.id=v.warehouse_id AND w.tenant_id=:t JOIN sku s ON s.id=v.sku_id AND s.tenant_id=:t";
        var named=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
        var rawSummary=named.queryForMap(base.replace("SELECT v.*,w.code warehouse_code,w.name warehouse_name,s.code sku_code,s.name sku_name", "SELECT COALESCE(SUM(v.cost_amount),0) cost_amount,COALESCE(SUM(v.blocked_qty),0) blocked_qty,COUNT(DISTINCT IF(v.pending_demand_qty>v.available_qty,v.sku_id,NULL)) shortage_count")+filter,args);
        var summary=camel(rawSummary);if(!scope.permissions().contains("cost:view"))summary.remove("costAmount");
        if(scope.permissions().contains("report:unsigned_shipments")) {
            String unsigned="SELECT COUNT(DISTINCT h.id) unreceived_count,COALESCE(SUM(l.cost_amount-COALESCE(l.signed_cost_amount,0)-IF(l.diff_disposition IN ('returned','lost'),COALESCE(l.diff_cost_amount,0),0)),0) unreceived_cost_amount FROM shipment_line l JOIN shipment h ON h.id=l.shipment_id AND h.tenant_id=l.tenant_id AND h.company_id=l.company_id JOIN sku s ON s.id=l.sku_id WHERE h.tenant_id=:t AND h.company_id=:c AND h.status IN ('posted','signed','signed_with_diff') AND l.base_qty-l.signed_base_qty-IF(l.diff_disposition IN ('returned','lost'),l.diff_base_qty,0)>0 AND (LOCATE(:k,s.code)>0 OR LOCATE(:k,s.name)>0 OR EXISTS(SELECT 1 FROM sku_barcode bc WHERE bc.tenant_id=:t AND bc.sku_id=s.id AND bc.barcode=:k))";
            if(warehouse!=null)unsigned+=" AND h.warehouse_id=:w";
            if(!scope.allWarehouses())unsigned+=" AND h.warehouse_id IN (:warehouses)";
            unsigned+=cn.erp.shared.OrgOwnership.filter(scope,"h");
            summary.putAll(camel(named.queryForMap(unsigned,args)));if(!scope.permissions().contains("cost:view"))summary.remove("unreceivedCostAmount");
        }
        String pageFilter=filter;
        if(cursor!=null&&!cursor.isBlank()) {
            String[] parts=cursor.split(":");if(parts.length!=2)throw invalid("库存游标无效");
            args.put("lastWarehouse",identifier(parts[0]));args.put("lastSku",identifier(parts[1]));
            pageFilter+=" AND (v.warehouse_id>:lastWarehouse OR (v.warehouse_id=:lastWarehouse AND v.sku_id>:lastSku))";
        }
        args.put("limit",limit+1);
        var rows=named.queryForList(base+pageFilter+" ORDER BY v.warehouse_id,v.sku_id LIMIT :limit",args);
        var items=rows.stream().limit(limit).map(raw->{var row=camel(raw);row.put("id",raw.get("warehouse_id")+":"+raw.get("sku_id"));if(!scope.permissions().contains("cost:view")){row.remove("costAmount");row.remove("unitCost");}return row;}).toList();
        var result=page(items,rows.size()>limit?items.getLast().get("id").toString():null);result.put("summary",summary);result.put("asOf",java.time.Instant.now().toString());return result;
    }
    private static final String BALANCES="""
        WITH balances AS (SELECT warehouse_id,sku_id,SUM(on_hand_qty) on_hand_qty,SUM(reserved_qty) reserved_qty,SUM(blocked_qty) blocked_qty
          FROM inventory_balance WHERE tenant_id=:t AND company_id=:c AND location_key='' AND lot_key='' GROUP BY warehouse_id,sku_id),
        demand AS (SELECT l.warehouse_id,l.sku_id,SUM(l.base_qty) pending_demand_qty FROM sales_order_line l
          JOIN sales_order h ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.order_id
          WHERE l.tenant_id=:t AND l.company_id=:c AND l.is_deleted=0 AND h.is_deleted=0 AND h.status='pending' AND l.warehouse_id IS NOT NULL %s GROUP BY l.warehouse_id,l.sku_id),
        k AS (SELECT warehouse_id,sku_id FROM balances UNION SELECT warehouse_id,sku_id FROM demand UNION SELECT warehouse_id,sku_id FROM inventory_cost WHERE tenant_id=:t AND company_id=:c),
        v AS (SELECT k.warehouse_id,k.sku_id,COALESCE(b.on_hand_qty,0) on_hand_qty,COALESCE(b.reserved_qty,0) reserved_qty,COALESCE(b.blocked_qty,0) blocked_qty,
          COALESCE(b.on_hand_qty,0)-COALESCE(b.reserved_qty,0)-COALESCE(b.blocked_qty,0) available_qty,COALESCE(d.pending_demand_qty,0) pending_demand_qty,
          COALESCE(c.total_cost,0) cost_amount,IF(b.on_hand_qty>0,ROUND(CAST(c.total_cost AS DECIMAL(36,12))/b.on_hand_qty,8),NULL) unit_cost
          FROM k LEFT JOIN balances b USING(warehouse_id,sku_id) LEFT JOIN demand d USING(warehouse_id,sku_id)
          LEFT JOIN inventory_cost c ON c.tenant_id=:t AND c.company_id=:c AND c.warehouse_id=k.warehouse_id AND c.sku_id=k.sku_id)
        SELECT v.*,w.code warehouse_code,w.name warehouse_name,s.code sku_code,s.name sku_name
        """;
}
