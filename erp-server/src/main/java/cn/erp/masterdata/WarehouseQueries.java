package cn.erp.masterdata;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class WarehouseQueries {
    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_TOTAL = 10_000;
    private static final String COLUMNS = "SELECT id, code, name, address, status, version ";
    private static final String SCOPE = " FROM warehouse WHERE tenant_id=:tenant AND company_id=:company";
    private final NamedParameterJdbcTemplate jdbc;

    WarehouseQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    WarehouseModels.Page list(CompanyAccess scope, String keyword, String status, int limit,
                              String cursor, boolean withTotal) {
        keyword = WarehouseModels.clean(keyword);
        status = WarehouseModels.clean(status);
        if (limit < 1 || limit > MAX_PAGE_SIZE || (keyword != null && keyword.length() > 100)
                || (status != null && !Set.of("active", "disabled").contains(status))) {
            throw new ApiException(400, "VALIDATION_FAILED", "仓库筛选条件或每页条数无效");
        }
        Long lastId = decodeCursor(cursor);
        var params = parameters(scope);
        var filter = new StringBuilder(SCOPE);
        if (!scope.allWarehouses()) {
            if (scope.warehouseIds().isEmpty()) {
                return new WarehouseModels.Page(List.of(), null, false, withTotal ? 0 : null, withTotal ? true : null);
            }
            filter.append(" AND id IN (:allowed)");
            params.addValue("allowed", scope.warehouseIds());
        }
        if (keyword != null) {
            filter.append(" AND (LOCATE(:keyword, code)>0 OR LOCATE(:keyword, name)>0)");
            params.addValue("keyword", keyword);
        }
        if (status != null) {
            filter.append(" AND status=:status");
            params.addValue("status", status);
        }
        Integer total = withTotal ? count(filter.toString(), params) : null;
        if (lastId != null) {
            filter.append(" AND id<:last");
            params.addValue("last", lastId);
        }
        params.addValue("limit", limit + 1);
        var rows = jdbc.query(COLUMNS + filter + " ORDER BY id DESC LIMIT :limit", params,
                (rs, n) -> map(rs, scope));
        boolean hasMore = rows.size() > limit;
        var page = rows.subList(0, Math.min(rows.size(), limit));
        return new WarehouseModels.Page(page, hasMore ? encodeCursor(page.getLast().id()) : null, hasMore,
                total == null ? null : Math.min(total, MAX_TOTAL), total == null ? null : total <= MAX_TOTAL);
    }

    WarehouseModels.View find(CompanyAccess scope, long id, boolean lock) {
        ensureVisible(scope, id);
        var rows = jdbc.query(COLUMNS + SCOPE + " AND id=:id" + (lock ? " FOR UPDATE" : ""),
                parameters(scope).addValue("id", id), (rs, n) -> map(rs, scope));
        if (rows.isEmpty()) {
            throw missing();
        }
        return rows.getFirst();
    }

    static void ensureVisible(CompanyAccess scope, long id) {
        if (id <= 0 || !scope.allowsWarehouse(id)) {
            throw missing();
        }
    }

    private static ApiException missing() {
        return new ApiException(404, "NOT_FOUND", "仓库不存在或无权查看");
    }

    private int count(String filter, MapSqlParameterSource params) {
        String sql = "SELECT COUNT(*) FROM (SELECT 1" + filter + " LIMIT " + (MAX_TOTAL + 1) + ") matched";
        return jdbc.queryForObject(sql, params, Integer.class);
    }

    private static MapSqlParameterSource parameters(CompanyAccess scope) {
        return new MapSqlParameterSource("tenant", scope.tenantId()).addValue("company", scope.companyId());
    }

    private static WarehouseModels.View map(ResultSet rs, CompanyAccess scope) throws SQLException {
        String status = rs.getString("status");
        var actions = scope.hasPermission("warehouse:update")
                ? List.of("update", "active".equals(status) ? "disable" : "enable") : List.<String>of();
        return new WarehouseModels.View(rs.getString("id"), rs.getString("code"), rs.getString("name"),
                rs.getString("address"), status, rs.getLong("version"), actions);
    }

    private static String encodeCursor(String id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(id.getBytes(StandardCharsets.US_ASCII));
    }

    private static Long decodeCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        if (!cursor.isEmpty() && cursor.length() <= 32) {
            try {
                String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
                long id = Long.parseLong(value);
                if (id > 0 && encodeCursor(Long.toString(id)).equals(cursor)) {
                    return id;
                }
            } catch (IllegalArgumentException exception) {
                throw new ApiException(400, "VALIDATION_FAILED", "分页游标无效，请返回第一页");
            }
        }
        throw new ApiException(400, "VALIDATION_FAILED", "分页游标无效，请返回第一页");
    }
}
