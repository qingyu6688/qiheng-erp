package cn.erp.workbench;

import cn.erp.documents.DocumentAccess;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class OrderRelatedDocuments {
    record Row(String id, String documentId, String documentNo, String status, LocalDate businessDate, String amount) {}
    record Page(List<Row> items, String nextCursor) {}
    record SettlementSummary(String allocatedCashAmount, String openAmount, String scope) {}

    private record Order(String lineTable, String fulfillment, String parentId, String returns, String originId,
                         String cash, String feeTable, String ledger, String chargeSide, String cashSide) {}
    private static final String ALLOCATIONS = "settlement-allocations";
    private static final Map<String,Order> ORDERS = Map.of(
            "sales-orders", new Order("sales_order_line", "shipments", "shipment_id", "sales-returns",
                    "shipment_line_id", "receipts", "sales_order_fee", "receivable", "debit_item_id", "credit_item_id"),
            "purchase-orders", new Order("purchase_order_line", "goods-receipts", "receipt_id", "purchase-returns",
                    "receipt_line_id", "payments", "purchase_order_fee", "payable", "credit_item_id", "debit_item_id"));

    private final NamedParameterJdbcTemplate jdbc;
    private final DocumentBrowser browser;

    OrderRelatedDocuments(NamedParameterJdbcTemplate jdbc, DocumentBrowser browser) {
        this.jdbc = jdbc;
        this.browser = browser;
    }

    SettlementSummary settlementSummary(CompanyAccess actor, String kind, long orderId) {
        var order = ORDERS.get(kind);
        if (order == null || orderId <= 0) {
            throw new ApiException(400, "VALIDATION_FAILED", "订单类型或编号无效");
        }
        if (browser.visibleNumber(actor, kind, orderId).isEmpty()) {
            throw new ApiException(404, "NOT_FOUND", "订单不存在或无权查看");
        }
        String scope = actor.allWarehouses() && actor.orgScope().allDepartments() ? "company" : "visible";
        if (!actor.hasPermission("settlement:view") || !actor.hasPermission(browser.permission(order.fulfillment()))) {
            return new SettlementSummary(null, null, scope);
        }
        var parameters = browser.params(actor);
        parameters.put("order", orderId);
        var origin = browser.spec(order.fulfillment());
        String originScope = browser.where(actor, origin, DocumentBrowser.Filter.empty(), parameters);
        String sourceType = order.ledger().equals("receivable")
                ? "(f.source_type=s.source_type OR (f.source_type='shipment' AND s.source_type='shipment_line'))"
                : "f.source_type=s.source_type";
        String cash = actor.hasPermission(browser.permission(order.cash()))
                ? allocatedCash(actor, order, parameters) : "NULL";
        // 一条未清项可以对应多笔核销。余额与现金分别聚合，避免重复累计余额。
        String sql = """
                WITH order_sources AS (%s), charges AS (
                    SELECT i.id, i.open_amount
                    FROM order_sources s JOIN %s f ON f.tenant_id=:tenant AND f.company_id=:company
                        AND %s AND f.source_line_id=s.line_id AND f.source_id=s.document_id
                        AND f.kind='charge' AND f.posting_action='post'
                    JOIN %s h ON h.id=s.document_id AND %s
                    JOIN partner_open_item i ON i.tenant_id=f.tenant_id AND i.company_id=f.company_id
                        AND i.item_type='%s' AND i.source_id=f.id
                    WHERE NOT EXISTS (SELECT 1 FROM %s r WHERE r.tenant_id=f.tenant_id AND r.company_id=f.company_id
                        AND r.reverses_%s_id=f.id)
                )
                SELECT %s cash_amount, (SELECT COALESCE(SUM(open_amount),0) FROM charges) open_amount
                """.formatted(charges(order, origin), order.ledger(), sourceType, origin.type(), originScope,
                        order.ledger(), order.ledger(), order.ledger(), cash);
        return jdbc.queryForObject(sql, parameters, (row, index) -> {
            var allocated = row.getBigDecimal("cash_amount");
            return new SettlementSummary(allocated == null ? null : allocated.setScale(2).toPlainString(),
                    row.getBigDecimal("open_amount").setScale(2).toPlainString(), scope);
        });
    }

    private String allocatedCash(CompanyAccess actor, Order order, Map<String,Object> parameters) {
        var cash = browser.spec(order.cash());
        String cashScope = browser.where(actor, cash, DocumentBrowser.Filter.empty(), parameters);
        String cashType = cash.type().equals("receipt") ? "h.receipt_type='customer'" : "h.payment_type='supplier'";
        return """
                (SELECT COALESCE(SUM(a.amount),0) FROM charges c
                 JOIN settlement_allocation a ON a.tenant_id=:tenant AND a.company_id=:company AND a.%s=c.id
                     AND a.status='active' AND a.allocation_type IN ('manual','auto_order')
                 JOIN partner_open_item money ON money.tenant_id=a.tenant_id AND money.company_id=a.company_id
                     AND money.id=a.%s AND money.item_type='%s'
                 JOIN %s h ON h.id=money.source_id AND %s AND h.status='confirmed' AND %s)
                """.formatted(order.chargeSide(), order.cashSide(), cash.type(), cash.type(), cashScope, cashType);
    }

    Page page(CompanyAccess actor, String kind, long orderId, String resource, Long cursor, int limit) {
        var order = ORDERS.get(kind);
        if (order == null || !List.of(order.fulfillment(), order.returns(), order.cash(), ALLOCATIONS).contains(resource)) {
            throw new ApiException(400, "VALIDATION_FAILED", "订单关联类型无效");
        }
        if (limit < 1 || limit > 100 || orderId <= 0 || cursor != null && cursor <= 0) {
            throw new ApiException(400, "VALIDATION_FAILED", "订单关联查询或分页条件无效");
        }
        if (browser.visibleNumber(actor, kind, orderId).isEmpty()) {
            throw new ApiException(404, "NOT_FOUND", "订单不存在或无权查看");
        }
        var parameters = browser.params(actor);
        parameters.put("order", orderId);
        parameters.put("cursor", cursor == null ? Long.MAX_VALUE : cursor);
        parameters.put("limit", limit + 1);
        List<Row> result = resource.equals(ALLOCATIONS) ? allocations(actor, order, parameters)
                : documents(actor, order, resource, parameters);
        return new Page(List.copyOf(result.subList(0, Math.min(limit, result.size()))),
                result.size() > limit ? result.get(limit - 1).id() : null);
    }

    private List<Row> documents(CompanyAccess actor, Order order, String resource, Map<String,Object> parameters) {
        DocumentAccess.require(actor, browser.permission(resource));
        var spec = browser.spec(resource);
        String where = browser.where(actor, spec, DocumentBrowser.Filter.empty(), parameters);
        String related = relation(actor, order, resource, parameters);
        return jdbc.query("""
                SELECT h.id, h.id document_id, h.%s document_no, h.status, h.%s business_date, NULL amount
                FROM %s h WHERE %s AND (%s) AND h.id<:cursor ORDER BY h.id DESC LIMIT :limit
                """.formatted(spec.number(), spec.date(), spec.type(), where, related), parameters, mapRow());
    }

    private List<Row> allocations(CompanyAccess actor, Order order, Map<String,Object> parameters) {
        DocumentAccess.require(actor, "settlement:view");
        DocumentAccess.require(actor, browser.permission(order.cash()));
        DocumentAccess.require(actor, browser.permission(order.fulfillment()));
        var original = browser.spec(order.fulfillment());
        var cash = browser.spec(order.cash());
        String originalScope = browser.where(actor, original, DocumentBrowser.Filter.empty(), parameters);
        String cashScope = browser.where(actor, cash, DocumentBrowser.Filter.empty(), parameters);
        String charges = charges(order, original);
        return jdbc.query("""
                WITH order_sources AS (%s)
                SELECT a.id, h.id document_id, h.%s document_no, a.status,
                       DATE(CONVERT_TZ(a.created_at,'+00:00','+08:00')) business_date, a.amount
                FROM order_sources s JOIN %s f ON f.tenant_id=:tenant AND f.company_id=:company
                    AND f.source_type=s.source_type AND f.source_line_id=s.line_id AND f.source_id=s.document_id
                    AND f.kind='charge' AND f.posting_action='post'
                JOIN (SELECT h.id FROM %s h WHERE %s) permitted ON permitted.id=f.source_id
                JOIN partner_open_item oi ON oi.tenant_id=f.tenant_id AND oi.company_id=f.company_id
                    AND oi.item_type='%s' AND oi.source_id=f.id
                JOIN settlement_allocation a ON a.tenant_id=oi.tenant_id AND a.company_id=oi.company_id AND a.%s=oi.id
                JOIN partner_open_item monetary ON monetary.tenant_id=a.tenant_id AND monetary.company_id=a.company_id
                    AND monetary.id=a.%s AND monetary.item_type='%s'
                JOIN %s h ON h.tenant_id=monetary.tenant_id AND h.company_id=monetary.company_id
                    AND h.id=monetary.source_id
                WHERE %s AND a.id<:cursor AND a.allocation_type IN ('manual','auto_order')
                ORDER BY a.id DESC LIMIT :limit
                """.formatted(charges, cash.number(), order.ledger(), original.type(), originalScope, order.ledger(),
                        order.chargeSide(), order.cashSide(), cash.type(), cash.type(), cashScope), parameters, mapRow());
    }

    private static String charges(Order order, DocumentBrowser.Spec original) {
        String recognized = "recognized_" + order.parentId();
        return """
                SELECT '%s_line' source_type, origin_line.id line_id, origin_line.%s document_id
                FROM %s o JOIN %s_line origin_line ON origin_line.tenant_id=o.tenant_id AND origin_line.company_id=o.company_id
                    AND origin_line.order_line_id=o.id
                WHERE o.tenant_id=:tenant AND o.company_id=:company AND o.order_id=:order
                UNION ALL
                SELECT '%s', fee.id, fee.%s
                FROM %s fee WHERE fee.tenant_id=:tenant AND fee.company_id=:company AND fee.order_id=:order
                    AND fee.%s IS NOT NULL
                """.formatted(original.type(), order.parentId(), order.lineTable(), original.type(), order.feeTable(),
                        recognized, order.feeTable(), recognized);
    }

    private static RowMapper<Row> mapRow() {
        return (row, index) -> new Row(row.getString("id"), row.getString("document_id"), row.getString("document_no"),
                row.getString("status"), row.getObject("business_date", LocalDate.class), row.getString("amount"));
    }

    private String relation(CompanyAccess actor, Order order, String resource, Map<String,Object> parameters) {
        if (resource.equals(order.cash())) return "h.related_order_id=:order";
        var fulfillment = browser.spec(order.fulfillment());
        String origin = """
                FROM %s o JOIN %s_line source_line ON source_line.tenant_id=o.tenant_id
                    AND source_line.company_id=o.company_id AND source_line.order_line_id=o.id
                """.formatted(order.lineTable(), fulfillment.type());
        String scope = "o.tenant_id=:tenant AND o.company_id=:company AND o.order_id=:order";
        if (resource.equals(order.fulfillment())) {
            return "h.id IN (SELECT source_line." + order.parentId() + " " + origin + " WHERE " + scope + ")";
        }
        // 退货与订单之间经过原收发单，关联查询也必须检查该收发单的可见范围。
        DocumentAccess.require(actor, browser.permission(order.fulfillment()));
        String originalScope = browser.where(actor, fulfillment, DocumentBrowser.Filter.empty(), parameters);
        return """
                h.id IN (SELECT returned.return_id %s
                    JOIN %s_line returned ON returned.tenant_id=source_line.tenant_id
                        AND returned.company_id=source_line.company_id AND returned.%s=source_line.id
                    JOIN (SELECT h.id FROM %s h WHERE %s) visible ON visible.id=source_line.%s
                    WHERE %s)
                """.formatted(origin, browser.spec(resource).type(), order.originId(), fulfillment.type(), originalScope,
                        order.parentId(), scope);
    }
}
