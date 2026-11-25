package cn.erp.inventory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import cn.erp.accounting.LedgerService;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.FinancialScope;

@Component
final class CostRevaluationPlanner {
    private static final int MAX_EVENTS = 50_000;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String SCOPE = "tenant_id=:t AND company_id=:c AND warehouse_id=:w AND sku_id=:s";
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final InventoryService inventory;
    private final LedgerService ledger;
    record Plan(long inventoryVersion, long period, int eventCount, BigDecimal quantity, BigDecimal before,
                BigDecimal after, String hash, List<CostRevaluationMath.Difference> lines) {
        BigDecimal delta() { return after.subtract(before); }
    }
    private record Ordered(LocalDateTime at, long id, CostRevaluationMath.Event event) {}

    CostRevaluationPlanner(JdbcTemplate jdbc, InventoryService inventory, LedgerService ledger) {
        this.jdbc = jdbc; this.named = new NamedParameterJdbcTemplate(jdbc);
        this.inventory = inventory; this.ledger = ledger;
    }

    Plan calculate(CompanyAccess actor, long warehouse, long sku, LocalDate start, LocalDate day) {
        FinancialScope.require(actor,"cost_revaluation:view");
        if (start == null || day == null || start.isAfter(day) || day.isAfter(LocalDate.now(BUSINESS_ZONE)))
            throw invalid("起算日不能晚于调整入账日，入账日不能晚于今天");
        long period = period(actor,day);
        ledger.lockOpenPeriod(actor,period,day);
        inventory.lockKeys(actor,List.of(new InventoryModels.Key(warehouse,sku)));
        var args = new LinkedHashMap<String,Object>();
        args.put("t",actor.tenantId()); args.put("c",actor.companyId()); args.put("w",warehouse); args.put("s",sku);
        args.put("start",Timestamp.from(start.atStartOfDay(BUSINESS_ZONE).toInstant())); args.put("max",MAX_EVENTS+1);
        var current = named.queryForMap("SELECT qty,total_cost,version FROM inventory_cost WHERE "+SCOPE,args);
        var movements = named.queryForList("SELECT id,reverses_movement_id,movement_type,on_hand_delta,cost_delta,posted_at,period_id,business_date"
                +" FROM inventory_movement WHERE "+SCOPE+" AND posted_at>=:start AND on_hand_delta<>0"
                +" AND movement_type NOT IN('location_move_in','location_move_out') ORDER BY posted_at,id LIMIT :max",args);
        var adjustments = adjustments(args);
        if (movements.size()+adjustments.size()>MAX_EVENTS) throw new ApiException(422,"COST_REPLAY_LIMIT","成本事件超过50000条，请缩短起算范围");
        // 所有实际参与重放的事实均须仍处于开放期间，不允许仅靠调整日期绕过关账。
        var dates = new java.util.TreeMap<Long,LocalDate>();
        for (var row : movements) dates.put(number(row,"period_id"),date(row,"business_date"));
        for (var row : adjustments) dates.put(number(row,"period_id"),date(row,"business_date"));
        if (movements.stream().anyMatch(row->date(row,"business_date").isAfter(day))
                || adjustments.stream().anyMatch(row->date(row,"business_date").isAfter(day)))
            throw invalid("调整入账日不能早于重放范围内已经发生的业务日期");
        dates.forEach((id,date)->ledger.lockOpenPeriod(actor,id,date));
        BigDecimal openingQuantity = sum("SELECT COALESCE(SUM(on_hand_delta),0) FROM inventory_movement WHERE "+SCOPE
                +" AND posted_at<:start AND movement_type NOT IN('location_move_in','location_move_out')",args);
        BigDecimal openingCost = sum("SELECT COALESCE(SUM(cost_delta),0) FROM inventory_movement WHERE "+SCOPE+" AND posted_at<:start",args)
                .add(sum("SELECT COALESCE(SUM(amount),0) FROM cost_adjustment WHERE "+SCOPE
                        +" AND created_at<:start AND adjust_type NOT IN('purchase_price_issued','purchase_fee_issued')",args));
        var folded = new HashMap<Long,BigDecimal>();
        var ids = movements.stream().map(row->number(row,"id")).collect(java.util.stream.Collectors.toSet());
        var ordered = new ArrayList<Ordered>();
        var batches = new LinkedHashMap<String,List<Map<String,Object>>>();
        BigDecimal recorded = openingCost, recordedQuantity = openingQuantity;
        for (var adjustment : adjustments) {
            BigDecimal amount = amount(adjustment,"amount");
            recorded = recorded.add(amount);
            long movement = number(adjustment,"movement_id");
            if (ids.contains(movement)) folded.merge(movement,amount,BigDecimal::add);
            else if("revaluation".equals(adjustment.get("adjust_type"))
                    && Set.of("cost_revaluation","document_reversal").contains(adjustment.get("source_type")))
                batches.computeIfAbsent(adjustment.get("source_type")+"/"+adjustment.get("source_id"),unused->new ArrayList<>()).add(adjustment);
            else ordered.add(new Ordered(time(adjustment,"created_at"),number(adjustment,"id"),
                    new CostRevaluationMath.Event(number(adjustment,"id"),0,0,"adjustment",BigDecimal.ZERO,amount,BigDecimal.ZERO,BigDecimal.ZERO)));
        }
        // 原任务整笔应用净差额；起算日前来源的零净额批次也必须整笔重放，不能制造零库存的临时成本。
        for(var batch:batches.values()) {
            var first=batch.getFirst();
            BigDecimal amount=batch.stream().map(row->amount(row,"amount")).reduce(BigDecimal.ZERO,BigDecimal::add);
            ordered.add(new Ordered(time(first,"created_at"),number(first,"id"),new CostRevaluationMath.Event(number(first,"id"),0,0,
                    "adjustment",BigDecimal.ZERO,amount,BigDecimal.ZERO,BigDecimal.ZERO)));
        }
        for (var movement : movements) {
            long id = number(movement,"id");
            recorded = recorded.add(amount(movement,"cost_delta"));
            recordedQuantity = recordedQuantity.add(amount(movement,"on_hand_delta"));
            ordered.add(new Ordered(time(movement,"posted_at"),id,new CostRevaluationMath.Event(id,id,
                    number(movement,"reverses_movement_id"),movement.get("movement_type").toString(),amount(movement,"on_hand_delta"),
                    amount(movement,"cost_delta"),folded.getOrDefault(id,BigDecimal.ZERO),BigDecimal.ZERO)));
        }
        if (recorded.compareTo(amount(current,"total_cost"))!=0 || recordedQuantity.compareTo(amount(current,"qty"))!=0)
            throw new ApiException(409,"COST_REPLAY_MISMATCH","库存余额与成本流水不一致，请先完成库存对账");
        ordered.sort(Comparator.comparing(Ordered::at).thenComparingLong(Ordered::id));
        var result = CostRevaluationMath.replay(openingQuantity,openingCost,ordered.stream().map(Ordered::event).toList());
        var snapshot = new StringBuilder().append(warehouse).append('/').append(sku).append('/').append(start).append('/').append(day)
                .append('/').append(current).append('/').append(openingQuantity).append('/').append(openingCost);
        movements.forEach(row->snapshot.append('/').append(row));
        adjustments.forEach(row->snapshot.append('/').append(row));
        return new Plan(number(current,"version"),period,movements.size()+adjustments.size(),result.quantity(),recorded,result.cost(),
                digest(snapshot.toString()),result.differences());
    }

    private List<Map<String,Object>> adjustments(Map<String,Object> args) {
        // 尾差、历史重估及其冲销合并到对应原流水；其余成本调整按实际写入时间参与重放。
        return named.queryForList("""
                SELECT a.id,a.amount,a.created_at,a.period_id,a.business_date,a.source_type,a.source_id,a.adjust_type,
                  COALESCE(d.id,l.movement_id,inverse.id,0) movement_id
                FROM cost_adjustment a
                LEFT JOIN inventory_movement d ON d.tenant_id=a.tenant_id AND d.company_id=a.company_id
                  AND d.warehouse_id=a.warehouse_id AND d.sku_id=a.sku_id AND d.source_type=a.source_type
                  AND d.source_line_id=a.source_line_id AND d.posting_action='post' AND d.on_hand_delta<0
                  AND a.adjust_type IN('zero_qty_residue','purchase_return_diff')
                LEFT JOIN cost_revaluation_line l ON l.tenant_id=a.tenant_id AND l.company_id=a.company_id
                  AND l.id=a.source_line_id AND a.source_type='cost_revaluation'
                LEFT JOIN cost_adjustment original ON original.tenant_id=a.tenant_id AND original.company_id=a.company_id
                  AND original.id=a.source_line_id AND a.source_type='document_reversal'
                  AND a.adjust_type IN('zero_qty_residue','purchase_return_diff','revaluation')
                LEFT JOIN cost_revaluation_line prior ON prior.tenant_id=original.tenant_id AND prior.company_id=original.company_id
                  AND prior.id=original.source_line_id AND original.source_type='cost_revaluation'
                LEFT JOIN inventory_movement origin ON origin.tenant_id=original.tenant_id AND origin.company_id=original.company_id
                  AND origin.source_type=original.source_type AND origin.source_line_id=original.source_line_id
                  AND origin.posting_action='post' AND origin.on_hand_delta<0
                LEFT JOIN inventory_movement inverse ON inverse.tenant_id=a.tenant_id AND inverse.company_id=a.company_id
                  AND inverse.source_type='document_reversal' AND inverse.source_id=a.source_id
                  AND inverse.reverses_movement_id=COALESCE(prior.movement_id,origin.id)
                WHERE a.tenant_id=:t AND a.company_id=:c AND a.warehouse_id=:w AND a.sku_id=:s AND a.created_at>=:start
                  AND a.adjust_type NOT IN('purchase_price_issued','purchase_fee_issued')
                ORDER BY a.created_at,a.id LIMIT :max
                """,args);
    }

    private long period(CompanyAccess actor, LocalDate day) {
        var periods = jdbc.queryForList("SELECT id FROM accounting_period WHERE tenant_id=? AND company_id=? AND start_date<=? AND end_date>=?",
                Long.class,actor.tenantId(),actor.companyId(),day,day);
        if (periods.size()!=1) throw invalid("调整入账日没有唯一的会计期间");
        return periods.getFirst();
    }
    private BigDecimal sum(String sql,Map<String,Object> args) { return named.queryForObject(sql,args,BigDecimal.class); }
    static long number(Map<String,Object> row,String field) { return row.get(field)==null?0:((Number)row.get(field)).longValue(); }
    static BigDecimal amount(Map<String,Object> row,String field) { return (BigDecimal)row.get(field); }
    static LocalDate date(Map<String,Object> row,String field) { return ((java.sql.Date)row.get(field)).toLocalDate(); }
    private static LocalDateTime time(Map<String,Object> row,String field) {
        var value=row.get(field);
        if(value instanceof LocalDateTime date)return date;
        if(value instanceof Timestamp date)return date.toLocalDateTime();
        throw new IllegalStateException("成本流水缺少有效过账时间");
    }
    static ApiException invalid(String message) { return new ApiException(400,"VALIDATION_FAILED",message); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("当前运行环境缺少SHA-256算法",error); }
    }
}
