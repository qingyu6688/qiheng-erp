package cn.erp.fulfillment;

import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryService;
import cn.erp.shared.ApiException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.fulfillment.ShipmentValues.*;

@Service
@Transactional(readOnly=true)
public class ShipmentQueries {
    private final JdbcTemplate jdbc;
    private final InventoryService inventory;
    public ShipmentQueries(JdbcTemplate jdbc,InventoryService inventory) {this.jdbc=jdbc;this.inventory=inventory;}

    public Map<String,Object> header(CompanyAccess scope,long id,boolean lock) {
        cn.erp.shared.OrgOwnership.require(jdbc,scope,"shipment",id);
        var rows=jdbc.queryForList("SELECT * FROM shipment WHERE tenant_id=? AND company_id=?"+cn.erp.shared.OrgOwnership.filter(scope,"shipment")+" AND id=?"+(lock?" FOR UPDATE":""),
                scope.tenantId(),scope.companyId(),id);
        if(rows.isEmpty() || !scope.allowsWarehouse(id(rows.getFirst(),"warehouse_id"))) {throw missing();}
        return rows.getFirst();
    }

    record LineReference(long id,long orderLineId) {}

    List<LineReference> lineReferences(CompanyAccess scope,long shipment) {
        return jdbc.query("""
                SELECT id,order_line_id FROM shipment_line
                WHERE tenant_id=? AND company_id=? AND shipment_id=?
                ORDER BY order_line_id,id
                """,(row,index)->new LineReference(row.getLong("id"),row.getLong("order_line_id")),
                scope.tenantId(),scope.companyId(),shipment);
    }

    List<Map<String,Object>> lockLines(CompanyAccess scope,long shipment,List<LineReference> references) {
        if(references.isEmpty()) {return List.of();}
        var args=new ArrayList<Object>(List.of(scope.tenantId(),scope.companyId(),shipment));
        references.forEach(reference->args.add(reference.id()));
        String ids=String.join(",",Collections.nCopies(references.size(),"?"));
        // 单头锁保护明细集合，按已有明细的唯一键加锁，避免锁住其他发货单的索引区间。
        return jdbc.queryForList("""
                SELECT * FROM shipment_line FORCE INDEX (uk_shipment_line_scope)
                WHERE tenant_id=? AND company_id=? AND shipment_id=? AND id IN (%s)
                ORDER BY order_line_id,id FOR UPDATE
                """.formatted(ids),args.toArray());
    }

    private List<Map<String,Object>> detailLines(CompanyAccess scope,long shipment) {
        return jdbc.queryForList("""
                SELECT l.*,s.name AS sku_name,u.name AS unit_name,h.order_no FROM shipment_line l
                JOIN sku s ON s.tenant_id=l.tenant_id AND s.id=l.sku_id
                JOIN unit u ON u.tenant_id=l.tenant_id AND u.id=l.unit_id
                JOIN sales_order_line ol ON ol.tenant_id=l.tenant_id AND ol.company_id=l.company_id AND ol.id=l.order_line_id
                JOIN sales_order h ON h.tenant_id=ol.tenant_id AND h.company_id=ol.company_id AND h.id=ol.order_id
                WHERE l.tenant_id=? AND l.company_id=? AND l.shipment_id=?
                ORDER BY l.order_line_id,l.id
                """,scope.tenantId(),scope.companyId(),shipment);
    }

    public Map<String,Object> get(CompanyAccess scope,long id) {
        var header=header(scope,id,false);
        var result=new LinkedHashMap<String,Object>();
        put(result,header,"id","customer_id","warehouse_id","delivery_method","business_date","status","version","remark","sign_date","sales_user_id",
                "receivable_amount","untaxed_amount","tax_amount");
        result.put("documentNo",header.get("shipment_no"));
        result.put("customerName",jdbc.queryForObject("SELECT name FROM business_partner WHERE tenant_id=? AND id=?",String.class,scope.tenantId(),header.get("customer_id")));
        result.put("warehouseName",jdbc.queryForObject("SELECT name FROM warehouse WHERE tenant_id=? AND company_id=? AND id=?",String.class,scope.tenantId(),scope.companyId(),header.get("warehouse_id")));
        if(scope.hasPermission("cost:view")) {put(result,header,"cost_amount","signed_cost_amount");}
        result.put("lines",detailLines(scope,id).stream().map(row->lineView(scope,row)).toList());
        result.put("recognizedFees",jdbc.queryForList("SELECT id AS fee_id,total_amount AS amount,untaxed_amount,tax_amount FROM sales_order_fee WHERE tenant_id=? AND company_id=? AND recognized_shipment_id=? ORDER BY id",
                scope.tenantId(),scope.companyId(),id).stream().map(row->{var fee=new LinkedHashMap<String,Object>();put(fee,row,"fee_id","amount","untaxed_amount","tax_amount");return fee;}).toList());
        result.put("autoAllocations",jdbc.queryForList("""
                SELECT a.id AS allocation_id,r.receipt_no,a.amount FROM settlement_allocation a
                JOIN partner_open_item d ON d.tenant_id=a.tenant_id AND d.company_id=a.company_id AND d.id=a.debit_item_id AND d.item_type='receivable'
                JOIN receivable ar ON ar.tenant_id=d.tenant_id AND ar.company_id=d.company_id AND ar.id=d.source_id
                JOIN partner_open_item c ON c.tenant_id=a.tenant_id AND c.company_id=a.company_id AND c.id=a.credit_item_id AND c.item_type='receipt'
                JOIN receipt r ON r.tenant_id=c.tenant_id AND r.company_id=c.company_id AND r.id=c.source_id
                WHERE a.tenant_id=? AND a.company_id=? AND a.allocation_type='auto_order' AND ar.source_id=?
                  AND ar.source_type IN ('shipment_line','sales_order_fee') %s ORDER BY a.id
                """.formatted(cn.erp.shared.OrgOwnership.filter(scope,"r")),scope.tenantId(),scope.companyId(),id).stream().map(row->{var allocation=new LinkedHashMap<String,Object>();put(allocation,row,"allocation_id","receipt_no","amount");return allocation;}).toList());
        result.put("allowedActions",actions(scope,header.get("status").toString()));
        cn.erp.shared.BusinessPeople.enrich(jdbc,scope,result);return result;
    }

    private Map<String,Object> lineView(CompanyAccess scope,Map<String,Object> row) {
        var line=new LinkedHashMap<String,Object>();
        put(line,row,"id","order_line_id","sku_id","unit_id","unit_rate","qty","base_qty","receivable_amount","untaxed_amount","tax_amount","diff_disposition",
                "signed_base_qty","returned_base_qty","pending_return_base_qty","credited_amount","credited_tax");
        line.put("signedQty",decimal(row,"signed_base_qty").toPlainString());
        line.put("diffQty",decimal(row,"diff_base_qty").toPlainString());
        line.put("returnableQty",decimal(row,"signed_base_qty").subtract(decimal(row,"returned_base_qty")).subtract(decimal(row,"pending_return_base_qty")).toPlainString());
        line.put("stockSelection",inventory.viewSelection(row.get("stock_selection")));
        var signed=inventory.decodeSelection(row.get("signed_serials"));
        if(signed!=null)line.put("signedSerials",signed.serials()==null?List.of():signed.serials());
        line.put("skuName",row.containsKey("sku_name")?row.get("sku_name"):jdbc.queryForObject("SELECT name FROM sku WHERE tenant_id=? AND id=?",String.class,scope.tenantId(),row.get("sku_id")));
        line.put("unitName",row.containsKey("unit_name")?row.get("unit_name"):jdbc.queryForObject("SELECT name FROM unit WHERE tenant_id=? AND id=?",String.class,scope.tenantId(),row.get("unit_id")));
        line.put("orderNo",row.containsKey("order_no")?row.get("order_no"):jdbc.queryForObject("SELECT h.order_no FROM sales_order_line l JOIN sales_order h ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.order_id WHERE l.tenant_id=? AND l.company_id=? AND l.id=?",String.class,scope.tenantId(),scope.companyId(),row.get("order_line_id")));
        if(scope.hasPermission("cost:view")) {put(line,row,"unit_cost","cost_amount","signed_cost_amount","diff_cost_amount");}
        return line;
    }

    public Map<String,Object> list(CompanyAccess scope,String status,boolean overdue,Long customer,String cursor,int limit) {
        long before=cursor(cursor,limit);
        if(status!=null && !Set.of("draft","submitted","posted","signed","signed_with_diff","voided","reversed").contains(status)) {throw invalid("发货状态无效");}
        String sql="SELECT id,warehouse_id FROM shipment WHERE tenant_id=? AND company_id=? AND id<?"+cn.erp.shared.OrgOwnership.filter(scope,"shipment");
        var params=new ArrayList<Object>(List.of(scope.tenantId(),scope.companyId(),before));
        if(status!=null) {sql+=" AND status=?";params.add(status);}
        if(customer!=null) {sql+=" AND customer_id=?";params.add(customer);}
        if(overdue) {sql+=" AND status='posted' AND business_date<DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00')),INTERVAL 7 DAY)";}
        if(!scope.allWarehouses()) {
            if(scope.warehouseIds().isEmpty()) {return page(List.of(),null);}
            sql+=" AND warehouse_id IN ("+String.join(",",Collections.nCopies(scope.warehouseIds().size(),"?"))+")";
            params.addAll(scope.warehouseIds());
        }
        params.add(limit+1);
        var rows=jdbc.queryForList(sql+" ORDER BY id DESC LIMIT ?",params.toArray());
        var items=rows.stream().limit(limit).map(row->get(scope,id(row,"id"))).toList();
        return page(items,rows.size()>limit?items.getLast().get("id"):null);
    }

    public Map<String,Object> returnable(CompanyAccess scope,Long customer,boolean onlyReturnable,String cursor,int limit) {
        long before=cursor(cursor,limit);
        String sql="""
                SELECT l.id,h.warehouse_id FROM shipment_line l JOIN shipment h ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.shipment_id
                WHERE l.tenant_id=? AND l.company_id=? AND l.id<? AND h.status IN ('signed','signed_with_diff')
                """;
        sql+=cn.erp.shared.OrgOwnership.filter(scope,"h");
        var params=new ArrayList<Object>(List.of(scope.tenantId(),scope.companyId(),before));
        if(customer!=null) {sql+=" AND h.customer_id=?";params.add(customer);}
        if(onlyReturnable) {sql+=" AND l.signed_base_qty-l.returned_base_qty-l.pending_return_base_qty>0";}
        if(!scope.allWarehouses()) {
            if(scope.warehouseIds().isEmpty()) {return page(List.of(),null);}
            sql+=" AND h.warehouse_id IN ("+String.join(",",Collections.nCopies(scope.warehouseIds().size(),"?"))+")";
            params.addAll(scope.warehouseIds());
        }
        params.add(limit+1);
        var rows=jdbc.queryForList(sql+" ORDER BY l.id DESC LIMIT ?",params.toArray());
        var items=rows.stream().limit(limit).map(row->{
            var source=jdbc.queryForMap("SELECT * FROM shipment_line WHERE tenant_id=? AND company_id=? AND id=?",scope.tenantId(),scope.companyId(),row.get("id"));
            var item=lineView(scope,source);
            var h=header(scope,id(source,"shipment_id"),false);
            item.put("shipmentId",h.get("id").toString());item.put("documentNo",h.get("shipment_no"));
            item.put("customerId",h.get("customer_id").toString());item.put("warehouseId",h.get("warehouse_id").toString());
            return item;
        }).toList();
        return page(items,rows.size()>limit?items.getLast().get("id"):null);
    }

    List<String> actions(CompanyAccess scope,String status) {
        var result=new ArrayList<String>();
        if(status.equals("draft")) {if(scope.hasPermission("shipment:update")) {result.add("update");} add(result,scope,"submit");}
        if(status.equals("submitted")) {add(result,scope,"post");add(result,scope,"void");}
        if(status.equals("posted")) {add(result,scope,"sign");}
        if(status.equals("signed_with_diff")) {add(result,scope,"resolve_diff");}
        return List.copyOf(result);
    }
    private void add(List<String> actions,CompanyAccess scope,String action) {if(scope.hasPermission("shipment:"+action)) {actions.add(action);}}

    static Map<String,Object> page(Object items,Object cursor) {
        var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("nextCursor",cursor);return result;
    }
    static long cursor(String cursor,int limit) {
        if(limit<1 || limit>200) {throw invalid("每页条数须在1到200之间");}
        if(cursor==null || cursor.isBlank()) {return Long.MAX_VALUE;}
        try {long id=Long.parseLong(cursor);if(id<=0) {throw invalid("分页游标无效");}return id;}
        catch(NumberFormatException error) {throw invalid("分页游标无效");}
    }
    static void put(Map<String,Object> target,Map<String,Object> source,String... keys) {
        for(String key:keys) {
            Object value=source.get(key);String field=camel(key);
            if(value instanceof java.math.BigDecimal decimal) {value=decimal.toPlainString();}
            if(value instanceof java.sql.Date date) {value=date.toLocalDate().toString();}
            if(value instanceof java.time.LocalDate date) {value=date.toString();}
            if(key.equals("id") || key.endsWith("_id")) {value=value==null?null:value.toString();}
            target.put(field,value);
        }
    }
    private static String camel(String text) {
        StringBuilder result=new StringBuilder();boolean upper=false;
        for(char c:text.toCharArray()) {if(c=='_') {upper=true;} else {result.append(upper?Character.toUpperCase(c):c);upper=false;}}
        return result.toString();
    }
}
