package cn.erp.exporting;

import java.nio.charset.StandardCharsets;
import java.util.*;
import cn.erp.reporting.ReportType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class ExportCsvTest {
    @Test void returnCsvShowsBusinessModeAndFrozenCostSourceOnlyWithCostPermission() {
        var rows=List.<Map<String,Object>>of(Map.of("sourceNo","SR-1","returnMode","sourceless","returnCostSource","manual","costAmount","9007199254740991.12","costBasis",Map.of("内部依据","不得导出")));
        var json=JsonMapper.builder().build();
        String allowed=new String(ExportCsv.writeReport(ReportType.INVENTORY_FLOW,true,true,rows,json),StandardCharsets.UTF_8);
        assertThat(allowed).contains("退货模式","无原单退货","退货成本来源","人工复核成本","'9007199254740991.12").doesNotContain("costBasis","不得导出");
        String hidden=new String(ExportCsv.writeReport(ReportType.INVENTORY_FLOW,true,false,rows,json),StandardCharsets.UTF_8);
        assertThat(hidden).contains("无原单退货").doesNotContain("退货成本来源","人工复核成本","9007199254740991.12","成本变动");
        String original=new String(ExportCsv.writeReport(ReportType.SALES_SUMMARY,true,true,List.of(Map.of("returnMode","original")),json),StandardCharsets.UTF_8);
        assertThat(original).contains("按原单退货");
    }
    @Test void utf8BomQuotesFormulaGuardsAndExactDecimalTextSurviveExport() {
        var row=new LinkedHashMap<String,Object>();row.put("id","9223372036854775801");row.put("amount","1234567890123456789012.34");row.put("name","=HYPERLINK(\"外部地址\")");row.put("memo","  +SUM(1,2)\r\n中文");
        byte[] bytes=ExportCsv.write(List.of(row),JsonMapper.builder().build());
        assertThat(Arrays.copyOf(bytes,3)).containsExactly((byte)0xef,(byte)0xbb,(byte)0xbf);
        assertThat(new String(bytes,StandardCharsets.UTF_8)).contains("'9223372036854775801","'1234567890123456789012.34","'=HYPERLINK(" ,"'  +SUM(1,2)","\"\"外部地址\"\"");
    }
    @Test void businessCsvHasChineseWhitelistHeadersAndNeverExportsInternalReferencesOrUnauthorizedCosts() {
        var rows=List.<Map<String,Object>>of(Map.of("warehouseName","仓库甲","skuCode","001","onHandQty","10.000000","costAmount","100.00","sourceId","999","detailUrl","/内部路径"));
        String costless=new String(ExportCsv.writeReport(ReportType.INVENTORY_SUMMARY,false,false,rows,JsonMapper.builder().build()),StandardCharsets.UTF_8);
        assertThat(costless).contains("在库数量","仓库甲","'10.000000").doesNotContain("库存成本","100.00","sourceId","999","内部路径");
        String empty=new String(ExportCsv.writeReport(ReportType.INVENTORY_SUMMARY,false,true,List.of(),JsonMapper.builder().build()),StandardCharsets.UTF_8);
        assertThat(empty).contains("在库数量","库存成本").doesNotContain("无符合条件的数据");
    }
}
