package cn.erp.crm;

import cn.erp.shared.ApiException;
import cn.erp.shared.ApiRows;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

final class CrmCycleData {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private CrmCycleData() {}

    static long id(String value) {
        try {
            if (value != null && value.matches("[1-9][0-9]{0,18}")) return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw invalid("单据或资料编号超出范围");
        }
        throw invalid("单据或资料编号无效");
    }

    static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    static BigDecimal value(Map<String, Object> row, String key) {
        return (BigDecimal) row.get(key);
    }

    static BigDecimal exact(String value, int scale, String label) {
        try {
            if (value == null || value.length() > 32 || !value.matches("[0-9]+(?:\\.[0-9]+)?"))
                throw invalid(label + "须为非负十进制数");
            var amount = new BigDecimal(value).setScale(scale, RoundingMode.UNNECESSARY);
            if (amount.precision() > 24) throw invalid(label + "超出支持的数值范围");
            return amount;
        } catch (ArithmeticException invalid) {
            throw invalid(label + "小数位数超出限制");
        }
    }

    static BigDecimal positive(String value, int scale, String label) {
        var amount = exact(value, scale, label);
        if (amount.signum() == 0) throw invalid(label + "须大于零");
        return amount;
    }

    static BigDecimal ratio(String value, String label) {
        var amount = exact(value, 6, label);
        if (amount.compareTo(BigDecimal.ONE) > 0) throw invalid(label + "须在零至一之间");
        return amount;
    }

    static String required(String value, int max, String label) {
        if (value == null || value.isBlank() || value.strip().length() > max)
            throw invalid(label + "不能为空且长度不能超过" + max + "字");
        return value.strip();
    }

    static void version(Long expected, Map<String, Object> row) {
        if (expected == null || expected < 0) throw invalid("请提供当前版本号");
        if (expected != number(row, "version")) throw conflict("VERSION_CONFLICT", "数据已更新，请刷新后重试");
    }

    static LocalDate today() { return LocalDate.now(BUSINESS_ZONE); }

    static long cursor(String value, int limit) {
        if (limit < 1 || limit > 200) throw invalid("每页条数须在1到200之间");
        return value == null ? Long.MAX_VALUE : id(value);
    }

    static Map<String, Object> page(List<Map<String, Object>> rows, int limit) {
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("items", rows.stream().limit(limit).map(ApiRows::row).toList());
        result.put("nextCursor", rows.size() > limit ? rows.get(limit - 1).get("id").toString() : null);
        return result;
    }

    static Map<String, Object> one(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    static ApiException invalid(String message) { return new ApiException(422, "VALIDATION_FAILED", message); }
    static ApiException missing() { return new ApiException(404, "NOT_FOUND", "CRM 记录不存在或不在当前查看范围"); }
    static ApiException forbidden(String message) { return new ApiException(403, "FORBIDDEN", message); }
    static ApiException conflict(String code, String message) { return new ApiException(409, code, message); }
}
