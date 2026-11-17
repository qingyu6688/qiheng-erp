package cn.erp.mfg;

import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryService;
import cn.erp.shared.OrgOwnership;
import cn.erp.shared.WorkflowViews;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import static cn.erp.mfg.MfgStockModels.*;
import static cn.erp.salesfinance.FinanceSupport.*;

@Component
class MfgStockQueries {
    private static final Set<String> STATUSES = Set.of("draft", "pending", "approved", "rejected", "posted", "reversed", "cancelled");
    private final JdbcTemplate jdbc;
    private final InventoryService inventory;

    MfgStockQueries(JdbcTemplate jdbc, InventoryService inventory) {
        this.jdbc = jdbc;
        this.inventory = inventory;
    }

    Map<String, Object> list(CompanyAccess actor, Type type, Filter filter) {
        long before = cursor(filter.cursor(), filter.limit());
        if (filter.status() != null && !STATUSES.contains(filter.status())) throw invalid("单据状态筛选无效");
        if (filter.direction() != null && (type != Type.ISSUE || !Set.of("issue", "return").contains(filter.direction())))
            throw invalid("领退料方向筛选无效");
        if (filter.warehouseId() != null && !actor.allowsWarehouse(filter.warehouseId())) throw missing();
        var args = new ArrayList<Object>(List.of(actor.tenantId(), actor.companyId(), before));
        var sql = select(type, "d.id,d.document_no,d.status,d.business_date,d.version,d.work_order_id,d.warehouse_id,"
                + "o.document_no work_order_no,w.name warehouse_name" + (type == Type.ISSUE ? ",d.direction,d.over_issue" : ""));
        sql.append(" AND d.id<?");
        criteria(sql, args, actor);
        append(sql, args, "d.warehouse_id", filter.warehouseId());
        append(sql, args, "d.work_order_id", filter.workOrderId());
        append(sql, args, "d.status", filter.status());
        append(sql, args, "d.direction", filter.direction());
        args.add(filter.limit() + 1);
        return page(jdbc.queryForList(sql + " ORDER BY d.id DESC LIMIT ?", args.toArray()), filter.limit());
    }

    Map<String, Object> header(CompanyAccess actor, Type type, long document, boolean lock) {
        var args = new ArrayList<Object>(List.of(actor.tenantId(), actor.companyId(), document));
        var sql = select(type, "d.*,o.document_no work_order_no,w.name warehouse_name");
        sql.append(" AND d.id=?");
        criteria(sql, args, actor);
        if (lock) sql.append(" FOR UPDATE OF d");
        return jdbc.queryForList(sql.toString(), args.toArray()).stream().findFirst().orElseThrow(cn.erp.salesfinance.FinanceSupport::missing);
    }

    Map<String, Object> detail(CompanyAccess actor, Type type, long document) {
        var header = header(actor, type, document, false);
        var result = row(header);
        result.put("lines", lines(actor, type, document, false).stream().map(line -> lineView(actor, line)).toList());
        var actions = actions(actor, type, header);
        result.put("allowedActions", type == Type.ISSUE ? WorkflowViews.actions(header, actions) : actions);
        result.remove("wfInstanceId");
        if (type == Type.ISSUE) WorkflowViews.decorate(jdbc, actor, type.head, document, result);
        return result;
    }

    List<Map<String, Object>> lines(CompanyAccess actor, Type type, long document, boolean lock) {
        return jdbc.queryForList("""
                SELECT l.*,s.code sku_code,s.name sku_name,u.name unit_name FROM %s l
                JOIN sku s ON s.tenant_id=l.tenant_id AND s.id=l.sku_id
                JOIN unit u ON u.tenant_id=l.tenant_id AND u.id=l.unit_id
                WHERE l.tenant_id=? AND l.company_id=? AND l.doc_id=? ORDER BY l.line_no
                """.formatted(type.line) + (lock ? " FOR UPDATE OF l" : ""), actor.tenantId(), actor.companyId(), document);
    }

    static void criteria(StringBuilder sql, List<Object> args, CompanyAccess actor) {
        sql.append(OrgOwnership.filter(actor, "d"));
        MfgOrders.appendScope(actor, "o", sql, args);
        if (!actor.allWarehouses()) {
            if (actor.warehouseIds().isEmpty()) {
                sql.append(" AND 1=0");
                return;
            }
            sql.append(" AND d.warehouse_id IN (")
                    .append(String.join(",", java.util.Collections.nCopies(actor.warehouseIds().size(), "?"))).append(')');
            args.addAll(actor.warehouseIds());
        }
    }

    private StringBuilder select(Type type, String columns) {
        return new StringBuilder("""
                SELECT %s FROM %s d JOIN mfg_work_order o
                  ON o.tenant_id=d.tenant_id AND o.company_id=d.company_id AND o.id=d.work_order_id
                JOIN warehouse w ON w.tenant_id=d.tenant_id AND w.company_id=d.company_id AND w.id=d.warehouse_id
                WHERE d.tenant_id=? AND d.company_id=?
                """.formatted(columns, type.head));
    }

    private void append(StringBuilder sql, List<Object> args, String column, Object value) {
        if (value == null) return;
        sql.append(" AND ").append(column).append("=?");
        args.add(value);
    }

    private Map<String, Object> lineView(CompanyAccess actor, Map<String, Object> raw) {
        var line = row(raw);
        line.put("stockSelection", inventory.viewSelection(raw.get("stock_selection")));
        if (!actor.hasPermission("cost:view")) {
            line.remove("amount");
            line.remove("unitCost");
            line.remove("estimatedUnitCost");
        }
        return line;
    }

    private List<String> actions(CompanyAccess actor, Type type, Map<String, Object> header) {
        var status = header.get("status");
        var actions = new ArrayList<String>();
        if ("draft".equals(status)) actions.addAll(List.of("update", "submit", "cancel"));
        if ("pending".equals(status) && independentReviewer(actor, header)) actions.addAll(List.of("approve", "reject"));
        if ("approved".equals(status)) actions.addAll(List.of("post", "cancel"));
        if ("rejected".equals(status)) actions.add("cancel");
        return actions.stream().filter(action -> actor.hasPermission(type.permission(action))).toList();
    }

    static boolean independentReviewer(CompanyAccess actor, Map<String, Object> header) {
        return id(header, "created_by") != actor.userId()
                && (header.get("submitted_by") == null || id(header, "submitted_by") != actor.userId());
    }
}
