package cn.erp.mfg;

import cn.erp.inventory.InventoryModels.Key;
import cn.erp.shared.ActorScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import static cn.erp.mfg.MfgCostMath.*;

@Component
class MfgCostFacts {
    private final JdbcTemplate jdbc;

    MfgCostFacts(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    record Period(long id,String number,LocalDate start,LocalDate end,String status,int reopenCount) {}
    record WorkRow(Work work,String documentNo,long sku,String skuCode) {}

    Period period(ActorScope actor,long period) {
        return jdbc.query("""
                SELECT id,period,start_date,end_date,status,reopen_count FROM accounting_period
                WHERE tenant_id=? AND company_id=? AND id=?
                """,(r,i)->new Period(r.getLong("id"),r.getString("period"),r.getDate("start_date").toLocalDate(),
                r.getDate("end_date").toLocalDate(),r.getString("status"),r.getInt("reopen_count")),
                actor.tenantId(),actor.companyId(),period).stream().findFirst().orElseThrow(MfgCostFacts::missing);
    }

    List<WorkRow> works(ActorScope actor,Period period) {
        return jdbc.query("""
                WITH activity AS (
                  SELECT work_order_id,
                     SUM(IF(period_id=? AND movement_type<>'mfg_receipt_in',-cost_delta,0)) material,
                     SUM(IF(period_id=? AND movement_type='mfg_receipt_in',cost_delta,0)) estimate,
                     SUM(IF(period_id=? AND movement_type='mfg_receipt_in',on_hand_delta,0)) finished,
                     SUM(IF(movement_type='mfg_receipt_in',on_hand_delta,0)) lifetime_finished,
                     SUM(period_id=?) period_moves
                  FROM mfg_cost_movement WHERE tenant_id=? AND company_id=? AND business_date<=? AND work_order_id IS NOT NULL
                  GROUP BY work_order_id
                ), previous AS (
                  SELECT i.work_order_id,i.wip_end,i.wip_end_material,
                         ROW_NUMBER() OVER(PARTITION BY i.work_order_id ORDER BY p.period DESC) prior_row
                  FROM mfg_cost_item i JOIN mfg_cost_period c ON c.tenant_id=i.tenant_id AND c.company_id=i.company_id AND c.period_id=i.period_id
                  JOIN accounting_period p ON p.tenant_id=i.tenant_id AND p.company_id=i.company_id AND p.id=i.period_id
                  WHERE i.tenant_id=? AND i.company_id=? AND p.period<? AND c.status='posted'
                )
                SELECT w.id,w.document_no,w.sku_id,s.code sku_code,w.plan_qty,
                   COALESCE(a.material,0) material,COALESCE(a.estimate,0) estimate,COALESCE(a.finished,0) finished,
                   COALESCE(a.lifetime_finished,0) lifetime_finished,COALESCE(prior.wip_end,0) beginning,
                   COALESCE(prior.wip_end_material,0) beginning_material,COALESCE(input.hours,0) hours,input.progress_pct,
                   short_close.cost_disposition short_close_disposition
                FROM mfg_work_order w JOIN sku s ON s.tenant_id=w.tenant_id AND s.id=w.sku_id
                LEFT JOIN activity a ON a.work_order_id=w.id
                LEFT JOIN previous prior ON prior.work_order_id=w.id AND prior.prior_row=1
                LEFT JOIN mfg_cost_input input ON input.tenant_id=w.tenant_id AND input.company_id=w.company_id
                    AND input.work_order_id=w.id AND input.period_id=?
                LEFT JOIN mfg_work_order_short_close short_close ON short_close.tenant_id=w.tenant_id AND short_close.company_id=w.company_id
                    AND short_close.work_order_id=w.id AND short_close.period_id=? AND short_close.status='approved'
                WHERE w.tenant_id=? AND w.company_id=? AND (a.period_moves>0 OR prior.wip_end<>0 OR short_close.id IS NOT NULL)
                ORDER BY w.id
                """,(r,i)->new WorkRow(new Work(r.getLong("id"),r.getBigDecimal("plan_qty"),r.getBigDecimal("finished"),
                r.getBigDecimal("lifetime_finished"),r.getBigDecimal("material"),r.getBigDecimal("estimate"),r.getBigDecimal("beginning"),
                r.getBigDecimal("beginning_material"),r.getBigDecimal("hours"),r.getBigDecimal("progress_pct"),
                r.getString("short_close_disposition")),
                r.getString("document_no"),r.getLong("sku_id"),r.getString("sku_code")),period.id(),period.id(),period.id(),period.id(),
                actor.tenantId(),actor.companyId(),period.end(),actor.tenantId(),actor.companyId(),period.number(),
                period.id(),period.id(),actor.tenantId(),actor.companyId());
    }

    Map<Long,Map<Destination,BigDecimal>> exposures(ActorScope actor,Period period) {
        var receipts=jdbc.queryForList("""
                SELECT m.id,m.sku_id,m.posted_at,m.work_order_id FROM mfg_cost_movement m
                WHERE m.tenant_id=? AND m.company_id=? AND m.period_id=? AND m.movement_type='mfg_receipt_in'
                  AND m.posting_action='post' AND m.work_order_id IS NOT NULL AND NOT EXISTS(
                    SELECT 1 FROM inventory_movement rev WHERE rev.tenant_id=m.tenant_id AND rev.company_id=m.company_id
                        AND rev.reverses_movement_id=m.id AND rev.period_id=m.period_id)
                ORDER BY m.posted_at,m.id
                """,actor.tenantId(),actor.companyId(),period.id());
        if (receipts.isEmpty()) return Map.of();
        var skuIds=receipts.stream().map(r->number(r,"sku_id")).distinct().sorted().toList();
        var layer=new LinkedHashMap<Long,Long>();
        receipts.forEach(r->layer.put(number(r,"id"),number(r,"work_order_id")));
        var flow=new MfgCostFlow();
        var start=receipts.getFirst().get("posted_at");
        String skuFilter=String.join(",",Collections.nCopies(skuIds.size(),"?"));
        var args=new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId(),start));
        args.addAll(skuIds);
        loadBalances(flow,skuFilter,args);
        var transferIds=new LinkedHashMap<Long,Long>();
        readFlow(flow,skuFilter,args,period,layer,transferIds);
        readTransferLosses(actor,flow,transferIds);
        return flow.finish();
    }

    private void loadBalances(MfgCostFlow flow,String skuFilter,List<Object> args) {
        jdbc.query("""
                SELECT warehouse_id,sku_id,SUM(on_hand_delta) qty FROM inventory_movement
                WHERE tenant_id=? AND company_id=? AND posted_at<? AND sku_id IN (%s)
                    AND movement_type NOT IN ('location_move_out','location_move_in')
                GROUP BY warehouse_id,sku_id
                """.formatted(skuFilter),r->{flow.opening(new Key(r.getLong("warehouse_id"),r.getLong("sku_id")),r.getBigDecimal("qty"));},args.toArray());
    }

    private void readFlow(MfgCostFlow flow,String skuFilter,List<Object> args,Period period,Map<Long,Long> layer,Map<Long,Long> transfers) {
        jdbc.query("""
                SELECT m.id,m.warehouse_id,m.sku_id,m.on_hand_delta,m.movement_type,m.source_line_id,
                  m.reverses_movement_id,m.period_id,production.work_order_id,e.transfer_line_id,
                  COALESCE(ret.original_issue_line_id,sale.shipment_line_id) returned_line
                FROM inventory_movement m
                LEFT JOIN mfg_cost_movement production ON production.tenant_id=m.tenant_id AND production.company_id=m.company_id
                    AND production.id=m.id AND m.movement_type='mfg_issue_out' AND m.posting_action='post'
                LEFT JOIN mfg_issue_line ret ON ret.tenant_id=m.tenant_id AND ret.company_id=m.company_id
                    AND ret.id=m.source_line_id AND m.source_type='mfg_issue_line' AND m.movement_type='mfg_return_in'
                LEFT JOIN sales_return_line sale ON sale.tenant_id=m.tenant_id AND sale.company_id=m.company_id
                    AND sale.id=m.source_line_id AND m.source_type='sales_return_line'
                LEFT JOIN stock_transfer_event_line e ON e.tenant_id=m.tenant_id AND e.company_id=m.company_id
                    AND e.id=m.source_line_id AND m.source_type='stock_transfer_event'
                WHERE m.tenant_id=? AND m.company_id=? AND m.posted_at>=? AND m.sku_id IN (%s)
                  AND m.on_hand_delta<>0 AND m.movement_type NOT IN ('location_move_out','location_move_in')
                ORDER BY m.posted_at,m.id
                """.formatted(skuFilter),r->{
            long id=r.getLong("id"), order=r.getLong("work_order_id"), line=r.getLong("source_line_id");
            var type=r.getString("movement_type");
            if (type.equals("transfer_out")) transfers.put(line,line);
            // 后期领用不能挤进本期工单，保留独立标识供计算拒绝跨期滚转。
            if (order>0 && r.getLong("period_id")!=period.id()) order=Long.MAX_VALUE;
            flow.record(new MfgCostFlow.Movement(id,new Key(r.getLong("warehouse_id"),r.getLong("sku_id")),
                    r.getBigDecimal("on_hand_delta"),type,line,r.getLong("reverses_movement_id"),
                    layer.getOrDefault(id,0L),order,r.getLong("transfer_line_id"),r.getLong("returned_line")));
        },args.toArray());
    }

    private void readTransferLosses(ActorScope actor,MfgCostFlow flow,Map<Long,Long> transfers) {
        if (transfers.isEmpty()) return;
        var args=new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId()));
        args.addAll(transfers.keySet());
        String keys=String.join(",",Collections.nCopies(transfers.size(),"?"));
        jdbc.query("""
                SELECT transfer_line_id,SUM(base_qty) qty FROM stock_transfer_event_line
                WHERE tenant_id=? AND company_id=? AND event_type='lost' AND transfer_line_id IN (%s)
                GROUP BY transfer_line_id ORDER BY transfer_line_id
                """.formatted(keys),r->{flow.transferLoss(r.getLong("transfer_line_id"),r.getBigDecimal("qty"));},args.toArray());
    }

    BigDecimal collectGl(ActorScope actor,long period,String kind,String subject) {
        String ledger=kind.equals("labor")?"PRODUCTION_LABOR":"PRODUCTION_OVERHEAD";
        var rows=jdbc.queryForList("""
                SELECT DISTINCT s.id FROM gl_business_mapping m JOIN gl_subject s ON s.tenant_id=m.tenant_id
                    AND s.company_id=m.company_id AND s.id=m.subject_id AND s.status='active' AND s.is_leaf=1
                JOIN gl_account_book b ON b.tenant_id=m.tenant_id AND b.company_id=m.company_id AND b.status='active'
                WHERE m.tenant_id=? AND m.company_id=? AND m.ledger_account_code=? AND m.enabled=1
                    AND m.condition_json IS NULL AND s.code=?
                """,Long.class,actor.tenantId(),actor.companyId(),ledger,subject);
        if (rows.size()!=1) throw invalid("费用科目须与当前账套启用的生产费用池映射一致");
        var amount=jdbc.queryForObject("""
                SELECT COALESCE(SUM(IF(l.direction='D',l.amount,-l.amount)),0)
                FROM gl_voucher_line l JOIN gl_voucher v ON v.tenant_id=l.tenant_id AND v.company_id=l.company_id AND v.id=l.voucher_id
                WHERE l.tenant_id=? AND l.company_id=? AND v.period_id=? AND l.subject_id=?
                  AND v.status IN ('posted','reversed') AND v.is_deleted=0
                  AND COALESCE(v.source_type,'') NOT IN ('mfg_cost_posting','mfg_cost_reopen')
                """,BigDecimal.class,actor.tenantId(),actor.companyId(),period,rows.getFirst());
        if (amount.signum()<0) throw invalid("本期费用科目的借方净额为负，请核对总账凭证");
        return amount;
    }

    static long number(Map<String,Object> row,String key) {return ((Number)row.get(key)).longValue();}
    static cn.erp.shared.ApiException missing() {return new cn.erp.shared.ApiException(404,"NOT_FOUND","未找到当前公司的生产成本期间或工单");}
}
