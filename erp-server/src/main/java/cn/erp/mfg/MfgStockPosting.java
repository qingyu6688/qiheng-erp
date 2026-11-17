package cn.erp.mfg;

import cn.erp.accounting.LedgerService;
import cn.erp.catalog.CatalogAvailability;
import cn.erp.catalog.CatalogAvailability.SkuUnit;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryModels.*;
import cn.erp.inventory.InventoryService;
import cn.erp.shared.ApiException;
import cn.erp.shared.ProductionCostPort;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import static cn.erp.mfg.MfgStockModels.*;
import static cn.erp.salesfinance.FinanceSupport.*;

@Component
class MfgStockPosting {
    static final String ISSUE_OUT = "mfg_issue_out";
    static final String RETURN_IN = "mfg_return_in";
    static final String RECEIPT_IN = "mfg_receipt_in";
    private static final int AMOUNT_SCALE = 2;
    private static final int QUANTITY_SCALE = 6;
    private static final int UNIT_COST_SCALE = 8;
    private final JdbcTemplate jdbc;
    private final MfgStockLines lines;
    private final InventoryService inventory;
    private final LedgerService ledger;
    private final CatalogAvailability catalog;
    private final ProductionCostPort productionCost;

    MfgStockPosting(JdbcTemplate jdbc, MfgStockLines lines, InventoryService inventory, LedgerService ledger,
                    CatalogAvailability catalog, ProductionCostPort productionCost) {
        this.jdbc = jdbc;
        this.lines = lines;
        this.inventory = inventory;
        this.ledger = ledger;
        this.catalog = catalog;
        this.productionCost = productionCost;
    }

    private record Document(Type type, long id, long order, long warehouse, long period, LocalDate day) {}
    private record Returned(BigDecimal qty, BigDecimal equivalent, BigDecimal amount) {
        Returned plus(Returned other) {
            return new Returned(qty.add(other.qty), equivalent.add(other.equivalent), amount.add(other.amount));
        }
    }

    void post(CompanyAccess actor, Type type, Map<String, Object> header, Map<String, Object> order,
              List<Map<String, Object>> rows, long period, LocalDate day) {
        var document = new Document(type, id(header, "id"), id(order, "id"), id(header, "warehouse_id"), period, day);
        catalog.requireActiveSkus(actor, rows.stream().map(line -> new SkuUnit(id(line, "sku_id"), id(line, "unit_id"))).toList());
        inventory.lockKeys(actor, rows.stream().map(line -> new Key(document.warehouse(), id(line, "sku_id"))).distinct().sorted().toList());
        var entries = new ArrayList<LedgerService.Line>();
        if (type == Type.RECEIPT) receipts(actor, document, order, rows, entries);
        else issues(actor, document, header, rows, entries);
        if (!entries.isEmpty()) ledger.post(actor, new LedgerService.Posting(type.head, document.id(), 0, period, day, entries));
        jdbc.update("UPDATE " + type.head + " SET status='posted',period_id=?,posted_at=UTC_TIMESTAMP(3),posted_by=?,updated_by=?,version=version+1"
                        + " WHERE tenant_id=? AND company_id=? AND id=?", period, actor.userId(), actor.userId(),
                actor.tenantId(), actor.companyId(), document.id());
        refreshOrder(actor, document.order());
    }

    private void issues(CompanyAccess actor, Document doc, Map<String, Object> header, List<Map<String, Object>> rows,
                        List<LedgerService.Line> entries) {
        boolean returning = "return".equals(header.get("direction"));
        if (!returning && !flag(header.get("over_issue")) && lines.overIssue(actor, doc.order(), rows))
            throw new ApiException(409, "MFG_OVER_ISSUE_REVIEW", "工单已领数量发生变化，请取消后重新提交超领审批");
        var returned = new HashMap<Long, Returned>();
        for (var line : rows) {
            long material = id(line, "material_id");
            var posting = posting(doc, line, returning ? RETURN_IN : ISSUE_OUT);
            var selection = inventory.decodeSelection(line.get("stock_selection"));
            BigDecimal qty = decimal(line, "qty"), equivalent;
            CostResult cost;
            if (returning) {
                long originId = id(line, "original_issue_line_id");
                var original = lines.original(actor, doc.order(), doc.warehouse(), material, originId, doc.day());
                var prior = returned.computeIfAbsent(originId, id -> returned(actor, id));
                var change = returnCost(original, prior, qty);
                var source = new Source(Type.ISSUE.line, id(original, "issue_doc_id"), originId);
                selection = inventory.returnSelection(actor, posting.key(), source, ISSUE_OUT, selection, qty, null);
                cost = inventory.receive(actor, posting, qty, change.amount(), selection, null);
                equivalent = change.equivalent();
                returned.put(originId, prior.plus(change));
            } else {
                cost = inventory.issue(actor, posting, qty, null, selection, null);
                equivalent = decimal(line, "material_qty");
            }
            updateMaterial(actor, material, returning, equivalent);
            entry(entries, "INVENTORY", returning ? "D" : "C", cost.movementCost(), posting);
            entry(entries, "WIP", returning ? "C" : "D", cost.movementCost(), posting);
            entry(entries, "INVENTORY", "D", cost.adjustment(), posting);
            entry(entries, "COST_VARIANCE", "C", cost.adjustment(), posting);
            jdbc.update("""
                    UPDATE mfg_issue_line SET unit_cost=?,amount=?,material_qty=? WHERE tenant_id=? AND company_id=? AND id=?
                    """, cost.unitCost(), cost.movementCost(), equivalent, actor.tenantId(), actor.companyId(), id(line, "id"));
        }
    }

    private Returned returned(CompanyAccess actor, long origin) {
        var posted = jdbc.queryForList("""
                SELECT l.qty,l.material_qty,l.amount FROM mfg_issue_line l JOIN mfg_issue_doc d
                  ON d.tenant_id=l.tenant_id AND d.company_id=l.company_id AND d.id=l.doc_id
                WHERE l.tenant_id=? AND l.company_id=? AND l.original_issue_line_id=? AND d.direction='return' AND d.status='posted'
                FOR SHARE OF d,l
                """, actor.tenantId(), actor.companyId(), origin);
        var sum = new Returned(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        for (var line : posted) sum = sum.plus(new Returned(decimal(line, "qty"), decimal(line, "material_qty"), decimal(line, "amount")));
        return sum;
    }

    private Returned returnCost(Map<String, Object> original, Returned prior, BigDecimal qty) {
        var total = prior.qty().add(qty);
        var originQty = decimal(original, "qty");
        if (total.compareTo(originQty) > 0) throw new ApiException(422, "MFG_RETURN_QUANTITY", "退料数量超过原领料明细的剩余可退数量");
        // 累计比例先取整，再扣减已退料金额，最后一次退料吸收前序舍入差。
        var amount = MfgStockLines.proportional(total, originQty, decimal(original, "amount"), AMOUNT_SCALE)
                .subtract(prior.amount()).max(BigDecimal.ZERO);
        var equivalent = MfgStockLines.proportional(total, originQty, decimal(original, "material_qty"), QUANTITY_SCALE).subtract(prior.equivalent());
        MfgStockLines.validEquivalent(equivalent);
        return new Returned(qty, equivalent, amount);
    }

    private void receipts(CompanyAccess actor, Document doc, Map<String, Object> order, List<Map<String, Object>> rows,
                          List<LedgerService.Line> entries) {
        var upcoming = rows.stream().map(line -> decimal(line, "qty")).reduce(BigDecimal.ZERO, BigDecimal::add);
        var planned = decimal(order, "plan_qty");
        var available = planned.multiply(BigDecimal.ONE.add(decimal(order, "over_receive_pct").movePointLeft(2)));
        var completed = decimal(order, "completed_qty").add(upcoming);
        if (completed.compareTo(available) > 0) throw new ApiException(422, "MFG_OVER_RECEIPT", "完工入库数量超出工单允许的上限");
        var rate = netMaterialCost(actor, doc).divide(planned, UNIT_COST_SCALE, RoundingMode.HALF_UP)
                .add(productionCost.estimatedExpenseRate(actor, doc.order(), doc.period()));
        for (var line : rows) {
            var post = posting(doc, line, RECEIPT_IN);
            var qty = decimal(line, "qty");
            var amount = qty.multiply(rate).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
            inventory.receive(actor, post, qty, amount, inventory.decodeSelection(line.get("stock_selection")), null);
            entry(entries, "INVENTORY", "D", amount, post);
            entry(entries, "WIP", "C", amount, post);
            jdbc.update("""
                    UPDATE mfg_receipt_line SET estimated_unit_cost=?,amount=? WHERE tenant_id=? AND company_id=? AND id=?
                    """, rate, amount, actor.tenantId(), actor.companyId(), id(line, "id"));
        }
        jdbc.update("UPDATE mfg_work_order SET completed_qty=? WHERE tenant_id=? AND company_id=? AND id=?",
                completed, actor.tenantId(), actor.companyId(), doc.order());
    }

    private BigDecimal netMaterialCost(CompanyAccess actor, Document doc) {
        var cost = jdbc.queryForList("""
                SELECT m.cost_delta FROM inventory_movement m JOIN mfg_issue_doc d
                  ON d.tenant_id=m.tenant_id AND d.company_id=m.company_id AND d.id=m.source_id
                WHERE d.tenant_id=? AND d.company_id=? AND d.work_order_id=? AND m.business_date<=?
                  AND m.source_type='mfg_issue_line' AND m.posting_action='post' FOR SHARE OF m
                """, BigDecimal.class, actor.tenantId(), actor.companyId(), doc.order(), doc.day());
        var reversed = jdbc.queryForList("""
                SELECT m.cost_delta FROM inventory_movement m JOIN document_reversal r
                  ON r.tenant_id=m.tenant_id AND r.company_id=m.company_id AND r.id=m.source_id
                JOIN mfg_issue_doc d ON d.tenant_id=r.tenant_id AND d.company_id=r.company_id AND d.id=r.source_id
                WHERE d.tenant_id=? AND d.company_id=? AND d.work_order_id=? AND m.business_date<=?
                  AND r.source_type='mfg_issue_doc' AND m.source_type='document_reversal' AND m.posting_action='reverse' FOR SHARE OF m
                """, BigDecimal.class, actor.tenantId(), actor.companyId(), doc.order(), doc.day());
        return java.util.stream.Stream.concat(cost.stream(), reversed.stream()).reduce(BigDecimal.ZERO, BigDecimal::add).negate();
    }

    void refreshOrder(CompanyAccess actor, long orderId) {
        var order = jdbc.queryForMap("SELECT completed_qty,plan_qty,status FROM mfg_work_order WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",
                actor.tenantId(), actor.companyId(), orderId);
        var completed = decimal(order, "completed_qty");
        var used = lines.materials(actor, orderId).values().stream()
                .anyMatch(material -> decimal(material, "issued_qty").compareTo(decimal(material, "returned_qty")) > 0);
        String next = completed.compareTo(decimal(order, "plan_qty")) >= 0 ? "completed"
                : completed.signum() > 0 || used ? "in_progress" : "released";
        // 已关闭工单冲销后按新的完工与领料事实恢复作业状态。
        jdbc.update("UPDATE mfg_work_order SET closed_at=IF(status='closed',NULL,closed_at),status=?,updated_by=?,version=version+1"
                        + " WHERE tenant_id=? AND company_id=? AND id=?",
                next, actor.userId(), actor.tenantId(), actor.companyId(), orderId);
    }

    void updateMaterial(CompanyAccess actor, long material, boolean returning, BigDecimal equivalent) {
        String field = returning ? "returned_qty" : "issued_qty";
        jdbc.update("UPDATE mfg_work_order_material SET " + field + "=" + field + "+? WHERE tenant_id=? AND company_id=? AND id=?",
                equivalent, actor.tenantId(), actor.companyId(), material);
    }

    static void orderState(Type type, Object direction, Map<String, Object> order) {
        if (type == Type.RECEIPT) state(order, "in_progress", "completed");
        else if ("return".equals(direction)) state(order, "released", "in_progress", "completed");
        else state(order, "released", "in_progress");
    }

    private Posting posting(Document doc, Map<String, Object> line, String movementType) {
        return new Posting(new Key(doc.warehouse(), id(line, "sku_id")), new Source(doc.type().line, doc.id(), id(line, "id")),
                doc.day(), doc.period(), movementType);
    }

    private void entry(List<LedgerService.Line> entries, String account, String side, BigDecimal amount, Posting posting) {
        if (amount.signum() == 0) return;
        if (amount.signum() < 0) side = "D".equals(side) ? "C" : "D";
        entries.add(new LedgerService.Line(account, side, amount.abs(), null, null, posting.key().warehouseId(),
                posting.key().skuId(), posting.source().lineId(), null));
    }

    static boolean flag(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() != 0;
    }
}
