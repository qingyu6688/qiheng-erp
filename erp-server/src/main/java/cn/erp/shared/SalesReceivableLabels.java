package cn.erp.shared;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 仅补充当前可见应收的来源名称，不读取成本或改变财务事实。 */
@Component
public class SalesReceivableLabels {
    private final JdbcTemplate jdbc;
    public SalesReceivableLabels(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<Long, Map<String, Object>> labels(ActorScope scope, Collection<Long> ids) {
        var result = new LinkedHashMap<Long, Map<String, Object>>();
        if (ids.isEmpty()) return result;
        var distinct = ids.stream().distinct().sorted().toList();
        var args = new ArrayList<Object>();
        args.add(scope.tenantId());
        args.add(scope.companyId());
        args.addAll(distinct);
        var rows = jdbc.queryForList("""
                SELECT r.id receivable_id,r.source_type,r.source_id,
                  CASE r.source_type WHEN 'sales_return_line' THEN sr.return_no
                    WHEN 'price_adjustment_line' THEN pa.adjust_no WHEN 'opening_entry' THEN oe.entry_no
                    ELSE sh.shipment_no END source_no,s.name sku_name
                FROM receivable r
                LEFT JOIN shipment sh ON sh.tenant_id=r.tenant_id AND sh.company_id=r.company_id
                  AND r.source_type IN('shipment_line','sales_order_fee') AND sh.id=r.source_id
                LEFT JOIN shipment_line sl ON sl.tenant_id=r.tenant_id AND sl.company_id=r.company_id
                  AND r.source_type='shipment_line' AND sl.id=r.source_line_id
                LEFT JOIN sales_return sr ON sr.tenant_id=r.tenant_id AND sr.company_id=r.company_id
                  AND r.source_type='sales_return_line' AND sr.id=r.source_id
                LEFT JOIN sales_return_line rl ON rl.tenant_id=r.tenant_id AND rl.company_id=r.company_id
                  AND r.source_type='sales_return_line' AND rl.id=r.source_line_id
                LEFT JOIN price_adjustment pa ON pa.tenant_id=r.tenant_id AND pa.company_id=r.company_id
                  AND r.source_type='price_adjustment_line' AND pa.kind='sales' AND pa.id=r.source_id
                LEFT JOIN price_adjustment_line pl ON pl.tenant_id=pa.tenant_id AND pl.company_id=pa.company_id
                  AND pl.adjustment_id=pa.id AND pl.id=r.source_line_id
                LEFT JOIN opening_entry oe ON oe.tenant_id=r.tenant_id AND oe.company_id=r.company_id
                  AND r.source_type='opening_entry' AND oe.id=r.source_id
                LEFT JOIN sku s ON s.tenant_id=r.tenant_id AND s.id=COALESCE(sl.sku_id,rl.sku_id,pl.sku_id)
                WHERE r.tenant_id=? AND r.company_id=? AND r.id IN(%s) AND %s
                """.formatted(String.join(",", java.util.Collections.nCopies(distinct.size(), "?")),
                    SalesInvoiceScope.receivableCondition(scope, "r")), args.toArray());
        for (var row : rows) {
            long id = ((Number) row.remove("receivable_id")).longValue();
            result.put(id, row);
        }
        return result;
    }
}
