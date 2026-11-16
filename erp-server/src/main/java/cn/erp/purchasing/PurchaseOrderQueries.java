package cn.erp.purchasing;

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
public class PurchaseOrderQueries {
    private final JdbcTemplate jdbc;
    public PurchaseOrderQueries(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    public Map<String,Object> header(CompanyAccess scope,long id,boolean lock) {
        cn.erp.shared.OrgOwnership.require(jdbc,scope,"purchase_order",id);
        var rows=jdbc.queryForList("SELECT * FROM purchase_order WHERE tenant_id=? AND company_id=?"+cn.erp.shared.OrgOwnership.filter(scope,"purchase_order")+" AND id=? AND is_deleted=0"+(lock?" FOR UPDATE":""),scope.tenantId(),scope.companyId(),id);
        if(rows.isEmpty()) {throw missing();}
        visible(scope,id);
        return rows.getFirst();
    }

    public void visible(CompanyAccess scope,long id) {
        cn.erp.shared.OrgOwnership.require(jdbc,scope,"purchase_order",id);
        if(scope.allWarehouses()) {return;}
        var warehouses=jdbc.queryForList("SELECT warehouse_id FROM purchase_order_line WHERE tenant_id=? AND company_id=? AND order_id=? AND is_deleted=0",Long.class,scope.tenantId(),scope.companyId(),id);
        if(warehouses.isEmpty() || warehouses.stream().anyMatch(w->w==null || !scope.allowsWarehouse(w))) {throw missing();}
    }

    public List<Map<String,Object>> lines(CompanyAccess scope,long id,boolean lock) {
        return jdbc.queryForList("SELECT * FROM purchase_order_line WHERE tenant_id=? AND company_id=? AND order_id=? AND is_deleted=0 ORDER BY id"+(lock?" FOR UPDATE":""),scope.tenantId(),scope.companyId(),id);
    }

    public Map<String,Object> get(CompanyAccess scope,long id) {
        var raw=header(scope,id,false);
        var result=camel(raw);
        result.put("documentNo",raw.get("order_no"));
        result.put("supplier",reference("business_partner",scope,number(raw,"supplier_id")));
        result.put("lines",lines(scope,id,false).stream().map(line->lineView(scope,line)).toList());
        result.put("fees",jdbc.queryForList("SELECT * FROM purchase_order_fee WHERE tenant_id=? AND company_id=? AND order_id=? ORDER BY id",scope.tenantId(),scope.companyId(),id).stream().map(fee->{
            var row=camel(fee);row.put("recognized",fee.get("recognized_receipt_id")!=null);return row;
        }).toList());
        result.put("allowedActions",actions(scope,raw));
        var changes=jdbc.queryForList("SELECT id,change_no,status,version,from_revision,to_revision FROM document_change WHERE tenant_id=? AND company_id=? AND document_type='purchase_order' AND document_id=? ORDER BY id DESC LIMIT 1",scope.tenantId(),scope.companyId(),id);
        if(!changes.isEmpty()) {result.put("change",camel(changes.getFirst()));}
        cn.erp.shared.BusinessPeople.enrich(jdbc,scope,result);cn.erp.shared.WorkflowViews.decorate(jdbc,scope,"purchase_order",id,result);return result;
    }

    private Map<String,Object> lineView(CompanyAccess scope,Map<String,Object> line) {
        var result=camel(line);
        result.put("sku",reference("sku",scope,number(line,"sku_id")));
        result.put("unit",reference("unit",scope,number(line,"unit_id")));
        result.put("warehouse",line.get("warehouse_id")==null?null:reference("warehouse",scope,number(line,"warehouse_id")));
        result.put("lineDiscountAmount",decimal(line,"line_discount").toPlainString());
        result.put("progress",Map.of("baseQty",decimal(line,"base_qty").toPlainString(),"closedQty",decimal(line,"closed_base_qty").toPlainString(),
            "receivedQty",decimal(line,"received_base_qty").toPlainString(),"closableQty",decimal(line,"receive_limit_base_qty").subtract(decimal(line,"closed_base_qty")).subtract(decimal(line,"received_base_qty")).subtract(decimal(line,"pending_receive_base_qty")).toPlainString(),"pendingReceiveQty",decimal(line,"pending_receive_base_qty").toPlainString(),
            "returnedQty",decimal(line,"returned_base_qty").toPlainString(),"receivableQty",decimal(line,"receive_limit_base_qty").subtract(decimal(line,"closed_base_qty")).subtract(decimal(line,"received_base_qty")).subtract(decimal(line,"pending_receive_base_qty")).toPlainString()));
        return result;
    }

    private Map<String,Object> reference(String table,CompanyAccess scope,long id) {
        String columns=table.equals("unit")?"id,name":"id,code,name";
        var rows=jdbc.queryForList("SELECT "+columns+" FROM "+table+" WHERE tenant_id=? AND id=?",scope.tenantId(),id);
        return rows.isEmpty()?Map.of("id",Long.toString(id)):camel(rows.getFirst());
    }

    public Map<String,Object> list(CompanyAccess scope,String keyword,String status,String supplierId,String cursor,int limit) {
        if(limit<1 || limit>200) {throw invalid("每页条数须在1到200之间");}
        if(status!=null && !Set.of("draft","pending","approved","change_pending","closed","cancelled").contains(status)) {throw invalid("订单状态无效");}
        long last=cursor==null||cursor.isBlank()?Long.MAX_VALUE:identifier(cursor);
        var args=new ArrayList<Object>(List.of(scope.tenantId(),scope.companyId(),last,keyword,keyword));
        String filter=" WHERE tenant_id=? AND company_id=? AND is_deleted=0 AND id<? AND (LOCATE(?,order_no)>0 OR LOCATE(?,COALESCE(remark,''))>0)";
        if(status!=null) {filter+=" AND status=?";args.add(status);}
        if(supplierId!=null) {filter+=" AND supplier_id=?";args.add(identifier(supplierId));}
        if(!scope.allWarehouses()) {
            if(scope.warehouseIds().isEmpty()) {return page(List.of(),null);}
            String marks=String.join(",",Collections.nCopies(scope.warehouseIds().size(),"?"));
            filter+=" AND NOT EXISTS(SELECT 1 FROM purchase_order_line l WHERE l.order_id=purchase_order.id AND l.is_deleted=0 AND (l.warehouse_id IS NULL OR l.warehouse_id NOT IN ("+marks+")))";
            args.addAll(scope.warehouseIds());
        }
        filter+=cn.erp.shared.OrgOwnership.filter(scope,"purchase_order");args.add(limit+1);
        var rows=jdbc.queryForList("SELECT * FROM purchase_order"+filter+" ORDER BY id DESC LIMIT ?",args.toArray());
        var suppliers=cn.erp.shared.ScopedReferences.load(jdbc,scope,"business_partner",rows.stream().limit(limit).map(row->number(row,"supplier_id")).toList());
        var items=rows.stream().limit(limit).map(row->{var item=camel(row);item.put("documentNo",row.get("order_no"));item.put("supplier",cn.erp.shared.ScopedReferences.get(suppliers,number(row,"supplier_id")));item.put("allowedActions",actions(scope,row));return item;}).toList();
        return page(items,rows.size()>limit?items.getLast().get("id").toString():null);
    }

    public List<String> actions(CompanyAccess scope,Map<String,Object> row) {
        var actions=new ArrayList<String>();String state=row.get("status").toString();
        if(state.equals("draft")) {add(scope,actions,"update");add(scope,actions,"delete");add(scope,actions,"submit");}
        if(state.equals("pending")) {add(scope,actions,"withdraw");add(scope,actions,"reject");if(canApprove(scope,row)) {add(scope,actions,"approve");}}
        if(state.equals("approved")) {for(String name:List.of("revoke_approval","close_lines","cancel","change")) {add(scope,actions,name);}}
        if(state.equals("closed")) {add(scope,actions,"change");}
        if(state.equals("change_pending")) {
            var pending=jdbc.queryForList("SELECT created_by FROM document_change WHERE tenant_id=? AND company_id=? AND document_type='purchase_order' AND document_id=? AND status='pending' ORDER BY id DESC LIMIT 1",scope.tenantId(),scope.companyId(),number(row,"id"));
            if(!pending.isEmpty()&&canApprove(scope,pending.getFirst())) {add(scope,actions,"change_approve");}
            add(scope,actions,"change_reject");
        }
        return cn.erp.shared.WorkflowViews.actions(row,actions);
    }
    private boolean canApprove(CompanyAccess scope,Map<String,Object> row) {
        var policies=jdbc.queryForList("SELECT separate_order_approver FROM company_policy WHERE tenant_id=? AND company_id=?",Integer.class,scope.tenantId(),scope.companyId());
        return row.get("created_by")==null || number(row,"created_by")!=scope.userId() || !policies.isEmpty()&&policies.getFirst()==0;
    }
    private void add(CompanyAccess scope,List<String> list,String action) {if(scope.hasPermission("purchase_order:"+action)) {list.add(action);}}

    @Transactional
    public void refreshProgress(CompanyAccess scope,long orderId) {
        var heads=jdbc.queryForList("SELECT * FROM purchase_order WHERE tenant_id=? AND company_id=?"+cn.erp.shared.OrgOwnership.filter(scope,"purchase_order")+" AND id=? AND is_deleted=0 FOR UPDATE",scope.tenantId(),scope.companyId(),orderId);
        if(heads.isEmpty()) {throw missing();}
        var total=jdbc.queryForMap("SELECT COALESCE(SUM(GREATEST(base_qty-closed_base_qty,0)),0) needed,COALESCE(SUM(received_base_qty),0) received,COALESCE(SUM(returned_base_qty),0) returned FROM purchase_order_line WHERE tenant_id=? AND company_id=? AND order_id=? AND is_deleted=0",scope.tenantId(),scope.companyId(),orderId);
        BigDecimal needed=decimal(total,"needed"),received=decimal(total,"received"),returned=decimal(total,"returned");
        var open=jdbc.queryForMap("""
            SELECT COALESCE(SUM(i.amount),0) amount,COALESCE(SUM(i.open_amount),0) remaining FROM partner_open_item i
            JOIN payable p ON p.tenant_id=i.tenant_id AND p.company_id=i.company_id AND i.item_type='payable' AND p.id=i.source_id
            WHERE p.tenant_id=? AND p.company_id=? AND p.kind='charge' AND p.posting_action='post'
              AND NOT EXISTS(SELECT 1 FROM payable reversed WHERE reversed.tenant_id=p.tenant_id AND reversed.company_id=p.company_id AND reversed.reverses_payable_id=p.id) AND
              ((p.source_type='goods_receipt_line' AND EXISTS(SELECT 1 FROM goods_receipt_line g JOIN purchase_order_line l ON l.id=g.order_line_id WHERE g.id=p.source_line_id AND l.order_id=?))
              OR (p.source_type='purchase_order_fee' AND EXISTS(SELECT 1 FROM purchase_order_fee f WHERE f.id=p.source_line_id AND f.order_id=?)))
            """,scope.tenantId(),scope.companyId(),orderId,orderId);
        String fulfillment=received.compareTo(needed)>=0?"full":received.signum()>0?"partial":"none";
        String settlement=decimal(open,"amount").signum()==0||decimal(open,"remaining").compareTo(decimal(open,"amount"))==0?"none":decimal(open,"remaining").signum()==0?"full":"partial";
        long pending=jdbc.queryForObject("SELECT COUNT(*) FROM purchase_return h JOIN purchase_return_line r ON r.return_id=h.id JOIN goods_receipt_line g ON g.id=r.receipt_line_id JOIN purchase_order_line l ON l.id=g.order_line_id WHERE h.tenant_id=? AND h.company_id=? AND h.status='submitted' AND l.order_id=?",Long.class,scope.tenantId(),scope.companyId(),orderId);
        String after=pending>0?"processing":returned.signum()==0?"none":returned.compareTo(received)>=0?"completed":"partial_returned";
        String state=heads.getFirst().get("status").toString();
        if(Set.of("approved","closed").contains(state)) {state=received.compareTo(needed)>=0?"closed":"approved";}
        jdbc.update("UPDATE purchase_order SET fulfillment_status=?,settlement_status=?,return_status=?,status=?,closed_at=IF(?='closed',COALESCE(closed_at,UTC_TIMESTAMP(3)),NULL) WHERE tenant_id=? AND company_id=? AND id=?",fulfillment,settlement,after,state,state,scope.tenantId(),scope.companyId(),orderId);
    }

    public Map<String,Object> candidates(CompanyAccess scope,String supplier,String warehouse,boolean receivable,int limit) {
        if(limit<1||limit>200) {throw invalid("每页条数须在1到200之间");}
        String filter=" WHERE l.tenant_id=? AND l.company_id=? AND l.is_deleted=0 AND h.is_deleted=0 AND h.status IN('approved','closed')";
        var args=new ArrayList<Object>(List.of(scope.tenantId(),scope.companyId()));
        if(supplier!=null) {filter+=" AND h.supplier_id=?";args.add(identifier(supplier));}
        if(warehouse!=null) {filter+=" AND l.warehouse_id=?";args.add(identifier(warehouse));}
        if(receivable) {filter+=" AND l.receive_limit_base_qty-l.closed_base_qty-l.received_base_qty-l.pending_receive_base_qty>0";}
        if(!scope.allWarehouses()) {
            if(scope.warehouseIds().isEmpty()) {return page(List.of(),null);}
            filter+=" AND l.warehouse_id IN("+String.join(",",Collections.nCopies(scope.warehouseIds().size(),"?"))+")";args.addAll(scope.warehouseIds());
        }
        filter+=cn.erp.shared.OrgOwnership.filter(scope,"h");args.add(limit);
        var rows=jdbc.queryForList("""
            SELECT l.id,l.order_id,h.order_no,h.supplier_id,l.sku_id,s.name sku_name,l.warehouse_id,l.unit_id,u.name unit_name,l.unit_rate,l.base_qty,
              l.receive_limit_base_qty,l.received_base_qty,l.pending_receive_base_qty,
              l.receive_limit_base_qty-l.closed_base_qty-l.received_base_qty-l.pending_receive_base_qty receivable_qty
            FROM purchase_order_line l JOIN purchase_order h ON h.id=l.order_id AND h.tenant_id=l.tenant_id AND h.company_id=l.company_id
            JOIN sku s ON s.tenant_id=l.tenant_id AND s.id=l.sku_id JOIN unit u ON u.tenant_id=l.tenant_id AND u.id=l.unit_id
            """+filter+" ORDER BY h.id DESC,l.line_no LIMIT ?",args.toArray());
        return page(rows.stream().map(raw->{var row=camel(raw);row.put("orderNo",raw.get("order_no"));return row;}).toList(),null);
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
    public static boolean flag(Object value) {return value instanceof Boolean b?b:((Number)value).intValue()!=0;}
    public static long number(Map<String,Object> row,String key) {return ((Number)row.get(key)).longValue();}
    public static BigDecimal decimal(Map<String,Object> row,String key) {return (BigDecimal)row.get(key);}
    public static long identifier(String text) {try {long value=Long.parseLong(text);if(value<=0) {throw invalid("编号无效");}return value;}catch(NumberFormatException error){throw invalid("编号无效");}}
    public static ApiException missing() {return new ApiException(404,"NOT_FOUND","订单不存在或无权查看");}
    public static ApiException invalid(String message) {return new ApiException(400,"VALIDATION_FAILED",message);}
    public static ApiException conflict(String message) {return new ApiException(409,"STATE_CONFLICT",message);}
}
