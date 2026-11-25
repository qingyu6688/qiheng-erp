package cn.erp.shared;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** 采购按成本归属仓授权，销售按原单据仓授权；详情和旧响应使用同一范围。 */
public final class PriceAdjustmentScope {
    private PriceAdjustmentScope() {}
    public static String sourceCondition(ActorScope actor,String type,String id) {
        String visible=condition(actor,"pa_scope");
        if(visible.equals("1=1")) return "1=1";
        String head="pa_scope.tenant_id="+actor.tenantId()+" AND pa_scope.company_id="+actor.companyId();
        String reversal="rv_scope.tenant_id="+actor.tenantId()+" AND rv_scope.company_id="+actor.companyId()+" AND rv_scope.id="+id+" AND rv_scope.source_type IN('price_adjustment','sales_price_adjustment')";
        return "(("+type+" NOT IN('price_adjustment','sales_price_adjustment','price_adjustment_line','price_adjustment_cost') OR EXISTS(SELECT 1 FROM price_adjustment pa_scope WHERE "+head+" AND pa_scope.id="+id+" AND "+visible+"))"
                +" AND ("+type+"<>'document_reversal' OR NOT EXISTS(SELECT 1 FROM document_reversal rv_scope WHERE "+reversal+")"
                +" OR EXISTS(SELECT 1 FROM document_reversal rv_scope JOIN price_adjustment pa_scope ON pa_scope.tenant_id=rv_scope.tenant_id AND pa_scope.company_id=rv_scope.company_id AND pa_scope.id=rv_scope.source_id WHERE "+reversal+" AND "+visible+")))";
    }
    public static String condition(ActorScope actor, String alias) {
        if (!(actor instanceof WarehouseScope warehouses)) return "1=0";
        boolean cost=actor instanceof PermissionScope permission && permission.hasPermission("cost:view");
        if (warehouses.allWarehouses()) return cost?"1=1":alias+".kind='sales'";
        if (warehouses.warehouseIds().isEmpty()) return "1=0";
        String ids = warehouses.warehouseIds().stream().sorted().map(Object::toString).collect(java.util.stream.Collectors.joining(","));
        String warehouse=alias+".warehouse_id IN("+ids+")";
        if(!cost)return "("+alias+".kind='sales' AND "+warehouse+")";
        return warehouse+" AND ("+alias+".kind='sales' OR NOT EXISTS(SELECT 1 FROM price_adjustment_cost pc WHERE pc.tenant_id="
                + alias + ".tenant_id AND pc.company_id=" + alias + ".company_id AND pc.adjustment_id=" + alias + ".id AND pc.warehouse_id NOT IN(" + ids + ")))";
    }
    public static void require(JdbcTemplate jdbc, ActorScope actor, Map<String, Object> header) {
        if (!"sales".equals(header.get("kind")) && (!(actor instanceof PermissionScope permission) || !permission.hasPermission("cost:view")))
            throw new ApiException(403, "FORBIDDEN", "查看调价需要成本权限");
        OrgOwnership.require(actor, header);
        if (!(actor instanceof WarehouseScope warehouses)) throw missing();
        if (!warehouses.allowsWarehouse(((Number) header.get("warehouse_id")).longValue())) throw missing();
        if (!warehouses.allWarehouses() && jdbc.queryForList("SELECT warehouse_id FROM price_adjustment_cost WHERE tenant_id=? AND company_id=? AND adjustment_id=?",
                Long.class, actor.tenantId(), actor.companyId(), header.get("id")).stream().anyMatch(id -> !warehouses.allowsWarehouse(id))) throw missing();
    }
    private static ApiException missing() { return new ApiException(404, "NOT_FOUND", "调价成本归属不在当前可见范围"); }
}
