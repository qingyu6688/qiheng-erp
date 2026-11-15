package cn.erp.importing;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ImportCsvTest {
    private byte[] csv(String value){return value.getBytes(StandardCharsets.UTF_8);}
    @Test void parsesBomQuotedCommaEscapedQuoteAndEmbeddedNewline() {
        var rows=ImportCsv.parse("partner",csv("\uFEFFcode,name,shortName,taxNo,roles\r\n00001,\"甲,乙\"\"公司\",\"第一行\n第二行\",,customer\r\n"));
        assertThat(rows).hasSize(1);assertThat(rows.getFirst().rowNo()).isEqualTo(2);
        assertThat(rows.getFirst().cells()).containsExactly("00001","甲,乙\"公司","第一行\n第二行","","customer");
    }
    @Test void preservesLongIdentifiersAndFormulaTextWithoutEvaluation() {
        var rows=ImportCsv.parse("partner",csv("code,name,shortName,taxNo,roles\n001234567890123456789,=测试,,,supplier"));
        assertThat(rows.getFirst().cells().getFirst()).isEqualTo("001234567890123456789");
        assertThat(rows.getFirst().cells().get(1)).isEqualTo("=测试");
    }
    @Test void rejectsMalformedQuotesAndWrongHeader() {
        assertThatThrownBy(()->ImportCsv.parse("partner",csv("code,name,shortName,taxNo,roles\na,\"公司\"x,,,customer"))).hasMessageContaining("引号");
        assertThatThrownBy(()->ImportCsv.parse("partner",csv("code,name\na,b"))).hasMessageContaining("表头");
        assertThatThrownBy(()->ImportCsv.parse("partner",csv("code,name,shortName,taxNo,roles\na,\"未结束"))).hasMessageContaining("未闭合");
    }
    @Test void rejectsInvalidUtf8AndEmptyData() {
        assertThatThrownBy(()->ImportCsv.parse("sku",new byte[]{(byte)0xff})).hasMessageContaining("UTF-8");
        assertThatThrownBy(()->ImportCsv.parse("sku",ImportCsv.template("sku"))).hasMessageContaining("数据行");
    }
    @Test void enforcesRowAndByteLimits() {
        String body="code,name,shortName,taxNo,roles\n"+"a,b,,,customer\n".repeat(ImportCsv.MAX_ROWS+1);
        assertThatThrownBy(()->ImportCsv.parse("partner",csv(body))).hasMessageContaining("10000");
        assertThatThrownBy(()->ImportCsv.parse("sku",new byte[ImportCsv.MAX_BYTES+1])).hasMessageContaining("20MB");
    }
    @Test void templateHasUtf8BomAndActualColumnContract() {
        var template=ImportCsv.template("opening_stock");
        assertThat(template).startsWith((byte)0xef,(byte)0xbb,(byte)0xbf);
        assertThat(new String(template,StandardCharsets.UTF_8)).contains("skuCode,unitName,qty,totalCost,reason");
    }
}
