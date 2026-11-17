package cn.erp.aitools;

import cn.erp.shared.ApiException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

record AiBusinessInput(JsonNode data) {
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final int MAX_TEXT = 100;

    static AiBusinessInput parse(byte[] body, Set<String> allowed) {
        JsonNode data;
        try {
            data = JSON.readTree(body);
        } catch (RuntimeException rejected) {
            throw invalid("查询参数不是有效的 JSON 对象");
        }
        if (data == null || !data.isObject()) throw invalid("查询参数必须是 JSON 对象");
        for (String field : data.propertyNames()) {
            if (!allowed.contains(field)) throw invalid("查询包含未授权的参数");
            if (!data.get(field).isTextual()) throw invalid("查询参数必须为字符串");
        }
        return new AiBusinessInput(data);
    }

    String text(String name) {
        JsonNode value = data.get(name);
        if (value == null) return null;
        String text = value.asText().strip();
        if (text.isEmpty() || text.length() > MAX_TEXT || text.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("查询参数长度或格式无效");
        }
        return text;
    }

    String required(String name) {
        String value = text(name);
        if (value == null) throw invalid("缺少必要查询参数");
        return value;
    }

    LocalDate date(String name) {
        String value = text(name);
        if (value == null) return null;
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid("日期请使用年-月-日格式");
            LocalDate date = LocalDate.parse(value);
            if (date.getYear() < 1900 || date.isAfter(today())) throw invalid("查询日期超出业务日期范围");
            return date;
        } catch (DateTimeParseException error) {
            throw invalid("查询日期无效");
        }
    }

    void range(LocalDate from, LocalDate to) {
        if (from != null && to != null && (from.isAfter(to) || to.isAfter(from.plusYears(1).minusDays(1)))) {
            throw invalid("日期区间须按顺序填写且不得超过一年");
        }
    }

    static LocalDate today() { return LocalDate.now(BUSINESS_ZONE); }
    static ApiException invalid(String message) { return new ApiException(400, "VALIDATION_FAILED", message); }
}
