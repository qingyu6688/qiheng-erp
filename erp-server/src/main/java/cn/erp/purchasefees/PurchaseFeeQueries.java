package cn.erp.purchasefees;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.*;

@Component
@Transactional(readOnly=true)
public class PurchaseFeeQueries {
    static final String TYPE="purchase_fee_doc";
    private final JdbcTemplate jdbc;
    public PurchaseFeeQueries(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    static void require(CompanyAccess actor,String permission) {
        if(!actor.hasPermission(permission)||!actor.hasPermission("cost:view"))
            throw new ApiException(403,"FORBIDDEN","采购费用操作需要对应权限和成本查看权限");
    }
    Map<String,Object> head(CompanyAccess actor,long id,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM purchase_fee_doc WHERE tenant_id=? AND company_id=? AND id=?"+(lock?" FOR UPDATE":""),actor.tenantId(),actor.companyId(),id);
        if(rows.isEmpty()) throw missing();
        PurchaseFeeScope.require(jdbc,actor,rows.getFirst());
        return rows.getFirst();
    }
    List<Map<String,Object>> lines(CompanyAccess actor,long id) {
        return jdbc.queryForList("SELECT l.*,p.due_date FROM purchase_fee_doc_line l LEFT JOIN payable p"
                +" ON p.tenant_id=l.tenant_id AND p.company_id=l.company_id AND p.source_line_id=l.id AND p.posting_action='post'"
                +" AND p.source_type IN('purchase_fee_doc_line','purchase_order_fee')"
                +" WHERE l.tenant_id=? AND l.company_id=? AND l.document_id=? ORDER BY l.line_no",actor.tenantId(),actor.companyId(),id);
    }
    List<Map<String,Object>> allocations(CompanyAccess actor,long id) {
        return jdbc.queryForList("SELECT a.*,s.name sku_name,u.name unit_name,r.receipt_id,h.receipt_no,h.warehouse_id source_warehouse_id,w.name source_warehouse_name"
                +" FROM purchase_fee_allocation a JOIN sku s ON s.tenant_id=a.tenant_id AND s.id=a.sku_id"
                +" JOIN unit u ON u.tenant_id=a.tenant_id AND u.id=a.unit_id"
                +" JOIN goods_receipt_line r ON r.tenant_id=a.tenant_id AND r.company_id=a.company_id AND r.id=a.receipt_line_id"
                +" JOIN goods_receipt h ON h.tenant_id=r.tenant_id AND h.company_id=r.company_id AND h.id=r.receipt_id"
                +" JOIN warehouse w ON w.tenant_id=h.tenant_id AND w.company_id=h.company_id AND w.id=h.warehouse_id"
                +" WHERE a.tenant_id=? AND a.company_id=? AND a.document_id=? ORDER BY a.receipt_line_id",actor.tenantId(),actor.companyId(),id);
    }
    public Map<String,Object> get(CompanyAccess actor,long id) {
        require(actor,TYPE+":view");
        var header=head(actor,id,false);
        var result=ApiRows.row(header);
        result.put("partnerName",jdbc.queryForObject("SELECT name FROM business_partner WHERE tenant_id=? AND id=?",String.class,actor.tenantId(),header.get("partner_id")));
        result.put("lines",lines(actor,id).stream().map(ApiRows::row).toList());
        result.put("allocations",allocations(actor,id).stream().map(ApiRows::row).toList());
        result.put("costAllocations",jdbc.queryForList("SELECT c.*,w.name warehouse_name FROM purchase_fee_cost c JOIN warehouse w"
                +" ON w.tenant_id=c.tenant_id AND w.company_id=c.company_id AND w.id=c.warehouse_id"
                +" WHERE c.tenant_id=? AND c.company_id=? AND c.document_id=? ORDER BY c.allocation_id,c.warehouse_id",actor.tenantId(),actor.companyId(),id).stream().map(ApiRows::row).toList());
        result.put("allowedActions",actions(actor,header));
        result.put("canPreviewCost","manual".equals(header.get("source_kind"))&&actor.allWarehouses()&&actor.orgScope().allDepartments()
                &&Set.of("draft","rejected","pending","approved").contains(header.get("status")));
        return result;
    }
    public Map<String,Object> list(CompanyAccess actor,String status,Long partner,Long cursor,int limit) {
        require(actor,TYPE+":view");
        if(limit<1||limit>100||cursor!=null&&cursor<=0) throw invalid("分页参数无效");
        if(status!=null&&!Set.of("draft","pending","approved","rejected","posted","voided","reversed").contains(status)) throw invalid("费用单状态无效");
        var args=new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId(),cursor==null?Long.MAX_VALUE:cursor));
        String filter="fd.tenant_id=? AND fd.company_id=? AND fd.id<?"+OrgOwnership.filter(actor,"fd")+" AND "+PurchaseFeeScope.condition(actor,"fd");
        if(status!=null) {filter+=" AND fd.status=?";args.add(status);}
        if(partner!=null) {filter+=" AND fd.partner_id=?";args.add(partner);}
        args.add(limit+1);
        var rows=jdbc.queryForList("SELECT fd.*,p.name partner_name FROM purchase_fee_doc fd JOIN business_partner p ON p.tenant_id=fd.tenant_id AND p.id=fd.partner_id WHERE "+filter+" ORDER BY fd.id DESC LIMIT ?",args.toArray());
        var result=new LinkedHashMap<String,Object>();
        result.put("items",rows.stream().limit(limit).map(ApiRows::row).toList());
        result.put("nextCursor",rows.size()>limit?rows.get(limit-1).get("id").toString():null);
        return result;
    }
    public Map<String,Object> metadata(CompanyAccess actor) {
        require(actor,TYPE+":view");
        if(!actor.hasPermission("partner:view")||!actor.hasPermission("goods_receipt:view"))
            throw new ApiException(403,"FORBIDDEN","选择费用承运商和收货来源需要往来单位与收货查看权限");
        var partners=jdbc.queryForList("SELECT p.id,p.name FROM business_partner p JOIN partner_role r ON r.tenant_id=p.tenant_id AND r.partner_id=p.id"
                +" AND r.role='supplier' AND r.status='active' WHERE p.tenant_id=? AND p.status='active' ORDER BY p.id",actor.tenantId());
        var policy=policy(actor,false);
        return Map.of("partners",partners.stream().map(ApiRows::row).toList(),"costEnabled",flag(policy,"purchase_fee_cost_enabled"),
                "allocationMethod",policy.get("purchase_fee_allocation_method"));
    }
    Map<String,Object> policy(CompanyAccess actor,boolean lock) {
        if(lock) jdbc.queryForObject("SELECT id FROM company WHERE tenant_id=? AND id=? FOR SHARE",Long.class,actor.tenantId(),actor.companyId());
        var rows=jdbc.queryForList("SELECT taxpayer_type,purchase_fee_cost_enabled,purchase_fee_allocation_method FROM company_policy WHERE tenant_id=? AND company_id=?"+(lock?" FOR SHARE":""),actor.tenantId(),actor.companyId());
        return rows.isEmpty()?Map.of("taxpayer_type","general","purchase_fee_cost_enabled",true,"purchase_fee_allocation_method","amount"):rows.getFirst();
    }
    boolean requireEnabled(CompanyAccess actor) {
        var policy=policy(actor,true);
        if(!flag(policy,"purchase_fee_cost_enabled")) throw invalid("公司已关闭采购费用资本化，请使用费用付款登记");
        return "small".equals(policy.get("taxpayer_type"));
    }
    Map<String,Object> source(CompanyAccess actor,long id,boolean lock) {
        if(!actor.hasPermission("goods_receipt:view")) throw new ApiException(403,"FORBIDDEN","选择来源需要收货查看权限");
        var rows=jdbc.queryForList("SELECT l.*,h.warehouse_id,h.status receipt_status,h.business_date receipt_date,h.owner_user_id,h.owner_department_id,COALESCE(s.weight_kg,0) sku_weight_kg"
                +" FROM goods_receipt_line l JOIN goods_receipt h ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.receipt_id"
                +" JOIN sku s ON s.tenant_id=l.tenant_id AND s.id=l.sku_id WHERE l.tenant_id=? AND l.company_id=? AND l.id=?"+(lock?" FOR UPDATE":""),actor.tenantId(),actor.companyId(),id);
        if(rows.isEmpty()||!actor.allowsWarehouse(number(rows.getFirst(),"warehouse_id"))) throw missing();
        OrgOwnership.require(actor,rows.getFirst());
        return rows.getFirst();
    }
    void eligible(CompanyAccess actor,Map<String,Object> source,LocalDate day) {
        if(!"posted".equals(source.get("receipt_status"))) throw invalid("费用来源必须是已过账收货行");
        if(day.isBefore(date(source,"receipt_date"))) throw invalid("费用日期不能早于来源收货日期");
        if(amount(source,"pending_return_base_qty").signum()!=0) throw invalid("来源有未完成的采购退货，请先处理");
        if(remaining(source).signum()<=0) throw invalid("已全部退货的来源不能分摊费用");
        if(!jdbc.queryForList("SELECT id FROM document_reversal WHERE tenant_id=? AND company_id=? AND source_type='goods_receipt' AND source_id=? AND status<>'rejected'",
                Long.class,actor.tenantId(),actor.companyId(),source.get("receipt_id")).isEmpty()) throw invalid("来源收货正在冲销，不能分摊费用");
    }
    private List<String> actions(CompanyAccess actor,Map<String,Object> header) {
        if(!"manual".equals(header.get("source_kind"))) return List.of();
        var actions=switch(header.get("status").toString()) {
            case "draft","rejected" -> List.of("update","submit","void");
            case "pending" -> List.of("approve","reject");
            case "approved" -> List.of("post","void");
            default -> List.<String>of();
        };
        boolean flow=number(header,"wf_instance_id")>0&&!jdbc.queryForList("SELECT id FROM wf_instance WHERE tenant_id=? AND company_id=? AND id=? AND status='running'",Long.class,actor.tenantId(),actor.companyId(),header.get("wf_instance_id")).isEmpty();
        return actions.stream().filter(a->actor.hasPermission(TYPE+":"+(a.equals("reject")?"approve":a)))
                .filter(a->!Set.of("approve","reject","post").contains(a)||actor.allWarehouses()&&actor.orgScope().allDepartments())
                .filter(a->!Set.of("approve","reject").contains(a)||number(header,"created_by")!=actor.userId()&&!flow).toList();
    }
    static BigDecimal remaining(Map<String,Object> source) {return amount(source,"base_qty").subtract(amount(source,"returned_base_qty"));}
    static BigDecimal remainingAmount(Map<String,Object> source) {return amount(source,"payable_amount").add(amount(source,"price_adjusted_amount")).subtract(amount(source,"debited_amount"));}
    static BigDecimal remainingTax(Map<String,Object> source) {return amount(source,"tax_amount").add(amount(source,"price_adjusted_tax")).subtract(amount(source,"debited_tax"));}
    static void day(LocalDate day) {if(day==null||day.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))) throw invalid("业务日期不能为空或晚于今天");}
    static long number(Map<String,Object> row,String key) {return ((Number)row.get(key)).longValue();}
    static BigDecimal amount(Map<String,Object> row,String key) {return (BigDecimal)row.get(key);}
    static LocalDate date(Map<String,Object> row,String key) {return ((java.sql.Date)row.get(key)).toLocalDate();}
    static boolean flag(Map<String,Object> row,String key) {Object v=row.get(key);return Boolean.TRUE.equals(v)||v instanceof Number n&&n.intValue()!=0;}
    static ApiException invalid(String message) {return new ApiException(400,"VALIDATION_FAILED",message);}
    static ApiException missing() {return new ApiException(404,"NOT_FOUND","费用单或收货来源不在当前可见范围");}
}
