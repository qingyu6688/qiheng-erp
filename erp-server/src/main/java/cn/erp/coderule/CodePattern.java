package cn.erp.coderule;

import cn.erp.shared.ApiException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class CodePattern {
    static final int MAX_DOC_CODE = 33;
    static final int MAX_MASTER_CODE = 32;
    private static final Map<String, String> DATES = Map.of(
            "yyyy", "uuuu", "yyyyMM", "uuuuMM", "yyyyMMdd", "uuuuMMdd", "yyMMdd", "uuMMdd");
    private static final Map<String, Integer> RESET_DATES = Map.of("none", 0, "year", 4, "month", 6, "day", 8);

    record Segment(String type, String value, Integer length) {}
    record Context(LocalDate date, String company, String department, String category) {}

    private CodePattern() {}

    static void validate(List<Segment> segments, String reset, boolean master) {
        if (segments == null || segments.isEmpty() || segments.size() > 10 || reset == null || !RESET_DATES.containsKey(reset))
            throw invalid("编码段最多十段，请填写有效的重置周期");
        int maximum = 0;
        int serials = 0;
        int dateWidth = 0;
        for (var segment : segments) {
            if (segment == null || segment.type() == null) throw invalid("编码段类型无效");
            switch (segment.type()) {
                case "literal" -> {
                    if (segment.value() == null || !segment.value().matches("[A-Za-z0-9_-]{1,16}"))
                        throw invalid("固定字符仅支持 1 到 16 位字母、数字、下划线或连字符");
                    maximum += segment.value().length();
                }
                case "date" -> {
                    if (segment.value() == null || !DATES.containsKey(segment.value())) throw invalid("日期格式无效");
                    maximum += segment.value().length();
                    if (!"yyMMdd".equals(segment.value())) dateWidth = Math.max(dateWidth, segment.value().length());
                    else dateWidth = Math.max(dateWidth, 8);
                }
                case "serial" -> { maximum += length(segment, 10); serials++; }
                case "company_code" -> maximum += length(segment, 16);
                case "dept_code" -> maximum += length(segment, 32);
                case "category_code" -> {
                    if (!master) throw invalid("单据编码不能使用物料分类段");
                    maximum += length(segment, 32);
                }
                default -> throw invalid("编码段类型无效");
            }
        }
        if (serials != 1 || maximum > (master ? MAX_MASTER_CODE : MAX_DOC_CODE))
            throw invalid("必须包含一个流水段，且编码总长度不得超过 " + (master ? MAX_MASTER_CODE : MAX_DOC_CODE) + " 位");
        if (dateWidth < RESET_DATES.get(reset)) throw invalid("日期段必须足以区分流水重置周期");
    }

    static String render(List<Segment> segments, Context context, long serial) {
        var result = new StringBuilder();
        for (var item : segments) {
            result.append(switch (item.type()) {
                case "literal" -> item.value();
                case "date" -> context.date().format(DateTimeFormatter.ofPattern(DATES.get(item.value()), Locale.ROOT));
                case "serial" -> serial(serial, item.length());
                case "company_code" -> source(context.company(), item.length(), "公司编码");
                case "dept_code" -> source(context.department(), item.length(), "主部门编码");
                case "category_code" -> source(context.category(), item.length(), "物料分类编码");
                default -> throw invalid("编码段类型无效");
            });
        }
        return result.toString();
    }

    static String cycle(String reset, LocalDate date) {
        return switch (reset) {
            case "year" -> date.format(DateTimeFormatter.ofPattern("uuuu", Locale.ROOT));
            case "month" -> date.format(DateTimeFormatter.ofPattern("uuuuMM", Locale.ROOT));
            case "day" -> date.format(DateTimeFormatter.BASIC_ISO_DATE);
            default -> "all";
        };
    }

    private static int length(Segment value, int maximum) {
        if (value.length() == null || value.length() < 1 || value.length() > maximum)
            throw invalid("请按编码段限制设置长度");
        return value.length();
    }

    private static String serial(long value, int width) {
        if (Long.toString(value).length() > width) throw new ApiException(422, "SEQUENCE_EXHAUSTED", "编码流水号已用完");
        return String.format(Locale.ROOT, "%0" + width + "d", value);
    }

    private static String source(String value, int width, String label) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > width)
            throw invalid(label + "缺失或超出规则长度");
        return value;
    }

    static ApiException invalid(String message) { return new ApiException(400, "VALIDATION_FAILED", message); }
}
