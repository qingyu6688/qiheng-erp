package cn.erp.reconciliation;

import cn.erp.shared.ActorScope;
import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReconciliationChecks {
    public static final Set<String> TYPES=Set.of("inventory_balance","inventory_cost","reservation","cash_balance","open_item","journal_balance","ledger_inventory","period_close","gl_trial","gl_operational","batch_balance","stock_blocks","location_balance");
    public record Difference(String objectType,String key,BigDecimal expected,BigDecimal actual) {}
    private final NamedParameterJdbcTemplate jdbc;

    public ReconciliationChecks(NamedParameterJdbcTemplate jdbc) {this.jdbc=jdbc;}

    private cn.erp.shared.FinancialReconciliationPort financial;
    @org.springframework.beans.factory.annotation.Autowired
    void financial(cn.erp.shared.FinancialReconciliationPort financial) {this.financial=financial;}

    public List<Difference> check(ActorScope scope,String type,Long period) {
        if(!TYPES.contains(type)) {throw new ApiException(400,"VALIDATION_FAILED","对账类型无效");}
        if(type.startsWith("gl_")) {if(financial==null)throw new ApiException(503,"SERVICE_UNAVAILABLE","财务对账尚未就绪");return financial.check(scope,type).stream().map(d->new Difference(type,d.key(),d.expected(),d.actual())).toList();}
        var params=new HashMap<String,Object>();params.put("t",scope.tenantId());params.put("c",scope.companyId());params.put("p",period);
        if(type.equals("period_close")) {
            if(period==null || jdbc.queryForObject("SELECT COUNT(*) FROM accounting_period WHERE tenant_id=:t AND company_id=:c AND id=:p",params,Long.class)==0) {
                throw new ApiException(404,"NOT_FOUND","请选择本公司的会计期间");
            }
            return periodChecks(scope,period,params);
        }
        var differences=new ArrayList<>(jdbc.query(sql(type),params,(r,n)->new Difference(type,r.getString("object_key"),r.getBigDecimal("expected"),r.getBigDecimal("actual"))));
        if(type.equals("inventory_cost"))differences.addAll(new SourcelessReturnCostChecks(jdbc).check(scope));
        return List.copyOf(differences);
    }

    private String sql(String type) {
        return switch(type) {
            case "inventory_balance" -> """
                WITH b AS (SELECT warehouse_id,sku_id,location_key,lot_key,on_hand_qty q,blocked_qty f FROM inventory_balance WHERE tenant_id=:t AND company_id=:c AND location_key='' AND lot_key=''),
                m AS (SELECT warehouse_id,sku_id,'' location_key,'' lot_key,SUM(on_hand_delta) q,SUM(blocked_delta) f FROM inventory_movement WHERE tenant_id=:t AND company_id=:c GROUP BY warehouse_id,sku_id),
                k AS (SELECT warehouse_id,sku_id,location_key,lot_key FROM b UNION SELECT warehouse_id,sku_id,location_key,lot_key FROM m)
                SELECT CONCAT(k.warehouse_id,'/',k.sku_id,'/',k.location_key,'/',k.lot_key,'/在库') object_key,COALESCE(m.q,0) expected,COALESCE(b.q,0) actual FROM k LEFT JOIN b USING(warehouse_id,sku_id,location_key,lot_key) LEFT JOIN m USING(warehouse_id,sku_id,location_key,lot_key)
                WHERE b.q IS NULL OR COALESCE(b.q,0)<>COALESCE(m.q,0)
                UNION ALL SELECT CONCAT(k.warehouse_id,'/',k.sku_id,'/',k.location_key,'/',k.lot_key,'/冻结'),COALESCE(m.f,0),COALESCE(b.f,0) FROM k LEFT JOIN b USING(warehouse_id,sku_id,location_key,lot_key) LEFT JOIN m USING(warehouse_id,sku_id,location_key,lot_key)
                WHERE COALESCE(b.f,0)<>COALESCE(m.f,0)
                """;
            case "inventory_cost" -> """
                WITH b AS (SELECT warehouse_id,sku_id,on_hand_qty q FROM inventory_balance WHERE tenant_id=:t AND company_id=:c AND location_key='' AND lot_key=''),
                c AS (SELECT warehouse_id,sku_id,qty q,total_cost a FROM inventory_cost WHERE tenant_id=:t AND company_id=:c),
                m AS (SELECT warehouse_id,sku_id,SUM(a) a FROM (
                    SELECT warehouse_id,sku_id,cost_delta a FROM inventory_movement WHERE tenant_id=:t AND company_id=:c
                    UNION ALL SELECT warehouse_id,sku_id,amount FROM cost_adjustment WHERE tenant_id=:t AND company_id=:c AND adjust_type NOT IN('purchase_price_issued','purchase_fee_issued')
                ) x GROUP BY warehouse_id,sku_id),
                k AS (SELECT warehouse_id,sku_id FROM b UNION SELECT warehouse_id,sku_id FROM c UNION SELECT warehouse_id,sku_id FROM m)
                SELECT CONCAT(k.warehouse_id,'/',k.sku_id,'/数量') object_key,COALESCE(b.q,0) expected,COALESCE(c.q,0) actual FROM k LEFT JOIN b USING(warehouse_id,sku_id) LEFT JOIN c USING(warehouse_id,sku_id)
                WHERE c.q IS NULL OR COALESCE(b.q,0)<>COALESCE(c.q,0)
                UNION ALL SELECT CONCAT(k.warehouse_id,'/',k.sku_id,'/成本'),COALESCE(m.a,0),COALESCE(c.a,0) FROM k LEFT JOIN m USING(warehouse_id,sku_id) LEFT JOIN c USING(warehouse_id,sku_id)
                WHERE COALESCE(m.a,0)<>COALESCE(c.a,0)
                UNION ALL SELECT CONCAT(x.id,'/采购调价在库成本'),x.stock_amount,COALESCE(a.amount,0)
                  FROM price_adjustment_cost x LEFT JOIN cost_adjustment a
                    ON a.tenant_id=x.tenant_id AND a.company_id=x.company_id AND a.source_type='price_adjustment_cost'
                    AND a.source_line_id=x.id AND a.adjust_type='purchase_price_stock'
                  WHERE x.tenant_id=:t AND x.company_id=:c AND x.stock_amount<>COALESCE(a.amount,0)
                UNION ALL SELECT CONCAT(x.id,'/采购调价已出库差异'),x.issued_amount,COALESCE(a.amount,0)
                  FROM price_adjustment_cost x LEFT JOIN cost_adjustment a
                    ON a.tenant_id=x.tenant_id AND a.company_id=x.company_id AND a.source_type='price_adjustment_cost'
                    AND a.source_line_id=x.id AND a.adjust_type='purchase_price_issued'
                  WHERE x.tenant_id=:t AND x.company_id=:c AND x.issued_amount<>COALESCE(a.amount,0)
                UNION ALL SELECT CONCAT(x.id,'/采购费用在库成本'),x.stock_amount,COALESCE(a.amount,0)
                  FROM purchase_fee_cost x LEFT JOIN cost_adjustment a
                    ON a.tenant_id=x.tenant_id AND a.company_id=x.company_id AND a.source_type IN('purchase_fee_cost','goods_receipt_fee')
                    AND a.source_line_id=x.id AND a.adjust_type='purchase_fee_stock'
                  WHERE x.tenant_id=:t AND x.company_id=:c AND x.stock_amount<>COALESCE(a.amount,0)
                UNION ALL SELECT CONCAT(x.id,'/采购费用已出库差异'),x.issued_amount,COALESCE(a.amount,0)
                  FROM purchase_fee_cost x LEFT JOIN cost_adjustment a
                    ON a.tenant_id=x.tenant_id AND a.company_id=x.company_id AND a.source_type='purchase_fee_cost'
                    AND a.source_line_id=x.id AND a.adjust_type='purchase_fee_issued'
                  WHERE x.tenant_id=:t AND x.company_id=:c AND x.issued_amount<>COALESCE(a.amount,0)
                UNION ALL SELECT CONCAT(h.id,'/采购费用分摊成本'),IF(h.cost_tax_inclusive=1,h.amount,h.untaxed_amount),COALESCE(a.cost,0)
                  FROM purchase_fee_doc h LEFT JOIN(SELECT tenant_id,company_id,document_id,SUM(cost_amount) cost FROM purchase_fee_allocation GROUP BY tenant_id,company_id,document_id) a
                    ON a.tenant_id=h.tenant_id AND a.company_id=h.company_id AND a.document_id=h.id
                  WHERE h.tenant_id=:t AND h.company_id=:c AND h.status IN('posted','reversed') AND IF(h.cost_tax_inclusive=1,h.amount,h.untaxed_amount)<>COALESCE(a.cost,0)
                """;
            case "reservation" -> """
                WITH b AS (SELECT warehouse_id,sku_id,location_key,lot_key,reserved_qty q FROM inventory_balance WHERE tenant_id=:t AND company_id=:c AND location_key='' AND lot_key=''),
                r AS (SELECT warehouse_id,sku_id,location_key,lot_key,SUM(remaining_qty) q FROM inventory_reservation WHERE tenant_id=:t AND company_id=:c AND status='active' GROUP BY warehouse_id,sku_id,location_key,lot_key),
                k AS (SELECT warehouse_id,sku_id,location_key,lot_key FROM b UNION SELECT warehouse_id,sku_id,location_key,lot_key FROM r),
                picks AS (
                    SELECT p.warehouse_id,p.sku_id,p.location_key,p.lot_key,SUM(p.qty) q
                    FROM inv_shipment_pick p WHERE p.tenant_id=:t AND p.company_id=:c
                    GROUP BY p.warehouse_id,p.sku_id,p.location_key,p.lot_key
                )
                SELECT CONCAT(k.warehouse_id,'/',k.sku_id,'/',k.location_key,'/',k.lot_key) object_key,COALESCE(r.q,0) expected,COALESCE(b.q,0) actual FROM k LEFT JOIN b USING(warehouse_id,sku_id,location_key,lot_key) LEFT JOIN r USING(warehouse_id,sku_id,location_key,lot_key)
                WHERE COALESCE(b.q,0)<>COALESCE(r.q,0) OR b.q IS NULL
                UNION ALL SELECT CONCAT(p.warehouse_id,'/',p.sku_id,'/',p.location_key,'/',p.lot_key,'/拣货占用'),
                    COALESCE(stock.on_hand_qty-stock.blocked_qty,0),p.q
                  FROM picks p LEFT JOIN inventory_balance stock ON stock.tenant_id=:t AND stock.company_id=:c
                    AND stock.warehouse_id=p.warehouse_id AND stock.sku_id=p.sku_id
                    AND stock.location_key=p.location_key AND stock.lot_key=p.lot_key
                  WHERE p.q>COALESCE(stock.on_hand_qty-stock.blocked_qty,0)
                """;
            case "batch_balance" -> """
                WITH base AS (
                    SELECT b.*, s.batch_managed,s.serial_managed,w.location_managed FROM inventory_balance b
                    JOIN sku s ON s.tenant_id=b.tenant_id AND s.id=b.sku_id
                    JOIN warehouse w ON w.tenant_id=b.tenant_id AND w.company_id=b.company_id AND w.id=b.warehouse_id
                    WHERE b.tenant_id=:t AND b.company_id=:c
                ), sums AS (
                    SELECT warehouse_id,sku_id,
                        SUM(IF(location_key='' AND lot_key='',on_hand_qty,0)) overview,
                        SUM(IF(location_key<>'' OR lot_key<>'',on_hand_qty,0)) detail,
                        SUM(IF(location_key='' AND lot_key='',blocked_qty,0)) overview_blocked,
                        SUM(IF(location_key<>'' OR lot_key<>'',blocked_qty,0)) detail_blocked
                    FROM base WHERE batch_managed=1 OR location_managed=1 GROUP BY warehouse_id,sku_id
                ), stock AS (
                    SELECT warehouse_id,sku_id,location_key,lot_key,on_hand_qty q FROM base
                    WHERE serial_managed=1 AND (location_key<>'' OR lot_key<>'' OR (batch_managed=0 AND location_managed=0))
                ), serials AS (
                    SELECT x.warehouse_id,x.sku_id,x.location_key,COALESCE(CAST(x.batch_id AS CHAR),'') lot_key,COUNT(*) q
                    FROM inv_serial x JOIN sku s ON s.tenant_id=x.tenant_id AND s.id=x.sku_id AND s.serial_managed=1
                    WHERE x.tenant_id=:t AND x.company_id=:c AND x.status IN ('in_stock','quality_hold','reserved','returned')
                    GROUP BY x.warehouse_id,x.sku_id,x.location_key,x.batch_id
                ), keys_in_stock AS (
                    SELECT warehouse_id,sku_id,location_key,lot_key FROM stock
                    UNION SELECT warehouse_id,sku_id,location_key,lot_key FROM serials
                ), lots AS (
                    SELECT warehouse_id,sku_id,lot_key,SUM(on_hand_qty) q,SUM(blocked_qty) f
                    FROM base WHERE lot_key<>'' GROUP BY warehouse_id,sku_id,lot_key
                ), movements AS (
                    SELECT warehouse_id,sku_id,lot_key,SUM(on_hand_delta) q,SUM(blocked_delta) f
                    FROM inventory_movement WHERE tenant_id=:t AND company_id=:c AND lot_key<>''
                    GROUP BY warehouse_id,sku_id,lot_key
                ), lot_keys AS (
                    SELECT warehouse_id,sku_id,lot_key FROM lots
                    UNION SELECT warehouse_id,sku_id,lot_key FROM movements
                )
                SELECT CONCAT(warehouse_id,'/',sku_id,'/在库') object_key,overview expected,detail actual FROM sums WHERE overview<>detail
                UNION ALL SELECT CONCAT(warehouse_id,'/',sku_id,'/冻结'),overview_blocked,detail_blocked FROM sums WHERE overview_blocked<>detail_blocked
                UNION ALL SELECT CONCAT(k.warehouse_id,'/',k.sku_id,'/',k.location_key,'/',k.lot_key,'/序列号'),COALESCE(stock.q,0),COALESCE(serials.q,0)
                    FROM keys_in_stock k LEFT JOIN stock USING(warehouse_id,sku_id,location_key,lot_key)
                    LEFT JOIN serials USING(warehouse_id,sku_id,location_key,lot_key) WHERE COALESCE(stock.q,0)<>COALESCE(serials.q,0)
                UNION ALL SELECT CONCAT(warehouse_id,'/',sku_id,'/',location_key,'/缺少批次'),0,on_hand_qty
                    FROM base WHERE batch_managed=1 AND location_key<>'' AND lot_key='' AND on_hand_qty<>0
                UNION ALL SELECT CONCAT(k.warehouse_id,'/',k.sku_id,'/',k.lot_key,'/批次流水'),COALESCE(m.q,0),COALESCE(l.q,0)
                    FROM lot_keys k LEFT JOIN lots l USING(warehouse_id,sku_id,lot_key)
                      LEFT JOIN movements m USING(warehouse_id,sku_id,lot_key) WHERE COALESCE(m.q,0)<>COALESCE(l.q,0)
                UNION ALL SELECT CONCAT(k.warehouse_id,'/',k.sku_id,'/',k.lot_key,'/批次冻结'),COALESCE(m.f,0),COALESCE(l.f,0)
                    FROM lot_keys k LEFT JOIN lots l USING(warehouse_id,sku_id,lot_key)
                      LEFT JOIN movements m USING(warehouse_id,sku_id,lot_key) WHERE COALESCE(m.f,0)<>COALESCE(l.f,0)
                UNION ALL SELECT CONCAT(l.warehouse_id,'/',l.sku_id,'/',l.lot_key,'/批次归属'),0,l.q
                    FROM lots l LEFT JOIN inv_batch d ON d.tenant_id=:t AND d.company_id=:c AND d.sku_id=l.sku_id
                      AND CAST(d.id AS CHAR)=l.lot_key WHERE d.id IS NULL AND l.q<>0
                """;
            case "stock_blocks" -> """
                WITH blocks AS (
                    SELECT balance_id,SUM(qty) q FROM inv_block_detail WHERE tenant_id=:t AND company_id=:c GROUP BY balance_id
                )
                SELECT CONCAT(b.warehouse_id,'/',b.sku_id,'/',b.location_key,'/',b.lot_key) object_key,b.blocked_qty expected,COALESCE(d.q,0) actual
                FROM inventory_balance b LEFT JOIN blocks d ON d.balance_id=b.id
                JOIN sku s ON s.tenant_id=b.tenant_id AND s.id=b.sku_id
                JOIN warehouse w ON w.tenant_id=b.tenant_id AND w.company_id=b.company_id AND w.id=b.warehouse_id
                WHERE b.tenant_id=:t AND b.company_id=:c AND
                    (b.location_key<>'' OR b.lot_key<>'' OR (s.batch_managed=0 AND w.location_managed=0))
                    AND b.blocked_qty<>COALESCE(d.q,0)
                """;
            case "location_balance" -> """
                WITH history AS (
                    SELECT warehouse_id,sku_id,location_key,lot_key,on_hand_delta qty,blocked_delta frozen FROM inventory_movement
                      WHERE tenant_id=:t AND company_id=:c AND location_key<>''
                    UNION ALL SELECT warehouse_id,sku_id,location_key,lot_key,on_hand_qty,blocked_qty FROM inv_location_opening
                      WHERE tenant_id=:t AND company_id=:c
                ), posted AS (
                    SELECT warehouse_id,sku_id,location_key,lot_key,SUM(qty) q,SUM(frozen) f FROM history GROUP BY warehouse_id,sku_id,location_key,lot_key
                ), balance AS (
                    SELECT warehouse_id,sku_id,location_key,lot_key,on_hand_qty q,blocked_qty f FROM inventory_balance
                      WHERE tenant_id=:t AND company_id=:c AND location_key<>''
                ), location_keys AS (
                    SELECT warehouse_id,sku_id,location_key,lot_key FROM posted
                    UNION SELECT warehouse_id,sku_id,location_key,lot_key FROM balance
                )
                SELECT CONCAT(k.warehouse_id,'/',k.sku_id,'/',k.location_key,'/',k.lot_key) object_key,COALESCE(posted.q,0) expected,COALESCE(balance.q,0) actual
                FROM location_keys k LEFT JOIN posted USING(warehouse_id,sku_id,location_key,lot_key)
                LEFT JOIN balance USING(warehouse_id,sku_id,location_key,lot_key)
                WHERE COALESCE(posted.q,0)<>COALESCE(balance.q,0)
                UNION ALL SELECT CONCAT(k.warehouse_id,'/',k.sku_id,'/',k.location_key,'/',k.lot_key,'/冻结'),COALESCE(posted.f,0),COALESCE(balance.f,0)
                FROM location_keys k LEFT JOIN posted USING(warehouse_id,sku_id,location_key,lot_key)
                LEFT JOIN balance USING(warehouse_id,sku_id,location_key,lot_key)
                WHERE COALESCE(posted.f,0)<>COALESCE(balance.f,0)
                UNION ALL SELECT CONCAT(b.warehouse_id,'/',b.sku_id,'/',b.location_key,'/库位归属'),0,b.q
                FROM balance b LEFT JOIN wms_location l ON l.tenant_id=:t AND l.company_id=:c AND l.warehouse_id=b.warehouse_id
                    AND CAST(l.id AS CHAR)=b.location_key WHERE l.id IS NULL AND b.q<>0
                UNION ALL SELECT CONCAT(b.warehouse_id,'/',b.sku_id,'/',b.lot_key,'/缺少库位'),0,b.on_hand_qty
                FROM inventory_balance b JOIN warehouse w ON w.tenant_id=b.tenant_id AND w.company_id=b.company_id AND w.id=b.warehouse_id
                WHERE b.tenant_id=:t AND b.company_id=:c AND w.location_managed=1 AND b.location_key='' AND b.lot_key<>'' AND b.on_hand_qty<>0
                """;
            case "cash_balance" -> """
                WITH a AS (SELECT id FROM cash_account WHERE tenant_id=:t AND company_id=:c),
                b AS (SELECT cash_account_id id,balance FROM cash_account_balance WHERE tenant_id=:t AND company_id=:c),
                l AS (SELECT cash_account_id id,SUM(IF(direction='D',amount,-amount)) a FROM journal_line WHERE tenant_id=:t AND company_id=:c AND account_code='FUNDS' GROUP BY cash_account_id),
                k AS (SELECT id FROM a UNION SELECT id FROM b UNION SELECT id FROM l)
                SELECT CAST(k.id AS CHAR) object_key,COALESCE(l.a,0) expected,COALESCE(b.balance,0) actual FROM k LEFT JOIN b USING(id) LEFT JOIN l USING(id)
                WHERE b.id IS NULL OR COALESCE(b.balance,0)<>COALESCE(l.a,0)
                """;
            case "open_item" -> """
                SELECT CAST(i.id AS CHAR) object_key,i.amount-COALESCE(a.used,0) expected,i.open_amount actual FROM partner_open_item i
                LEFT JOIN (SELECT item_id,SUM(amount) used FROM (
                    SELECT debit_item_id item_id,amount FROM settlement_allocation WHERE tenant_id=:t AND company_id=:c AND status IN ('active','reserved')
                    UNION ALL SELECT credit_item_id,amount FROM settlement_allocation WHERE tenant_id=:t AND company_id=:c AND status IN ('active','reserved')
                ) x GROUP BY item_id) a ON a.item_id=i.id
                WHERE i.tenant_id=:t AND i.company_id=:c AND i.open_amount<>i.amount-COALESCE(a.used,0)
                UNION ALL SELECT CONCAT(l.id,'/采购调价应付'),ABS(l.amount),COALESCE(p.amount,0)
                  FROM price_adjustment_line l JOIN price_adjustment h
                    ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.adjustment_id
                  LEFT JOIN payable p ON p.tenant_id=l.tenant_id AND p.company_id=l.company_id
                    AND p.source_type='price_adjustment_line' AND p.source_line_id=l.id AND p.posting_action='post'
                  WHERE l.tenant_id=:t AND l.company_id=:c AND h.kind='purchase' AND h.status IN('posted','reversed')
                    AND (p.id IS NULL OR p.amount<>ABS(l.amount) OR p.tax_amount<>ABS(l.tax_amount)
                      OR p.untaxed_amount<>ABS(l.untaxed_amount) OR p.supplier_id<>h.partner_id
                      OR p.source_id<>h.id OR p.kind<>IF(l.amount>0,'charge','credit'))
                UNION ALL SELECT CONCAT(l.id,'/采购费用应付'),l.amount,COALESCE(p.amount,0)
                  FROM purchase_fee_doc_line l JOIN purchase_fee_doc h ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.document_id
                  LEFT JOIN payable p ON p.tenant_id=l.tenant_id AND p.company_id=l.company_id AND p.source_line_id=l.id AND p.posting_action='post'
                    AND p.source_type=IF(h.source_kind='receipt','purchase_order_fee','purchase_fee_doc_line')
                  WHERE l.tenant_id=:t AND l.company_id=:c AND h.status IN('posted','reversed')
                    AND(p.id IS NULL OR p.amount<>l.amount OR p.tax_amount<>l.tax_amount OR p.untaxed_amount<>l.untaxed_amount
                      OR p.supplier_id<>h.partner_id OR p.kind<>'charge' OR p.source_id<>IF(h.source_kind='receipt',h.source_receipt_id,h.id))
                """;
            case "journal_balance" -> """
                WITH l AS (SELECT journal_id,SUM(IF(direction='D',amount,0)) d,SUM(IF(direction='C',amount,0)) c FROM journal_line WHERE tenant_id=:t AND company_id=:c GROUP BY journal_id)
                SELECT CONCAT(j.id,'/借方') object_key,j.total_debit expected,COALESCE(l.d,0) actual FROM journal j LEFT JOIN l ON l.journal_id=j.id WHERE j.tenant_id=:t AND j.company_id=:c AND j.total_debit<>COALESCE(l.d,0)
                UNION ALL SELECT CONCAT(j.id,'/贷方'),j.total_credit,COALESCE(l.c,0) FROM journal j LEFT JOIN l ON l.journal_id=j.id WHERE j.tenant_id=:t AND j.company_id=:c AND j.total_credit<>COALESCE(l.c,0)
                UNION ALL SELECT CONCAT(j.id,'/平衡'),COALESCE(l.d,0),COALESCE(l.c,0) FROM journal j LEFT JOIN l ON l.journal_id=j.id WHERE j.tenant_id=:t AND j.company_id=:c AND COALESCE(l.d,0)<>COALESCE(l.c,0)
                """;
            case "ledger_inventory" -> """
                WITH expected AS (
                  SELECT 'INVENTORY' code,COALESCE(SUM(total_cost),0) a FROM inventory_cost WHERE tenant_id=:t AND company_id=:c
                  UNION ALL SELECT 'GOODS_IN_TRANSIT',COALESCE(SUM(l.cost_amount-l.received_cost_amount-l.diff_cost_amount),0)
                    FROM stock_transfer_line l JOIN stock_transfer h ON h.id=l.transfer_id AND h.tenant_id=l.tenant_id AND h.company_id=l.company_id
                    WHERE l.tenant_id=:t AND l.company_id=:c AND h.status IN ('shipped','received_with_diff')
                  UNION ALL SELECT 'GOODS_SHIPPED',COALESCE(SUM(l.cost_amount-COALESCE(l.signed_cost_amount,0)-IF(l.diff_disposition IN ('returned','lost'),COALESCE(l.diff_cost_amount,0),0)),0)
                    FROM shipment_line l JOIN shipment h ON h.id=l.shipment_id AND h.tenant_id=l.tenant_id AND h.company_id=l.company_id
                    WHERE l.tenant_id=:t AND l.company_id=:c AND h.status IN ('posted','signed','signed_with_diff')
                ), actual AS (SELECT account_code code,SUM(IF(direction='D',amount,-amount)) a FROM journal_line WHERE tenant_id=:t AND company_id=:c AND account_code IN ('INVENTORY','GOODS_IN_TRANSIT','GOODS_SHIPPED') GROUP BY account_code)
                SELECT expected.code object_key,expected.a expected,COALESCE(actual.a,0) actual FROM expected LEFT JOIN actual USING(code) WHERE expected.a<>COALESCE(actual.a,0)
                """;
            default -> throw new IllegalArgumentException("对账类型无效");
        };
    }

    private List<Difference> periodChecks(ActorScope scope,long period,Map<String,Object> params) {
        var diffs=new ArrayList<Difference>();
        var documents=List.of("shipment","goods_receipt","sales_return","purchase_return","stock_transfer","stock_adjustment","stock_count","stock_misc_doc","document_reversal","price_adjustment","purchase_fee_doc");
        for(String table:documents) {
            String status=switch(table) {
                case "stock_transfer" -> "IN ('submitted','shipped','received_with_diff')";
                case "stock_count" -> "IN ('counting','submitted','approved')";
                case "sales_return" -> "IN ('submitted','pending','approved')";
                case "stock_adjustment","stock_misc_doc","document_reversal","price_adjustment","purchase_fee_doc" -> "IN ('pending','approved')";
                default -> "IN ('submitted')";
            };
            String query="SELECT COUNT(*) FROM "+table+" d JOIN accounting_period p ON p.tenant_id=d.tenant_id AND p.company_id=d.company_id AND p.id=:p WHERE d.tenant_id=:t AND d.company_id=:c AND d.business_date BETWEEN p.start_date AND p.end_date AND d.status "+status;
            long count=jdbc.queryForObject(query,params,Long.class);
            if(count>0) {diffs.add(new Difference("period_close",table,BigDecimal.ZERO,BigDecimal.valueOf(count)));}
        }
        for(String table:List.of("receipt","payment","refund_request","account_transfer","opening_entry")) {
            String state=table.equals("refund_request")?"IN ('pending_review','pending_payment')":table.equals("opening_entry")?"IN ('approved')":"IN ('pending_confirm')";
            long count=jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" d JOIN accounting_period p ON p.id=:p AND p.tenant_id=d.tenant_id AND p.company_id=d.company_id WHERE d.tenant_id=:t AND d.company_id=:c AND d.business_date BETWEEN p.start_date AND p.end_date AND d.status "+state,params,Long.class);
            if(count>0) {diffs.add(new Difference("period_close",table,BigDecimal.ZERO,BigDecimal.valueOf(count)));}
        }
        for(String type:List.of("inventory_balance","inventory_cost","cash_balance","open_item","batch_balance","stock_blocks","location_balance")) {diffs.addAll(check(scope,type,null));}
        return List.copyOf(diffs);
    }
}
