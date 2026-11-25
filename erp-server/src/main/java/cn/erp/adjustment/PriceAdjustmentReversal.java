package cn.erp.adjustment;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ActorScope;
import cn.erp.shared.ApiException;
import cn.erp.shared.FinancialScope;
import cn.erp.shared.PriceAdjustmentReversalPort;
import static cn.erp.adjustment.PriceAdjustmentQueries.*;

@Component
class PriceAdjustmentReversal implements PriceAdjustmentReversalPort {
    private final JdbcTemplate jdbc;
    private final PriceAdjustmentQueries queries;
    private final cn.erp.inventory.ReceiptCostAllocator costs;
    PriceAdjustmentReversal(JdbcTemplate jdbc, PriceAdjustmentQueries queries, cn.erp.inventory.ReceiptCostAllocator costs) {
        this.jdbc = jdbc; this.queries = queries; this.costs = costs;
    }

    @Override public void prepare(ActorScope scope, long adjustmentId) {
        FinancialScope.require(scope, "reversal:post");
        CompanyAccess actor = actor(scope);
        queries.head(actor, adjustmentId, false);
        var lines = sorted(actor, adjustmentId);
        for (var line : lines) queries.source(actor, number(line, "receipt_line_id"), true);
        // 依赖检查与反向成本写入共用库存锁，不能在两者之间插入新出入库。
        costs.lock(actor, lines.stream().map(line -> number(line, "sku_id")).collect(java.util.stream.Collectors.toSet()));
    }

    @Override public void dependencies(ActorScope scope, long adjustmentId) {
        CompanyAccess actor = actor(scope);
        var header = queries.head(actor, adjustmentId, false);
        for (var line : sorted(actor, adjustmentId)) {
            var source = queries.source(actor, number(line, "receipt_line_id"), true);
            if (amount(source, "returned_base_qty").compareTo(amount(line, "source_returned_qty")) != 0
                    || amount(source, "pending_return_base_qty").signum() != 0) throw dependency("调价后已经发生采购退货或有待处理退货，请先处理退货单");
            var later = jdbc.queryForList("""
                    SELECT h.id FROM price_adjustment h JOIN price_adjustment_line l
                      ON l.tenant_id=h.tenant_id AND l.company_id=h.company_id AND l.adjustment_id=h.id
                    WHERE h.tenant_id=? AND h.company_id=? AND l.receipt_line_id=? AND h.id<>?
                      AND h.status NOT IN('voided','reversed','rejected')
                      AND (h.id>? OR h.posted_at>?) LIMIT 1
                    """, Long.class, actor.tenantId(), actor.companyId(), line.get("receipt_line_id"), adjustmentId, adjustmentId, header.get("posted_at"));
            if (!later.isEmpty()) throw dependency("原收货行已有后续调价申请，请先处理后续调价");
        }
        var used = jdbc.queryForList("""
                SELECT m.id FROM inventory_movement m JOIN price_adjustment_cost c
                  ON c.tenant_id=m.tenant_id AND c.company_id=m.company_id AND c.warehouse_id=m.warehouse_id AND c.sku_id=m.sku_id
                WHERE c.tenant_id=? AND c.company_id=? AND c.adjustment_id=? AND c.stock_amount<>0
                  AND (m.posted_at>c.movement_cutoff_at OR (m.posted_at=c.movement_cutoff_at AND m.id>c.movement_cutoff_id))
                  AND m.on_hand_delta<>0 AND m.posting_action='post'
                  AND m.movement_type NOT IN('location_move_in','location_move_out')
                  AND NOT EXISTS(SELECT 1 FROM inventory_movement r WHERE r.tenant_id=m.tenant_id AND r.company_id=m.company_id AND r.reverses_movement_id=m.id)
                LIMIT 1
                """, Long.class, actor.tenantId(), actor.companyId(), adjustmentId);
        if (!used.isEmpty()) throw dependency("调价成本已有后续库存流水使用，请先按业务顺序处理后续库存单据");
    }

    @Override public void restore(ActorScope scope, long adjustmentId) {
        CompanyAccess actor = actor(scope);
        for (var line : sorted(actor, adjustmentId)) {
            jdbc.update("UPDATE goods_receipt_line SET price_adjusted_amount=price_adjusted_amount-?,price_adjusted_tax=price_adjusted_tax-?,pricing_return_basis=?,version=version+1,updated_by=?"
                    + " WHERE tenant_id=? AND company_id=? AND id=?", line.get("amount"), line.get("tax_amount"), line.get("prior_return_basis"), actor.userId(), actor.tenantId(), actor.companyId(), line.get("receipt_line_id"));
        }
    }

    private List<java.util.Map<String, Object>> sorted(CompanyAccess actor, long id) {
        return queries.lines(actor, id).stream().sorted(java.util.Comparator.comparingLong(line -> number(line, "receipt_line_id"))).toList();
    }
    private static CompanyAccess actor(ActorScope actor) {
        if (actor instanceof CompanyAccess access) return access;
        throw new ApiException(403, "FORBIDDEN", "缺少当前公司授权");
    }
    private static ApiException dependency(String message) { return new ApiException(422, "DEPENDENCY_EXISTS", message); }
}
