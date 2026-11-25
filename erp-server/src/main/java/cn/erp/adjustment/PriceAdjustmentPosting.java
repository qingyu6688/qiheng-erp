package cn.erp.adjustment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import cn.erp.accounting.LedgerService;
import cn.erp.accounting.SettlementService;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryModels;
import cn.erp.inventory.InventoryService;
import cn.erp.inventory.ReceiptCostAllocator;
import cn.erp.shared.ApiException;
import cn.erp.shared.FinancialScope;
import cn.erp.shared.Ids;
import cn.erp.shared.PaymentTerms;
import cn.erp.shared.ReceiptReturnPricing;
import static cn.erp.adjustment.PriceAdjustmentQueries.*;

@Component
class PriceAdjustmentPosting {
    private final JdbcTemplate jdbc;
    private final PriceAdjustmentWriter writer;
    private final ReceiptCostAllocator costs;
    private final InventoryService inventory;
    private final LedgerService ledger;
    private final SettlementService settlement;
    private final ObjectMapper json;

    PriceAdjustmentPosting(JdbcTemplate jdbc, PriceAdjustmentWriter writer, ReceiptCostAllocator costs,
                            InventoryService inventory, LedgerService ledger, SettlementService settlement, ObjectMapper json) {
        this.jdbc = jdbc; this.writer = writer; this.costs = costs; this.inventory = inventory;
        this.ledger = ledger; this.settlement = settlement; this.json = json;
    }

    List<Map<String, Object>> preview(CompanyAccess actor, Map<String, Object> header, List<Map<String, Object>> lines) {
        FinancialScope.require(actor, "cost:view");
        writer.revalidate(actor, header, lines);
        var shares = trace(actor, lines);
        Map<Long,String> names=new HashMap<>();
        jdbc.query("SELECT id,name FROM warehouse WHERE tenant_id=? AND company_id=?",row->{names.put(row.getLong("id"),row.getString("name"));},actor.tenantId(),actor.companyId());
        return lines.stream().map(line -> Map.<String, Object>of("lineId", line.get("id").toString(), "receiptLineId", line.get("receipt_line_id").toString(),
                "costDelta", cost(line).toPlainString(), "allocations", plan(actor, header, line, shares).stream().map(part->partView(part,names)).toList())).toList();
    }

    void post(CompanyAccess actor, Map<String, Object> header, List<Map<String, Object>> lines) {
        FinancialScope.require(actor, "price_adjustment:post");
        LocalDate day = date(header, "business_date");
        long period = period(actor, day);
        ledger.lockOpenPeriod(actor, period, day);
        var sources = writer.revalidate(actor, header, lines);
        costs.lock(actor, lines.stream().map(line -> number(line, "sku_id")).collect(Collectors.toSet()));
        var shares = trace(actor, lines);
        var journals = new PriceAdjustmentJournals();
        BigDecimal stock = BigDecimal.ZERO, issued = BigDecimal.ZERO;
        for (var line : lines) {
            List<LedgerService.Line> entries = new ArrayList<>();
            var parts = plan(actor, header, line, shares);
            for (var part : parts) {
                applyCost(actor, header, line, part, period, day, entries);
                stock = stock.add(part.stockAmount()); issued = issued.add(part.issuedAmount());
            }
            postPayable(actor, header, line, sources.get(number(line, "id")), period, day);
            applyReturnBasis(actor, line, day);
            add(entries, "INPUT_VAT", "D", inclusive(line) ? BigDecimal.ZERO : amount(line, "tax_amount"), null, null, null, number(line, "id"));
            journals.add(entries, number(header, "partner_id"), number(line, "id"));
        }
        int revision = 0;
        for (var entries : journals.batches())
            ledger.post(actor, new LedgerService.Posting(TYPE, number(header, "id"), revision++, period, day, entries));
        jdbc.update("UPDATE price_adjustment SET status='posted',stock_cost=?,issued_cost=?,period_id=?,posted_by=?,posted_at=UTC_TIMESTAMP(3),version=version+1,updated_by=?"
                + " WHERE tenant_id=? AND company_id=? AND id=?", stock, issued, period, actor.userId(), actor.userId(), actor.tenantId(), actor.companyId(), header.get("id"));
    }

    private Map<Long, Map<Long, BigDecimal>> trace(CompanyAccess actor, List<Map<String, Object>> lines) {
        Map<Long, Map<Long, BigDecimal>> result = new HashMap<>();
        Map<Long, Set<Long>> bySku = lines.stream().collect(Collectors.groupingBy(line -> number(line, "sku_id"),
                Collectors.mapping(line -> number(line, "receipt_line_id"), Collectors.toSet())));
        bySku.forEach((sku, receipts) -> result.putAll(costs.shares(actor, sku, receipts)));
        return result;
    }

    private List<ReceiptCostAllocator.Part> plan(CompanyAccess actor, Map<String, Object> header, Map<String, Object> line,
                                                 Map<Long, Map<Long, BigDecimal>> shares) {
        return costs.plan(actor, number(line, "sku_id"), number(header, "warehouse_id"), amount(line, "source_base_qty"),
                cost(line), shares.getOrDefault(number(line, "receipt_line_id"), Map.of()));
    }

    private void applyCost(CompanyAccess actor, Map<String, Object> header, Map<String, Object> line, ReceiptCostAllocator.Part part,
                            long period, LocalDate day, List<LedgerService.Line> entries) {
        long id = Ids.next(), lineId = number(line, "id"), document = number(header, "id"), sku = number(line, "sku_id");
        var movements = jdbc.queryForList("SELECT id,posted_at FROM inventory_movement WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=?"
                + " AND on_hand_delta<>0 AND movement_type NOT IN('location_move_in','location_move_out') ORDER BY posted_at DESC,id DESC LIMIT 1",
                actor.tenantId(), actor.companyId(), part.warehouseId(), sku);
        jdbc.update("INSERT INTO price_adjustment_cost(id,tenant_id,company_id,adjustment_id,adjustment_line_id,warehouse_id,sku_id,in_stock_qty,stock_amount,issued_amount,movement_cutoff_id,movement_cutoff_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                id, actor.tenantId(), actor.companyId(), document, lineId, part.warehouseId(), sku, part.inStockQty(), part.stockAmount(), part.issuedAmount(),
                movements.isEmpty() ? 0 : movements.getFirst().get("id"), movements.isEmpty() ? null : movements.getFirst().get("posted_at"));
        inventory.purchasePriceCost(actor, new InventoryModels.Posting(new InventoryModels.Key(part.warehouseId(), sku),
                new InventoryModels.Source("price_adjustment_cost", document, id), day, period, "purchase_price"), part.stockAmount(), part.issuedAmount());
        jdbc.update("UPDATE price_adjustment_line SET stock_cost=stock_cost+?,issued_cost=issued_cost+? WHERE id=? AND tenant_id=? AND company_id=?",
                part.stockAmount(), part.issuedAmount(), lineId, actor.tenantId(), actor.companyId());
        add(entries, "INVENTORY", "D", part.stockAmount(), null, part.warehouseId(), sku, lineId);
        add(entries, "COST_VARIANCE", "D", part.issuedAmount(), null, null, null, lineId);
    }

    private void postPayable(CompanyAccess actor, Map<String, Object> header, Map<String, Object> line, Map<String, Object> source, long period, LocalDate day) {
        boolean charge = amount(line, "amount").signum() > 0;
        var due = jdbc.queryForList("SELECT due_date FROM payable WHERE tenant_id=? AND company_id=? AND source_type='goods_receipt_line' AND source_line_id=? AND posting_action='post'",
                actor.tenantId(), actor.companyId(), line.get("receipt_line_id"));
        Object dueDate = due.isEmpty() ? PaymentTerms.forOrder(jdbc, actor, number(source, "order_id"), "supplier", date(source, "receipt_date")) : due.getFirst().get("due_date");
        long id = Ids.next(), supplier = number(header, "partner_id");
        jdbc.update("""
                INSERT INTO payable(id,tenant_id,company_id,supplier_id,kind,source_type,source_id,source_line_id,amount,
                  untaxed_amount,tax_amount,business_date,period_id,created_by,due_date)
                VALUES(?,?,?,?,?,'price_adjustment_line',?,?,?,?,?,?,?,?,?)
                """, id, actor.tenantId(), actor.companyId(), supplier, charge ? "charge" : "credit", header.get("id"), line.get("id"),
                amount(line, "amount").abs(), amount(line, "untaxed_amount").abs(), amount(line, "tax_amount").abs(), day, period, actor.userId(), dueDate);
        settlement.open(actor, new SettlementService.OpenItem("payable", id, supplier, charge ? "C" : "D", amount(line, "amount").abs(), day));
    }

    private void applyReturnBasis(CompanyAccess actor, Map<String, Object> line, LocalDate day) {
        BigDecimal next = amount(line, "old_amount").add(amount(line, "amount"));
        BigDecimal nextTax = amount(line, "old_tax").add(amount(line, "tax_amount"));
        BigDecimal nextCost = inclusive(line) ? next : next.subtract(nextTax);
        var basis = new ReceiptReturnPricing.Basis(amount(line, "source_returned_qty"), amount(line, "source_base_qty"), next, nextTax,
                nextCost.divide(amount(line, "source_base_qty"), 8, RoundingMode.HALF_UP), day);
        jdbc.update("UPDATE goods_receipt_line SET price_adjusted_amount=price_adjusted_amount+?,price_adjusted_tax=price_adjusted_tax+?,pricing_return_basis=?,version=version+1,updated_by=?"
                + " WHERE tenant_id=? AND company_id=? AND id=?", line.get("amount"), line.get("tax_amount"), json.writeValueAsString(basis), actor.userId(), actor.tenantId(), actor.companyId(), line.get("receipt_line_id"));
    }

    private long period(CompanyAccess actor, LocalDate day) {
        var periods = jdbc.queryForList("SELECT id FROM accounting_period WHERE tenant_id=? AND company_id=? AND start_date<=? AND end_date>=?",
                Long.class, actor.tenantId(), actor.companyId(), day, day);
        if (periods.size() != 1) throw new ApiException(422, "PERIOD_NOT_FOUND", "调价日期没有对应的会计期间");
        return periods.getFirst();
    }
    private static boolean inclusive(Map<String, Object> line) {
        Object value = line.get("cost_tax_inclusive");
        return value instanceof Boolean flag ? flag : ((Number) value).intValue() != 0;
    }
    private static BigDecimal cost(Map<String, Object> line) { return amount(line, inclusive(line) ? "amount" : "untaxed_amount"); }
    private Map<String, Object> partView(ReceiptCostAllocator.Part part,Map<Long,String> names) {
        return Map.of("warehouseId", Long.toString(part.warehouseId()), "inStockQty", part.inStockQty().toPlainString(),
                "warehouseName",names.get(part.warehouseId()),"stockAmount", part.stockAmount().toPlainString(), "issuedAmount", part.issuedAmount().toPlainString());
    }
    private void add(List<LedgerService.Line> rows, String account, String direction, BigDecimal amount, Long partner, Long warehouse, Long sku, long line) {
        if (amount.signum() == 0) return;
        rows.add(new LedgerService.Line(account, amount.signum() > 0 ? direction : direction.equals("D") ? "C" : "D",
                amount.abs(), partner, null, warehouse, sku, line, "采购价格调整"));
    }
}
