package cn.erp.shared;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;

/** 信用额退款的所有入口使用同一来源范围；写入前先锁退货，再锁未清项。 */
public final class SourcelessRefundCredits {
    private static final int MAX_ITEMS=200;
    private SourcelessRefundCredits() {}

    public static String mode(String value) {
        String result=value==null?"receipt":value;
        if(!List.of("receipt","sourceless_credit").contains(result)) throw invalid("退款模式无效");
        return result;
    }
    public static boolean usesCredit(Map<String,Object> request) {return "sourceless_credit".equals(request.get("refund_mode"));}

    public static void selection(List<Long> ids) {
        if(ids==null || ids.isEmpty() || ids.size()>MAX_ITEMS || ids.stream().anyMatch(id->id==null || id<=0)
                || new HashSet<>(ids).size()!=ids.size()) throw invalid("请明确选择1到200项不重复的无原单退货信用额");
    }

    public static void require(JdbcTemplate jdbc,ActorScope actor,long customer,List<Long> ids,boolean lock,boolean posted) {
        selection(ids);
        if(!(actor instanceof PermissionScope permission) || !permission.hasPermission("sales_return:view"))
            throw new ApiException(403,"FORBIDDEN","查看或办理信用额退款需要退货来源查看权限");
        var headers=new TreeMap<Long,Map<String,Object>>();
        for(long id:ids) {
            var rows=jdbc.queryForList("""
                    SELECT h.* FROM partner_open_item i
                    JOIN receivable r ON r.tenant_id=i.tenant_id AND r.company_id=i.company_id AND r.id=i.source_id
                      AND i.item_type='receivable' AND r.kind='credit' AND r.posting_action='post' AND r.source_type='sales_return_line'
                    JOIN sales_return h ON h.tenant_id=r.tenant_id AND h.company_id=r.company_id AND h.id=r.source_id AND h.return_mode='sourceless'
                    JOIN sales_return_line l ON l.tenant_id=h.tenant_id AND l.company_id=h.company_id AND l.return_id=h.id AND l.id=r.source_line_id
                    WHERE i.tenant_id=? AND i.company_id=? AND i.id=? AND i.direction='C' AND i.partner_id=? AND r.customer_id=? AND h.customer_id=?
                    """,actor.tenantId(),actor.companyId(),id,customer,customer,customer);
            if(rows.size()!=1) throw new ApiException(422,"REFUND_CREDIT_SOURCE_INVALID","额度须来自本客户已过账的无原单销售退货");
            var header=rows.getFirst();headers.put(((Number)header.get("id")).longValue(),header);
        }
        for(var entry:headers.entrySet()) {
            var header=lock?jdbc.queryForMap("SELECT * FROM sales_return WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",
                    actor.tenantId(),actor.companyId(),entry.getKey()):entry.getValue();
            OrgOwnership.require(actor,header);
            if(actor instanceof WarehouseScope warehouses && !warehouses.allowsWarehouse(((Number)header.get("warehouse_id")).longValue()))
                throw new ApiException(404,"NOT_FOUND","退货来源不存在或不在当前范围");
            if(posted && !"posted".equals(header.get("status"))) throw new ApiException(409,"REFUND_CREDIT_SOURCE_CHANGED","退货来源已冲销或尚未过账，请重新核对额度");
        }
    }

    public static void requireRequest(JdbcTemplate jdbc,ActorScope actor,Map<String,Object> request,boolean lock,boolean posted) {
        if(!usesCredit(request)) return;
        require(jdbc,actor,((Number)request.get("customer_id")).longValue(),selected(jdbc,actor,request.get("id")),lock,posted);
    }

    public static List<Long> selected(JdbcTemplate jdbc,ActorScope actor,Object requestId) {
        return jdbc.queryForList("""
                SELECT chosen.item_id FROM refund_request r,
                  JSON_TABLE(r.credit_item_ids,'$[*]' COLUMNS(position FOR ORDINALITY,item_id BIGINT UNSIGNED PATH '$')) chosen
                WHERE r.tenant_id=? AND r.company_id=? AND r.id=? ORDER BY chosen.position
                """,Long.class,actor.tenantId(),actor.companyId(),requestId);
    }

    /** 在分页前排除任意一项来源不可见的退款，不能先分页再过滤。 */
    public static String condition(ActorScope actor,String alias) {
        return condition(actor,alias,true);
    }
    private static String condition(ActorScope actor,String alias,boolean requireView) {
        if(requireView && (!(actor instanceof PermissionScope permission) || !permission.hasPermission("sales_return:view")))
            return alias+".refund_mode<>'sourceless_credit'";
        String warehouse="1=1";
        if(actor instanceof WarehouseScope scope && !scope.allWarehouses()) warehouse=scope.warehouseIds().isEmpty()?"1=0":
                "credit_return.warehouse_id IN("+String.join(",",scope.warehouseIds().stream().sorted().map(String::valueOf).toList())+")";
        return "("+alias+".refund_mode<>'sourceless_credit' OR (JSON_LENGTH("+alias+".credit_item_ids)>0 AND NOT EXISTS("
                +"SELECT 1 FROM JSON_TABLE("+alias+".credit_item_ids,'$[*]' COLUMNS(item_id BIGINT UNSIGNED PATH '$')) chosen WHERE NOT EXISTS("
                +"SELECT 1 FROM partner_open_item credit_item JOIN receivable credit_fact ON credit_fact.tenant_id=credit_item.tenant_id AND credit_fact.company_id=credit_item.company_id"
                +" AND credit_item.item_type='receivable' AND credit_fact.id=credit_item.source_id AND credit_fact.kind='credit' AND credit_fact.posting_action='post' AND credit_fact.source_type='sales_return_line'"
                +" JOIN sales_return credit_return ON credit_return.tenant_id=credit_fact.tenant_id AND credit_return.company_id=credit_fact.company_id AND credit_return.id=credit_fact.source_id AND credit_return.return_mode='sourceless'"
                +" WHERE credit_item.tenant_id="+alias+".tenant_id AND credit_item.company_id="+alias+".company_id AND credit_item.id=chosen.item_id AND credit_item.direction='C'"
                +" AND credit_item.partner_id="+alias+".customer_id AND credit_fact.customer_id="+alias+".customer_id AND credit_return.customer_id="+alias+".customer_id"
                +" AND "+warehouse+OrgOwnership.filter(actor,"credit_return")+"))))";
    }

    public static boolean unrestricted(ActorScope actor) {
        return actor instanceof WarehouseScope warehouses && warehouses.allWarehouses() && OrgOwnership.scope(actor).allDepartments();
    }

    /** 往来、核销与财务投影遵守实际来源范围，不把来源的编辑权限当成财务查看权限。 */
    public static String sourceCondition(ActorScope actor,String type,String id) {
        if(unrestricted(actor)) return "1=1";
        String visible="1=0";
        if(actor instanceof WarehouseScope warehouses) visible=warehouses.allWarehouses()?"1=1":warehouses.warehouseIds().isEmpty()?"1=0":
                "return_scope.warehouse_id IN("+String.join(",",warehouses.warehouseIds().stream().sorted().map(String::valueOf).toList())+")";
        visible+=OrgOwnership.filter(actor,"return_scope");
        String tenant="return_scope.tenant_id="+actor.tenantId()+" AND return_scope.company_id="+actor.companyId();
        String reversal="return_reverse.tenant_id="+actor.tenantId()+" AND return_reverse.company_id="+actor.companyId()+" AND return_reverse.id="+id+" AND return_reverse.source_type='sales_return'";
        String join=" FROM document_reversal return_reverse JOIN sales_return return_scope ON return_scope.tenant_id=return_reverse.tenant_id AND return_scope.company_id=return_reverse.company_id"
                +" AND return_scope.id=return_reverse.source_id AND return_scope.return_mode='sourceless' WHERE "+reversal;
        return "(("+type+" NOT IN('sales_return','sales_return_line') OR EXISTS(SELECT 1 FROM sales_return return_scope WHERE "+tenant+" AND return_scope.id="+id
                +" AND (return_scope.return_mode<>'sourceless' OR ("+visible+"))))"
                +" AND ("+type+"<>'refund_request' OR EXISTS(SELECT 1 FROM refund_request refund_scope WHERE refund_scope.tenant_id="+actor.tenantId()+" AND refund_scope.company_id="+actor.companyId()
                +" AND refund_scope.id="+id+" AND "+condition(actor,"refund_scope",false)+"))"
                +" AND ("+type+"<>'document_reversal' OR NOT EXISTS(SELECT 1"+join+") OR EXISTS(SELECT 1"+join+" AND "+visible+")))";
    }

    public static void requireSource(JdbcTemplate jdbc,ActorScope actor,String type,Object id) {
        if(unrestricted(actor) || !List.of("sales_return","sales_return_line","refund_request","document_reversal").contains(type)) return;
        String table=type.equals("sales_return_line")?"sales_return":type;
        if(jdbc.queryForList("SELECT id FROM "+table+" source_document WHERE tenant_id=? AND company_id=? AND id=? AND "
                +sourceCondition(actor,"'"+type+"'","source_document.id"),Long.class,actor.tenantId(),actor.companyId(),id).isEmpty())
            throw new ApiException(404,"NOT_FOUND","退货或退款来源不在当前可见范围");
    }
    private static ApiException invalid(String message) {return new ApiException(400,"VALIDATION_FAILED",message);}
}
