package cn.erp.reference;

import cn.erp.shared.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ReferenceRows {
    private ReferenceRows() {}

    static Map<String,Object> publicRow(Map<String,Object> row) {
        var result = new LinkedHashMap<String,Object>();
        row.forEach((key,value) -> {
            StringBuilder name = new StringBuilder();
            boolean upper = false;
            for (char c : key.toCharArray()) {
                if (c == '_') { upper = true; }
                else { name.append(upper ? Character.toUpperCase(c) : c); upper = false; }
            }
            String field = name.toString();
            Object visible = (field.equals("id") || field.endsWith("Id")) && value != null ? value.toString() : value;
            if (visible instanceof java.math.BigDecimal decimal) { visible = decimal.toPlainString(); }
            if (visible instanceof java.sql.Date date) { visible = date.toLocalDate().toString(); }
            result.put(field, visible);
        });
        return result;
    }

    static Map<String,Object> one(List<Map<String,Object>> rows) {
        if (rows.isEmpty()) { throw new ApiException(404, "NOT_FOUND", "资料不存在或无权查看"); }
        return publicRow(rows.getFirst());
    }

    static void version(Long requested, Map<String,Object> row) {
        if (requested == null || requested < 0) { throw invalid("请提供有效的版本号"); }
        if (requested.longValue() != ((Number) row.get("version")).longValue()) {
            throw new ApiException(409, "VERSION_CONFLICT", "资料已被他人修改，请刷新后重试",
                    Map.of("currentVersion", row.get("version")));
        }
    }

    static Map<String,Object> page(List<Map<String,Object>> rows, int limit) {
        boolean more = rows.size() > limit;
        var items = rows.stream().limit(limit).map(ReferenceRows::publicRow).toList();
        var result = new LinkedHashMap<String,Object>();
        result.put("items", items);
        result.put("nextCursor", more ? items.getLast().get("id") : null);
        return result;
    }

    static long cursor(String cursor, int limit) {
        if (limit < 1 || limit > 200) { throw invalid("每页条数须在 1 到 200 之间"); }
        if (cursor == null || cursor.isBlank()) { return Long.MAX_VALUE; }
        try {
            long id = Long.parseLong(cursor);
            if (id <= 0) { throw invalid("分页游标无效"); }
            return id;
        } catch (NumberFormatException ex) { throw invalid("分页游标无效"); }
    }

    static ApiException invalid(String message) { return new ApiException(400, "VALIDATION_FAILED", message); }

    static String status(String action) {
        return switch(action) {
            case "enable" -> "active";
            case "disable" -> "disabled";
            default -> throw invalid("资料操作无效");
        };
    }
}
