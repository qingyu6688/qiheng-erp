package cn.erp.reporting;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.OrgOwnership;

final class ReportSql {
    private ReportSql() {}
    static String query(ReportType type,boolean detail) { return query(type,detail,null); }
    static String query(ReportType type,boolean detail,CompanyAccess actor) {
        return switch(type) {
            case INVENTORY_SUMMARY -> inventorySummary();
            case INVENTORY_FLOW -> inventoryFlow(detail,actor);
            case UNSIGNED_SHIPMENTS -> unsigned(actor);
            case SALES_SUMMARY,GROSS_MARGIN -> commercial(true,actor);
            case PURCHASE_SUMMARY -> commercial(false,actor);
            case RECEIVABLE_AGING -> aging(true,actor);
            case PAYABLE_AGING -> aging(false,actor);
            case PARTNER_STATEMENT -> statement(actor);
            case CASH_JOURNAL -> cash(actor);
        };
    }
    private static String inventorySummary() {
        return """
            WITH b AS (SELECT warehouse_id,sku_id,SUM(on_hand_qty) on_hand_qty,SUM(reserved_qty) reserved_qty,SUM(blocked_qty) blocked_qty
              FROM inventory_balance WHERE tenant_id=:t AND company_id=:c AND location_key='' AND lot_key='' GROUP BY warehouse_id,sku_id),
            k AS (SELECT warehouse_id,sku_id FROM b UNION SELECT warehouse_id,sku_id FROM inventory_cost WHERE tenant_id=:t AND company_id=:c)
            SELECT k.warehouse_id,k.sku_id,COALESCE(b.on_hand_qty,0) on_hand_qty,COALESCE(b.reserved_qty,0) reserved_qty,
              COALESCE(b.blocked_qty,0) blocked_qty,COALESCE(b.on_hand_qty-b.reserved_qty-b.blocked_qty,0) available_qty,COALESCE(c.total_cost,0) cost_amount
            FROM k LEFT JOIN b USING(warehouse_id,sku_id) LEFT JOIN inventory_cost c ON c.tenant_id=:t AND c.company_id=:c AND c.warehouse_id=k.warehouse_id AND c.sku_id=k.sku_id
            """;
    }
    private static String inventoryFlow(boolean detail,CompanyAccess actor) {
        String events="""
            WITH e AS (SELECT id,warehouse_id,sku_id,business_date,on_hand_delta qty,blocked_delta blocked_qty,cost_delta cost_amount,source_type,source_id,source_line_id,posting_action
              FROM inventory_movement m WHERE tenant_id=:t AND company_id=:c AND %s
              UNION ALL SELECT id,warehouse_id,sku_id,business_date,0,0,amount,source_type,source_id,source_line_id,'adjustment'
              FROM cost_adjustment a WHERE tenant_id=:t AND company_id=:c AND %s)
            """.formatted(source(actor,"m.source_type","m.source_id"),source(actor,"a.source_type","a.source_id"));
        if(detail) { return events+"SELECT * FROM e WHERE business_date BETWEEN :f AND :d"; }
        return events+"""
            SELECT warehouse_id,sku_id,SUM(IF(business_date<:f,qty,0)) opening_qty,
              SUM(IF(business_date BETWEEN :f AND :d AND qty>0,qty,0)) in_qty,
              SUM(IF(business_date BETWEEN :f AND :d AND qty<0,-qty,0)) out_qty,SUM(qty) closing_qty,
              SUM(IF(business_date<:f,cost_amount,0)) opening_cost,
              SUM(IF(business_date BETWEEN :f AND :d AND cost_amount>0,cost_amount,0)) in_cost,
              SUM(IF(business_date BETWEEN :f AND :d AND cost_amount<0,-cost_amount,0)) out_cost,SUM(cost_amount) closing_cost
            FROM e WHERE business_date<=:d GROUP BY warehouse_id,sku_id
            """;
    }
    private static String unsigned(CompanyAccess actor) {
        return """
            SELECT l.id,h.warehouse_id,l.sku_id,h.customer_id partner_id,h.id source_id,'shipment' source_type,h.shipment_no source_no,
              h.business_date,l.base_qty-l.signed_base_qty-IF(l.diff_disposition IN ('returned','lost'),l.diff_base_qty,0) unsigned_qty,
              l.cost_amount-COALESCE(l.signed_cost_amount,0)-IF(l.diff_disposition IN ('returned','lost'),COALESCE(l.diff_cost_amount,0),0) cost_amount,l.diff_disposition
            FROM shipment_line l JOIN shipment h ON h.id=l.shipment_id AND h.tenant_id=l.tenant_id AND h.company_id=l.company_id
            WHERE l.tenant_id=:t AND l.company_id=:c AND h.status IN ('posted','signed','signed_with_diff')
              AND %s AND h.business_date BETWEEN :f AND :d AND l.base_qty-l.signed_base_qty-IF(l.diff_disposition IN ('returned','lost'),l.diff_base_qty,0)>0
            """.formatted(owner(actor,"h"));
    }
    private static String commercial(boolean sales,CompanyAccess actor) {
        String head=sales?"shipment":"goods_receipt",line=head+"_line",key=sales?"shipment_id":"receipt_id";
        String ret=sales?"sales_return":"purchase_return",partner=sales?"customer_id":"supplier_id";
        String date=sales?"sign_date":"business_date",no=sales?"shipment_no":"receipt_no";
        String amount=sales?"receivable_amount":"payable_amount",credit=sales?"credit_amount":"debit_amount";
        String qty=sales?"signed_base_qty":"base_qty",cost=sales?"signed_cost_amount":"cost_amount";
        String fact=sales?"receivable":"payable",fee=sales?"sales_order_fee":"purchase_order_fee",rep=sales?"sales_user_id":"buyer_user_id";
        // 原始事实始终保留；冲销仅追加一次反向行，避免按单据状态删除后再次扣减。
        String base="SELECT l.id,h.warehouse_id,l.sku_id,h."+partner+" partner_id,h."+date+" business_date,'"+head+"' source_type,h.id source_id,h."+no+" source_no,l.id source_line_id,l."+qty+" qty,l."+amount+" amount,l.untaxed_amount,l.tax_amount,l."+cost+" cost_amount,h."+rep+" business_user_id,h.owner_department_id department_id FROM "+line+" l JOIN "+head+" h ON h.id=l."+key+" AND h.tenant_id=l.tenant_id AND h.company_id=l.company_id WHERE l.tenant_id=:t AND l.company_id=:c AND h."+date+" IS NOT NULL AND l."+cost+" IS NOT NULL AND h.status IN ('posted','signed','signed_with_diff','reversed')";
        base+=ownerAnd(actor,"h");
        base+=" UNION ALL SELECT l.id,h.warehouse_id,l.sku_id,h."+partner+",h.business_date,'"+ret+"',h.id,h.return_no,l.id,-l.base_qty,-l."+credit+",-l.untaxed_amount,-l.tax_amount,-l.cost_amount,h."+rep+",h.owner_department_id FROM "+ret+"_line l JOIN "+ret+" h ON h.id=l.return_id AND h.tenant_id=l.tenant_id AND h.company_id=l.company_id WHERE l.tenant_id=:t AND l.company_id=:c AND h.status IN ('posted','reversed')";
        base+=ownerAnd(actor,"h");
        base+=" UNION ALL SELECT r.id,h.warehouse_id,NULL,r."+partner+",r.business_date,'"+head+"',h.id,h."+no+",r.source_line_id,0,r.amount,r.untaxed_amount,r.tax_amount,0,h."+rep+",h.owner_department_id FROM "+fact+" r JOIN "+head+" h ON h.id=r.source_id AND h.tenant_id=r.tenant_id AND h.company_id=r.company_id WHERE r.tenant_id=:t AND r.company_id=:c AND r.source_type='"+fee+"' AND r.posting_action='post'";
        base+=ownerAnd(actor,"h");
        return "WITH b AS ("+base+"), f AS (SELECT * FROM b UNION ALL SELECT r.id,b.warehouse_id,b.sku_id,b.partner_id,r.business_date,'document_reversal',r.id,r.reversal_no,b.source_line_id,-b.qty,-b.amount,-b.untaxed_amount,-b.tax_amount,-b.cost_amount,b.business_user_id,b.department_id FROM b JOIN document_reversal r ON r.tenant_id=:t AND r.company_id=:c AND r.source_type=b.source_type AND r.source_id=b.source_id AND r.status='posted') SELECT *,untaxed_amount-cost_amount gross_margin FROM f WHERE business_date BETWEEN :f AND :d";
    }
    private static String aging(boolean ar,CompanyAccess actor) {
        String fact=ar?"receivable":"payable",direction=ar?"D":"C",head=ar?"shipment":"goods_receipt",ret=ar?"sales_return":"purchase_return",rep=ar?"sales_user_id":"buyer_user_id";
        String st="COALESCE(original.source_type,r.source_type)",si="COALESCE(original.source_id,r.source_id)";
        String remaining="IF(:agingNow,i.open_amount,i.amount-"+allocatedAtCutoff("debit","D")+"-"+allocatedAtCutoff("credit","C")+")";
        return "WITH facts AS (SELECT i.id,i.partner_id,r.source_type,r.source_id,r.source_line_id,i.aging_date,i.direction,"
                +remaining+" remaining,DATEDIFF(:today,i.aging_date) age_days,COALESCE(h."+rep+",b."+rep+",0) business_user_id,"
                +"COALESCE(h.owner_department_id,b.owner_department_id,e.owner_department_id,0) department_id FROM partner_open_item i"
                +" JOIN "+fact+" r ON r.tenant_id=i.tenant_id AND r.company_id=i.company_id AND r.id=i.source_id"
                +" LEFT JOIN "+fact+" original ON original.tenant_id=r.tenant_id AND original.company_id=r.company_id AND original.id=r.reverses_"+fact+"_id"
                +" LEFT JOIN "+head+" h ON h.tenant_id=r.tenant_id AND h.company_id=r.company_id AND h.id="+si
                +" AND "+st+" IN('"+head+"_line','"+(ar?"sales_order_fee":"purchase_order_fee")+"')"
                +" LEFT JOIN "+ret+" b ON b.tenant_id=r.tenant_id AND b.company_id=r.company_id AND b.id="+si+" AND "+st+"='"+ret+"_line'"
                +" LEFT JOIN opening_entry e ON e.tenant_id=r.tenant_id AND e.company_id=r.company_id AND e.id="+si+" AND "+st+"='opening_entry'"
                +" WHERE i.tenant_id=:t AND i.company_id=:c AND i.item_type='"+fact+"' AND i.aging_date<=:today"
                +" AND (:agingNow OR (r.business_date<=:today AND i.created_at<:agingEnd)) AND "+source(actor,"r.source_type","r.source_id")
                +") SELECT id,partner_id,source_type,source_id,source_line_id,aging_date,direction,IF(direction='"+direction
                +"',remaining,-remaining) open_amount,age_days,business_user_id,department_id FROM facts WHERE remaining>0";
    }

    private static String allocatedAtCutoff(String side,String direction) {
        // 核销表的复合外键按往来单位、币种、方向和未清项建立索引，借贷分开查询可使用完整索引。
        return """
                COALESCE((SELECT SUM(a.amount) FROM settlement_allocation a
                  WHERE a.tenant_id=i.tenant_id AND a.company_id=i.company_id AND a.partner_id=i.partner_id
                    AND a.currency=i.currency AND a.%s_direction='%s' AND a.%s_item_id=i.id AND a.created_at<:agingEnd
                    AND (a.status IN ('active','reserved') OR a.status='revoked' AND a.revoked_at>=:agingEnd
                      OR a.status='released' AND a.updated_at>=:agingEnd)
                ),0)
                """.formatted(side,direction,side);
    }
    private static String statement(CompanyAccess actor) {
        return """
            SELECT l.id,l.partner_id,j.business_date,j.source_type,j.source_id,l.source_line_id,l.account_code,l.direction,
              IF(l.direction='D',l.amount,0) debit_amount,IF(l.direction='C',l.amount,0) credit_amount,
              IF(j.business_date<:f,IF(l.direction='D',l.amount,-l.amount),0) opening_amount,
              IF(j.business_date>=:f,IF(l.direction='D',l.amount,-l.amount),0) period_amount
            FROM journal_line l JOIN journal j ON j.id=l.journal_id AND j.tenant_id=l.tenant_id AND j.company_id=l.company_id
            WHERE l.tenant_id=:t AND l.company_id=:c AND l.account_code IN ('AR','AP') AND l.partner_id IS NOT NULL AND j.business_date<=:d AND %s
            """.formatted(source(actor,"j.source_type","j.source_id"));
    }
    private static String cash(CompanyAccess actor) {
        return """
            SELECT l.id,l.cash_account_id,l.partner_id,j.business_date,j.source_type,j.source_id,l.source_line_id,l.direction,
              IF(l.direction='D',l.amount,0) receipt_amount,IF(l.direction='C',l.amount,0) payment_amount,
              IF(j.business_date<:f,IF(l.direction='D',l.amount,-l.amount),0) opening_amount,
              IF(j.business_date>=:f,IF(l.direction='D',l.amount,-l.amount),0) period_amount
            FROM journal_line l JOIN journal j ON j.id=l.journal_id AND j.tenant_id=l.tenant_id AND j.company_id=l.company_id
            WHERE l.tenant_id=:t AND l.company_id=:c AND l.account_code='FUNDS' AND j.business_date<=:d AND %s
            """.formatted(source(actor,"j.source_type","j.source_id"));
    }
    private static String owner(CompanyAccess actor,String alias) { return actor==null?"1=1":actor.orgScope().ownerFilter(alias); }
    private static String ownerAnd(CompanyAccess actor,String alias) { return actor==null?"":OrgOwnership.filter(actor,alias); }
    private static String source(CompanyAccess actor,String type,String id) { return actor==null?"1=1":OrgOwnership.sourceCondition(actor,type,id); }
}
