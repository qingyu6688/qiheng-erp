package cn.erp.reversal;

import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.ReservationService;
import cn.erp.purchasing.PurchaseOrderQueries;
import cn.erp.salesorder.SalesOrderQueries;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import static cn.erp.salesfinance.FinanceSupport.*;

@Component
class ReversalProgress {
    private final JdbcTemplate jdbc;
    private final SalesOrderQueries sales;
    private final PurchaseOrderQueries purchases;
    private final ReservationService reservations;
    ReversalProgress(JdbcTemplate jdbc,SalesOrderQueries sales,PurchaseOrderQueries purchases,ReservationService reservations){this.jdbc=jdbc;this.sales=sales;this.purchases=purchases;this.reservations=reservations;}
    List<Map<String,Object>> lines(CompanyAccess scope,String type,long source) {
        return lines(scope,type,source,true);
    }
    private List<Map<String,Object>> lines(CompanyAccess scope,String type,long source,boolean current) {
        String from=switch(type) {
            case "shipment" -> "shipment_line l JOIN sales_order_line o ON o.id=l.order_line_id AND o.tenant_id=l.tenant_id AND o.company_id=l.company_id";
            case "sales_return" -> "sales_return_line l JOIN shipment_line s ON s.id=l.shipment_line_id AND s.tenant_id=l.tenant_id AND s.company_id=l.company_id JOIN sales_order_line o ON o.id=s.order_line_id AND o.tenant_id=l.tenant_id AND o.company_id=l.company_id";
            case "goods_receipt" -> "goods_receipt_line l JOIN purchase_order_line o ON o.id=l.order_line_id AND o.tenant_id=l.tenant_id AND o.company_id=l.company_id";
            case "purchase_return" -> "purchase_return_line l JOIN goods_receipt_line s ON s.id=l.receipt_line_id AND s.tenant_id=l.tenant_id AND s.company_id=l.company_id JOIN purchase_order_line o ON o.id=s.order_line_id AND o.tenant_id=l.tenant_id AND o.company_id=l.company_id";
            default -> null;
        };
        if(from==null)return List.of();
        String key=switch(type){case "shipment"->"shipment_id";case "goods_receipt"->"receipt_id";default->"return_id";};
        return jdbc.queryForList("SELECT l.*,o.id source_order_line_id,o.order_id FROM "+from+" WHERE l.tenant_id=? AND l.company_id=? AND l."+key+"=? ORDER BY o.order_id,o.id,l.id"+(current?" FOR SHARE":""),scope.tenantId(),scope.companyId(),source);
    }
    void lock(CompanyAccess scope,String type,long source) {
        var rows=lines(scope,type,source,false);
        boolean salesType=Set.of("shipment","sales_return").contains(type);
        if(Set.of("sales_return","purchase_return").contains(type)) {
            String line=salesType?"shipment_line":"goods_receipt_line",head=salesType?"shipment":"goods_receipt",key=salesType?"shipment_id":"receipt_id",ref=salesType?"shipment_line_id":"receipt_line_id";
            var heads=new TreeSet<Long>();
            for(var row:rows)heads.add(jdbc.queryForObject("SELECT "+key+" FROM "+line+" WHERE tenant_id=? AND company_id=? AND id=?",Long.class,scope.tenantId(),scope.companyId(),row.get(ref)));
            lockRows(scope,head,heads);
        }
        lockRows(scope,salesType?"sales_order":"purchase_order",ids(rows,"order_id"));
        lockRows(scope,salesType?"sales_order_line":"purchase_order_line",ids(rows,"source_order_line_id"));
        if(!rows.isEmpty())lockRows(scope,type+"_line",ids(rows,"id"));
        if(type.equals("sales_return"))lockRows(scope,"shipment_line",ids(rows,"shipment_line_id"));
        if(type.equals("purchase_return"))lockRows(scope,"goods_receipt_line",ids(rows,"receipt_line_id"));
    }
    void reverse(CompanyAccess scope,String type,long source,Map<String,Object> head) {
        var rows=lines(scope,type,source);
        for(var row:rows) {
            long orderLine=id(row,"source_order_line_id");BigDecimal qty=decimal(row,"base_qty");
            switch(type) {
                case "shipment" -> {
                    BigDecimal shipped=head.get("status").equals("posted")?qty:decimal(row,"signed_base_qty");
                    jdbc.update("UPDATE sales_order_line SET shipped_base_qty=shipped_base_qty-?,signed_base_qty=signed_base_qty-?,recognized_amount=recognized_amount-?,recognized_tax=recognized_tax-?,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",shipped,decimal(row,"signed_base_qty"),zero(row,"receivable_amount"),zero(row,"tax_amount"),scope.userId(),scope.tenantId(),scope.companyId(),orderLine);
                    reservations.refreshLineState(scope,orderLine);
                }
                case "sales_return" -> {
                    jdbc.update("UPDATE shipment_line SET returned_base_qty=returned_base_qty-?,credited_amount=credited_amount-?,credited_tax=credited_tax-?,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",qty,decimal(row,"credit_amount"),decimal(row,"tax_amount"),scope.userId(),scope.tenantId(),scope.companyId(),row.get("shipment_line_id"));
                    jdbc.update("UPDATE sales_order_line SET returned_base_qty=returned_base_qty-?,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",qty,scope.userId(),scope.tenantId(),scope.companyId(),orderLine);
                }
                case "goods_receipt" -> jdbc.update("UPDATE purchase_order_line SET received_base_qty=received_base_qty-?,recognized_amount=recognized_amount-?,recognized_tax=recognized_tax-?,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",qty,decimal(row,"payable_amount"),decimal(row,"tax_amount"),scope.userId(),scope.tenantId(),scope.companyId(),orderLine);
                case "purchase_return" -> {
                    jdbc.update("UPDATE goods_receipt_line SET returned_base_qty=returned_base_qty-?,debited_amount=debited_amount-?,debited_tax=debited_tax-?,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",qty,decimal(row,"debit_amount"),decimal(row,"tax_amount"),scope.userId(),scope.tenantId(),scope.companyId(),row.get("receipt_line_id"));
                    jdbc.update("UPDATE purchase_order_line SET returned_base_qty=returned_base_qty-?,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",qty,scope.userId(),scope.tenantId(),scope.companyId(),orderLine);
                }
                default -> { }
            }
        }
        if(type.equals("shipment"))jdbc.update("UPDATE sales_order_fee SET recognized_shipment_id=NULL,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND recognized_shipment_id=?",scope.userId(),scope.tenantId(),scope.companyId(),source);
        if(type.equals("goods_receipt"))reverseFees(scope,source);
    }
    private void reverseFees(CompanyAccess scope,long receipt) {
        // 旧费用用原订单费用编号，新费用用本次归集明细编号，两种事实分别退回进度。
        jdbc.update("UPDATE purchase_order_fee f JOIN payable p ON p.tenant_id=f.tenant_id AND p.company_id=f.company_id AND p.source_type='purchase_order_fee' AND p.source_line_id=f.id AND p.posting_action='post'"
                +" SET f.recognized_receipt_id=NULL,f.recognized_amount=0,f.recognized_tax=0,f.version=f.version+1,f.updated_by=?"
                +" WHERE f.tenant_id=? AND f.company_id=? AND p.source_id=?",scope.userId(),scope.tenantId(),scope.companyId(),receipt);
        var fees=jdbc.queryForList("SELECT l.* FROM purchase_fee_doc_line l JOIN purchase_fee_doc h ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.document_id"
                +" WHERE h.tenant_id=? AND h.company_id=? AND h.source_kind='receipt' AND h.source_receipt_id=? AND h.status='posted' ORDER BY l.order_fee_id FOR UPDATE",scope.tenantId(),scope.companyId(),receipt);
        for(var fee:fees) {
            var others=jdbc.queryForList("SELECT h.source_receipt_id FROM purchase_fee_doc_line l JOIN purchase_fee_doc h ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.document_id"
                    +" WHERE h.tenant_id=? AND h.company_id=? AND l.order_fee_id=? AND h.source_kind='receipt' AND h.source_receipt_id<>? AND h.status='posted' ORDER BY h.id LIMIT 1 FOR SHARE",Long.class,scope.tenantId(),scope.companyId(),fee.get("order_fee_id"),receipt);
            if(jdbc.update("UPDATE purchase_order_fee SET recognized_receipt_id=?,recognized_amount=recognized_amount-?,recognized_tax=recognized_tax-?,version=version+1,updated_by=?"
                    +" WHERE tenant_id=? AND company_id=? AND id=? AND recognized_amount>=? AND recognized_tax>=?",others.isEmpty()?null:others.getFirst(),fee.get("amount"),fee.get("tax_amount"),scope.userId(),scope.tenantId(),scope.companyId(),fee.get("order_fee_id"),fee.get("amount"),fee.get("tax_amount"))!=1)
                throw new cn.erp.shared.ApiException(409,"FEE_PROGRESS_MISMATCH","随单费用进度不一致，请先核对");
        }
        jdbc.update("UPDATE purchase_fee_doc SET status='reversed',version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND source_kind='receipt' AND source_receipt_id=? AND status='posted'",scope.userId(),scope.tenantId(),scope.companyId(),receipt);
    }
    void refresh(CompanyAccess scope,String type,long source,Map<String,Object> head) {
        for(long order:ids(lines(scope,type,source),"order_id")) {
            if(Set.of("shipment","sales_return").contains(type))sales.refreshProgress(scope,order);else purchases.refreshProgress(scope,order);
        }
        if(head.get("related_order_id")!=null) {
            if(type.equals("receipt"))sales.refreshProgress(scope,id(head,"related_order_id"));
            if(type.equals("payment"))purchases.refreshProgress(scope,id(head,"related_order_id"));
        }
    }
    private void lockRows(CompanyAccess scope,String table,Set<Long> ids) {for(long id:ids)jdbc.queryForObject("SELECT id FROM "+table+" WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",Long.class,scope.tenantId(),scope.companyId(),id);}
    private static Set<Long> ids(List<Map<String,Object>> rows,String key){var result=new TreeSet<Long>();for(var row:rows)result.add(id(row,key));return result;}
    private static BigDecimal zero(Map<String,Object> row,String key){return row.get(key)==null?BigDecimal.ZERO:(BigDecimal)row.get(key);}
}
