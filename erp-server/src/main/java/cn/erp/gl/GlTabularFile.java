package cn.erp.gl;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** 编码和辅助编号以文本写入，避免Excel截断长编号或执行导出单元格中的公式。 */
final class GlTabularFile {
    private GlTabularFile() {}

    static byte[] csv(List<GlModels.ExportColumn> columns, List<Map<String, Object>> rows) {
        var output = new StringBuilder("\uFEFF");
        csvLine(output, columns.stream().map(GlModels.ExportColumn::label).toList());
        for (var row : rows)
            csvLine(
                    output,
                    columns.stream()
                            .map(c -> safe(Objects.toString(row.get(c.field()), "")))
                            .toList());
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String safe(String value) {
        return !value.matches("-?[0-9]+(?:\\.[0-9]+)?")
                        && value.stripLeading().matches("^[=+\\-@].*")
                ? "'" + value
                : value;
    }

    private static void csvLine(StringBuilder out, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(',');
            out.append('"').append(values.get(i).replace("\"", "\"\"")).append('"');
        }
        out.append("\r\n");
    }

    static byte[] xlsx(List<GlModels.ExportColumn> columns, List<Map<String, Object>> rows) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                entry(
                        zip,
                        "[Content_Types].xml",
                        "<Types"
                            + " xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default"
                            + " Extension=\"rels\""
                            + " ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default"
                            + " Extension=\"xml\" ContentType=\"application/xml\"/><Override"
                            + " PartName=\"/xl/workbook.xml\""
                            + " ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override"
                            + " PartName=\"/xl/worksheets/sheet1.xml\""
                            + " ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>");
                entry(
                        zip,
                        "_rels/.rels",
                        "<Relationships"
                            + " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship"
                            + " Id=\"rId1\""
                            + " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\""
                            + " Target=\"xl/workbook.xml\"/></Relationships>");
                entry(
                        zip,
                        "xl/workbook.xml",
                        "<workbook"
                            + " xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                            + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet"
                            + " name=\"财务资料\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
                entry(
                        zip,
                        "xl/_rels/workbook.xml.rels",
                        "<Relationships"
                            + " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship"
                            + " Id=\"rId1\""
                            + " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\""
                            + " Target=\"worksheets/sheet1.xml\"/></Relationships>");
                var sheet =
                        new StringBuilder(
                                "<worksheet"
                                    + " xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetViews><sheetView"
                                    + " workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\""
                                    + " activePane=\"bottomLeft\""
                                    + " state=\"frozen\"/></sheetView></sheetViews><sheetData>");
                xmlRow(sheet, 1, columns.stream().map(GlModels.ExportColumn::label).toList());
                int index = 1;
                for (var row : rows)
                    xmlRow(
                            sheet,
                            ++index,
                            columns.stream()
                                    .map(c -> Objects.toString(row.get(c.field()), ""))
                                    .toList());
                sheet.append("</sheetData></worksheet>");
                entry(zip, "xl/worksheets/sheet1.xml", sheet.toString());
            }
            return bytes.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("无法生成Excel财务文件", error);
        }
    }

    private static void xmlRow(StringBuilder out, int row, List<String> values) {
        out.append("<row r=\"").append(row).append("\">");
        for (String value : values)
            out.append("<c t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                    .append(xml(value))
                    .append("</t></is></c>");
        out.append("</row>");
    }

    private static String xml(String value) {
        return value.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "")
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static void entry(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(
                ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" + value)
                        .getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
