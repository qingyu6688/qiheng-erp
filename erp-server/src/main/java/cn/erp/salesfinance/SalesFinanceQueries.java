package cn.erp.salesfinance;

import cn.erp.accounting.SettlementService;
import cn.erp.identity.CompanyAccess;
import cn.erp.salesorder.SalesOrderQueries;
import cn.erp.purchasing.PurchaseOrderQueries;
import cn.erp.shared.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.salesfinance.FinanceSupport.*;

@Service
@Transactional(readOnly=true)
public class SalesFinanceQueries {
    private final JdbcTemplate jdbc;
    private final FinanceSupport support;
    private final SettlementService settlement;
    private final BusinessCommands commands;
    private final AuditService audit;
    private final SalesOrderQueries orders;
    private final PurchaseOrderQueries purchaseOrders;
    private final SalesInvoiceAmounts invoiceAmounts;
    private final SalesReceivableLabels receivableLabels;
    public SalesFinanceQueries(JdbcTemplate jdbc,FinanceSupport support,SettlementService settlement,BusinessCommands commands,AuditService audit,SalesOrderQueries orders,PurchaseOrderQueries purchaseOrders,SalesInvoiceAmounts invoiceAmounts,SalesReceivableLabels receivableLabels) {
        this.jdbc=jdbc;this.support=support;this.settlement=settlement;this.commands=commands;this.audit=audit;this.orders=orders;this.purchaseOrders=purchaseOrders;this.invoiceAmounts=invoiceAmounts;this.receivableLabels=receivableLabels;
    }

    public Map<String,Object> receivables(CompanyAccess scope,Long customer,boolean openOnly,Long cursor,int limit) {
        return receivables(scope,customer,openOnly,cursor,limit,false);
    }

    public Map<String,Object> receivables(CompanyAccess scope,Long customer,boolean openOnly,Long cursor,int limit,boolean invoiceable) {
        long before=cursor(cursor,limit);
        var rows=jdbc.queryForList("""
                SELECT r.id,r.customer_id,r.kind,r.amount,r.tax_amount,r.untaxed_amount,r.business_date,
                r.source_type,r.source_id,r.source_line_id,r.invoiced_amount
                FROM receivable r LEFT JOIN partner_open_item i ON i.tenant_id=r.tenant_id AND i.company_id=r.company_id
                    AND i.item_type='receivable' AND i.source_id=r.id
                WHERE r.tenant_id=? AND r.company_id=? AND r.id<? AND (? IS NULL OR r.customer_id=?)
                    AND r.posting_action='post' AND r.source_type<>'document_reversal' AND NOT EXISTS(SELECT 1 FROM receivable reversed WHERE reversed.tenant_id=r.tenant_id AND reversed.company_id=r.company_id AND reversed.reverses_receivable_id=r.id)
                    AND (?=0 OR i.open_amount>0) AND (?=0 OR r.kind='charge') AND %s ORDER BY r.id DESC LIMIT ?
                """.formatted(SalesInvoiceScope.receivableCondition(scope,"r")),scope.tenantId(),scope.companyId(),before,customer,customer,openOnly,invoiceable,limit+1);
        var visible=rows.stream().limit(limit).toList();
        var ids=visible.stream().map(row->((Number)row.get("id")).longValue()).toList();
        var labels=receivableLabels.labels(scope,ids);
        var capacities=invoiceable?invoiceAmounts.capacities(scope,ids):Map.<Long,SalesInvoiceAmounts.Capacity>of();
        for(var row:visible) {
            long id=((Number)row.get("id")).longValue();
            row.putAll(labels.getOrDefault(id,Map.of()));
            var capacity=capacities.get(id);
            if(capacity!=null) {
                row.put("invoice_quota_amount",capacity.amount());
                row.put("invoice_available_amount",capacity.available());
                row.put("invoice_available_tax_amount",capacity.availableTax());
            }
        }
        return page(rows,limit);
    }

    public Map<String,Object> payables(CompanyAccess scope,Long supplier,boolean openOnly,Long cursor,int limit) {
        long before=cursor(cursor,limit);
        return page(jdbc.queryForList("""
                SELECT a.id,a.supplier_id,p.name supplier_name,a.kind,a.source_type,a.source_id,a.source_line_id,
                    a.amount,a.untaxed_amount,a.tax_amount,a.invoiced_amount,a.business_date,COALESCE(i.open_amount,0) open_amount,
                    CASE a.source_type WHEN 'purchase_return_line' THEN pr.return_no
                        WHEN 'opening_entry' THEN oe.entry_no WHEN 'price_adjustment_line' THEN pa.adjust_no WHEN 'purchase_fee_doc_line' THEN fd.document_no ELSE gr.receipt_no END source_no
                FROM payable a JOIN business_partner p ON p.tenant_id=a.tenant_id AND p.id=a.supplier_id
                LEFT JOIN partner_open_item i ON i.tenant_id=a.tenant_id AND i.company_id=a.company_id
                    AND i.item_type='payable' AND i.source_id=a.id
                LEFT JOIN goods_receipt gr ON gr.tenant_id=a.tenant_id AND gr.company_id=a.company_id
                    AND a.source_type IN('goods_receipt_line','purchase_order_fee') AND gr.id=a.source_id
                LEFT JOIN purchase_return pr ON pr.tenant_id=a.tenant_id AND pr.company_id=a.company_id
                    AND a.source_type='purchase_return_line' AND pr.id=a.source_id
                LEFT JOIN purchase_fee_doc fd ON fd.tenant_id=a.tenant_id AND fd.company_id=a.company_id AND fd.id=a.source_id AND a.source_type='purchase_fee_doc_line'
                LEFT JOIN price_adjustment pa ON pa.tenant_id=a.tenant_id AND pa.company_id=a.company_id AND pa.id=a.source_id AND a.source_type='price_adjustment_line'
                LEFT JOIN opening_entry oe ON oe.tenant_id=a.tenant_id AND oe.company_id=a.company_id
                    AND a.source_type='opening_entry' AND oe.id=a.source_id
                WHERE a.tenant_id=? AND a.company_id=? AND a.id<? AND (? IS NULL OR a.supplier_id=?)
                    AND (?=0 OR i.open_amount>0) AND %s ORDER BY a.id DESC LIMIT ?
                """.formatted(cn.erp.shared.OrgOwnership.sourceCondition(scope,"a.source_type","a.source_id")),scope.tenantId(),scope.companyId(),before,supplier,supplier,openOnly,limit+1),limit);
    }

    public Map<String,Object> openItems(CompanyAccess scope,Long partner,String side,boolean openOnly,Long cursor,int limit) {
        long before=cursor(cursor,limit);
        if(!Set.of("ar","ap").contains(side)) {throw invalid("往来侧须为ar或ap");}
        return page(jdbc.queryForList("""
                SELECT i.*,p.name partner_name,DATEDIFF(DATE(CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00')),i.aging_date) aging_days,
                  COALESCE(ar.due_date,ap.due_date) due_date,GREATEST(0,DATEDIFF(DATE(CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00')),COALESCE(ar.due_date,ap.due_date))) overdue_days,
                  CASE i.item_type WHEN 'receipt' THEN r.receipt_no WHEN 'refund_request' THEN f.refund_no
                    WHEN 'payment' THEN pm.payment_no
                    WHEN 'payable' THEN CASE ap.source_type WHEN 'purchase_return_line' THEN pr.return_no WHEN 'price_adjustment_line' THEN pa.adjust_no ELSE gr.receipt_no END
                    WHEN 'receivable' THEN CASE ar.source_type WHEN 'sales_return_line' THEN sr.return_no ELSE sh.shipment_no END ELSE NULL END source_no,
                  CASE WHEN i.item_type IN('payable','payment') OR r.receipt_type='supplier_refund' THEN 'ap' ELSE 'ar' END side
                FROM partner_open_item i JOIN business_partner p ON p.tenant_id=i.tenant_id AND p.id=i.partner_id
                LEFT JOIN receipt r ON r.tenant_id=i.tenant_id AND r.company_id=i.company_id AND i.item_type='receipt' AND r.id=i.source_id
                LEFT JOIN refund_request f ON f.tenant_id=i.tenant_id AND f.company_id=i.company_id AND i.item_type='refund_request' AND f.id=i.source_id
                LEFT JOIN receivable ar ON ar.tenant_id=i.tenant_id AND ar.company_id=i.company_id AND i.item_type='receivable' AND ar.id=i.source_id
                LEFT JOIN sales_return sr ON sr.tenant_id=ar.tenant_id AND sr.company_id=ar.company_id AND ar.source_type='sales_return_line' AND sr.id=ar.source_id
                LEFT JOIN shipment sh ON sh.tenant_id=ar.tenant_id AND sh.company_id=ar.company_id AND ar.source_type IN('shipment_line','sales_order_fee') AND sh.id=ar.source_id
                LEFT JOIN payment pm ON pm.tenant_id=i.tenant_id AND pm.company_id=i.company_id AND i.item_type='payment' AND pm.id=i.source_id
                LEFT JOIN payable ap ON ap.tenant_id=i.tenant_id AND ap.company_id=i.company_id AND i.item_type='payable' AND ap.id=i.source_id
                LEFT JOIN purchase_fee_doc fd ON fd.tenant_id=ap.tenant_id AND fd.company_id=ap.company_id AND fd.id=ap.source_id AND ap.source_type='purchase_fee_doc_line'
                LEFT JOIN price_adjustment pa ON pa.tenant_id=ap.tenant_id AND pa.company_id=ap.company_id AND pa.id=ap.source_id AND ap.source_type='price_adjustment_line'
                LEFT JOIN goods_receipt gr ON gr.tenant_id=ap.tenant_id AND gr.company_id=ap.company_id AND ap.source_type IN('goods_receipt_line','purchase_order_fee') AND gr.id=ap.source_id
                LEFT JOIN purchase_return pr ON pr.tenant_id=ap.tenant_id AND pr.company_id=ap.company_id AND ap.source_type='purchase_return_line' AND pr.id=ap.source_id
                WHERE i.tenant_id=? AND i.company_id=? AND i.id<? AND (? IS NULL OR i.partner_id=?)
                    AND (?=0 OR i.open_amount>0) AND (f.id IS NULL OR f.status NOT IN('draft','cancelled'))
                    AND (CASE WHEN i.item_type IN('payable','payment') OR r.receipt_type='supplier_refund' THEN 'ap' ELSE 'ar' END)=?
                AND %s ORDER BY i.id DESC LIMIT ?
                """.formatted(cn.erp.shared.OrgOwnership.itemCondition(scope,"i")),scope.tenantId(),scope.companyId(),before,partner,partner,openOnly,side,limit+1),limit);
    }

    public Map<String,Object> allocations(CompanyAccess scope,Long partner,Long cursor,int limit) {
        long before=cursor(cursor,limit);
        var rows=jdbc.queryForList("""
                SELECT a.*,p.name partner_name FROM settlement_allocation a JOIN business_partner p ON p.tenant_id=a.tenant_id AND p.id=a.partner_id
                WHERE a.tenant_id=? AND a.company_id=? AND a.id<? AND (? IS NULL OR a.partner_id=?)
                AND %s ORDER BY a.id DESC LIMIT ?
                """.formatted("EXISTS(SELECT 1 FROM partner_open_item oi WHERE oi.tenant_id=a.tenant_id AND oi.company_id=a.company_id AND oi.id=a.debit_item_id AND "+cn.erp.shared.OrgOwnership.itemCondition(scope,"oi")+") AND EXISTS(SELECT 1 FROM partner_open_item oi WHERE oi.tenant_id=a.tenant_id AND oi.company_id=a.company_id AND oi.id=a.credit_item_id AND "+cn.erp.shared.OrgOwnership.itemCondition(scope,"oi")+")"),scope.tenantId(),scope.companyId(),before,partner,partner,limit+1);
        var result=page(rows,limit);
        @SuppressWarnings("unchecked") var items=(List<Map<String,Object>>)result.get("items");
        items.forEach(item->actions(scope,item));return result;
    }

    public Map<String,Object> allocation(CompanyAccess scope,long id) {
        var result=row(support.find(scope,"settlement_allocation",id,false));actions(scope,result);return result;
    }

    private void actions(CompanyAccess scope,Map<String,Object> item) {
        item.put("allowedActions",scope.hasPermission("settlement:revoke")&&"active".equals(item.get("status"))&&!Set.of("refund","reversal").contains(item.get("allocationType"))?List.of("revoke"):List.of());
    }

    @Transactional
    public Map<String,Object> allocate(CompanyAccess scope,String key,FinanceModels.Allocations input) {
        return execute(scope,"settlement.allocate",key,input,()->{
            if(input.allocations()==null||input.allocations().isEmpty()||input.allocations().size()>200) {throw invalid("核销须有1到200组明细");}
            var ids=new TreeSet<Long>();
            input.allocations().forEach(pair->{ids.add(pair.debitItemId());ids.add(pair.creditItemId());amount(pair.amount());});
            var orderIds=orderIds(scope,ids);lockOrders(scope,orderIds);
            var purchaseIds=purchaseIds(scope,ids);lockPurchaseOrders(scope,purchaseIds);lockItems(scope,ids);
            for(long id:ids) {
                var item=item(scope,id);
                if(id(item,"partner_id")!=input.partnerId()) {throw new ApiException(422,"SETTLEMENT_NOT_ALLOWED","核销双方须属于所选往来单位");}
                if("refund_request".equals(item.get("item_type"))) {throw new ApiException(422,"SETTLEMENT_NOT_ALLOWED","退款额度请通过退款申请处理");}
            }
            var result=new ArrayList<Map<String,Object>>();
            for(var pair:input.allocations()) {
                long id=settlement.allocate(scope,pair.debitItemId(),pair.creditItemId(),amount(pair.amount()),"manual",null,null);
                audit.record(scope,"settlement.allocate","settlement_allocation",id,null,Map.of("amount",pair.amount()));
                result.add(allocation(scope,id));
            }
            for(long order:orderIds) {orders.refreshProgress(scope,order);}
            for(long order:purchaseIds) {purchaseOrders.refreshProgress(scope,order);}
            return Map.of("items",result);
        });
    }

    @Transactional
    public Map<String,Object> revoke(CompanyAccess scope,long id,String key,FinanceModels.Version input) {
        return execute(scope,"settlement.revoke",key,List.of(id,input),()->{
            var snapshot=support.find(scope,"settlement_allocation",id,false);
            var ids=new TreeSet<>(List.of(id(snapshot,"debit_item_id"),id(snapshot,"credit_item_id")));
            var orderIds=orderIds(scope,ids);lockOrders(scope,orderIds);
            var purchaseIds=purchaseIds(scope,ids);lockPurchaseOrders(scope,purchaseIds);lockItems(scope,ids);
            var before=support.find(scope,"settlement_allocation",id,true);version(before,input.version());state(before,"active");
            settlement.revoke(scope,id,input.reason());
            for(long order:orderIds) {orders.refreshProgress(scope,order);}
            for(long order:purchaseIds) {purchaseOrders.refreshProgress(scope,order);}
            audit.record(scope,"settlement.revoke","settlement_allocation",id,null,Map.of("reason",input.reason()));
            return allocation(scope,id);
        });
    }

    private Map<String,Object> item(CompanyAccess scope,long id) {
        cn.erp.shared.OrgOwnership.requireItem(jdbc,scope,id);
        var items=jdbc.queryForList("SELECT * FROM partner_open_item WHERE tenant_id=? AND company_id=? AND id=?",scope.tenantId(),scope.companyId(),id);
        if(items.isEmpty()) {throw missing();}return items.getFirst();
    }
    private void lockItems(CompanyAccess scope,Collection<Long> ids) {
        for(long id:ids) {
            var found=jdbc.queryForList("SELECT id FROM partner_open_item WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",Long.class,scope.tenantId(),scope.companyId(),id);
            if(found.isEmpty()) {throw missing();}
        }
    }
    private Set<Long> orderIds(CompanyAccess scope,Collection<Long> items) {
        var result=new TreeSet<Long>();
        for(long item:items) {
            result.addAll(jdbc.queryForList("""
                    SELECT DISTINCT o.order_id FROM partner_open_item i JOIN receivable r ON r.tenant_id=i.tenant_id AND r.company_id=i.company_id AND i.item_type='receivable' AND r.id=i.source_id
                    JOIN shipment_line l ON l.tenant_id=r.tenant_id AND l.company_id=r.company_id AND r.source_type='shipment_line' AND l.id=r.source_line_id
                    JOIN sales_order_line o ON o.tenant_id=l.tenant_id AND o.company_id=l.company_id AND o.id=l.order_line_id
                    WHERE i.tenant_id=? AND i.company_id=? AND i.id=?
                    """,Long.class,scope.tenantId(),scope.companyId(),item));
        }
        return result;
    }
    private void lockOrders(CompanyAccess scope,Set<Long> ids) {
        for(long id:ids) {
            OrgOwnership.require(jdbc,scope,"sales_order",id);
            jdbc.queryForObject("SELECT id FROM sales_order WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",Long.class,scope.tenantId(),scope.companyId(),id);
        }
    }
    private Set<Long> purchaseIds(CompanyAccess scope,Collection<Long> items) {
        var result=new TreeSet<Long>();
        for(long item:items) {
            result.addAll(jdbc.queryForList("""
                SELECT DISTINCT ol.order_id FROM partner_open_item i
                JOIN payable a ON a.tenant_id=i.tenant_id AND a.company_id=i.company_id AND i.item_type='payable' AND a.id=i.source_id
                JOIN goods_receipt_line l ON l.tenant_id=a.tenant_id AND l.company_id=a.company_id AND a.source_type='goods_receipt_line' AND l.id=a.source_line_id
                JOIN purchase_order_line ol ON ol.tenant_id=l.tenant_id AND ol.company_id=l.company_id AND ol.id=l.order_line_id
                WHERE i.tenant_id=? AND i.company_id=? AND i.id=?
                UNION SELECT f.order_id FROM partner_open_item i
                JOIN payable a ON a.tenant_id=i.tenant_id AND a.company_id=i.company_id AND i.item_type='payable' AND a.id=i.source_id
                LEFT JOIN purchase_fee_doc_line new_fee ON new_fee.tenant_id=a.tenant_id AND new_fee.company_id=a.company_id AND a.source_type='purchase_order_fee' AND new_fee.id=a.source_line_id
                JOIN purchase_order_fee f ON f.tenant_id=a.tenant_id AND f.company_id=a.company_id AND a.source_type='purchase_order_fee' AND f.id=COALESCE(new_fee.order_fee_id,a.source_line_id)
                WHERE i.tenant_id=? AND i.company_id=? AND i.id=?
                """,Long.class,scope.tenantId(),scope.companyId(),item,scope.tenantId(),scope.companyId(),item));
        }
        return result;
    }
    private void lockPurchaseOrders(CompanyAccess scope,Set<Long> ids) {
        for(long id:ids) {
            OrgOwnership.require(jdbc,scope,"purchase_order",id);
            jdbc.queryForObject("SELECT id FROM purchase_order WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",Long.class,scope.tenantId(),scope.companyId(),id);
        }
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    private Map<String,Object> execute(CompanyAccess scope,String operation,String key,Object payload,java.util.function.Supplier<Map<String,Object>> work) {
        return commands.execute(scope,operation,key,payload,200,work,(Class)Map.class);
    }
}
