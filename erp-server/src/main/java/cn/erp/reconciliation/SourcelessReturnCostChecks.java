package cn.erp.reconciliation;

import cn.erp.shared.ActorScope;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** 汇总成本相抵时，仍逐行核对无原单退货及其原成本反向事实。 */
final class SourcelessReturnCostChecks {
    private final NamedParameterJdbcTemplate jdbc;

    SourcelessReturnCostChecks(NamedParameterJdbcTemplate jdbc) {
        this.jdbc=jdbc;
    }

    List<ReconciliationChecks.Difference> check(ActorScope actor) {
        var params=Map.of("t",actor.tenantId(),"c",actor.companyId());
        var result=new ArrayList<ReconciliationChecks.Difference>();
        for(boolean reversed:List.of(false,true)) {
            for(var row:jdbc.queryForList(query(reversed),params)) {
                String key=row.get("return_no")+"/无原单退货/"+source(row.get("cost_source"))+"/"+row.get("id")+"/"+(reversed?"冲销":"入库");
                compare(result,key+"数量",row,"expected_qty","actual_qty");
                compare(result,key+"成本",row,"expected_cost","actual_cost");
            }
        }
        return result;
    }

    private void compare(List<ReconciliationChecks.Difference> result,String key,Map<String,Object> row,String expectedField,String actualField) {
        var expected=(BigDecimal)row.get(expectedField);var actual=(BigDecimal)row.get(actualField);
        if(expected.compareTo(actual)!=0)result.add(new ReconciliationChecks.Difference("inventory_cost",key,expected,actual));
    }

    private static String source(Object value) {
        return switch(String.valueOf(value)) {
            case "average" -> "加权平均成本";
            case "last_in" -> "最近入库成本";
            case "manual" -> "人工复核成本";
            default -> "未记录成本来源";
        };
    }

    private static String query(boolean reversed) {
        String quantities=reversed?"-l.base_qty expected_qty,-l.cost_amount expected_cost":"l.base_qty expected_qty,l.cost_amount expected_cost";
        String movement=reversed?"""
                SELECT back.source_id,origin.source_id return_id,origin.source_line_id,SUM(back.on_hand_delta) qty,SUM(back.cost_delta) cost
                FROM inventory_movement origin JOIN inventory_movement back
                  ON back.tenant_id=origin.tenant_id AND back.company_id=origin.company_id AND back.source_line_id=origin.id
                  AND back.source_type='document_reversal' AND back.posting_action='reverse'
                WHERE origin.tenant_id=:t AND origin.company_id=:c AND origin.source_type='sales_return_line' AND origin.posting_action='post'
                GROUP BY back.source_id,origin.source_id,origin.source_line_id
                """:"""
                SELECT source_id,source_line_id,SUM(on_hand_delta) qty,SUM(cost_delta) cost FROM inventory_movement
                WHERE tenant_id=:t AND company_id=:c AND source_type='sales_return_line' AND posting_action='post'
                GROUP BY source_id,source_line_id
                """;
        String reversal=reversed?" JOIN document_reversal r ON r.tenant_id=h.tenant_id AND r.company_id=h.company_id AND r.source_type='sales_return' AND r.source_id=h.id AND r.status='posted'":"";
        String join=reversed?"m.source_id=r.id AND m.return_id=h.id":"m.source_id=h.id";
        return "SELECT * FROM (SELECT l.id,h.return_no,JSON_UNQUOTE(JSON_EXTRACT(l.cost_basis,'$.source')) cost_source,"
                +quantities+",COALESCE(m.qty,0) actual_qty,COALESCE(m.cost,0) actual_cost"
                +" FROM sales_return_line l JOIN sales_return h ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.return_id"
                +reversal+" LEFT JOIN ("+movement+") m ON "+join+" AND m.source_line_id=l.id"
                +" WHERE l.tenant_id=:t AND l.company_id=:c AND h.return_mode='sourceless' AND h.status IN('posted','reversed')) q"
                +" WHERE expected_qty<>actual_qty OR expected_cost<>actual_cost";
    }
}
