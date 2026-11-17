package cn.erp.shared;

import java.util.stream.Collectors;

public final class ManufacturingScope {
    private ManufacturingScope() {}

    /** 生产单据自身及其工单、冻结物料仓均须在当前授权范围内。 */
    public static String stockCondition(ActorScope actor, String alias) {
        return "1=1" + OrgOwnership.filter(actor, alias) + warehouses(actor, alias + ".warehouse_id")
                + " AND EXISTS(SELECT 1 FROM mfg_work_order mo WHERE mo.tenant_id=" + alias + ".tenant_id AND mo.company_id="
                + alias + ".company_id AND mo.id=" + alias + ".work_order_id AND " + orderCondition(actor, "mo") + ")";
    }

    public static String orderCondition(ActorScope actor, String alias) {
        String department = "1=1" + OrgOwnership.filter(actor, alias);
        if (!(actor instanceof WarehouseScope scope) || scope.allWarehouses()) return department;
        if (scope.warehouseIds().isEmpty()) return "1=0";
        var allowed = scope.warehouseIds().stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
        return department + " AND " + alias + ".issue_warehouse_id IN (" + allowed + ") AND " + alias + ".receive_warehouse_id IN ("
                + allowed + ") AND NOT EXISTS(SELECT 1 FROM mfg_work_order_material mm WHERE mm.tenant_id=" + alias
                + ".tenant_id AND mm.company_id=" + alias + ".company_id AND mm.work_order_id=" + alias
                + ".id AND mm.issue_warehouse_id NOT IN (" + allowed + "))";
    }

    private static String warehouses(ActorScope actor, String column) {
        if (!(actor instanceof WarehouseScope scope) || scope.allWarehouses()) return "";
        if (scope.warehouseIds().isEmpty()) return " AND 1=0";
        return " AND " + column + " IN (" + scope.warehouseIds().stream().sorted().map(String::valueOf).collect(Collectors.joining(",")) + ")";
    }
}
