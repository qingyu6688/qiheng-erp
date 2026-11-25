package cn.erp.adjustment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiRows;
import cn.erp.shared.OrgOwnership;
import cn.erp.shared.SalesInvoiceAmounts;

@Component
@Transactional(readOnly=true)
public class SalesPriceAdjustmentQueries {
    static final String TYPE="sales_price_adjustment";
    private final JdbcTemplate jdbc;
    private final SalesInvoiceAmounts invoices;
    SalesPriceAdjustmentQueries(JdbcTemplate jdbc, SalesInvoiceAmounts invoices) { this.jdbc=jdbc; this.invoices=invoices; }

    static void require(CompanyAccess actor,String permission) {
        if(!actor.hasPermission(permission))throw new ApiException(403,"FORBIDDEN","没有对应的销售调价权限");
    }
    static void requireFinancial(CompanyAccess actor,String permission) {
        require(actor,permission);
        if(!actor.allWarehouses()||!actor.orgScope().allDepartments())throw new ApiException(403,"FORBIDDEN","销售调价复核和过账需要当前公司的完整仓库、部门范围");
    }
    Map<String,Object> head(CompanyAccess actor,long id,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM price_adjustment WHERE tenant_id=? AND company_id=? AND id=? AND kind='sales'"+(lock?" FOR UPDATE":""),actor.tenantId(),actor.companyId(),id);
        if(rows.isEmpty()||!actor.allowsWarehouse(number(rows.getFirst(),"warehouse_id")))throw missing();
        OrgOwnership.require(actor,rows.getFirst());return rows.getFirst();
    }
    List<Map<String,Object>> lines(CompanyAccess actor,long id) {
        return jdbc.queryForList("SELECT l.*,s.name sku_name,u.name unit_name,h.shipment_no FROM price_adjustment_line l"
                +" JOIN sku s ON s.tenant_id=l.tenant_id AND s.id=l.sku_id JOIN unit u ON u.tenant_id=l.tenant_id AND u.id=l.unit_id"
                +" JOIN shipment_line sl ON sl.tenant_id=l.tenant_id AND sl.company_id=l.company_id AND sl.id=l.shipment_line_id"
                +" JOIN shipment h ON h.tenant_id=sl.tenant_id AND h.company_id=sl.company_id AND h.id=sl.shipment_id"
                +" WHERE l.tenant_id=? AND l.company_id=? AND l.adjustment_id=? ORDER BY l.line_no",actor.tenantId(),actor.companyId(),id);
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> get(CompanyAccess actor,long id) {
        require(actor,TYPE+":view");var header=head(actor,id,false);var result=ApiRows.row(header);
        result.remove("stockCost");result.remove("issuedCost");
        result.put("documentNo",header.get("adjust_no"));
        result.put("partnerName",jdbc.queryForObject("SELECT name FROM business_partner WHERE tenant_id=? AND id=?",String.class,actor.tenantId(),header.get("partner_id")));
        result.put("warehouseName",jdbc.queryForObject("SELECT name FROM warehouse WHERE tenant_id=? AND company_id=? AND id=?",String.class,actor.tenantId(),actor.companyId(),header.get("warehouse_id")));
        var sourceLines=lines(actor,id);
        var negativeSources=negativeSources(sourceLines);
        var incomplete=invoices.incompleteRedSources(actor,negativeSources);
        var redPage=actor.hasPermission("invoice_registration:view")?invoices.redInvoicePage(actor,negativeSources,null,20):Map.<String,Object>of("items",List.of());
        var redItems=(List<Map<String,Object>>)redPage.get("items");
        result.put("redInvoices",redItems);result.put("redInvoiceCursor",redPage.get("nextCursor"));
        result.put("lines",sourceLines.stream().map(raw->{
            var line=ApiRows.row(raw);
            for(String field:List.of("stockCost","issuedCost","costTaxInclusive","priorReturnBasis"))line.remove(field);
            if(amount(raw,"amount").signum()<0) {
                line.put("needsRed",incomplete.contains(number(raw,"shipment_line_id")));
                if(actor.hasPermission("invoice_registration:view")) {
                    line.put("redInvoices",redItems.stream().filter(row->((List<?>)row.get("shipmentLineIds")).contains(raw.get("shipment_line_id").toString())).map(row->{
                        var summary=new LinkedHashMap<>(row);summary.remove("shipmentLineIds");return summary;
                    }).toList());
                    line.put("redInvoiceCursor",redPage.get("nextCursor"));
                }
            } else line.put("needsRed",false);
            return line;
        }).toList());
        result.put("allowedActions",actions(actor,header));return result;
    }
    public Map<String,Object> redInvoices(CompanyAccess actor,long id,Long lineId,Long cursor,int limit) {
        require(actor,TYPE+":view");require(actor,"invoice_registration:view");head(actor,id,false);
        var selected=lines(actor,id).stream().filter(line->amount(line,"amount").signum()<0).toList();
        if(lineId!=null) {
            selected=selected.stream().filter(line->number(line,"id")==lineId).toList();
            if(selected.isEmpty())throw missing();
        }
        return invoices.redInvoicePage(actor,negativeSources(selected),cursor,limit);
    }

    private static List<Long> negativeSources(List<Map<String,Object>> lines) {
        return lines.stream().filter(line->amount(line,"amount").signum()<0).map(line->number(line,"shipment_line_id")).distinct().toList();
    }
    public Map<String,Object> list(CompanyAccess actor,String status,Long partner,Long cursor,int limit) {
        require(actor,TYPE+":view");page(cursor,limit);
        if(partner!=null&&partner<=0)throw invalid("客户编号无效");
        if(status!=null&&!Set.of("draft","pending","approved","rejected","posted","voided","reversed").contains(status))throw invalid("销售调价状态无效");
        var args=new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId(),cursor==null?Long.MAX_VALUE:cursor));
        String filter=" AND p.kind='sales'"+OrgOwnership.filter(actor,"p")+warehouseFilter(actor,"p");
        if(status!=null){filter+=" AND p.status=?";args.add(status);}if(partner!=null){filter+=" AND p.partner_id=?";args.add(partner);}args.add(limit+1);
        var ids=jdbc.queryForList("SELECT p.id FROM price_adjustment p WHERE p.tenant_id=? AND p.company_id=? AND p.id<?"+filter+" ORDER BY p.id DESC LIMIT ?",Long.class,args.toArray());
        return paged(ids.stream().limit(limit).map(id->get(actor,id)).toList(),ids,limit);
    }
    public Map<String,Object> sources(CompanyAccess actor,Long partner,Long warehouse,Long cursor,int limit) {
        require(actor,TYPE+":view");require(actor,"shipment:view");page(cursor,limit);
        if(partner!=null&&partner<=0||warehouse!=null&&warehouse<=0)throw invalid("客户或仓库编号无效");
        var args=new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId(),cursor==null?Long.MAX_VALUE:cursor));
        String filter=OrgOwnership.filter(actor,"h")+warehouseFilter(actor,"h");
        if(partner!=null){filter+=" AND h.customer_id=?";args.add(partner);}if(warehouse!=null){filter+=" AND h.warehouse_id=?";args.add(warehouse);}args.add(limit+1);
        var ids=jdbc.queryForList("SELECT l.id FROM shipment_line l JOIN shipment h ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.shipment_id"
                +" WHERE l.tenant_id=? AND l.company_id=? AND l.id<? AND h.status IN('signed','signed_with_diff')"
                +" AND l.signed_base_qty>l.returned_base_qty AND l.pending_return_base_qty=0"+filter+" ORDER BY l.id DESC LIMIT ?",Long.class,args.toArray());
        var items=ids.stream().limit(limit).map(id->{var raw=source(actor,id,false);Map<String,Object> row=new LinkedHashMap<>();
            row.put("id",Long.toString(id));for(String field:List.of("shipment_id","shipment_no","customer_id","warehouse_id","sku_id","sku_name","unit_id","unit_name","version"))row.putAll(ApiRows.row(Map.of(field,raw.get(field))));
            row.put("returnableQty",remaining(raw).toPlainString());row.put("amount",gross(raw).toPlainString());row.put("taxAmount",tax(raw).toPlainString());return row;
        }).toList();return paged(items,ids,limit);
    }
    public Map<String,Object> metadata(CompanyAccess actor) {
        require(actor,TYPE+":view");require(actor,"shipment:view");
        String filter=OrgOwnership.filter(actor,"h")+warehouseFilter(actor,"h");
        var rows=jdbc.queryForList("SELECT DISTINCT p.id partner_id,p.name partner_name,w.id warehouse_id,w.name warehouse_name FROM shipment h"
                +" JOIN business_partner p ON p.tenant_id=h.tenant_id AND p.id=h.customer_id AND p.status='active'"
                +" JOIN partner_role r ON r.tenant_id=p.tenant_id AND r.partner_id=p.id AND r.role='customer' AND r.status='active'"
                +" JOIN warehouse w ON w.tenant_id=h.tenant_id AND w.company_id=h.company_id AND w.id=h.warehouse_id AND w.status='active'"
                +" WHERE h.tenant_id=? AND h.company_id=? AND h.status IN('signed','signed_with_diff')"+filter
                +" AND EXISTS(SELECT 1 FROM shipment_line l WHERE l.tenant_id=h.tenant_id AND l.company_id=h.company_id AND l.shipment_id=h.id AND l.signed_base_qty>l.returned_base_qty AND l.pending_return_base_qty=0) ORDER BY p.id,w.id",actor.tenantId(),actor.companyId());
        var partners=new LinkedHashMap<String,Map<String,String>>();var warehouses=new LinkedHashMap<String,Map<String,String>>();
        for(var row:rows){String p=row.get("partner_id").toString(),w=row.get("warehouse_id").toString();partners.put(p,Map.of("id",p,"name",row.get("partner_name").toString()));warehouses.put(w,Map.of("id",w,"name",row.get("warehouse_name").toString()));}
        return Map.of("partners",List.copyOf(partners.values()),"warehouses",List.copyOf(warehouses.values()));
    }
    Map<String,Object> source(CompanyAccess actor,long id,boolean lock) {
        require(actor,"shipment:view");
        var rows=jdbc.queryForList("SELECT l.*,h.shipment_no,h.customer_id,h.warehouse_id,h.status shipment_status,h.sign_date,h.owner_user_id,h.owner_department_id,o.tax_rate,o.order_id,s.name sku_name,u.name unit_name"
                +" FROM shipment_line l JOIN shipment h ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.shipment_id"
                +" JOIN sales_order_line o ON o.tenant_id=l.tenant_id AND o.company_id=l.company_id AND o.id=l.order_line_id"
                +" JOIN sku s ON s.tenant_id=l.tenant_id AND s.id=l.sku_id JOIN unit u ON u.tenant_id=l.tenant_id AND u.id=l.unit_id"
                +" WHERE l.tenant_id=? AND l.company_id=? AND l.id=?"+(lock?" FOR UPDATE":""),actor.tenantId(),actor.companyId(),id);
        if(rows.isEmpty()||!actor.allowsWarehouse(number(rows.getFirst(),"warehouse_id")))throw missing();OrgOwnership.require(actor,rows.getFirst());return rows.getFirst();
    }
    void eligible(CompanyAccess actor,Map<String,Object> row,long partner,long warehouse,LocalDate day) {
        if(!Set.of("signed","signed_with_diff").contains(row.get("shipment_status")))throw invalid("只能调整已签收的发货行");
        if(number(row,"customer_id")!=partner||number(row,"warehouse_id")!=warehouse)throw invalid("调价行的客户和仓库必须与调整单一致");
        if(day.isBefore(date(row,"sign_date")))throw invalid("调价日期不能早于原签收日期");
        if(remaining(row).signum()<=0||amount(row,"pending_return_base_qty").signum()!=0)throw invalid("原发货行已无可调数量或有待完成退货");
        if(!jdbc.queryForList("SELECT id FROM document_reversal WHERE tenant_id=? AND company_id=? AND source_type='shipment' AND source_id=? AND status<>'rejected'",actor.tenantId(),actor.companyId(),row.get("shipment_id")).isEmpty())throw invalid("原发货单正在冲销，不能调价");
        if(!jdbc.queryForList("SELECT h.id FROM price_adjustment h JOIN price_adjustment_line l ON l.tenant_id=h.tenant_id AND l.company_id=h.company_id AND l.adjustment_id=h.id"
                +" WHERE h.tenant_id=? AND h.company_id=? AND l.shipment_line_id=? AND h.status='posted' AND h.business_date>? LIMIT 1",actor.tenantId(),actor.companyId(),row.get("id"),day).isEmpty())throw invalid("调价日期不能早于该行已过账调价");
    }
    private List<String> actions(CompanyAccess actor,Map<String,Object> head) {
        var allowed=switch(head.get("status").toString()){case "draft","rejected"->List.of("update","submit","void");case "pending"->List.of("approve","reject");case "approved"->List.of("post","void");default->List.<String>of();};
        boolean flow=number(head,"wf_instance_id")>0&&!jdbc.queryForList("SELECT id FROM wf_instance WHERE tenant_id=? AND company_id=? AND id=? AND status='running'",actor.tenantId(),actor.companyId(),head.get("wf_instance_id")).isEmpty();
        return allowed.stream().filter(a->actor.hasPermission(TYPE+":"+(a.equals("reject")?"approve":a)))
                .filter(a->!Set.of("approve","reject","post").contains(a)||actor.allWarehouses()&&actor.orgScope().allDepartments())
                .filter(a->!Set.of("approve","reject").contains(a)||number(head,"created_by")!=actor.userId()&&!flow).toList();
    }
    private static String warehouseFilter(CompanyAccess actor,String alias) {return actor.allWarehouses()?"":actor.warehouseIds().isEmpty()?" AND 1=0":" AND "+alias+".warehouse_id IN("+actor.warehouseIds().stream().sorted().map(Object::toString).collect(java.util.stream.Collectors.joining(","))+")";}
    private static void page(Long cursor,int limit){if(limit<1||limit>100||cursor!=null&&cursor<=0)throw invalid("分页参数无效");}
    private static Map<String,Object> paged(List<Map<String,Object>> items,List<Long> ids,int limit){var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("nextCursor",ids.size()>limit?ids.get(limit-1).toString():null);return result;}
    static BigDecimal remaining(Map<String,Object> row){return amount(row,"signed_base_qty").subtract(amount(row,"returned_base_qty"));}
    static BigDecimal gross(Map<String,Object> row){return amount(row,"receivable_amount").add(amount(row,"price_adjusted_amount")).subtract(amount(row,"credited_amount"));}
    static BigDecimal tax(Map<String,Object> row){return amount(row,"tax_amount").add(amount(row,"price_adjusted_tax")).subtract(amount(row,"credited_tax"));}
    static long number(Map<String,Object> row,String field){return ((Number)row.get(field)).longValue();}
    static BigDecimal amount(Map<String,Object> row,String field){return (BigDecimal)row.get(field);}
    static LocalDate date(Map<String,Object> row,String field){return ((java.sql.Date)row.get(field)).toLocalDate();}
    static ApiException invalid(String message){return new ApiException(400,"VALIDATION_FAILED",message);}
    static ApiException missing(){return new ApiException(404,"NOT_FOUND","销售调价单或原发货不在当前可见范围");}
}
