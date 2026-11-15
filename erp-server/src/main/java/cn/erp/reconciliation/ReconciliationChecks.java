package cn.erp.reconciliation;

import cn.erp.shared.ActorScope;
import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReconciliationChecks {
    public static final Set<String> TYPES=Set.of("inventory_balance","inventory_cost","reservation","cash_balance","open_item","journal_balance","ledger_inventory","period_close");
    public record Difference(String objectType,String key,BigDecimal expected,BigDecimal actual) {}
    private final NamedParameterJdbcTemplate jdbc;

    public ReconciliationChecks(NamedParameterJdbcTemplate jdbc) {this.jdbc=jdbc;}

    public List<Difference> check(ActorScope scope,String type,Long period) {
        if(!TYPES.contains(type)) {throw new ApiException(400,"VALIDATION_FAILED","对账类型无效");}
        var params=new HashMap<String,Object>();params.put("t",scope.tenantId());params.put("c",scope.companyId());params.put("p",period);
        if(type.equals("period_close")) {
            if(period==null || jdbc.queryForObject("SELECT COUNT(*) FROM accounting_period WHERE tenant_id=:t AND company_id=:c AND id=:p",params,Long.class)==0) {
                throw new ApiException(404,"NOT_FOUND","请选择本公司的会计期间");
            }
            return periodChecks(scope,period,params);
        }
        return jdbc.query(sql(type),params,(r,n)->new Difference(type,r.getString("object_key"),r.getBigDecimal("expected"),r.getBigDecimal("actual")));
    }

    private String sql(String type) {
        return switch(type) {
            case "inventory_balance" -> """
                WITH b AS (SELECT warehouse_id,sku_id,location_key,lot_key,on_hand_qty q,blocked_qty f FROM inventory_balance WHERE tenant_id=:t AND company_id=:c),
                m AS (SELECT warehouse_id,sku_id,location_key,lot_key,SUM(on_hand_delta) q,SUM(blocked_delta) f FROM inventory_movement WHERE tenant_id=:t AND company_id=:c GROUP BY warehouse_id,sku_id,location_key,lot_key),
                k AS (SELECT warehouse_id,sku_id,location_key,lot_key FROM b UNION SELECT warehouse_id,sku_id,location_key,lot_key FROM m)
                SELECT CONCAT(k.warehouse_id,'/',k.sku_id,'/',k.location_key,'/',k.lot_key,'/在库') object_key,COALESCE(m.q,0) expected,COALESCE(b.q,0) actual FROM k LEFT JOIN b USING(warehouse_id,sku_id,location_key,lot_key) LEFT JOIN m USING(warehouse_id,sku_id,location_key,lot_key)
                WHERE b.q IS NULL OR COALESCE(b.q,0)<>COALESCE(m.q,0)
                UNION ALL SELECT CONCAT(k.warehouse_id,'/',k.sku_id,'/',k.location_key,'/',k.lot_key,'/冻结'),COALESCE(m.f,0),COALESCE(b.f,0) FROM k LEFT JOIN b USING(warehouse_id,sku_id,location_key,lot_key) LEFT JOIN m USING(warehouse_id,sku_id,location_key,lot_key)
                WHERE COALESCE(b.f,0)<>COALESCE(m.f,0)
                """;
            case "inventory_cost" -> """
                WITH b AS (SELECT warehouse_id,sku_id,SUM(on_hand_qty) q FROM inventory_balance WHERE tenant_id=:t AND company_id=:c GROUP BY warehouse_id,sku_id),
                c AS (SELECT warehouse_id,sku_id,qty q,total_cost a FROM inventory_cost WHERE tenant_id=:t AND company_id=:c),
                m AS (SELECT warehouse_id,sku_id,SUM(a) a FROM (
                    SELECT warehouse_id,sku_id,cost_delta a FROM inventory_movement WHERE tenant_id=:t AND company_id=:c
                    UNION ALL SELECT warehouse_id,sku_id,amount FROM cost_adjustment WHERE tenant_id=:t AND company_id=:c
                ) x GROUP BY warehouse_id,sku_id),
                k AS (SELECT warehouse_id,sku_id FROM b UNION SELECT warehouse_id,sku_id FROM c UNION SELECT warehouse_id,sku_id FROM m)
                SELECT CONCAT(k.warehouse_id,'/',k.sku_id,'/数量') object_key,COALESCE(b.q,0) expected,COALESCE(c.q,0) actual FROM k LEFT JOIN b USING(warehouse_id,sku_id) LEFT JOIN c USING(warehouse_id,sku_id)
                WHERE c.q IS NULL OR COALESCE(b.q,0)<>COALESCE(c.q,0)
                UNION ALL SELECT CONCAT(k.warehouse_id,'/',k.sku_id,'/成本'),COALESCE(m.a,0),COALESCE(c.a,0) FROM k LEFT JOIN m USING(warehouse_id,sku_id) LEFT JOIN c USING(warehouse_id,sku_id)
                WHERE COALESCE(m.a,0)<>COALESCE(c.a,0)
                """;
            case "reservation" -> """
                WITH b AS (SELECT warehouse_id,sku_id,location_key,lot_key,reserved_qty q FROM inventory_balance WHERE tenant_id=:t AND company_id=:c),
                r AS (SELECT warehouse_id,sku_id,location_key,lot_key,SUM(remaining_qty) q FROM inventory_reservation WHERE tenant_id=:t AND company_id=:c AND status='active' GROUP BY warehouse_id,sku_id,location_key,lot_key),
                k AS (SELECT warehouse_id,sku_id,location_key,lot_key FROM b UNION SELECT warehouse_id,sku_id,location_key,lot_key FROM r)
                SELECT CONCAT(k.warehouse_id,'/',k.sku_id,'/',k.location_key,'/',k.lot_key) object_key,COALESCE(r.q,0) expected,COALESCE(b.q,0) actual FROM k LEFT JOIN b USING(warehouse_id,sku_id,location_key,lot_key) LEFT JOIN r USING(warehouse_id,sku_id,location_key,lot_key)
                WHERE COALESCE(b.q,0)<>COALESCE(r.q,0) OR b.q IS NULL
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
        var documents=List.of("shipment","goods_receipt","sales_return","purchase_return","stock_transfer");
        for(String table:documents) {
            String status=table.equals("stock_transfer")?"IN ('submitted','shipped','received_with_diff')":"IN ('submitted')";
            String query="SELECT COUNT(*) FROM "+table+" d JOIN accounting_period p ON p.tenant_id=d.tenant_id AND p.company_id=d.company_id AND p.id=:p WHERE d.tenant_id=:t AND d.company_id=:c AND d.business_date BETWEEN p.start_date AND p.end_date AND d.status "+status;
            long count=jdbc.queryForObject(query,params,Long.class);
            if(count>0) {diffs.add(new Difference("period_close",table,BigDecimal.ZERO,BigDecimal.valueOf(count)));}
        }
        for(String table:List.of("receipt","payment","refund_request")) {
            String state=table.equals("refund_request")?"IN ('pending_review','pending_payment')":"IN ('pending_confirm')";
            long count=jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" d JOIN accounting_period p ON p.id=:p AND p.tenant_id=d.tenant_id AND p.company_id=d.company_id WHERE d.tenant_id=:t AND d.company_id=:c AND d.business_date BETWEEN p.start_date AND p.end_date AND d.status "+state,params,Long.class);
            if(count>0) {diffs.add(new Difference("period_close",table,BigDecimal.ZERO,BigDecimal.valueOf(count)));}
        }
        for(String type:List.of("inventory_balance","inventory_cost","cash_balance","open_item")) {diffs.addAll(check(scope,type,null));}
        return List.copyOf(diffs);
    }
}
