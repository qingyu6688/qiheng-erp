package cn.erp.customfield;

import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

final class CustomFieldTypes {
    static final Set<String> TYPES = Set.of("text", "textarea", "number", "decimal", "date", "datetime",
            "select", "multi_select", "user", "department", "bool");
    static final Map<String, String> VIEW = Map.of("sales_order", "sales_order:view", "sales_order_line", "sales_order:view",
            "purchase_order", "purchase_order:view", "sku", "sku:view", "business_partner", "partner:view");

    private CustomFieldTypes() {}

    static Object value(String type, JsonNode value, List<String> choices, String label) {
        if (value == null || value.isNull() || value.isMissingNode() || value.isString() && value.asString().isBlank()) return null;
        try {
            return switch (type) {
                case "text" -> text(value, 256, label);
                case "textarea" -> text(value, 2000, label);
                case "number" -> number(value, false, label);
                case "decimal" -> number(value, true, label);
                case "date" -> LocalDate.parse(text(value, 10, label)).toString();
                case "datetime" -> LocalDateTime.parse(text(value, 19, label)).toString();
                case "select" -> choice(value, choices, label);
                case "multi_select" -> multiple(value, choices, label);
                case "user", "department" -> identifier(text(value, 19, label), label);
                case "bool" -> bool(value, label);
                default -> throw bad(label);
            };
        } catch (DateTimeParseException | NumberFormatException error) { throw bad(label); }
    }

    private static String number(JsonNode value, boolean decimal, String label) {
        if (!value.isNumber() && !value.isString()) throw bad(label);
        String text = value.asString();
        if (!text.matches(decimal ? "-?[0-9]{1,18}(\\.[0-9]{1,8})?" : "-?[0-9]{1,18}")) throw bad(label);
        return new BigDecimal(text).stripTrailingZeros().toPlainString();
    }

    private static String choice(JsonNode node, List<String> choices, String label) {
        String text = text(node, 100, label);
        if (!choices.contains(text)) throw bad(label);
        return text;
    }

    private static List<String> multiple(JsonNode node, List<String> choices, String label) {
        if (!node.isArray() || node.size() > 20) throw bad(label);
        var selected = new TreeSet<String>();
        for (var item : node) selected.add(choice(item, choices, label));
        return List.copyOf(selected);
    }

    private static boolean bool(JsonNode value, String label) {
        if (value.isBoolean()) return value.asBoolean();
        if (value.isString() && Set.of("true", "false").contains(value.asString())) return Boolean.parseBoolean(value.asString());
        throw bad(label);
    }

    private static String text(JsonNode node, int maximum, String label) {
        if (!node.isString()) throw bad(label);
        String value = node.asString().strip();
        if (value.isEmpty() || value.length() > maximum || value.indexOf(0) >= 0) throw bad(label);
        return value;
    }

    private static String identifier(String value, String label) {
        if (!value.matches("[1-9][0-9]{0,18}")) throw bad(label);
        Long.parseLong(value);
        return value;
    }

    static ApiException bad(String label) { return invalid(label + "的值或格式无效"); }
    static ApiException invalid(String message) { return new ApiException(400, "CUSTOM_FIELD_INVALID", message); }
}
