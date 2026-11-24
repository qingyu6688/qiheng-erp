package cn.erp.exporting;

import cn.erp.reporting.ReportType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ExportCsv {
    private ExportCsv() {}
    public static byte[] write(List<Map<String,Object>> rows,ObjectMapper json) {
        var columns=new LinkedHashSet<String>();rows.forEach(r->columns.addAll(r.keySet()));
        if(columns.isEmpty()) { columns.add("无符合条件的数据"); }
        var labels=new LinkedHashMap<String,String>();columns.forEach(column->labels.put(column,column));return write(rows,labels,json);
    }
    public static byte[] writeReport(ReportType type,boolean detail,boolean cost,List<Map<String,Object>> rows,ObjectMapper json) {
        return writeReport(type,detail,cost,!rows.isEmpty()&&rows.getFirst().containsKey("brandName")?"brand":null,rows,json);
    }
    static byte[] writeReport(ReportType type,boolean detail,boolean cost,String groupBy,List<Map<String,Object>> rows,ObjectMapper json) {
        var columns=new LinkedHashMap<String,String>();
        boolean commerce=Set.of(ReportType.SALES_SUMMARY,ReportType.PURCHASE_SUMMARY,ReportType.GROSS_MARGIN).contains(type);
        boolean grouped=commerce&&Set.of("brand","customer","sku","business_user","department").contains(Objects.toString(groupBy,""));
        if("brand".equals(groupBy)) {add(columns,"brandName","品牌");}
        else if(grouped) switch(groupBy) {
            case "customer" -> add(columns,"partnerName",type==ReportType.PURCHASE_SUMMARY?"供应商":"客户");
            case "sku" -> add(columns,"skuCode","商品编码","skuName","商品");
            case "business_user" -> add(columns,"businessUserName",type==ReportType.PURCHASE_SUMMARY?"采购员":"销售员");
            case "department" -> add(columns,"departmentName","部门");
            default -> throw new IllegalStateException("报表汇总维度无效");
        }
        else if(!type.companyWide()) { add(columns,"warehouseName","仓库","skuCode","商品编码","skuName","商品"); }
        switch(type) {
            case INVENTORY_SUMMARY -> add(columns,"onHandQty","在库数量","reservedQty","预留数量","blockedQty","冻结数量","availableQty","可用数量","costAmount","库存成本");
            case INVENTORY_FLOW -> {
                if(detail) { add(columns,"businessDate","业务日期","sourceNo","来源单号","qty","数量变动","blockedQty","冻结变动","costAmount","成本变动","postingAction","过账动作"); }
                else { add(columns,"openingQty","期初数量","inQty","入库数量","outQty","出库数量","closingQty","期末数量","openingCost","期初成本","inCost","入库成本","outCost","出库成本","closingCost","期末成本"); }
            }
            case UNSIGNED_SHIPMENTS -> add(columns,"partnerName","客户","sourceNo","发货单号","businessDate","发货日期","unsignedQty","未签收数量","costAmount","发出商品成本","diffDisposition","差异处理");
            case SALES_SUMMARY,PURCHASE_SUMMARY,GROSS_MARGIN -> {
                if(!grouped) {
                    add(columns,"businessUserName",type==ReportType.PURCHASE_SUMMARY?"采购员":"销售员");
                    add(columns,"partnerName",type==ReportType.PURCHASE_SUMMARY?"供应商":"客户","businessDate","业务日期");
                    if(detail || type==ReportType.GROSS_MARGIN) { add(columns,"sourceNo","来源单号"); }
                }
                add(columns,"qty","基础数量","amount","含税金额","untaxedAmount","不含税金额","taxAmount","税额","costAmount","成本金额");
                if(type==ReportType.GROSS_MARGIN) { add(columns,"grossMargin","毛利"); }
                else if(!detail) { add(columns,"sourceCount","明细行数"); }
            }
            case RECEIVABLE_AGING,PAYABLE_AGING -> add(columns,"partnerName",type==ReportType.RECEIVABLE_AGING?"客户":"供应商","sourceNo","来源单号","agingDate","账龄起算日","dueDate","到期日","overdueDays","逾期天数","direction","方向","openAmount","未核销金额","ageDays","账龄天数","age030","0至30天","age3160","31至60天","age6190","61至90天","age91180","91至180天","ageOver180","180天以上");
            case PARTNER_STATEMENT -> add(columns,"partnerName","往来单位","businessDate","业务日期","sourceNo","来源单号","accountCode","科目","direction","方向","debitAmount","借方发生额","creditAmount","贷方发生额","openingBalance","期初余额","runningBalance","本行余额","invoicingStatus","到票状态（截至结束日）");
            case CASH_JOURNAL -> add(columns,"cashAccountName","资金账户","businessDate","业务日期","sourceNo","来源单号","receiptAmount","收入","paymentAmount","支出","openingBalance","期初余额","runningBalance","本行余额");
            case UNINVOICED_RECEIPTS -> add(columns,"partnerName","供应商","businessDate","来源日期","sourceNo","采购来源单号","originalReceiptNo","原收货单号","basisUntaxedAmount","净可到票未税额","basisTaxAmount","净可到票税额","invoicedUntaxedAmount","已到票未税额","invoicedTaxAmount","已到票税额","uninvoicedUntaxedAmount","未到票未税额","uninvoicedTaxAmount","未到票税额","uninvoicedAmount","未到票合计","invoicingStatus","到票状态","invoiceMatchNotice","核对提示");
        }
        if(Set.of(ReportType.RECEIVABLE_AGING,ReportType.PAYABLE_AGING).contains(type)&&!rows.isEmpty()&&rows.getFirst().get("agingLabels") instanceof Map<?,?> labels) {columns.keySet().removeIf(field->field.startsWith("age"));columns.put("businessUserName","业务员");columns.put("departmentName","部门");labels.forEach((field,label)->{String key=field.toString().replace("aging_","aging");columns.put(key,label.toString());});}
        if(columns.containsKey("sourceNo")&&rows.stream().anyMatch(row->row.containsKey("returnMode")))
            add(columns,"returnMode","退货模式","returnCostSource","退货成本来源");
        if(!cost) { columns.keySet().removeIf(field->field.toLowerCase(Locale.ROOT).contains("cost") || field.equals("grossMargin")); }
        return write(rows,columns,json);
    }
    private static void add(Map<String,String> columns,String... pairs) { for(int i=0;i<pairs.length;i+=2) { columns.put(pairs[i],pairs[i+1]); } }
    private static byte[] write(List<Map<String,Object>> rows,LinkedHashMap<String,String> columns,ObjectMapper json) {
        var output=new StringBuilder("\uFEFF");line(output,new ArrayList<>(columns.values()));
        for(var row:rows) {
            var values=new ArrayList<String>();
            for(String column:columns.keySet()) {
                Object value=row.get(column);String text=value==null?"":value.toString();
                if(value instanceof Map<?,?> || value instanceof Collection<?>) {
                    try { text=json.writeValueAsString(value); }
                    catch(JacksonException error) { throw new IllegalStateException("导出字段无法序列化",error); }
                }
                if(column.equals("direction")) { text=Map.of("D","借方","C","贷方").getOrDefault(text,text); }
                if(column.equals("postingAction")) { text=Map.of("post","过账","reverse","冲销","adjustment","成本调整").getOrDefault(text,text); }
                if(column.equals("diffDisposition")) { text=Map.of("pending","待处理","returned","拒收退回","lost","运输损失","none","无差异").getOrDefault(text,text); }
                if(column.equals("accountCode")) { text=Map.of("AR","应收","AP","应付","FUNDS","资金").getOrDefault(text,text); }
                if(column.equals("invoicingStatus")) {text=Map.of("uninvoiced","未到票","partial","部分到票","invoiced","已到票","not_required","无待到票额度","source_unavailable","不适用或来源失效").getOrDefault(text,text);}
                if(column.equals("returnMode"))text=Map.of("original","按原单退货","sourceless","无原单退货").getOrDefault(text,"");
                if(column.equals("returnCostSource"))text=Map.of("average","加权平均成本","last_in","最近入库成本","manual","人工复核成本").getOrDefault(text,"");
                // 标识及精确小数使用文本单元格，避免表格软件按浮点数截断；外部文本不能成为公式。
                boolean exact=column.equals("id") || column.endsWith("Id") || text.matches("-?\\d+(\\.\\d+)?");
                String stripped=text.stripLeading();
                boolean formula=!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0))>=0;
                if(!text.isEmpty() && (exact || "=+-@\t\r\n".indexOf(text.charAt(0))>=0 || formula)) { text="'"+text; }
                values.add(text);
            }
            line(output,values);
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static void line(StringBuilder output,List<String> values) {
        for(int i=0;i<values.size();i++) { if(i>0) { output.append(','); }output.append('"').append(values.get(i).replace("\"","\"\"")).append('"'); }
        output.append("\r\n");
    }
}
