package cn.erp.crm;

import cn.erp.shared.ApiRows;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CrmCoreRows {
    private CrmCoreRows() {}

    static long cursor(String cursor, int limit) {
        if (limit < 1 || limit > 200) throw CrmScope.invalid("每页显示 1 到 200 条记录");
        if (cursor == null || cursor.isBlank()) return Long.MAX_VALUE;
        if (!cursor.matches("[1-9][0-9]{0,18}")) throw CrmScope.invalid("分页标识无效");
        try { return Long.parseLong(cursor); }
        catch (NumberFormatException outOfRange) { throw CrmScope.invalid("分页标识超出范围"); }
    }

    static Map<String, Object> page(List<Map<String, Object>> rows, int limit, String id) {
        var page = new LinkedHashMap<String, Object>();
        var items = rows.stream().limit(limit).toList();
        page.put("items", items);
        page.put("nextCursor", rows.size() > limit ? items.getLast().get(id) : null);
        return page;
    }

    static Map<String, Object> row(Map<String, Object> raw) {
        return ApiRows.row(raw);
    }

    static Timestamp timestamp(Instant time) {
        if (time == null) return null;
        if (time.isBefore(Instant.parse("1000-01-01T00:00:00Z")) || time.isAfter(Instant.parse("9999-12-31T23:59:59Z"))) {
            throw CrmScope.invalid("时间超出数据库支持范围");
        }
        return Timestamp.from(time);
    }

    static String trim(String text) {
        return text == null ? "" : text.strip();
    }

    static String optional(String text) {
        return trim(text).isEmpty() ? null : trim(text);
    }
}
