package cn.erp.shared;

import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** 手工登记也要按每条应收的实际发货范围校验，不能只检查登记人的部门。 */
public final class SalesInvoiceScope {
    private SalesInvoiceScope() {}

    public static Map<String, Object> registration(JdbcTemplate jdbc, ActorScope actor, long id) {
        var rows = jdbc.queryForList("SELECT b.* FROM invoice_registration b WHERE b.tenant_id=? AND b.company_id=? AND b.id=?"
                        + OrgOwnership.filter(actor, "b") + " AND " + registrationCondition(actor, "b"),
                actor.tenantId(), actor.companyId(), id);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    public static void receivable(JdbcTemplate jdbc, ActorScope actor, long id) {
        OrgOwnership.requireSource(jdbc, actor, "receivable", id);
        var rows = jdbc.queryForList("SELECT source_type,source_id FROM receivable WHERE tenant_id=? AND company_id=? AND id=?",
                actor.tenantId(), actor.companyId(), id);
        if (rows.isEmpty()) throw missing();
        if (Set.of("shipment_line", "sales_order_fee").contains(rows.getFirst().get("source_type"))) {
            var shipments = jdbc.queryForList("SELECT warehouse_id,owner_user_id,owner_department_id FROM shipment WHERE tenant_id=? AND company_id=? AND id=?",
                    actor.tenantId(), actor.companyId(), rows.getFirst().get("source_id"));
            if (shipments.isEmpty() || !(actor instanceof WarehouseScope warehouses)
                    || !warehouses.allowsWarehouse(((Number) shipments.getFirst().get("warehouse_id")).longValue())) throw missing();
            OrgOwnership.require(actor, shipments.getFirst());
        }
    }

    public static void red(JdbcTemplate jdbc, ActorScope actor, Object id) {
        var rows = jdbc.queryForList("SELECT invoice_id FROM invoice_red_registration WHERE tenant_id=? AND company_id=? AND id=?",
                actor.tenantId(), actor.companyId(), id);
        if (rows.isEmpty()) throw missing();
        registration(jdbc, actor, ((Number) rows.getFirst().get("invoice_id")).longValue());
    }

    public static String registrationCondition(ActorScope actor, String alias) {
        if (!(actor instanceof WarehouseScope warehouses)) return "1=0";
        String visible = OrgOwnership.sourceCondition(actor, "invoice_ar.source_type", "invoice_ar.source_id");
        String shipment = "1=1" + OrgOwnership.filter(actor, "invoice_shipment");
        if (!warehouses.allWarehouses()) shipment += warehouses.warehouseIds().isEmpty() ? " AND 1=0"
                : " AND invoice_shipment.warehouse_id IN(" + warehouses.warehouseIds().stream().sorted()
                    .map(Object::toString).collect(java.util.stream.Collectors.joining(",")) + ")";
        // 固定按整张发票定位来源，避免历史批量导入后沿应收索引扫描整家公司。
        String sources = "NOT EXISTS(SELECT 1 FROM invoice_registration_line invoice_line FORCE INDEX(idx_invoice_registration_line_invoice) LEFT JOIN receivable invoice_ar"
                + " ON invoice_ar.tenant_id=invoice_line.tenant_id AND invoice_ar.company_id=invoice_line.company_id AND invoice_ar.id=invoice_line.receivable_id"
                + " WHERE invoice_line.tenant_id=" + alias + ".tenant_id AND invoice_line.company_id=" + alias + ".company_id AND invoice_line.invoice_id=" + alias + ".id"
                + " AND (invoice_ar.id IS NULL OR NOT COALESCE((" + visible + "),FALSE)"
                + " OR (invoice_ar.source_type IN('shipment_line','sales_order_fee') AND NOT EXISTS(SELECT 1 FROM shipment invoice_shipment WHERE invoice_shipment.tenant_id=invoice_ar.tenant_id"
                + " AND invoice_shipment.company_id=invoice_ar.company_id AND invoice_shipment.id=invoice_ar.source_id AND " + shipment + "))))";
        return "(" + alias + ".invoice_type<>'sales' OR " + sources + ")";
    }

    public static String receivableCondition(ActorScope actor, String alias) {
        if (!(actor instanceof WarehouseScope warehouses)) return "1=0";
        String visible = OrgOwnership.sourceCondition(actor, alias + ".source_type", alias + ".source_id");
        if (warehouses.allWarehouses()) return visible;
        if (warehouses.warehouseIds().isEmpty()) return "1=0";
        String ids = warehouses.warehouseIds().stream().sorted().map(Object::toString)
                .collect(java.util.stream.Collectors.joining(","));
        return visible + " AND (" + alias + ".source_type NOT IN('shipment_line','sales_order_fee','sales_return_line')"
                + " OR (" + alias + ".source_type IN('shipment_line','sales_order_fee') AND EXISTS(SELECT 1 FROM shipment candidate_sh"
                + " WHERE candidate_sh.tenant_id=" + alias + ".tenant_id AND candidate_sh.company_id=" + alias + ".company_id"
                + " AND candidate_sh.id=" + alias + ".source_id AND candidate_sh.warehouse_id IN(" + ids + ")))"
                + " OR (" + alias + ".source_type='sales_return_line' AND EXISTS(SELECT 1 FROM sales_return candidate_sr"
                + " WHERE candidate_sr.tenant_id=" + alias + ".tenant_id AND candidate_sr.company_id=" + alias + ".company_id"
                + " AND candidate_sr.id=" + alias + ".source_id AND candidate_sr.warehouse_id IN(" + ids + "))))";
    }

    private static ApiException missing() { return new ApiException(404, "NOT_FOUND", "发票或关联来源不存在或无权访问"); }
}
