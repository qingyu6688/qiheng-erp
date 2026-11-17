package cn.erp.stockmisc;

import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryService;
import cn.erp.shared.OrgOwnership;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import static cn.erp.salesfinance.FinanceSupport.*;

@Component
class StockMiscQueries {
    static final String RESOURCE="stock_misc_doc";
    private static final Set<String> STATES=Set.of("draft","pending","approved","posted","reversed","voided");
    private final JdbcTemplate jdbc;
    private final InventoryService inventory;

    StockMiscQueries(JdbcTemplate jdbc,InventoryService inventory){this.jdbc=jdbc;this.inventory=inventory;}

    Map<String,Object> head(CompanyAccess actor,long document,boolean lock){
        var rows=jdbc.queryForList("SELECT h.* FROM stock_misc_doc h WHERE h.tenant_id=? AND h.company_id=? AND h.id=?"
                +OrgOwnership.filter(actor,"h")+(lock?" FOR UPDATE":""),actor.tenantId(),actor.companyId(),document);
        if(rows.isEmpty()||!actor.allowsWarehouse(id(rows.getFirst(),"warehouse_id")))throw missing();
        return rows.getFirst();
    }

    List<Map<String,Object>> lines(CompanyAccess actor,long document,boolean lock){
        return jdbc.queryForList("""
                SELECT l.*,s.name sku_name,u.name unit_name
                FROM stock_misc_line l JOIN sku s ON s.tenant_id=l.tenant_id AND s.id=l.sku_id
                JOIN unit u ON u.tenant_id=l.tenant_id AND u.id=l.unit_id
                WHERE l.tenant_id=? AND l.company_id=? AND l.doc_id=? ORDER BY l.line_no
                """+(lock?" FOR UPDATE":""),actor.tenantId(),actor.companyId(),document);
    }

    Map<String,Object> detail(CompanyAccess actor,long document){
        var header=head(actor,document,false);
        var result=row(header);
        boolean sourceVisible=visibleLoan(actor,header);
        if(!sourceVisible)result.remove("originalLoanId");
        result.put("warehouseName",jdbc.queryForObject("SELECT name FROM warehouse WHERE tenant_id=? AND company_id=? AND id=?",
                String.class,actor.tenantId(),actor.companyId(),header.get("warehouse_id")));
        if(header.get("partner_id")!=null)result.put("partnerName",jdbc.queryForObject("SELECT name FROM business_partner WHERE tenant_id=? AND id=?",
                String.class,actor.tenantId(),header.get("partner_id")));
        result.put("lines",lines(actor,document,false).stream().map(line->{
            var fields=lineView(actor,line);
            if(!sourceVisible)fields.remove("originalLoanLineId");
            return fields;
        }).toList());
        result.put("allowedActions",actions(actor,header));
        return result;
    }

    private boolean visibleLoan(CompanyAccess actor,Map<String,Object> header){
        if(header.get("original_loan_id")==null)return true;
        var rows=jdbc.queryForList("SELECT h.warehouse_id FROM stock_misc_doc h WHERE h.tenant_id=? AND h.company_id=? AND h.id=?"
                +OrgOwnership.filter(actor,"h"),Long.class,actor.tenantId(),actor.companyId(),header.get("original_loan_id"));
        return !rows.isEmpty()&&actor.allowsWarehouse(rows.getFirst());
    }

    private Map<String,Object> lineView(CompanyAccess actor,Map<String,Object> line){
        var result=row(line);
        result.put("stockSelection",inventory.viewSelection(line.get("stock_selection")));
        if(!actor.hasPermission("cost:view"))for(String field:List.of("inputUnitCost","unitCost","amount","zeroCostReason"))result.remove(field);
        return result;
    }

    Map<String,Object> list(CompanyAccess actor,String direction,String status,Long beforeId,int limit,String keyword,boolean loans){
        long before=cursor(beforeId,limit);
        if(!Set.of("in","out").contains(direction)||status!=null&&!STATES.contains(status)||keyword.length()>64)throw invalid("方向、状态或搜索条件无效");
        if(!actor.allWarehouses()&&actor.warehouseIds().isEmpty())return page(List.of(),limit);
        var args=new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId(),before,direction,keyword));
        String condition="h.tenant_id=? AND h.company_id=? AND h.id<? AND h.direction=? AND LOCATE(?,h.document_no)>0"+OrgOwnership.filter(actor,"h");
        if(!actor.allWarehouses()){
            condition+=" AND h.warehouse_id IN ("+String.join(",",Collections.nCopies(actor.warehouseIds().size(),"?"))+")";
            args.addAll(actor.warehouseIds());
        }
        if(status!=null){condition+=" AND h.status=?";args.add(status);}
        String projection="h.id,h.document_no,h.direction,h.purpose,h.business_date,h.expected_return_date,h.status,h.warehouse_id,w.name warehouse_name,h.partner_id,p.name partner_name,h.version";
        if(loans){
            condition+=" AND h.purpose='loan' AND h.status='posted' AND EXISTS (SELECT 1 FROM stock_misc_line loan WHERE loan.tenant_id=h.tenant_id AND loan.company_id=h.company_id AND loan.doc_id=h.id AND loan.base_qty>"+returned("loan")+")";
        }
        args.add(limit+1);
        return page(jdbc.queryForList("SELECT "+projection+" FROM stock_misc_doc h JOIN warehouse w ON w.tenant_id=h.tenant_id AND w.company_id=h.company_id AND w.id=h.warehouse_id LEFT JOIN business_partner p ON p.tenant_id=h.tenant_id AND p.id=h.partner_id WHERE "
                +condition+" ORDER BY h.id DESC LIMIT ?",args.toArray()),limit);
    }

    List<Map<String,Object>> outstanding(CompanyAccess actor,long document){
        var header=head(actor,document,false);
        state(header,"posted");
        if(!"loan".equals(header.get("purpose")))throw missing();
        return jdbc.queryForList("SELECT loan.id,loan.sku_id,s.name sku_name,loan.unit_id,loan.base_qty,loan.base_qty-"+returned("loan")
                        +" remaining_qty,loan.stock_selection FROM stock_misc_line loan JOIN sku s ON s.tenant_id=loan.tenant_id AND s.id=loan.sku_id WHERE loan.tenant_id=? AND loan.company_id=? AND loan.doc_id=? ORDER BY loan.line_no",
                actor.tenantId(),actor.companyId(),document).stream().map(line->lineView(actor,line)).toList();
    }

    private static String returned(String alias){
        return "(SELECT COALESCE(SUM(r.base_qty),0) FROM stock_misc_line r JOIN stock_misc_doc rd ON rd.tenant_id=r.tenant_id AND rd.company_id=r.company_id AND rd.id=r.doc_id"
                +" WHERE r.tenant_id="+alias+".tenant_id AND r.company_id="+alias+".company_id AND r.original_loan_line_id="+alias+".id AND rd.status='posted')";
    }

    private static List<String> actions(CompanyAccess actor,Map<String,Object> header){
        var allowed=new ArrayList<String>();
        switch(header.get("status").toString()){
            case "draft" -> allowed.addAll(List.of("update","submit","void"));
            case "pending" -> {
                if(id(header,"created_by")!=actor.userId())allowed.addAll(List.of("approve","reject"));
                allowed.add("void");
            }
            case "approved" -> allowed.addAll(List.of("post","void"));
            default -> { }
        }
        return allowed.stream().filter(action->actor.hasPermission(RESOURCE+":"+action)).toList();
    }
}
