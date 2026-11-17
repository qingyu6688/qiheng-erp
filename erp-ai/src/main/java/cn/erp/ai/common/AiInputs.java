package cn.erp.ai.common;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

public final class AiInputs {
    private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,18}");
    private AiInputs() {}

    public static long id(String value) {
        if (value == null || !ID.matcher(value).matches()) throw AiException.invalid("编号格式无效");
        try { return Long.parseLong(value); }
        catch (NumberFormatException invalid) { throw AiException.invalid("编号超出范围"); }
    }

    public static String text(String value, int max, String label) {
        if (value == null || value.isBlank() || value.length() > max || value.indexOf(0) >= 0)
            throw AiException.invalid(label + "不能为空且最多" + max + "字");
        return value.strip();
    }

    public static Set<String> ids(Collection<String> input, int max) {
        if (input == null) return Set.of();
        if (input.size() > max) throw AiException.invalid("授权条目超出上限");
        var ids = new LinkedHashSet<String>();
        for (String value : input) { id(value); ids.add(value); }
        return Set.copyOf(ids);
    }
}
