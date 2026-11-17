package cn.erp.aitools;

import cn.erp.identity.AccessService;
import cn.erp.masterdata.WarehouseService;
import cn.erp.shared.ApiException;
import cn.erp.shared.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
class AiBridgeService {
    private static final String USE_PERMISSION = "ai:use";
    private static final String WAREHOUSE_PERMISSION = "warehouse:view";
    private static final String WAREHOUSE_TOOL = "ai.tool.list_warehouses";

    private final AccessService access;
    private final AiBridgePrincipalRepository principals;
    private final WarehouseService warehouses;
    private final AuditService audit;

    AiBridgeService(AccessService access, AiBridgePrincipalRepository principals,
                    WarehouseService warehouses, AuditService audit) {
        this.access = access;
        this.principals = principals;
        this.warehouses = warehouses;
        this.audit = audit;
    }

    AiBridgePrincipalRepository.Principal context(HttpServletRequest request) {
        var actor = access.requireCompany(request, USE_PERMISSION);
        var principal = principals.read(actor);
        if (!principal.permissions().contains(USE_PERMISSION)) {
            throw new ApiException(403, "FORBIDDEN", "没有使用 AI 助手的权限");
        }
        return principal;
    }

    WarehouseService.ActiveSelections listWarehouses(HttpServletRequest request) {
        var actor = access.requireCompany(request, USE_PERMISSION);
        if (!actor.hasPermission(WAREHOUSE_PERMISSION)) {
            audit.record(actor, WAREHOUSE_TOOL, "company", actor.companyId(), null, Map.of("outcome", "denied"));
            throw new ApiException(403, "FORBIDDEN", "没有查看仓库的权限");
        }
        var result = warehouses.listActiveSelections(actor);
        audit.record(actor, WAREHOUSE_TOOL, "company", actor.companyId(), null, Map.of("outcome", "success"));
        return result;
    }
}
