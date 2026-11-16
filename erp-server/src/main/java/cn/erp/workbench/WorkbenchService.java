package cn.erp.workbench;

import cn.erp.documents.DocumentAccess;
import cn.erp.identity.CompanyAccess;
import cn.erp.salesfinance.FinanceSupport;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class WorkbenchService {
    private final DocumentBrowser browser;
    private final DocumentAccess documents;
    private final NamedParameterJdbcTemplate jdbc;
    public WorkbenchService(DocumentBrowser browser,DocumentAccess documents,NamedParameterJdbcTemplate jdbc) {this.browser=browser;this.documents=documents;this.jdbc=jdbc;}
    public Map<String,Object> summary(CompanyAccess actor) {
        var result=new LinkedHashMap<String,Object>();var blocks=new ArrayList<Map<String,Object>>();var todos=new ArrayList<Map<String,Object>>();
        for(var task:List.of(new Task("sales-orders","sales_order:approve","pending","待审核销售订单","approve"),
                new Task("purchase-orders","purchase_order:approve","pending","待审核采购订单","approve"),
                new Task("purchase-requisitions","purchase_requisition:approve","pending","待审核请购","approve"),
                new Task("shipments","shipment:sign","posted","签收超期","sign"),
                new Task("receipts","receipt:confirm","pending_confirm","待确认收款","confirm"),
                new Task("payments","payment:confirm","pending_confirm","待确认付款","confirm"),
                new Task("refund-requests","refund_request:approve","pending_review","待审核退款","approve"))) {
            if(!actor.hasPermission(task.permission())||!actor.hasPermission(browser.permission(task.kind())))continue;
            var spec=browser.spec(task.kind());var params=browser.params(actor);params.put("user",actor.userId());params.put("state",task.state());
            var filter=new DocumentBrowser.Filter(null,null,null,null,null,null,null,null,task.kind().equals("shipments"));
            String where=browser.where(actor,spec,filter,params)+" AND h.status=:state";
            if(task.action().equals("approve")&&!task.kind().equals("refund-requests"))where+=" AND (h.created_by IS NULL OR h.created_by<>:user OR EXISTS(SELECT 1 FROM company_policy p WHERE p.tenant_id=h.tenant_id AND p.company_id=h.company_id AND p.separate_order_approver=0))";
            if(task.action().equals("confirm"))where+=" AND (h.created_by IS NULL OR h.created_by<>:user OR EXISTS(SELECT 1 FROM company_policy p WHERE p.tenant_id=h.tenant_id AND p.company_id=h.company_id AND p.separate_receipt_confirmer=0))";
            if(task.kind().equals("refund-requests"))where+=" AND h.created_by<>:user";
            long count=jdbc.queryForObject("SELECT COUNT(*) FROM "+spec.type()+" h WHERE "+where,params,Long.class);
            String url="/"+task.kind()+"?status="+task.state()+(task.kind().equals("shipments")?"&signOverdue=true":"");
            blocks.add(Map.of("key",task.kind(),"label",task.label(),"count",count,"url",url));
            for(long id:jdbc.queryForList("SELECT h.id FROM "+spec.type()+" h WHERE "+where+" ORDER BY h.id DESC LIMIT 10",params,Long.class)) {
                var document=documents.view(actor,spec.type(),id);
                if(!(document.get("allowedActions") instanceof List<?> actions)||!actions.contains(task.action()))continue;
                var todo=new LinkedHashMap<String,Object>();todo.put("id",Long.toString(id));todo.put("label",task.label());todo.put("documentNo",document.get("documentNo"));todo.put("businessDate",document.get("businessDate"));
                todo.put("partnerName",partnerName(document));
                todo.put("amount",document.getOrDefault("totalAmount",document.getOrDefault("receivableAmount",document.getOrDefault("payableAmount",document.get("amount")))));todo.put("url","/"+task.kind()+"/"+id);todos.add(todo);
            }
        }
        result.put("blocks",blocks);result.put("todos",todos.stream().limit(10).toList());
        if(actor.hasPermission("inventory:view")) {
            var params=browser.params(actor);
            String scope=actor.allWarehouses()?"":" AND l.warehouse_id IN (:warehouses)";
            String alerts="""
                WITH demand AS (SELECT l.warehouse_id,l.sku_id,SUM(l.base_qty) pending_demand_qty
                  FROM sales_order_line l JOIN sales_order h ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.order_id
                  WHERE l.tenant_id=:tenant AND l.company_id=:company AND l.is_deleted=0 AND h.is_deleted=0 AND h.status='pending'
                """+scope+cn.erp.shared.OrgOwnership.filter(actor,"h")+"""
                  GROUP BY l.warehouse_id,l.sku_id),b AS (SELECT warehouse_id,sku_id,SUM(on_hand_qty-reserved_qty-blocked_qty) available_qty
                  FROM inventory_balance WHERE tenant_id=:tenant AND company_id=:company GROUP BY warehouse_id,sku_id)
                SELECT d.warehouse_id,d.sku_id,w.name warehouse_name,s.code sku_code,s.name sku_name,COALESCE(b.available_qty,0) available_qty,d.pending_demand_qty
                FROM demand d LEFT JOIN b USING(warehouse_id,sku_id) JOIN warehouse w ON w.tenant_id=:tenant AND w.id=d.warehouse_id
                JOIN sku s ON s.tenant_id=:tenant AND s.id=d.sku_id WHERE d.pending_demand_qty>COALESCE(b.available_qty,0)
                """;
            long count=jdbc.queryForObject("SELECT COUNT(*) FROM ("+alerts+") alerts",params,Long.class);
            blocks.add(Map.of("key","inventory-shortage","label","待审需求超过可用","count",count,"url","/inventory-balances"));
            result.put("inventoryAlerts",jdbc.queryForList(alerts+" ORDER BY d.warehouse_id,d.sku_id LIMIT 10",params).stream().map(FinanceSupport::row).toList());
        }
        if(actor.hasPermission("period:view")) {
            var params=browser.params(actor);params.put("today",LocalDate.now(ZoneId.of("Asia/Shanghai")));
            result.put("periods",jdbc.queryForList("SELECT id,period,status,start_date,end_date FROM accounting_period WHERE tenant_id=:tenant AND company_id=:company AND start_date<=:today AND end_date>=:today",params).stream().map(FinanceSupport::row).toList());
        }
        if(actor.hasPermission("audit:view")&&actor.allWarehouses()&&actor.orgScope().allDepartments()) {
            var params=browser.params(actor);params.put("user",actor.userId());
            result.put("recentActions",jdbc.queryForList("SELECT action,object_type,object_id,created_at FROM audit_log WHERE tenant_id=:tenant AND company_id=:company AND operator_id=:user ORDER BY id DESC LIMIT 5",params).stream().map(FinanceSupport::row).toList());
        }
        result.put("asOf",Instant.now().toString());return result;
    }
    private record Task(String kind,String permission,String state,String label,String action) {}
    private Object partnerName(Map<String,Object> document) {
        for(String key:List.of("customerName","supplierName","partnerName"))if(document.get(key)!=null)return document.get(key);
        for(String key:List.of("customer","supplier"))if(document.get(key) instanceof Map<?,?> party&&party.get("name")!=null)return party.get("name");
        return null;
    }
}
