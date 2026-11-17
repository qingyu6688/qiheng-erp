package cn.erp.shared;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** 归属只在新建时写入，后续审批、移部门和调整人员任职都不回写历史单据。 */
public final class OrgOwnership {
    public static final Set<String> TABLES=Set.of("sales_order","shipment","receipt","sales_return","refund_request","purchase_requisition","purchase_order","goods_receipt","purchase_return","payment","account_transfer","opening_entry","stock_adjustment","stock_count","stock_transfer","stock_misc_doc","qc_inspection","document_reversal","invoice_registration");
    private OrgOwnership() {}
    public static OrgScope scope(ActorScope actor) { return actor instanceof OrgScoped org?org.orgScope():OrgScope.all(); }
    public static String filter(ActorScope actor,String alias) { return scope(actor).allDepartments()?"":" AND ("+scope(actor).ownerFilter(alias)+")"; }
    public static void require(ActorScope actor,Map<String,Object> row) {
        if(!scope(actor).allows(number(row.get("owner_department_id")),number(row.get("owner_user_id")))) throw missing();
    }
    public static void require(JdbcTemplate jdbc,ActorScope actor,String table,Object id) {
        if(scope(actor).allDepartments()) return;
        if(!TABLES.contains(table)) throw new IllegalArgumentException("单据类型无效");
        var rows=jdbc.queryForList("SELECT owner_department_id,owner_user_id FROM "+table+" WHERE tenant_id=? AND company_id=? AND id=?",actor.tenantId(),actor.companyId(),id);
        if(rows.isEmpty()) throw missing();require(actor,rows.getFirst());
    }
    public static long department(ActorScope actor) {
        var org=scope(actor);
        if(!org.allDepartments()&&org.primaryDepartmentId()==0) throw new ApiException(422,"PRIMARY_DEPARTMENT_REQUIRED","请先在当前公司设置有效主部门，再新建业务单据");
        if(!org.allDepartments()&&!org.allows(org.primaryDepartmentId(),actor.userId())) throw new ApiException(422,"PRIMARY_DEPARTMENT_NOT_ALLOWED","当前主部门不在可写范围，请调整组织或部门授权");
        return org.primaryDepartmentId();
    }
    public static void requireItem(JdbcTemplate jdbc,ActorScope actor,Object id) {
        if(scope(actor).allDepartments()) return;
        var rows=jdbc.queryForList("SELECT 1 FROM partner_open_item oi WHERE oi.tenant_id=? AND oi.company_id=? AND oi.id=? AND "+itemCondition(actor,"oi"),actor.tenantId(),actor.companyId(),id);
        if(rows.isEmpty()) throw missing();
    }
    public static void requireAllocation(JdbcTemplate jdbc,ActorScope actor,Object id) {
        if(scope(actor).allDepartments()) return;
        var rows=jdbc.queryForList("SELECT debit_item_id,credit_item_id FROM settlement_allocation WHERE tenant_id=? AND company_id=? AND id=?",actor.tenantId(),actor.companyId(),id);
        if(rows.isEmpty()) throw missing();var row=rows.getFirst();requireItem(jdbc,actor,row.get("debit_item_id"));requireItem(jdbc,actor,row.get("credit_item_id"));
    }
    private static final Map<String,String> SOURCE_TABLES=sources();
    private static Map<String,String> sources() {
        var result=new LinkedHashMap<String,String>();
        for(String table:TABLES.stream().sorted().toList()) {result.put(table,table);result.put(table+"_line",table);}
        result.put("sales_order_fee","shipment");result.put("purchase_order_fee","goods_receipt");
        result.put("stock_misc_line","stock_misc_doc");
        result.put("shipment_diff","shipment");result.put("stock_transfer_event","stock_transfer");return Map.copyOf(result);
    }
    public static String sourceCondition(ActorScope actor,String type,String id) {
        if(scope(actor).allDepartments()) return "1=1";
        var parts=new ArrayList<String>();
        for(String table:TABLES.stream().sorted().toList()) {
            String kinds=SOURCE_TABLES.entrySet().stream().filter(entry->entry.getValue().equals(table)).map(entry->"'"+entry.getKey()+"'").sorted().reduce((a,b)->a+","+b).orElseThrow();
            parts.add("("+type+" IN ("+kinds+") AND EXISTS(SELECT 1 FROM "+table+" od WHERE od.tenant_id="+actor.tenantId()+" AND od.company_id="+actor.companyId()+" AND od.id="+id+filter(actor,"od")+"))");
        }
        return "("+String.join(" OR ",parts)+")";
    }
    public static String itemCondition(ActorScope actor,String alias) {
        if(scope(actor).allDepartments()) return "1=1";
        var parts=new ArrayList<String>();parts.add(sourceCondition(actor,alias+".item_type",alias+".source_id"));
        for(String fact:List.of("receivable","payable")) parts.add("("+alias+".item_type='"+fact+"' AND EXISTS(SELECT 1 FROM "+fact+" osf WHERE osf.tenant_id="+alias+".tenant_id AND osf.company_id="+alias+".company_id AND osf.id="+alias+".source_id AND "+sourceCondition(actor,"osf.source_type","osf.source_id")+"))");
        return "("+String.join(" OR ",parts)+")";
    }
    public static void requireSource(JdbcTemplate jdbc,ActorScope actor,String type,Object id) {
        if(scope(actor).allDepartments()) return;
        if(type.equals("receivable")||type.equals("payable")) {
            var facts=jdbc.queryForList("SELECT source_type,source_id FROM "+type+" WHERE tenant_id=? AND company_id=? AND id=?",actor.tenantId(),actor.companyId(),id);
            if(facts.isEmpty()) throw missing();var fact=facts.getFirst();requireSource(jdbc,actor,fact.get("source_type").toString(),fact.get("source_id"));return;
        }
        String table=SOURCE_TABLES.get(type);if(table==null) throw missing();require(jdbc,actor,table,id);
    }
    private static long number(Object value) { return value==null?0:Long.parseLong(value.toString()); }
    private static ApiException missing() { return new ApiException(404,"NOT_FOUND","单据不存在或无权查看"); }
}
