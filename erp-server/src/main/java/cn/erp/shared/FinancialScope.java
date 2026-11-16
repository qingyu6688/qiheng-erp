package cn.erp.shared;

/** 财务整账不能用受限仓库、部门或缺少成本权的身份拼接；调用方仍须绑定当前公司。 */
public final class FinancialScope {
    private FinancialScope() {}

    public static void require(ActorScope actor, String permission) {
        if (actor == null
                || actor.tenantId() <= 0
                || actor.companyId() <= 0
                || !(actor instanceof PermissionScope permissions)
                || !permissions.hasPermission(permission)
                || !permissions.hasPermission("cost:view")
                || !(actor instanceof WarehouseScope warehouses)
                || !warehouses.allWarehouses()
                || !OrgOwnership.scope(actor).allDepartments()) {
            throw new ApiException(403, "FORBIDDEN", "财务操作需要当前公司的完整数据范围、成本权限及对应财务权限");
        }
    }
}
