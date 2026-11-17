package cn.erp.workflow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/** 通用表单使用固定控件及可预测的 JSON 值，脚本和任意组件无法进入运行时。 */
final class WorkflowFormSchema {
    record Field(String key, String label, String type, boolean required, List<String> options) {}

    private WorkflowFormSchema() {}

    static boolean validKey(String key) {
        return key != null && key.matches("[a-z][a-zA-Z0-9_]{0,39}")
                && !Set.of("constructor", "prototype").contains(key);
    }

    static List<Field> describe(List<Field> fields) {
        if (fields == null || fields.isEmpty() || fields.size() > 50)
            throw SimpleModel.invalid("请配置1至50个表单字段");
        var keys = new HashSet<String>();
        for (var field : fields) {
            if (field == null || !validKey(field.key()) || !keys.add(field.key())
                    || !Set.of("text", "number", "date", "select", "boolean").contains(Objects.toString(field.type(), "")))
                throw SimpleModel.invalid("表单字段编码重复、格式无效或类型不受支持");
            SimpleModel.safeLabel(field.label());
            var options = field.options() == null ? List.<String>of() : field.options();
            if (options.size() > 50 || new HashSet<>(options).size() != options.size()
                    || field.type().equals("select") == options.isEmpty())
                throw SimpleModel.invalid("下拉字段须设置1至50个不重复的选项，其他字段不接受选项");
            options.forEach(SimpleModel::safeLabel);
        }
        return List.copyOf(fields);
    }

    static Map<String,Object> values(List<Field> fields, Map<String,Object> input, Set<String> write, boolean initial) {
        var proposed = input == null ? Map.<String,Object>of() : input;
        if (proposed.size() > fields.size() || !write.containsAll(proposed.keySet())
                || fields.stream().map(Field::key).filter(proposed::containsKey).count() != proposed.size())
            throw SimpleModel.invalid("表单包含未授权或未登记字段");
        var validated = new LinkedHashMap<String,Object>();
        for (var field : fields) {
            if (!initial && !proposed.containsKey(field.key())) continue;
            var value = proposed.get(field.key());
            if (value == null || value instanceof String text && text.isBlank()) {
                if (field.required()) throw SimpleModel.invalid("请填写表单字段：" + field.label());
                validated.put(field.key(), null);
            } else validated.put(field.key(), checked(field, value));
        }
        return validated;
    }

    private static Object checked(Field field, Object value) {
        if (field.type().equals("text") && value instanceof String text && text.length() <= 2000 && text.indexOf(0) < 0)
            return text;
        if (field.type().equals("boolean") && value instanceof Boolean) return value;
        if (field.type().equals("select") && field.options().contains(value)) return value;
        if (field.type().equals("date") && value instanceof String text && text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            try { return LocalDate.parse(text).toString(); }
            catch (DateTimeParseException invalid) { throw SimpleModel.invalid("表单日期无效：" + field.label()); }
        }
        if (field.type().equals("number") && (value instanceof String || value instanceof Number)) {
            String number = value.toString();
            if (number.matches("-?[0-9]{1,18}(?:\\.[0-9]{1,6})?")) return new BigDecimal(number).toPlainString();
        }
        throw SimpleModel.invalid("表单字段类型或值无效：" + field.label());
    }
}
