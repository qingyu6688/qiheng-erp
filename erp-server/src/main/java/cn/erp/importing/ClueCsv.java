package cn.erp.importing;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** CRM 使用相同的 UTF-8 CSV 解析规则，固定表头不接受自定义列。 */
public final class ClueCsv {
    private static final List<String> COLUMNS = List.of("name", "contactName", "mobile", "email", "taxNo", "source", "industry");
    public static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final int MAX_ROWS = 500;

    public record Row(int number, List<String> cells) {}

    private ClueCsv() {}

    public static byte[] template() {
        return ("\uFEFF" + String.join(",", COLUMNS) + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    public static List<Row> read(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw ImportCsv.invalid("线索导入文件最多 2 MB");
        var parsed = ImportCsv.parse(COLUMNS, false, bytes);
        if (parsed.size() > MAX_ROWS) throw ImportCsv.invalid("每次最多导入 500 条线索");
        return parsed.stream().map(row -> {
            if (row.cells().size() != COLUMNS.size()) throw ImportCsv.invalid("第 " + row.rowNo() + " 行列数与模板不一致");
            return new Row(row.rowNo(), row.cells());
        }).toList();
    }
}
