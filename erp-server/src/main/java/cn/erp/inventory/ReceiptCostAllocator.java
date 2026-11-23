package cn.erp.inventory;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.FinancialScope;
import cn.erp.inventory.InventoryModels.Key;

/** 采购调价和后续费用分摊共用的收货成本归属计算。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ReceiptCostAllocator {
    private final JdbcTemplate jdbc;
    private final InventoryService inventory;
    public record Part(long warehouseId, BigDecimal inStockQty, BigDecimal stockAmount, BigDecimal issuedAmount) {}

    public ReceiptCostAllocator(JdbcTemplate jdbc, InventoryService inventory) {
        this.jdbc = jdbc;
        this.inventory = inventory;
    }

    /** 覆盖当前全部仓库，防止已在途的调拨并发进入一个尚无成本余额的仓库。 */
    public void lock(CompanyAccess actor, Set<Long> skus) {
        FinancialScope.require(actor, "cost:view");
        List<Long> warehouses = jdbc.queryForList("SELECT id FROM warehouse WHERE tenant_id=? AND company_id=? ORDER BY id FOR SHARE",
                Long.class, actor.tenantId(), actor.companyId());
        List<Key> keys = warehouses.stream().flatMap(warehouse -> skus.stream().sorted().map(sku -> new Key(warehouse, sku))).sorted().toList();
        inventory.lockKeys(actor, keys);
    }

    public Map<Long, Map<Long, BigDecimal>> shares(CompanyAccess actor, long sku, Set<Long> receiptLines) {
        FinancialScope.require(actor, "cost:view");
        if (receiptLines.isEmpty()) return Map.of();
        // 份额在同一库存池中变化，必须一并追踪其他收货，不能让结果随本次选行而改变。
        var tracked=new java.util.HashSet<>(jdbc.queryForList("SELECT DISTINCT source_line_id FROM inventory_movement"
                +" WHERE tenant_id=? AND company_id=? AND sku_id=? AND source_type='goods_receipt_line'"
                +" AND movement_type='purchase_in' AND posting_action='post'",Long.class,actor.tenantId(),actor.companyId(),sku));
        if(!tracked.containsAll(receiptLines)) throw new ApiException(409,"COST_TRACE_MISSING","收货行缺少原始入库流水，请先核对");
        var args = new ArrayList<Object>(List.of(actor.tenantId(), actor.companyId(), sku));
        Timestamp start = jdbc.queryForObject("SELECT MIN(posted_at) FROM inventory_movement WHERE tenant_id=? AND company_id=? AND sku_id=?"
                + " AND source_type='goods_receipt_line' AND movement_type='purchase_in' AND posting_action='post'", Timestamp.class, args.toArray());
        if (start == null) throw new ApiException(409, "COST_TRACE_MISSING", "收货行缺少原始入库流水，请先核对");
        var trace = new ReceiptCostTrace(tracked);
        jdbc.query("SELECT warehouse_id,SUM(on_hand_delta) qty FROM inventory_movement WHERE tenant_id=? AND company_id=? AND sku_id=?"
                + " AND posted_at<? AND movement_type NOT IN('location_move_out','location_move_in') GROUP BY warehouse_id",
                result -> { trace.opening(result.getLong("warehouse_id"), result.getBigDecimal("qty")); },
                actor.tenantId(), actor.companyId(), sku, start);
        read(actor, sku, start, trace);
        var result=trace.shares();
        return result.entrySet().stream().filter(entry->receiptLines.contains(entry.getKey()))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,Map.Entry::getValue));
    }

    private void read(CompanyAccess actor, long sku, Timestamp start, ReceiptCostTrace trace) {
        jdbc.query("""
                SELECT m.id,m.warehouse_id,m.on_hand_delta,m.movement_type,m.source_type,m.source_line_id,
                  m.posting_action,m.reverses_movement_id,e.transfer_line_id,return_diff.id weighted_return,
                  COALESCE(sale.shipment_line_id,production.original_issue_line_id,misc.original_loan_line_id,
                    purchase_return.receipt_line_id,IF(m.movement_type='sales_reject_in',m.source_line_id,NULL)) returned_line
                FROM inventory_movement m
                LEFT JOIN stock_transfer_event_line e ON e.tenant_id=m.tenant_id AND e.company_id=m.company_id
                  AND e.id=m.source_line_id AND m.source_type='stock_transfer_event'
                LEFT JOIN sales_return_line sale ON sale.tenant_id=m.tenant_id AND sale.company_id=m.company_id
                  AND sale.id=m.source_line_id AND m.source_type='sales_return_line'
                LEFT JOIN mfg_issue_line production ON production.tenant_id=m.tenant_id AND production.company_id=m.company_id
                  AND production.id=m.source_line_id AND m.source_type='mfg_issue_line'
                LEFT JOIN stock_misc_line misc ON misc.tenant_id=m.tenant_id AND misc.company_id=m.company_id
                  AND misc.id=m.source_line_id AND m.source_type='stock_misc_line'
                LEFT JOIN purchase_return_line purchase_return ON purchase_return.tenant_id=m.tenant_id AND purchase_return.company_id=m.company_id
                  AND purchase_return.id=m.source_line_id AND m.source_type='purchase_return_line'
                LEFT JOIN cost_adjustment return_diff ON return_diff.tenant_id=m.tenant_id AND return_diff.company_id=m.company_id
                  AND return_diff.source_type='purchase_return_line' AND return_diff.source_line_id=m.source_line_id
                  AND m.source_type='purchase_return_line' AND return_diff.adjust_type='purchase_return_diff'
                WHERE m.tenant_id=? AND m.company_id=? AND m.sku_id=? AND m.posted_at>=? AND m.on_hand_delta<>0
                  AND m.movement_type NOT IN('location_move_out','location_move_in') ORDER BY m.posted_at,m.id
                """, result -> {
            trace.record(new ReceiptCostTrace.Movement(result.getLong("id"), result.getLong("warehouse_id"),
                    result.getBigDecimal("on_hand_delta"), result.getString("movement_type"), result.getLong("source_line_id"),
                    result.getLong("reverses_movement_id"), result.getLong("transfer_line_id"), result.getLong("returned_line"),
                    "goods_receipt_line".equals(result.getString("source_type")) && "purchase_in".equals(result.getString("movement_type"))
                            && "post".equals(result.getString("posting_action")), result.getObject("weighted_return")!=null));
        }, actor.tenantId(), actor.companyId(), sku, start);
    }

    public List<Part> plan(CompanyAccess actor, long sku, long originalWarehouse, BigDecimal remaining,
                           BigDecimal costDelta, Map<Long, BigDecimal> shares) {
        FinancialScope.require(actor, "cost:view");
        Map<Long, BigDecimal> costs = new TreeMap<>();
        jdbc.query("SELECT warehouse_id,total_cost FROM inventory_cost WHERE tenant_id=? AND company_id=? AND sku_id=?",
                result -> { costs.put(result.getLong("warehouse_id"), result.getBigDecimal("total_cost")); },
                actor.tenantId(), actor.companyId(), sku);
        return ReceiptCostMath.allocate(originalWarehouse, remaining, costDelta, shares, costs).stream()
                .map(row -> new Part(row.warehouse(), row.quantity(), row.stock(), row.issued())).toList();
    }
}
