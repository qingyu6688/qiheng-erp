package cn.erp.portal;

import cn.erp.shared.ApiException;
import java.util.List;

final class PortalValues {
    private PortalValues() {}

    record Page<T>(List<T> items, String nextCursor) {}

    static long cursor(Long cursor, int limit) {
        if (limit < 1 || limit > 100 || cursor != null && cursor <= 0) throw invalid("分页条件无效");
        return cursor == null ? Long.MAX_VALUE : cursor;
    }

    static <T> Page<T> page(List<T> rows, int limit, java.util.function.Function<T,String> id) {
        var page = List.copyOf(rows.subList(0,Math.min(rows.size(),limit)));
        return new Page<>(page, rows.size() > limit ? id.apply(page.getLast()) : null);
    }

    static long id(Long id) {
        if (id == null || id <= 0) throw invalid("记录编号无效");
        return id;
    }

    static String text(String value, int limit, String name) {
        if (value == null || value.isBlank() || value.length() > limit)
            throw invalid(name+"不能为空且最多 "+limit+" 个字符");
        return value.strip();
    }

    static ApiException invalid(String message) { return new ApiException(400,"VALIDATION_FAILED",message); }
    static ApiException missing(String subject) { return new ApiException(404,"NOT_FOUND",subject+"不存在或无权查看"); }
}
