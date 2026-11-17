package cn.erp.ai.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import cn.erp.ai.common.AiException;

class KnowledgeParserTest {
    private final KnowledgeParser parser = new KnowledgeParser(mock(KnowledgeHttp.class), "", "");

    @Test
    void preservesNestedHeadingsAndSplitsOnCodePointsWithOverlap() {
        String block = "甲乙丙丁戊己庚辛壬癸😃".repeat(100);
        var input = parser.validate("采购.md", ("# 采购\n## 到货\n" + block + "\n## 付款\n付款需要复核。")
                .getBytes(StandardCharsets.UTF_8));
        var parts = parser.parse(input.bytes(), input.mime());
        assertThat(parts).hasSize(3);
        assertThat(parts.get(0).heading()).isEqualTo("采购 / 到货");
        String first = parts.get(0).content();
        String second = parts.get(1).content();
        int overlap = first.offsetByCodePoints(first.length(), -50);
        assertThat(second).startsWith(first.substring(overlap));
        assertThat(first.codePointCount(0, first.length())).isBetween(300, 800);
        assertThat(second.codePointCount(0, second.length())).isBetween(300, 800);
        assertThat(parts.getLast().heading()).isEqualTo("采购 / 付款");
    }

    @Test
    void rejectsPathNamesDisguisedFilesAndInvalidUtf8() {
        byte[] text = "库存规则".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> parser.validate("../库存.md", text)).isInstanceOf(AiException.class);
        assertThatThrownBy(() -> parser.validate("库存.pdf", text)).isInstanceOf(AiException.class);
        assertThatThrownBy(() -> parser.validate("库存.xlsx", text)).isInstanceOf(AiException.class);
        assertThatThrownBy(() -> parser.validate("库存.png", text)).isInstanceOf(AiException.class);
        assertThatThrownBy(() -> parser.validate("库存.txt", new byte[]{(byte) 0xc0, (byte) 0xaf}))
                .isInstanceOf(AiException.class);
    }

    @Test
    void extractsPageNumbersFromTextPdf() throws IOException {
        var output = new ByteArrayOutputStream();
        try (var pdf = new PDDocument()) {
            for (String text : new String[]{"Sales returns", "Stock transfers"}) {
                var page = new PDPage();
                pdf.addPage(page);
                try (var content = new PDPageContentStream(pdf, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(100, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            pdf.save(output);
        }
        var file = parser.validate("仓储.pdf", output.toByteArray());
        var parts = parser.parse(file.bytes(), file.mime());
        assertThat(parts).extracting(KnowledgeParser.Part::pageFrom).containsExactly(1, 2);
        assertThat(parts).extracting(KnowledgeParser.Part::content).containsExactly("Sales returns", "Stock transfers");
    }
}
