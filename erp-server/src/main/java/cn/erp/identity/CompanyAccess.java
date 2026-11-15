package cn.erp.identity;

import java.util.Set;

/** 当前操作人经过服务端校验的公司、仓库与操作权限。 */
public record CompanyAccess(long tenantId, long userId, long companyId, boolean allWarehouses,
                            Set<Long> warehouseIds, Set<String> permissions) implements cn.erp.shared.WarehouseScope {
    public CompanyAccess {
        warehouseIds = Set.copyOf(warehouseIds);
        permissions = Set.copyOf(permissions);
    }

    public boolean allowsWarehouse(long id) {
        return allWarehouses || warehouseIds.contains(id);
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }
}
