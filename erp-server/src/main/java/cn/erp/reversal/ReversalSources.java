package cn.erp.reversal;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.ManufacturingReversalPort;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import static cn.erp.salesfinance.FinanceSupport.*;

@Component
class ReversalSources {
    static final Set<String> TYPES=Set.of("shipment","sales_return","goods_receipt","purchase_return","receipt","payment","account_transfer","stock_adjustment","stock_misc_doc","mfg_issue_doc","mfg_receipt_doc","price_adjustment","purchase_fee_doc");
    private final JdbcTemplate jdbc;
    private final ManufacturingReversalPort production;
    private final cn.erp.shared.PriceAdjustmentReversalPort prices;
    ReversalSources(JdbcTemplate jdbc,ManufacturingReversalPort production,cn.erp.shared.PriceAdjustmentReversalPort prices){this.jdbc=jdbc;this.production=production;this.prices=prices;}
    private cn.erp.shared.PurchaseFeeReversalPort fees;
    @org.springframework.beans.factory.annotation.Autowired
    void fees(cn.erp.shared.PurchaseFeeReversalPort fees) {this.fees=fees;}
    Map<String,Object> head(CompanyAccess scope,String type,long source,boolean lock) {
        if(!TYPES.contains(type))throw invalid("该类型不支持冲销");
        if(ManufacturingReversalPort.supports(type))return production.source(scope,type,source,lock);
        cn.erp.shared.OrgOwnership.require(jdbc,scope,type,source);
        if(!scope.hasPermission(type+":view"))throw new ApiException(403,"FORBIDDEN","没有查看来源单据的权限");
        var rows=jdbc.queryForList("SELECT * FROM "+type+" WHERE tenant_id=? AND company_id=? AND id=?"+(lock?" FOR UPDATE":""),scope.tenantId(),scope.companyId(),source);
        if(rows.isEmpty())throw missing();
        var result=rows.getFirst();
        if(type.equals("purchase_fee_doc")) {
            cn.erp.shared.PurchaseFeeScope.require(jdbc,scope,result);
            if(!"manual".equals(result.get("source_kind"))) throw invalid("随单费用只能通过原收货单整体冲销");
        }
        if(type.equals("price_adjustment"))cn.erp.shared.PriceAdjustmentScope.require(jdbc,scope,result);
        if(result.get("warehouse_id")!=null&&!scope.allowsWarehouse(id(result,"warehouse_id")))throw missing();
        return result;
    }
    void posted(Map<String,Object> head,String type) {
        if(type.equals("shipment"))state(head,"posted","signed","signed_with_diff");
        else if(Set.of("receipt","payment","account_transfer").contains(type))state(head,"confirmed");
        else state(head,"posted");
    }
    String number(String type,Map<String,Object> head) {
        return head.get(switch(type){case "shipment"->"shipment_no";case "sales_return","purchase_return"->"return_no";case "goods_receipt","receipt"->"receipt_no";case "payment"->"payment_no";case "account_transfer"->"transfer_no";case "stock_misc_doc","mfg_issue_doc","mfg_receipt_doc","purchase_fee_doc"->"document_no";default->"adjust_no";}).toString();
    }
    List<String> warehouses(Map<String,Object> head) {return head.get("warehouse_id")==null?List.of():List.of(head.get("warehouse_id").toString());}

    List<Map<String,Object>> facts(CompanyAccess scope,String type,long source,String fact,boolean lock) {
        String allowed=switch(type) {
            case "shipment" -> fact.equals("receivable")?"'shipment_line','sales_order_fee'":null;
            case "sales_return" -> fact.equals("receivable")?"'sales_return_line'":null;
            case "goods_receipt" -> fact.equals("payable")?"'goods_receipt_line','purchase_order_fee'":null;
            case "purchase_return" -> fact.equals("payable")?"'purchase_return_line'":null;
            case "price_adjustment" -> fact.equals("payable")?"'price_adjustment_line'":null;
            case "purchase_fee_doc" -> fact.equals("payable")?"'purchase_fee_doc_line'":null;
            default -> null;
        };
        if(allowed==null)return List.of();
        return jdbc.queryForList("SELECT * FROM "+fact+" WHERE tenant_id=? AND company_id=? AND source_id=? AND source_type IN("+allowed+") AND posting_action='post' ORDER BY id"+(lock?" FOR UPDATE":""),scope.tenantId(),scope.companyId(),source);
    }
    List<Map<String,Object>> items(CompanyAccess scope,String type,long source,boolean lock) {
        var ids=new TreeSet<Long>();
        for(String fact:List.of("receivable","payable"))for(var row:facts(scope,type,source,fact,lock)) {
            ids.addAll(jdbc.queryForList("SELECT id FROM partner_open_item WHERE tenant_id=? AND company_id=? AND item_type=? AND source_id=? FOR SHARE",Long.class,scope.tenantId(),scope.companyId(),fact,row.get("id")));
        }
        if(Set.of("receipt","payment").contains(type))ids.addAll(jdbc.queryForList("SELECT id FROM partner_open_item WHERE tenant_id=? AND company_id=? AND item_type=? AND source_id=? FOR SHARE",Long.class,scope.tenantId(),scope.companyId(),type,source));
        var result=new ArrayList<Map<String,Object>>();
        for(long id:ids)result.add(jdbc.queryForMap("SELECT * FROM partner_open_item WHERE tenant_id=? AND company_id=? AND id=?"+(lock?" FOR UPDATE":""),scope.tenantId(),scope.companyId(),id));
        return result;
    }
    void dependencies(CompanyAccess scope,String type,long source) {
        if(ManufacturingReversalPort.supports(type)) {production.dependencies(scope,type,source);return;}
        var found=new ArrayList<Map<String,Object>>();
        if(type.equals("price_adjustment")) prices.dependencies(scope,source);
        if(type.equals("purchase_fee_doc")) fees.dependencies(scope,source);
        if(type.equals("goods_receipt")) {
            for(var row:jdbc.queryForList("SELECT DISTINCT h.id,h.document_no,h.warehouse_id FROM purchase_fee_doc h JOIN purchase_fee_allocation a ON a.tenant_id=h.tenant_id AND a.company_id=h.company_id AND a.document_id=h.id JOIN goods_receipt_line l ON l.tenant_id=a.tenant_id AND l.company_id=a.company_id AND l.id=a.receipt_line_id WHERE h.tenant_id=? AND h.company_id=? AND l.receipt_id=? AND h.source_kind='manual' AND h.status NOT IN('voided','reversed','rejected')",scope.tenantId(),scope.companyId(),source)) found.add(dependency(scope,"purchase_fee_doc",row,"费用引用了本收货单，请先处理费用单"));
            for(var row:jdbc.queryForList("SELECT DISTINCT h.id,h.adjust_no document_no,h.warehouse_id FROM price_adjustment h JOIN price_adjustment_line l ON l.tenant_id=h.tenant_id AND l.company_id=h.company_id AND l.adjustment_id=h.id JOIN goods_receipt_line r ON r.tenant_id=l.tenant_id AND r.company_id=l.company_id AND r.id=l.receipt_line_id WHERE h.tenant_id=? AND h.company_id=? AND r.receipt_id=? AND h.status NOT IN('voided','reversed','rejected')",scope.tenantId(),scope.companyId(),source)) found.add(dependency(scope,"price_adjustment",row,"调价引用了本收货单，请先处理调价单"));
        }
        if(type.equals("shipment"))children(scope,found,"sales_return","return_no","sales_return_line","return_id","shipment_line","shipment_id","shipment_line_id",source);
        if(type.equals("goods_receipt"))children(scope,found,"purchase_return","return_no","purchase_return_line","return_id","goods_receipt_line","receipt_id","receipt_line_id",source);
        if(type.equals("stock_misc_doc"))for(var row:jdbc.queryForList("SELECT id,document_no,warehouse_id FROM stock_misc_doc WHERE tenant_id=? AND company_id=? AND original_loan_id=? AND status NOT IN('voided','reversed') ORDER BY id FOR SHARE",scope.tenantId(),scope.companyId(),source))
            found.add(dependency(scope,type,row,"归还单引用了本借出单，请先处理归还单"));
        if(Set.of("purchase_return","stock_adjustment").contains(type)&&!jdbc.queryForList("""
                SELECT d.id FROM qc_disposition d JOIN qc_inspection q
                  ON q.tenant_id=d.tenant_id AND q.company_id=d.company_id AND q.id=d.inspection_id
                WHERE d.tenant_id=? AND d.company_id=? AND d.target_type=? AND d.target_id=?
                  AND (q.round_no<>d.round_no OR q.status<>'approved') FOR SHARE
                """,Long.class,scope.tenantId(),scope.companyId(),type,source).isEmpty())
            throw new ApiException(422,"DEPENDENCY_EXISTS","该处置关联的来料检已进入新的检验轮次，不能单独冲销");
        if(type.equals("receipt"))for(var row:jdbc.queryForList("SELECT id,refund_no document_no FROM refund_request WHERE tenant_id=? AND company_id=? AND original_receipt_id=? AND status<>'cancelled' ORDER BY id FOR SHARE",scope.tenantId(),scope.companyId(),source))found.add(dependency(scope,"refund_request",row,"退款引用了本收款单"));
        for(String fact:List.of("receivable","payable"))for(var row:facts(scope,type,source,fact,true)) {
            if(decimal(row,"invoiced_amount").signum()>0) {
                var invoices=jdbc.queryForList("SELECT h.id,h.invoice_no document_no FROM invoice_registration h JOIN invoice_registration_line l ON l.tenant_id=h.tenant_id AND l.company_id=h.company_id AND l.invoice_id=h.id WHERE h.tenant_id=? AND h.company_id=? AND l."+(fact.equals("receivable")?"receivable_id":"payable_id")+"=? AND h.status='registered' ORDER BY h.id FOR SHARE",scope.tenantId(),scope.companyId(),row.get("id"));
                for(var invoice:invoices)found.add(dependency(scope,"invoice_registration",invoice,"来源往来已登记开票，请先作废开票登记"));
            }
        }
        for(var item:items(scope,type,source,true)) {
            for(var row:jdbc.queryForList("SELECT id FROM settlement_allocation WHERE tenant_id=? AND company_id=? AND (debit_item_id=? OR credit_item_id=?) AND status IN ('active','reserved') ORDER BY id FOR UPDATE",scope.tenantId(),scope.companyId(),item.get("id"),item.get("id")))found.add(dependency(scope,"settlement_allocation",row,"本单的未清项已被核销或退款占用"));
        }
        if(!found.isEmpty())throw new ApiException(422,"DEPENDENCY_EXISTS","存在下游单据，请先处理",found);
    }
    void prepare(CompanyAccess scope,String type,long source,long period,LocalDate day) {
        if(ManufacturingReversalPort.supports(type))production.prepare(scope,type,source,period,day);
        if(type.equals("price_adjustment"))prices.prepare(scope,source);
        if(type.equals("purchase_fee_doc"))fees.prepare(scope,source);
    }
    void restoreProgress(CompanyAccess scope,String type,Map<String,Object> source) {
        if(ManufacturingReversalPort.supports(type))production.restoreProgress(scope,type,source);
        if(type.equals("price_adjustment"))prices.restore(scope,id(source,"id"));
    }
    private void children(CompanyAccess scope,List<Map<String,Object>> result,String child,String number,String lines,String headId,String original,String originalHead,String sourceKey,long source) {
        String sql="SELECT DISTINCT h.id,h."+number+" document_no FROM "+child+" h JOIN "+lines+" l ON l.tenant_id=h.tenant_id AND l.company_id=h.company_id AND l."+headId+"=h.id JOIN "+original+" o ON o.tenant_id=l.tenant_id AND o.company_id=l.company_id AND o.id=l."+sourceKey+" WHERE h.tenant_id=? AND h.company_id=? AND o."+originalHead+"=? AND h.status NOT IN ('voided','reversed') ORDER BY h.id FOR SHARE";
        for(var row:jdbc.queryForList(sql,scope.tenantId(),scope.companyId(),source))result.add(dependency(scope,child,row,"退货引用了本单，请先处理退货单"));
    }
    private Map<String,Object> dependency(CompanyAccess scope,String type,Map<String,Object> row,String relation) {
        try {
            if(row.get("warehouse_id")!=null&&!scope.allowsWarehouse(id(row,"warehouse_id")))throw missing();
            if(type.equals("settlement_allocation")) cn.erp.shared.OrgOwnership.requireAllocation(jdbc,scope,row.get("id"));
            else cn.erp.shared.OrgOwnership.requireSource(jdbc,scope,type,row.get("id"));
        } catch(ApiException denied) {
            if(denied.getStatus()!=404) throw denied;
            // 仍阻止破坏下游事实，但不在依赖错误中暴露范围外单号和标识。
            return Map.of("documentType",type,"relation","存在范围外的下游引用，请联系有权限的人员处理");
        }
        var result=new LinkedHashMap<String,Object>();result.put("documentType",type);result.put("documentId",row.get("id").toString());result.put("documentNo",row.get("document_no"));result.put("relation",relation);return result;
    }
}
