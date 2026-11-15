package cn.erp.masterdata;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
class WarehouseCommands {
    private static final String LOOKUP = """
            SELECT id, operator_id, request_hash, status, resource_id, response_body
            FROM idempotency_record
            WHERE tenant_id=? AND company_id=? AND operation=? AND idempotency_key=?
            """;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final cn.erp.shared.BusinessCommands commands;

    WarehouseCommands(JdbcTemplate jdbc,ObjectMapper json,cn.erp.shared.BusinessCommands commands) {
        this.jdbc=jdbc;this.json=json;this.commands=commands;
    }

    WarehouseModels.View execute(CompanyAccess scope,String operation,String key,Object payload,
                                int httpStatus,Supplier<WarehouseModels.View> command) {
        var result=commands.execute(scope,operation,key,payload,httpStatus,command,WarehouseModels.View.class);
        WarehouseQueries.ensureVisible(scope,Long.parseLong(result.id()));
        return result;
    }

    WarehouseModels.Result result(CompanyAccess scope, String operation, String key) {
        validateKey(key);
        Entry entry = lookup(scope, operation, key, false);
        return new WarehouseModels.Result(entry.status(), entry.response() == null ? null : view(scope, entry));
    }

    private Entry lookup(CompanyAccess scope, String operation, String key, boolean lock) {
        List<Entry> entries = jdbc.query(LOOKUP + (lock ? " FOR UPDATE" : ""),
                (rs, n) -> new Entry(rs.getLong("id"), rs.getLong("operator_id"), rs.getString("request_hash"),
                        rs.getString("status"), rs.getLong("resource_id"), rs.getString("response_body")),
                scope.tenantId(), scope.companyId(), operation, key);
        if (entries.isEmpty()) {
            throw new ApiException(404, "NOT_FOUND", "未找到该操作结果");
        }
        Entry entry = entries.getFirst();
        if (entry.operatorId() != scope.userId()) {
            throw new ApiException(403, "FORBIDDEN", "只有原操作人可以查看此结果");
        }
        return entry;
    }

    private WarehouseModels.View view(CompanyAccess scope, Entry entry) {
        var response=json.readValue(entry.response(), WarehouseModels.View.class);
        WarehouseQueries.ensureVisible(scope, Long.parseLong(response.id()));
        return response;
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ApiException(400, "IDEMPOTENCY_KEY_REQUIRED", "缺少幂等键");
        }
        if (!key.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new ApiException(400, "VALIDATION_FAILED", "幂等键格式有误");
        }
    }

    private record Entry(long id, long operatorId, String hash, String status, long resourceId, String response) {}
}
