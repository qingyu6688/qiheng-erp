package cn.erp.shared;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** 业务员只用于统计，不能代替归属人或扩大公司、仓库及部门授权。 */
public final class BusinessPeople {
    public static void enrich(JdbcTemplate jdbc,ActorScope actor,java.util.Map<String,Object> result) {
        for(String field:java.util.List.of("salesUser","buyerUser")) {
            Object id=result.get(field+"Id");if(id==null) continue;
            var names=jdbc.queryForList("SELECT display_name FROM sys_user WHERE tenant_id=? AND id=?",String.class,actor.tenantId(),id);
            result.put(field+"Name",id.toString().equals("0")?"未分配":names.isEmpty()?"历史业务员（"+id+"）":names.getFirst());
        }
    }
    private static final Set<String> SALES=Set.of("sales_order","shipment","sales_return","receipt");
    private static final Set<String> PURCHASE=Set.of("purchase_order","goods_receipt","purchase_return","payment");
    private BusinessPeople() {}
    public static String column(String table) {
        if(SALES.contains(table)) return "sales_user_id";
        if(PURCHASE.contains(table)) return "buyer_user_id";
        throw new IllegalArgumentException("业务员单据类型无效");
    }
    public static void assign(JdbcTemplate jdbc,ActorScope actor,String table,long id,Long requested) {
        String field=column(table);
        OrgOwnership.require(jdbc,actor,table,id);
        if(requested!=null&&requested<=0) throw new ApiException(400,"VALIDATION_FAILED","请选择有效业务员");
        var rows=jdbc.queryForList("SELECT "+field+",status FROM "+table+" WHERE tenant_id=? AND company_id=? AND id=?",actor.tenantId(),actor.companyId(),id);
        if(rows.isEmpty()) throw missing();
        long previous=((Number)rows.getFirst().get(field)).longValue();
        if(!"draft".equals(rows.getFirst().get("status"))) {
            if(requested!=null&&requested!=previous) throw new ApiException(422,"BUSINESS_USER_IMMUTABLE","业务员只能在草稿阶段调整");
            return;
        }
        long selected=requested==null?(previous>0?previous:inherited(jdbc,actor,table,id)):requested;
        if(requested!=null&&selected!=previous) {
            if(selected<=0) throw new ApiException(400,"VALIDATION_FAILED","请选择有效业务员");
            Long count=jdbc.queryForObject("SELECT COUNT(*) FROM sys_user u WHERE u.tenant_id=? AND u.id=? AND u.status='active' AND EXISTS(SELECT 1 FROM org_user_data_scope s WHERE s.tenant_id=u.tenant_id AND s.user_id=u.id AND s.company_id=?)",Long.class,actor.tenantId(),selected,actor.companyId());
            if(count==null||count==0) throw missing();
        }
        jdbc.update("UPDATE "+table+" SET "+field+"=? WHERE tenant_id=? AND company_id=? AND id=?",selected,actor.tenantId(),actor.companyId(),id);
    }
    private static long inherited(JdbcTemplate jdbc,ActorScope actor,String table,long id) {
        String source=switch(table) {
            case "shipment" -> "SELECT DISTINCT h.id,h.sales_user_id user_id FROM shipment_line l JOIN sales_order_line s ON s.id=l.order_line_id AND s.tenant_id=l.tenant_id AND s.company_id=l.company_id JOIN sales_order h ON h.id=s.order_id AND h.tenant_id=s.tenant_id AND h.company_id=s.company_id WHERE l.tenant_id=? AND l.company_id=? AND l.shipment_id=?";
            case "sales_return" -> "SELECT DISTINCT h.id,h.sales_user_id user_id FROM sales_return_line l JOIN shipment_line s ON s.id=l.shipment_line_id AND s.tenant_id=l.tenant_id AND s.company_id=l.company_id JOIN shipment h ON h.id=s.shipment_id AND h.tenant_id=s.tenant_id AND h.company_id=s.company_id WHERE l.tenant_id=? AND l.company_id=? AND l.return_id=?";
            case "goods_receipt" -> "SELECT DISTINCT h.id,h.buyer_user_id user_id FROM goods_receipt_line l JOIN purchase_order_line s ON s.id=l.order_line_id AND s.tenant_id=l.tenant_id AND s.company_id=l.company_id JOIN purchase_order h ON h.id=s.order_id AND h.tenant_id=s.tenant_id AND h.company_id=s.company_id WHERE l.tenant_id=? AND l.company_id=? AND l.receipt_id=?";
            case "purchase_return" -> "SELECT DISTINCT h.id,h.buyer_user_id user_id FROM purchase_return_line l JOIN goods_receipt_line s ON s.id=l.receipt_line_id AND s.tenant_id=l.tenant_id AND s.company_id=l.company_id JOIN goods_receipt h ON h.id=s.receipt_id AND h.tenant_id=s.tenant_id AND h.company_id=s.company_id WHERE l.tenant_id=? AND l.company_id=? AND l.return_id=?";
            case "receipt","payment" -> {
                String parent=table.equals("receipt")?"sales_order":"purchase_order";
                yield "SELECT h.id,h."+column(parent)+" user_id FROM "+table+" l JOIN "+parent+" h ON h.id=l.related_order_id AND h.tenant_id=l.tenant_id AND h.company_id=l.company_id WHERE l.tenant_id=? AND l.company_id=? AND l.id=?";
            }
            default -> null;
        };
        if(source==null) return actor.userId();
        String parent=switch(table) {case "shipment","receipt"->"sales_order";case "sales_return"->"shipment";case "goods_receipt","payment"->"purchase_order";default->"goods_receipt";};
        var users=new HashSet<Long>();
        for(var row:jdbc.queryForList(source,actor.tenantId(),actor.companyId(),id)) {
            OrgOwnership.require(jdbc,actor,parent,row.get("id"));users.add(((Number)row.get("user_id")).longValue());
        }
        if(users.size()>1) throw new ApiException(422,"BUSINESS_USER_REQUIRED","来源单据业务员不一致，请明确选择本单业务员");
        return users.isEmpty()?actor.userId():users.iterator().next();
    }
    private static ApiException missing() {return new ApiException(404,"NOT_FOUND","业务员或来源单据不存在，或不在当前公司范围内");}
}
