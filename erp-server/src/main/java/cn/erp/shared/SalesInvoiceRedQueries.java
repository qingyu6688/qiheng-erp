package cn.erp.shared;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** 全量红字校验与分页展示使用同一判断，展示条数不影响过账门槛。 */
final class SalesInvoiceRedQueries {
    private static final String SOURCE_LINE = "CASE WHEN a.source_type='shipment_line' THEN a.source_line_id ELSE p.shipment_line_id END";
    private static final String COMPLETE = """
            CASE WHEN i.einvoice_request_id IS NULL THEN COALESCE(red.status='confirmed',FALSE)
              ELSE (e.status='red_issued' OR e.status='failed') END
            """;
    private static final String FIELDS = "i.id invoice_id,i.invoice_no,i.status,i.einvoice_request_id,e.status electronic_status,e.red_invoice_no," + COMPLETE + " red_complete";
    private static final String FROM = """
            FROM invoice_registration i JOIN invoice_registration_line il
              ON il.tenant_id=i.tenant_id AND il.company_id=i.company_id AND il.invoice_id=i.id
            JOIN receivable a ON a.tenant_id=il.tenant_id AND a.company_id=il.company_id AND a.id=il.receivable_id
            LEFT JOIN price_adjustment_line p ON p.tenant_id=a.tenant_id AND p.company_id=a.company_id
              AND p.id=a.source_line_id AND a.source_type='price_adjustment_line'
            LEFT JOIN einv_issue_request e ON e.tenant_id=i.tenant_id AND e.company_id=i.company_id AND e.id=i.einvoice_request_id
            LEFT JOIN invoice_red_registration red ON red.tenant_id=i.tenant_id AND red.company_id=i.company_id AND red.active_invoice_id=i.id
            WHERE i.tenant_id=? AND i.company_id=? AND i.invoice_type='sales'
              AND ((a.source_type='shipment_line' AND a.source_line_id IN(%s)) OR p.shipment_line_id IN(%s))
            """;
    private final JdbcTemplate jdbc;
    SalesInvoiceRedQueries(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    Set<Long> incomplete(ActorScope actor, Collection<Long> sources) {
        if (sources.isEmpty()) return Set.of();
        var filter = filter(actor, sources);
        return Set.copyOf(jdbc.queryForList("SELECT DISTINCT " + SOURCE_LINE + " " + filter.sql()
                + " AND NOT COALESCE((" + COMPLETE + "),FALSE)", Long.class, filter.args().toArray()));
    }

    List<Map<String, Object>> all(ActorScope actor, Collection<Long> sources) {
        if (sources.isEmpty()) return List.of();
        var filter = filter(actor, sources);
        return jdbc.queryForList("SELECT DISTINCT " + FIELDS + " " + filter.sql() + " ORDER BY i.id", filter.args().toArray())
                .stream().map(ApiRows::row).toList();
    }

    Map<String, Object> page(ActorScope actor, Collection<Long> sources, Long cursor, int limit) {
        if (limit < 1 || limit > 200 || cursor != null && cursor < 1)
            throw new ApiException(400, "VALIDATION_FAILED", "原票分页参数有误");
        var result = new LinkedHashMap<String, Object>();
        result.put("items", List.of()); result.put("nextCursor", null);
        if (sources.isEmpty()) return result;
        var filter = filter(actor, sources);
        var args = new ArrayList<>(filter.args());
        args.add(cursor == null ? Long.MAX_VALUE : cursor); args.add(limit + 1);
        var rows = jdbc.queryForList("SELECT DISTINCT " + FIELDS + " " + filter.sql() + " AND i.id<?"
                + OrgOwnership.filter(actor, "i") + " AND " + SalesInvoiceScope.registrationCondition(actor, "i")
                + " ORDER BY i.id DESC LIMIT ?", args.toArray());
        var items = rows.stream().limit(limit).map(ApiRows::row).toList();
        appendSourceLines(actor, sources, items);
        result.put("items", items);
        result.put("nextCursor", rows.size() > limit ? items.getLast().get("invoiceId") : null);
        return result;
    }

    private void appendSourceLines(ActorScope actor, Collection<Long> sources, List<Map<String, Object>> items) {
        if (items.isEmpty()) return;
        var filter = filter(actor, sources);
        var ids = items.stream().map(row -> Long.valueOf(row.get("invoiceId").toString())).toList();
        var args = new ArrayList<>(filter.args()); args.addAll(ids);
        var links = jdbc.queryForList("SELECT DISTINCT i.id invoice_id," + SOURCE_LINE + " shipment_line_id "
                + filter.sql() + " AND i.id IN(" + placeholders(ids.size()) + ")", args.toArray());
        var byInvoice = new LinkedHashMap<String, List<String>>();
        for (var link : links) byInvoice.computeIfAbsent(link.get("invoice_id").toString(), key -> new ArrayList<>())
                .add(link.get("shipment_line_id").toString());
        for (var item : items) item.put("shipmentLineIds", byInvoice.getOrDefault(item.get("invoiceId").toString(), List.of()));
    }

    private static Filter filter(ActorScope actor, Collection<Long> sources) {
        var ids = sources.stream().distinct().sorted().toList();
        var args = new ArrayList<Object>(); args.add(actor.tenantId()); args.add(actor.companyId());
        args.addAll(ids); args.addAll(ids);
        return new Filter(FROM.formatted(placeholders(ids.size()), placeholders(ids.size())), args);
    }
    private static String placeholders(int size) { return String.join(",", java.util.Collections.nCopies(size, "?")); }
    private record Filter(String sql, List<Object> args) {}
}
