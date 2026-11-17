package cn.erp.importing;

import cn.erp.shared.ApiException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class ImportCsv {
    static final int MAX_BYTES=20*1024*1024;
    static final int MAX_ROWS=10_000;
    static final Map<String,List<String>> HEADERS=Map.of(
            "sku",List.of("productCode","code","name","spec","defaultTaxRate","purchasePrice","salePrice","status"),
            "partner",List.of("code","name","shortName","taxNo","roles"),
            "opening_stock",List.of("skuCode","unitName","qty","totalCost","reason"));
    record Row(int rowNo,List<String> cells,List<String> keys) {
        Row(int rowNo,List<String> cells) {this(rowNo,cells,List.of());}
    }
    private ImportCsv() {}
    static List<String> headers(String type) {
        var values=HEADERS.get(type);if(values==null)throw invalid("导入类型无效");return values;
    }
    static byte[] template(String type) {
        return template(type,List.of());
    }
    static byte[] template(String type,List<String> extra) {
        // 模板仅含固定英文字段，无公式和长整型编号，中文业务名称在页面说明。
        var columns=new ArrayList<>(headers(type));columns.addAll(extra);
        return ("\uFEFF"+String.join(",",columns)+"\r\n").getBytes(StandardCharsets.UTF_8);
    }
    static List<Row> parse(String type,byte[] bytes) {
        return parse(headers(type),!type.equals("opening_stock"),bytes);
    }
    static List<Row> parse(List<String> base,boolean customFields,byte[] bytes) {
        if(bytes.length==0||bytes.length>MAX_BYTES)throw invalid("CSV 文件须大于 0 且不超过 20MB");
        String value;
        try {value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException error){throw invalid("请使用 UTF-8 编码的 CSV 文件");}
        if(value.startsWith("\uFEFF"))value=value.substring(1);
        var records=new ArrayList<Row>();var fields=new ArrayList<String>();var field=new StringBuilder();
        boolean quoted=false,closed=false;int rowNo=1;
        for(int index=0;index<value.length();index++) {
            char ch=value.charAt(index);
            if(ch=='\0'||(ch<32&&ch!='\r'&&ch!='\n'&&ch!='\t'))throw invalid("CSV 包含不支持的控制字符");
            if(quoted) {
                if(ch=='"') {
                    if(index+1<value.length()&&value.charAt(index+1)=='"'){field.append('"');index++;}
                    else{quoted=false;closed=true;}
                }else field.append(ch);
            } else if(ch=='"') {
                if(!field.isEmpty()||closed)throw invalid("CSV 引号格式错误，第 "+rowNo+" 行");quoted=true;
            } else if(ch==','||ch=='\r'||ch=='\n') {
                fields.add(field.toString());field.setLength(0);closed=false;
                if(fields.size()>128)throw invalid("CSV 单行列数过多，请使用下载的模板");
                if(ch!=',') {
                    records.add(new Row(rowNo++,List.copyOf(fields)));fields.clear();
                    if(ch=='\r'&&index+1<value.length()&&value.charAt(index+1)=='\n')index++;
                    if(records.size()>MAX_ROWS+1)throw invalid("CSV 最多允许 10000 条数据行");
                }
            } else {
                if(closed)throw invalid("CSV 引号后只能出现逗号或换行，第 "+rowNo+" 行");field.append(ch);
            }
            if(field.length()>10_000)throw invalid("CSV 单元格最多允许 10000 个字符");
        }
        if(quoted)throw invalid("CSV 存在未闭合的引号");
        if(!fields.isEmpty()||!field.isEmpty()||closed){fields.add(field.toString());records.add(new Row(rowNo,List.copyOf(fields)));}
        var header=records.isEmpty()?List.<String>of():records.getFirst().cells();
        if(header.size()<base.size()||!header.subList(0,base.size()).equals(base))throw invalid("CSV 表头不匹配，请重新下载导入模板");
        var keys=List.copyOf(header.subList(base.size(),header.size()));
        if ((!keys.isEmpty()&&!customFields)||keys.size()>30||keys.stream().anyMatch(k->!k.matches("cf_[a-z][a-z0-9_]{0,31}"))
                ||keys.stream().distinct().count()!=keys.size()) throw invalid("CSV 自定义字段表头无效");
        if(records.size()==1)throw invalid("CSV 至少需要一条数据行");
        if(records.size()>MAX_ROWS+1)throw invalid("CSV 最多允许 10000 条数据行");
        return records.subList(1,records.size()).stream().map(row->new Row(row.rowNo(),row.cells(),keys)).toList();
    }
    static ApiException invalid(String message){return new ApiException(400,"VALIDATION_FAILED",message);}
}
