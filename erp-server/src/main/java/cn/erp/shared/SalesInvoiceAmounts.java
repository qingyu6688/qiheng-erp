package cn.erp.shared;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 正向应收保留原值，开票另取同一发货行退货、调价后的净额度。 */
@Component
public class SalesInvoiceAmounts {
    public record Capacity(long receivableId, Long shipmentLineId, BigDecimal amount, BigDecimal tax,
                           BigDecimal used, BigDecimal groupAmount, BigDecimal groupUsed) {
        public String group() { return shipmentLineId == null ? "receivable:" + receivableId : "shipment:" + shipmentLineId; }
        public BigDecimal available() { return amount.subtract(used).min(groupAmount.subtract(groupUsed)).max(BigDecimal.ZERO); }
        public BigDecimal availableTax() {
            BigDecimal remaining = available();
            if (remaining.signum() == 0 || amount.signum() == 0) return BigDecimal.ZERO.setScale(2);
            return tax.multiply(used.add(remaining)).divide(amount, 2, java.math.RoundingMode.HALF_UP)
                    .subtract(tax.multiply(used).divide(amount, 2, java.math.RoundingMode.HALF_UP));
        }
    }
    private final JdbcTemplate jdbc;
    private final SalesInvoiceRedQueries reds;
    public SalesInvoiceAmounts(JdbcTemplate jdbc) { this.jdbc = jdbc; this.reds = new SalesInvoiceRedQueries(jdbc); }

    public Map<Long, Capacity> capacities(ActorScope actor, Collection<Long> ids) {
        var groups = new HashMap<Long, SalesInvoiceAllocation.Result>();
        var result = new LinkedHashMap<Long, Capacity>();
        for (long id : ids.stream().distinct().sorted().toList()) {
            var ar = source(actor, id);
            Long shipment = ar.get("shipment_line") == null ? null : ((Number) ar.get("shipment_line")).longValue();
            if (shipment == null) {
                BigDecimal used = used(actor, id);
                result.put(id, new Capacity(id, null, money(ar, "amount"), money(ar, "tax_amount"), used, money(ar, "amount"), used));
            } else {
                var group = groups.computeIfAbsent(shipment, line -> group(actor, line));
                var quota = group.quotas().stream().filter(row -> row.id() == id).findFirst().orElseThrow(SalesInvoiceAmounts::changed);
                result.put(id, new Capacity(id, shipment, quota.amount(), quota.tax(), quota.used(), group.amount(), group.used()));
            }
        }
        return result;
    }

    private Map<String, Object> source(ActorScope actor, long id) {
        var rows = jdbc.queryForList("""
                SELECT a.*,CASE WHEN a.source_type='shipment_line' THEN a.source_line_id
                    WHEN a.source_type='price_adjustment_line' THEN p.shipment_line_id END shipment_line
                FROM receivable a LEFT JOIN price_adjustment_line p ON p.tenant_id=a.tenant_id AND p.company_id=a.company_id
                  AND p.id=a.source_line_id AND a.source_type='price_adjustment_line'
                WHERE a.tenant_id=? AND a.company_id=? AND a.id=? AND a.kind='charge' AND a.posting_action='post'
                  AND NOT EXISTS(SELECT 1 FROM receivable r WHERE r.tenant_id=a.tenant_id AND r.company_id=a.company_id AND r.reverses_receivable_id=a.id)
                """, actor.tenantId(), actor.companyId(), id);
        if (rows.isEmpty()) throw changed();
        return rows.getFirst();
    }

    private SalesInvoiceAllocation.Result group(ActorScope actor, long shipment) {
        var rows = jdbc.queryForList("""
                SELECT a.id,a.kind,a.untaxed_amount,a.tax_amount,
                  COALESCE((SELECT SUM(l.amount) FROM invoice_registration_line l FORCE INDEX(idx_invoice_line_receivable) STRAIGHT_JOIN invoice_registration i
                    ON i.tenant_id=l.tenant_id AND i.company_id=l.company_id AND i.id=l.invoice_id
                    WHERE l.tenant_id=a.tenant_id AND l.company_id=a.company_id AND l.receivable_id=a.id AND i.status='registered'),0) used
                FROM receivable a
                LEFT JOIN price_adjustment_line p ON p.tenant_id=a.tenant_id AND p.company_id=a.company_id
                  AND p.id=a.source_line_id AND a.source_type='price_adjustment_line'
                LEFT JOIN sales_return_line r ON r.tenant_id=a.tenant_id AND r.company_id=a.company_id
                  AND r.id=a.source_line_id AND a.source_type='sales_return_line'
                WHERE a.tenant_id=? AND a.company_id=? AND a.posting_action='post'
                  AND NOT EXISTS(SELECT 1 FROM receivable reverse_row WHERE reverse_row.tenant_id=a.tenant_id
                    AND reverse_row.company_id=a.company_id AND reverse_row.reverses_receivable_id=a.id)
                  AND ((a.source_type='shipment_line' AND a.source_line_id=?) OR p.shipment_line_id=? OR r.shipment_line_id=?)
                ORDER BY CASE WHEN a.source_type='shipment_line' THEN 0 ELSE COALESCE(p.source_version,0)+1 END,a.id
                """, actor.tenantId(), actor.companyId(), shipment, shipment, shipment);
        var charges = new java.util.ArrayList<SalesInvoiceAllocation.Charge>();
        BigDecimal untaxed = BigDecimal.ZERO, tax = BigDecimal.ZERO;
        for (var row : rows) {
            if ("charge".equals(row.get("kind"))) charges.add(new SalesInvoiceAllocation.Charge(number(row, "id"), money(row, "untaxed_amount"), money(row, "tax_amount"), money(row, "used")));
            else { untaxed = untaxed.add(money(row, "untaxed_amount")); tax = tax.add(money(row, "tax_amount")); }
        }
        return SalesInvoiceAllocation.allocate(charges, untaxed, tax);
    }

    public List<Map<String, Object>> redRequirements(ActorScope actor, long shipment) {
        return reds.all(actor, List.of(shipment));
    }

    public java.util.Set<Long> incompleteRedSources(ActorScope actor, Collection<Long> sources) { return reds.incomplete(actor, sources); }
    public Map<String, Object> redInvoicePage(ActorScope actor, Collection<Long> sources, Long cursor, int limit) { return reds.page(actor, sources, cursor, limit); }

    public void requireRed(ActorScope actor, long shipment) {
        requireRed(actor, List.of(shipment));
    }
    public void requireRed(ActorScope actor, Collection<Long> sources) {
        if (!incompleteRedSources(actor, sources).isEmpty())
            throw new ApiException(422, "SALES_RED_REQUIRED", "该发货行已经开票，请先完成原票整票红字；待确认、处理中或仅作废登记不能作为已完成红字");
    }

    private BigDecimal used(ActorScope actor, long id) {
        // 新导入历史票据时统计信息可能滞后，先按应收索引缩小范围，避免从整家公司历史票据开始扫描。
        return jdbc.queryForObject("SELECT COALESCE(SUM(l.amount),0) FROM invoice_registration_line l FORCE INDEX(idx_invoice_line_receivable) STRAIGHT_JOIN invoice_registration h"
                + " ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.invoice_id"
                + " WHERE l.tenant_id=? AND l.company_id=? AND l.receivable_id=? AND h.status='registered'", BigDecimal.class,
                actor.tenantId(), actor.companyId(), id);
    }
    private static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private static BigDecimal money(Map<String, Object> row, String key) { return (BigDecimal) row.get(key); }
    private static ApiException changed() { return new ApiException(409, "INVOICE_CAPACITY_CHANGED", "应收来源或净开票额度已变化，请刷新后重新选择"); }
}
