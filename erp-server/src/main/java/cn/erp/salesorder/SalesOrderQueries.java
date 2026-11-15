package cn.erp.salesorder;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class SalesOrderQueries {
    private final JdbcTemplate jdbc;
    public SalesOrderQueries(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    public Map<String,Object> header(CompanyAccess scope,long id,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM sales_order WHERE tenant_id=? AND company_id=? AND id=? AND is_deleted=0"+(lock?" FOR UPDATE":""),scope.tenantId(),scope.companyId(),id);
        if(rows.isEmpty()) {throw missing();}
        visible(scope,id);
        return rows.getFirst();
    }

    public void visible(CompanyAccess scope,long id) {
        if(scope.allWarehouses()) {return;}
        var warehouses=jdbc.queryForList("SELECT warehouse_id FROM sales_order_line WHERE tenant_id=? AND company_id=? AND order_id=? AND is_deleted=0",Long.class,scope.tenantId(),scope.companyId(),id);
        if(warehouses.isEmpty() || warehouses.stream().anyMatch(w->w==null || !scope.allowsWarehouse(w))) {throw missing();}
    }

    public List<Map<String,Object>> lines(CompanyAccess scope,long id,boolean lock) {
        return jdbc.queryForList("SELECT * FROM sales_order_line WHERE tenant_id=? AND company_id=? AND order_id=? AND is_deleted=0 ORDER BY id"+(lock?" FOR UPDATE":""),scope.tenantId(),scope.companyId(),id);
    }

    public Map<String,Object> get(CompanyAccess scope,long id) {
        var raw=header(scope,id,false);
        var result=camel(raw);
        result.put("documentNo",raw.get("order_no"));
        result.put("customer",reference("business_partner",scope,number(raw,"customer_id")));
        result.put("lines",lines(scope,id,false).stream().map(line->lineView(scope,line)).toList());
        result.put("fees",jdbc.queryForList("SELECT * FROM sales_order_fee WHERE tenant_id=? AND company_id=? AND order_id=? ORDER BY id",scope.tenantId(),scope.companyId(),id).stream().map(fee->{
            var row=camel(fee);row.put("recognized",fee.get("recognized_shipment_id")!=null);return row;
        }).toList());
        result.put("allowedActions",actions(scope,raw));
        var changes=jdbc.queryForList("SELECT id,change_no,status,version,from_revision,to_revision FROM document_change WHERE tenant_id=? AND company_id=? AND document_type='sales_order' AND document_id=? ORDER BY id DESC LIMIT 1",scope.tenantId(),scope.companyId(),id);
        if(!changes.isEmpty()) {result.put("change",camel(changes.getFirst()));}
        return result;
    }

    private Map<String,Object> lineView(CompanyAccess scope,Map<String,Object> line) {
        var result=camel(line);
        result.put("sku",reference("sku",scope,number(line,"sku_id")));
        result.put("unit",reference("unit",scope,number(line,"unit_id")));
        result.put("warehouse",line.get("warehouse_id")==null?null:reference("warehouse",scope,number(line,"warehouse_id")));
        result.put("lineDiscountAmount",decimal(line,"line_discount").toPlainString());
        var reservations=jdbc.queryForList("SELECT status,remaining_qty FROM inventory_reservation WHERE tenant_id=? AND company_id=? AND order_line_id=? ORDER BY id DESC LIMIT 1",scope.tenantId(),scope.companyId(),number(line,"id"));
        String reservationState=reservations.isEmpty()?"none":reservations.getFirst().get("status").toString();
        result.put("reservationState",reservationState.equals("active")?"reserved":reservationState);
        result.put("reservedQty",reservationState.equals("active")?decimal(reservations.getFirst(),"remaining_qty").toPlainString():"0.000000");
        result.put("progress",Map.of("baseQty",decimal(line,"base_qty").toPlainString(),"closedQty",decimal(line,"closed_base_qty").toPlainString(),
            "shippedQty",decimal(line,"shipped_base_qty").toPlainString(),"signedQty",decimal(line,"signed_base_qty").toPlainString(),
            "pendingShipQty",decimal(line,"pending_ship_base_qty").toPlainString(),"returnedQty",decimal(line,"returned_base_qty").toPlainString(),
            "shippableQty",decimal(line,"base_qty").subtract(decimal(line,"closed_base_qty")).subtract(decimal(line,"shipped_base_qty")).subtract(decimal(line,"pending_ship_base_qty")).toPlainString()));
        return result;
    }

    private Map<String,Object> reference(String table,CompanyAccess scope,long id) {
        String columns=table.equals("unit")?"id,name":"id,code,name";
        var rows=jdbc.queryForList("SELECT "+columns+" FROM "+table+" WHERE tenant_id=? AND id=?",scope.tenantId(),id);
        return rows.isEmpty()?Map.of("id",Long.toString(id)):camel(rows.getFirst());
    }

    public Map<String,Object> list(CompanyAccess scope,String keyword,String status,String customerId,String cursor,int limit) {
        if(limit<1 || limit>200) {throw invalid("每页条数须在1到200之间");}
        if(status!=null && !Set.of("draft","pending","approved","change_pending","closed","cancelled").contains(status)) {throw invalid("订单状态无效");}
        long last=cursor==null||cursor.isBlank()?Long.MAX_VALUE:identifier(cursor);
        var args=new ArrayList<Object>(List.of(scope.tenantId(),scope.companyId(),last,keyword,keyword));
        String filter=" WHERE tenant_id=? AND company_id=? AND is_deleted=0 AND id<? AND (LOCATE(?,order_no)>0 OR LOCATE(?,COALESCE(remark,''))>0)";
        if(status!=null) {filter+=" AND status=?";args.add(status);}
        if(customerId!=null) {filter+=" AND customer_id=?";args.add(identifier(customerId));}
        if(!scope.allWarehouses()) {
            if(scope.warehouseIds().isEmpty()) {return page(List.of(),null);}
            String marks=String.join(",",Collections.nCopies(scope.warehouseIds().size(),"?"));
            filter+=" AND NOT EXISTS(SELECT 1 FROM sales_order_line l WHERE l.order_id=sales_order.id AND l.is_deleted=0 AND (l.warehouse_id IS NULL OR l.warehouse_id NOT IN ("+marks+")))";
            args.addAll(scope.warehouseIds());
        }
        args.add(limit+1);
        var rows=jdbc.queryForList("SELECT * FROM sales_order"+filter+" ORDER BY id DESC LIMIT ?",args.toArray());
        var items=rows.stream().limit(limit).map(row->{var item=camel(row);item.put("documentNo",row.get("order_no"));item.put("customer",reference("business_partner",scope,number(row,"customer_id")));item.put("allowedActions",actions(scope,row));return item;}).toList();
        return page(items,rows.size()>limit?items.getLast().get("id").toString():null);
    }

    public List<String> actions(CompanyAccess scope,Map<String,Object> row) {
        var actions=new ArrayList<String>();String state=row.get("status").toString();
        if(state.equals("draft")) {add(scope,actions,"update");add(scope,actions,"delete");add(scope,actions,"submit");}
        if(state.equals("pending")) {add(scope,actions,"withdraw");add(scope,actions,"reject");if(canApprove(scope,row)) {add(scope,actions,"approve");}}
        if(state.equals("approved")) {for(String name:List.of("revoke_approval","close_lines","cancel","change","reserve")) {add(scope,actions,name);}}
        if(state.equals("closed")) {add(scope,actions,"change");}
        if(state.equals("change_pending")) {
            var pending=jdbc.queryForList("SELECT created_by FROM document_change WHERE tenant_id=? AND company_id=? AND document_type='sales_order' AND document_id=? AND status='pending' ORDER BY id DESC LIMIT 1",scope.tenantId(),scope.companyId(),number(row,"id"));
            if(!pending.isEmpty()&&canApprove(scope,pending.getFirst())) {add(scope,actions,"change_approve");}
            add(scope,actions,"change_reject");
        }
        return actions;
    }
    private boolean canApprove(CompanyAccess scope,Map<String,Object> row) {
        var policies=jdbc.queryForList("SELECT separate_order_approver FROM company_policy WHERE tenant_id=? AND company_id=?",Integer.class,scope.tenantId(),scope.companyId());
        return row.get("created_by")==null || number(row,"created_by")!=scope.userId() || !policies.isEmpty()&&policies.getFirst()==0;
    }
    private void add(CompanyAccess scope,List<String> list,String action) {if(scope.hasPermission("sales_order:"+action)) {list.add(action);}}

    @Transactional
    public void refreshProgress(CompanyAccess scope,long orderId) {
        // 履约入口已逐仓校验；跨仓订单允许各仓人员更新共享进度。
        var heads=jdbc.queryForList("SELECT * FROM sales_order WHERE tenant_id=? AND company_id=? AND id=? AND is_deleted=0 FOR UPDATE",scope.tenantId(),scope.companyId(),orderId);
        if(heads.isEmpty()) {throw missing();}
        var row=heads.getFirst();
        var total=jdbc.queryForMap("""
            SELECT COALESCE(SUM(base_qty-closed_base_qty),0) needed,COALESCE(SUM(shipped_base_qty),0) shipped,
              COALESCE(SUM(signed_base_qty),0) signed,COALESCE(SUM(returned_base_qty),0) returned
            FROM sales_order_line WHERE tenant_id=? AND company_id=? AND order_id=? AND is_deleted=0
            """,scope.tenantId(),scope.companyId(),orderId);
        BigDecimal needed=decimal(total,"needed"),shipped=decimal(total,"shipped"),signed=decimal(total,"signed"),returned=decimal(total,"returned");
        String fulfillment=signed.compareTo(needed)>=0?"full":shipped.signum()>0?"partial":"none";
        var open=jdbc.queryForMap("""
            SELECT COALESCE(SUM(p.amount),0) amount,COALESCE(SUM(p.open_amount),0) remaining FROM partner_open_item p
            JOIN receivable r ON r.tenant_id=p.tenant_id AND r.company_id=p.company_id AND p.item_type='receivable' AND r.id=p.source_id
            WHERE r.tenant_id=? AND r.company_id=? AND r.kind='charge' AND r.posting_action='post'
              AND NOT EXISTS(SELECT 1 FROM receivable reversed WHERE reversed.tenant_id=r.tenant_id AND reversed.company_id=r.company_id AND reversed.reverses_receivable_id=r.id) AND (
              (r.source_type IN ('shipment_line','shipment') AND EXISTS(SELECT 1 FROM shipment_line sl JOIN sales_order_line ol ON ol.id=sl.order_line_id
                WHERE sl.id=r.source_line_id AND ol.order_id=?))
              OR (r.source_type='sales_order_fee' AND EXISTS(SELECT 1 FROM sales_order_fee f WHERE f.id=r.source_line_id AND f.order_id=?)))
            """,scope.tenantId(),scope.companyId(),orderId,orderId);
        String settlement=decimal(open,"amount").signum()==0||decimal(open,"remaining").compareTo(decimal(open,"amount"))==0?"none":decimal(open,"remaining").signum()==0?"full":"partial";
        long processing=jdbc.queryForObject("""
            SELECT (SELECT COUNT(*) FROM sales_return h JOIN sales_return_line rl ON rl.return_id=h.id
              JOIN shipment_line sl ON sl.id=rl.shipment_line_id JOIN sales_order_line ol ON ol.id=sl.order_line_id
              WHERE h.tenant_id=? AND h.company_id=? AND h.status='submitted' AND ol.order_id=?) +
              (SELECT COUNT(*) FROM refund_request f JOIN settlement_allocation a ON a.tenant_id=f.tenant_id AND a.company_id=f.company_id
                AND a.trigger_type='refund_request' AND a.trigger_id=f.id AND a.status IN ('reserved','active')
              JOIN partner_open_item p ON p.id=a.credit_item_id AND p.item_type='receivable'
              JOIN receivable r ON r.id=p.source_id AND r.source_type='sales_return_line'
              JOIN sales_return_line rl ON rl.id=r.source_line_id JOIN shipment_line sl ON sl.id=rl.shipment_line_id
              JOIN sales_order_line ol ON ol.id=sl.order_line_id
              WHERE f.tenant_id=? AND f.company_id=? AND f.status IN ('pending_review','pending_payment') AND ol.order_id=?)
            """,Long.class,scope.tenantId(),scope.companyId(),orderId,scope.tenantId(),scope.companyId(),orderId);
        String after=processing>0?"processing":returned.signum()==0?"none":returned.compareTo(signed)>=0?"completed":"partial_returned";
        String state=row.get("status").toString();
        if(Set.of("approved","closed").contains(state)) {state=shipped.compareTo(needed)>=0?"closed":"approved";}
        jdbc.update("UPDATE sales_order SET fulfillment_status=?,settlement_status=?,after_sale_status=?,status=?,closed_at=IF(?='closed',COALESCE(closed_at,UTC_TIMESTAMP(3)),NULL) WHERE tenant_id=? AND company_id=? AND id=?",
            fulfillment,settlement,after,state,state,scope.tenantId(),scope.companyId(),orderId);
    }

    public Map<String,Object> reservations(CompanyAccess scope,long id) {
        header(scope,id,false);
        return Map.of("items",jdbc.queryForList("SELECT r.* FROM inventory_reservation r JOIN sales_order_line l ON l.id=r.order_line_id WHERE r.tenant_id=? AND r.company_id=? AND l.order_id=? ORDER BY r.id",scope.tenantId(),scope.companyId(),id).stream().map(SalesOrderQueries::camel).toList());
    }

    public static Map<String,Object> camel(Map<String,Object> input) {
        var result=new LinkedHashMap<String,Object>();
        input.forEach((key,value)->{
            StringBuilder field=new StringBuilder();boolean upper=false;
            for(char c:key.toCharArray()) {if(c=='_') {upper=true;} else {field.append(upper?Character.toUpperCase(c):c);upper=false;}}
            Object output=value;
            if((key.equals("id")||key.endsWith("_id")||key.endsWith("_by"))&&value!=null) {output=value.toString();}
            if(value instanceof BigDecimal d) {output=d.toPlainString();}
            if(value instanceof java.sql.Date d) {output=d.toLocalDate().toString();}
            if(value instanceof java.sql.Timestamp d) {output=d.toInstant().toString();}
            if(value instanceof LocalDateTime d) {output=d.toInstant(ZoneOffset.UTC).toString();}
            result.put(field.toString(),output);
        });
        return result;
    }
    public static Map<String,Object> page(List<?> items,String cursor) {var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("nextCursor",cursor);return result;}
    public static long number(Map<String,Object> row,String key) {return ((Number)row.get(key)).longValue();}
    public static BigDecimal decimal(Map<String,Object> row,String key) {return (BigDecimal)row.get(key);}
    public static long identifier(String text) {try {long value=Long.parseLong(text);if(value<=0) {throw invalid("编号无效");}return value;}catch(NumberFormatException error){throw invalid("编号无效");}}
    public static ApiException missing() {return new ApiException(404,"NOT_FOUND","订单不存在或无权查看");}
    public static ApiException invalid(String message) {return new ApiException(400,"VALIDATION_FAILED",message);}
    public static ApiException conflict(String message) {return new ApiException(409,"STATE_CONFLICT",message);}
}
