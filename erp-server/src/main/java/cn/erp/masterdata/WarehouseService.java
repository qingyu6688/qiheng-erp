package cn.erp.masterdata;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import cn.erp.shared.RequestIds;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
public class WarehouseService {
    private static final int ACTIVE_SELECTION_LIMIT = 20;
    private final JdbcTemplate jdbc;
    private final WarehouseQueries queries;
    private final WarehouseCommands commands;
    private final ObjectMapper json;

    WarehouseService(JdbcTemplate jdbc, WarehouseQueries queries, WarehouseCommands commands, ObjectMapper json) {
        this.jdbc = jdbc;
        this.queries = queries;
        this.commands = commands;
        this.json = json;
    }

    WarehouseModels.Page list(CompanyAccess scope, String keyword, String status, int limit,
                              String cursor, boolean withTotal) {
        return queries.list(scope, keyword, status, limit, cursor, withTotal);
    }

    public ActiveSelections listActiveSelections(CompanyAccess scope) {
        var page = list(scope, null, "active", ACTIVE_SELECTION_LIMIT, null, false);
        var items = page.items().stream()
                .map(row -> new ActiveSelection(row.id(), row.code(), row.name()))
                .toList();
        return new ActiveSelections(items, page.hasMore());
    }

    public record ActiveSelection(String id, String code, String name) {}

    public record ActiveSelections(List<ActiveSelection> items, boolean hasMore) {}

    WarehouseModels.View get(CompanyAccess scope, long id) {
        return queries.find(scope, id, false);
    }

    @Transactional
    WarehouseModels.View create(CompanyAccess scope, String key, WarehouseModels.Create input) {
        if (!scope.allWarehouses()) {
            throw new ApiException(403, "FORBIDDEN", "创建仓库需要整家公司范围的权限");
        }
        return commands.execute(scope, "warehouse.create", key, input, 201, () -> {
            long id = Ids.next();
            try {
                jdbc.update("""
                        INSERT INTO warehouse(id,tenant_id,company_id,code,name,address,created_by,updated_by)
                        VALUES(?,?,?,?,?,?,?,?)
                        """, id, scope.tenantId(), scope.companyId(), input.code(), input.name(),
                        input.address(), scope.userId(), scope.userId());
            } catch (DuplicateKeyException exception) {
                throw duplicateCode(exception);
            }
            return afterChange(scope, id, "warehouse.create", null);
        });
    }

    @Transactional
    WarehouseModels.View edit(CompanyAccess scope, long id, WarehouseModels.Edit input) {
        var before = queries.find(scope, id, true);
        checkVersion(input.version(), before);
        try {
            jdbc.update("""
                    UPDATE warehouse SET code=?,name=?,address=?,version=version+1,updated_by=?
                    WHERE id=? AND tenant_id=? AND company_id=? AND version=?
                    """, input.code(), input.name(), input.address(), scope.userId(), id, scope.tenantId(),
                    scope.companyId(), input.version());
        } catch (DuplicateKeyException exception) {
            throw duplicateCode(exception);
        }
        return afterChange(scope, id, "warehouse.update", before);
    }

    @Transactional
    WarehouseModels.View changeStatus(CompanyAccess scope, long id, String action, String key, long version) {
        WarehouseQueries.ensureVisible(scope, id);
        String operation = "warehouse." + action;
        String status = switch (action) {
            case "enable" -> "active";
            case "disable" -> "disabled";
            default -> throw new ApiException(400, "VALIDATION_FAILED", "仓库操作无效");
        };
        return commands.execute(scope, operation, key, List.of(id, version), 200, () -> {
            var before = queries.find(scope, id, true);
            checkVersion(version, before);
            if (before.status().equals(status)) {
                throw new ApiException(409, "STATE_CONFLICT", "仓库已经处于目标状态，请刷新后重试");
            }
            jdbc.update("""
                    UPDATE warehouse SET status=?,version=version+1,updated_by=?
                    WHERE id=? AND tenant_id=? AND company_id=? AND version=?
                    """, status, scope.userId(), id, scope.tenantId(), scope.companyId(), version);
            return afterChange(scope, id, operation, before);
        });
    }

    WarehouseModels.Result result(CompanyAccess scope, String operation, String key) {
        return commands.result(scope, operation, key);
    }

    private WarehouseModels.View afterChange(CompanyAccess scope, long id, String operation,
                                            WarehouseModels.View before) {
        var after = queries.find(scope, id, true);
        jdbc.update("""
                INSERT INTO audit_log(id,tenant_id,company_id,operator_id,action,object_type,
                object_id,before_data,after_data,request_id) VALUES(?,?,?,?,?,'warehouse',?,?,?,?)
                """, Ids.next(), scope.tenantId(), scope.companyId(), scope.userId(), operation,
                id, before == null ? null : json.writeValueAsString(before), json.writeValueAsString(after),
                RequestIds.current());
        return after;
    }

    private static void checkVersion(long supplied, WarehouseModels.View current) {
        if (supplied != current.version()) {
            throw new ApiException(409, "VERSION_CONFLICT", "仓库已被他人修改，请刷新后重试",
                    Map.of("currentVersion", current.version()));
        }
    }

    private static RuntimeException duplicateCode(DuplicateKeyException exception) {
        String detail = exception.getMostSpecificCause().getMessage();
        if (detail != null && detail.contains("uk_warehouse_code")) {
            return new ApiException(409, "DUPLICATE", "该公司的仓库编码已存在", Map.of("field", "code"));
        }
        return exception;
    }
}
