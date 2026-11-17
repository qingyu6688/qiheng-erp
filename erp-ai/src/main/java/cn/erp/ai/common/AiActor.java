package cn.erp.ai.common;

import java.util.Set;

public record AiActor(String tenantId, String userId, String companyId,
        Set<String> permissions, Set<String> roleIds, Set<String> departmentIds) {
    public AiActor {
        AiInputs.id(tenantId);
        AiInputs.id(userId);
        AiInputs.id(companyId);
        permissions = Set.copyOf(permissions);
        roleIds = Set.copyOf(roleIds);
        departmentIds = Set.copyOf(departmentIds);
    }

    public boolean has(String permission) { return permissions.contains(permission); }

    public void require(String permission) {
        if (!has(permission)) throw AiException.forbidden();
    }

    public void requireAdminView() {
        if (!has("ai_admin:view") && !has("ai_admin:manage")) throw AiException.forbidden();
    }
}
