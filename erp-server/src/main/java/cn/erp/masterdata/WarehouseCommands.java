package cn.erp.masterdata;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
    private static final int RETENTION_DAYS = 7;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    WarehouseCommands(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    WarehouseModels.View execute(CompanyAccess scope, String operation, String key, Object payload,
                                 int httpStatus, Supplier<WarehouseModels.View> command) {
        validateKey(key);
        String hash = hash(payload);
        long claimId = Ids.next();
        // 唯一键争用由 InnoDB 排队；首笔事务回滚时等待者可以接手执行。
        jdbc.update("""
                INSERT INTO idempotency_record
                (id, tenant_id, company_id, operation, idempotency_key, request_hash, operator_id, expire_at)
                VALUES(?,?,?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(3), INTERVAL ? DAY))
                ON DUPLICATE KEY UPDATE id=id
                """, claimId, scope.tenantId(), scope.companyId(), operation, key, hash, scope.userId(), RETENTION_DAYS);
        Entry entry = lookup(scope, operation, key, true);
        if (entry.id() != claimId) {
            if (!entry.hash().equals(hash)) {
                throw new ApiException(409, "IDEMPOTENCY_KEY_REUSED", "同一请求标识对应了不同内容");
            }
            if (entry.response() == null) {
                throw new ApiException(409, "REQUEST_IN_PROGRESS", "请求正在处理，请稍后查看结果");
            }
            return view(scope, entry);
        }
        var response = command.get();
        jdbc.update("""
                UPDATE idempotency_record SET status='succeeded', http_status=?, resource_type='warehouse',
                resource_id=?, response_body=? WHERE id=?
                """, httpStatus, Long.parseLong(response.id()), json.writeValueAsString(response), claimId);
        return response;
    }

    WarehouseModels.Result result(CompanyAccess scope, String operation, String key) {
        validateKey(key);
        Entry entry = lookup(scope, operation, key, false);
        return new WarehouseModels.Result(entry.status(), entry.response() == null ? null : view(scope, entry));
    }

    private String hash(Object payload) {
        try {
            byte[] value = json.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 运行时缺少 SHA-256", exception);
        }
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
        WarehouseQueries.ensureVisible(scope, entry.resourceId());
        return json.readValue(entry.response(), WarehouseModels.View.class);
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
