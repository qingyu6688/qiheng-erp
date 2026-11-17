package cn.erp.gl;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class GlTabularFileTest {
    @Test
    void xlsxIsReadableXmlAndKeepsTextWithoutFormula() throws Exception {
        var bytes =
                GlTabularFile.xlsx(
                        List.of(
                                new GlModels.ExportColumn("code", "科目编码"),
                                new GlModels.ExportColumn("digest", "摘要")),
                        List.of(
                                Map.of(
                                        "code",
                                        "001122334455667788",
                                        "digest",
                                        "=HYPERLINK(\"https://invalid\") <核对>&")));
        var entries = new HashMap<String, String>();
        try (var zip =
                new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null)
                entries.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertThat(entries)
                .containsKeys("[Content_Types].xml", "xl/workbook.xml", "xl/worksheets/sheet1.xml");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        for (var value : entries.values())
            factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
        assertThat(entries.get("xl/worksheets/sheet1.xml"))
                .contains("001122334455667788", "t=\"inlineStr\"", "&lt;核对&gt;&amp;")
                .doesNotContain("<f>");
    }

    @Test
    void csvQuotesAndNeutralizesUntrustedFormula() {
        var file =
                new String(
                        GlTabularFile.csv(
                                List.of(new GlModels.ExportColumn("text", "摘要")),
                                List.of(Map.of("text", "=1+1"), Map.of("text", "引号\"与,逗号\n换行"))),
                        StandardCharsets.UTF_8);
        assertThat(file).startsWith("\uFEFF").contains("'‎=1+1".replace("‎", ""), "引号\"\"与,逗号\n换行");
    }
}
