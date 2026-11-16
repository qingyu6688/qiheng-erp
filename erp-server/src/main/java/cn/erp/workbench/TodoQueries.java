package cn.erp.workbench;

import cn.erp.documents.DocumentAccess;
import cn.erp.identity.CompanyAccess;
import java.util.*;
import org.springframework.stereotype.Service;

/** 待办资格与业务列表使用同一权限过滤，消息读取时重新计算，避免撤权后读取旧消息。 */
@Service
public class TodoQueries {
    public record Task(String kind,String permission,String state,String label,String action) {}
    public record Query(Task task,String type,String where,Map<String,Object> params) {}
    public static final List<Task> TASKS=List.of(new Task("sales-orders","sales_order:approve","pending","待审核销售订单","approve"),new Task("purchase-orders","purchase_order:approve","pending","待审核采购订单","approve"),new Task("purchase-requisitions","purchase_requisition:approve","pending","待审核请购","approve"),new Task("shipments","shipment:sign","posted","签收超期","sign"),new Task("receipts","receipt:confirm","pending_confirm","待确认收款","confirm"),new Task("payments","payment:confirm","pending_confirm","待确认付款","confirm"),new Task("refund-requests","refund_request:approve","pending_review","待审核退款","approve"));
    private final DocumentBrowser browser;private final DocumentAccess documents;
    public TodoQueries(DocumentBrowser browser,DocumentAccess documents) {this.browser=browser;this.documents=documents;}
    public List<Query> eligible(CompanyAccess actor) {
        var queries=new ArrayList<Query>();
        for(var task:TASKS) {
            if(!actor.hasPermission(task.permission())||!actor.hasPermission(browser.permission(task.kind()))) continue;
            var spec=browser.spec(task.kind());var params=browser.params(actor);params.put("user",actor.userId());params.put("state",task.state());
            String where=browser.where(actor,spec,new DocumentBrowser.Filter(null,null,null,null,null,null,null,null,task.kind().equals("shipments")),params)+" AND h.status=:state";
            if(Set.of("sales_order","purchase_order","refund_request").contains(spec.type())) where+=" AND h.wf_instance_id=0";
            if(task.action().equals("approve")&&!task.kind().equals("refund-requests")) where+=" AND (h.created_by IS NULL OR h.created_by<>:user OR EXISTS(SELECT 1 FROM company_policy p WHERE p.tenant_id=h.tenant_id AND p.company_id=h.company_id AND p.separate_order_approver=0))";
            if(task.action().equals("confirm")) where+=" AND (h.created_by IS NULL OR h.created_by<>:user OR EXISTS(SELECT 1 FROM company_policy p WHERE p.tenant_id=h.tenant_id AND p.company_id=h.company_id AND p.separate_receipt_confirmer=0))";
            if(task.kind().equals("refund-requests")) where+=" AND h.created_by<>:user";
            queries.add(new Query(task,spec.type(),where,params));
        }return queries;
    }
    public Map<String,Object> detail(CompanyAccess actor,Query query,long id) {
        var document=documents.view(actor,query.type(),id);if(!(document.get("allowedActions") instanceof List<?> actions)||!actions.contains(query.task().action())) return null;
        var todo=new LinkedHashMap<String,Object>();todo.put("id",Long.toString(id));todo.put("label",query.task().label());todo.put("documentNo",document.get("documentNo"));todo.put("businessDate",document.get("businessDate"));
        Object partner=null;for(String key:List.of("customerName","supplierName","partnerName")) if(document.get(key)!=null) partner=document.get(key);
        if(partner==null) for(String key:List.of("customer","supplier")) if(document.get(key) instanceof Map<?,?> party&&party.get("name")!=null) partner=party.get("name");
        todo.put("partnerName",partner);todo.put("amount",document.getOrDefault("totalAmount",document.getOrDefault("receivableAmount",document.getOrDefault("payableAmount",document.get("amount")))));todo.put("url","/"+query.task().kind()+"/"+id);return todo;
    }
}
