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
    private final AiBusinessTools tools;

    AiBridgeService(AccessService access, AiBridgePrincipalRepository principals,
                    WarehouseService warehouses, AuditService audit, AiBusinessTools tools) {
        this.access = access;
        this.principals = principals;
        this.warehouses = warehouses;
        this.audit = audit;
        this.tools = tools;
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

    Map<String,Object> query(HttpServletRequest request, String code, byte[] body) {
        var actor=access.requireCompany(request,USE_PERMISSION);
        var tool=AiBusinessTools.Tool.parse(code);
        var action="ai.tool."+tool.code();
        try {
            var result=tools.query(actor,tool,AiBusinessInput.parse(body,tool.fields));
            audit.record(actor,action,"company",actor.companyId(),null,Map.of("outcome",result.get("status")));
            return result;
        } catch(ApiException error) {
            audit.record(actor,action,"company",actor.companyId(),null,Map.of("outcome","rejected","code",error.getErrorCode()));
            throw error;
        }
    }

    Map<String,String> scope(HttpServletRequest request, String code) {
        var actor=access.requireCompany(request,USE_PERMISSION);
        var tool=AiBusinessTools.Tool.parse(code);
        return Map.of("scopeFingerprint",tools.scope(actor,tool));
    }

    Map<String,String> documentScope(HttpServletRequest request,byte[] body) {
        var actor=access.requireCompany(request,USE_PERMISSION);
        var input=AiBusinessInput.parse(body,AiBusinessTools.Tool.DOCUMENT_EXPLAIN.fields);
        return Map.of("scopeFingerprint",tools.documentScope(actor,input));
    }
}
