package cn.erp.reporting;

import cn.erp.identity.CompanyAccess;
import cn.erp.salesfinance.FinanceSupport;
import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ReportService {
    public static final int MAX_ROWS=50_000;
    private final NamedParameterJdbcTemplate jdbc;
    private final ReportReadDatabase database;
    public ReportService(ReportReadDatabase database) { this.database=database;this.jdbc=database.jdbc(); }

    public Map<String,Object> page(CompanyAccess actor,ReportType type,ReportQuery query,Long cursor,int limit) {
        return database.read(()->pageInside(actor,type,query,cursor,limit));
    }

    private Map<String,Object> pageInside(CompanyAccess actor,ReportType type,ReportQuery query,Long cursor,int limit) {
        if(limit<1 || limit>200 || cursor!=null && (cursor<0 || cursor>MAX_ROWS)) { throw invalid("报表分页条件无效"); }
        var snapshot=snapshot(actor,type,query);
        int offset=cursor==null?0:cursor.intValue();
        if(offset>snapshot.items().size()) { throw invalid("报表分页位置已失效，请重新查询"); }
        var result=new LinkedHashMap<String,Object>();
        result.put("reportType",type.code());result.put("asOf",snapshot.asOf());
        result.put("dataSource",database.replicaEnabled()?"replica":"primary");
        result.put("statisticDate",LocalDate.now(ZoneId.of("Asia/Shanghai")).toString());
        result.put("items",snapshot.items().subList(offset,Math.min(offset+limit,snapshot.items().size())));
        result.put("nextCursor",offset+limit<snapshot.items().size()?Integer.toString(offset+limit):null);
        result.put("summary",snapshot.summary());result.put("rowCount",snapshot.items().size());return result;
    }

    public record Snapshot(String asOf,List<Map<String,Object>> items,Map<String,Object> summary) {}

    public Snapshot snapshot(CompanyAccess actor,ReportType type,ReportQuery input) {
        return database.read(()->snapshotInside(actor,type,input));
    }

    private Snapshot snapshotInside(CompanyAccess actor,ReportType type,ReportQuery input) {
        type.authorize(actor,false);var query=input.normalized();
        if(query.warehouseId()!=null && !actor.allowsWarehouse(query.warehouseId())) { throw new ApiException(404,"NOT_FOUND","仓库不存在或无权查看"); }
        var params=new HashMap<String,Object>();params.put("t",actor.tenantId());params.put("c",actor.companyId());
        params.put("f",query.dateFrom());params.put("d",query.dateTo());params.put("today",LocalDate.now(ZoneId.of("Asia/Shanghai")));
        String sql="SELECT q.* FROM ("+ReportSql.query(type,Boolean.TRUE.equals(query.detail()))+") q WHERE 1=1";
        if(!type.companyWide()) {
            if(!actor.allWarehouses()) {
                params.put("ws",actor.warehouseIds().isEmpty()?Set.of(-1L):actor.warehouseIds());sql+=" AND warehouse_id IN (:ws)";
            }
            if(query.warehouseId()!=null) { params.put("w",query.warehouseId());sql+=" AND warehouse_id=:w"; }
            if(query.skuId()!=null) { params.put("s",query.skuId());sql+=" AND sku_id=:s"; }
        } else if(query.warehouseId()!=null || query.skuId()!=null) { throw invalid("公司财务报表不支持仓库或商品筛选"); }
        if(query.partnerId()!=null) {
            if(Set.of(ReportType.INVENTORY_SUMMARY,ReportType.INVENTORY_FLOW).contains(type)) { throw invalid("库存报表不支持往来单位筛选"); }
            params.put("partner",query.partnerId());sql+=" AND partner_id=:partner";
        }
        if(query.cashAccountId()!=null) {
            if(type!=ReportType.CASH_JOURNAL) { throw invalid("此报表不支持资金账户筛选"); }
            params.put("cash",query.cashAccountId());sql+=" AND cash_account_id=:cash";
        }
        boolean commercial=Set.of(ReportType.SALES_SUMMARY,ReportType.PURCHASE_SUMMARY).contains(type);
        if(Boolean.TRUE.equals(query.feesOnly())) {
            if(!commercial || query.skuId()!=null) { throw invalid("仅费用筛选只适用于销售或采购报表，且不能同时选择商品"); }
            sql+=" AND sku_id IS NULL";
        }
        boolean aggregate=commercial && !Boolean.TRUE.equals(query.detail());
        if(aggregate) {
            sql="SELECT MIN(id) id,partner_id,sku_id,business_date,IF(COUNT(DISTINCT warehouse_id)=1,MIN(warehouse_id),NULL) warehouse_id,"+
                "IF(COUNT(DISTINCT warehouse_id)=1,NULL,'多个仓库') warehouse_name,SUM(qty) qty,SUM(amount) amount,SUM(untaxed_amount) untaxed_amount,SUM(tax_amount) tax_amount,SUM(cost_amount) cost_amount,COUNT(*) source_count FROM ("+sql+") facts GROUP BY partner_id,sku_id,business_date";
        }
        sql+=" ORDER BY "+switch(type) {
            case INVENTORY_SUMMARY,INVENTORY_FLOW -> Boolean.TRUE.equals(query.detail()) && type==ReportType.INVENTORY_FLOW?"business_date,id":"warehouse_id,sku_id";
            case RECEIVABLE_AGING,PAYABLE_AGING -> "aging_date,id";
            case SALES_SUMMARY,PURCHASE_SUMMARY,GROSS_MARGIN -> aggregate?"business_date,id,partner_id,sku_id":"business_date,id,source_line_id";
            default -> aggregate?"business_date,id,partner_id,sku_id":"business_date,id";
        }+" LIMIT "+(MAX_ROWS+1);
        var raw=jdbc.queryForList(sql,params);
        if(raw.size()>MAX_ROWS) { throw invalid("报表超过50000行，请缩小日期或筛选范围后重试"); }
        var asOf=database.asOf();
        if(type==ReportType.PARTNER_STATEMENT || type==ReportType.CASH_JOURNAL) { raw=balances(raw,query,type); }
        names(actor,raw);
        documentNumbers(actor,raw);
        var totals=new LinkedHashMap<String,BigDecimal>();var items=new ArrayList<Map<String,Object>>();
        for(var row:raw) {
            if(!actor.hasPermission("cost:view")) { row.keySet().removeIf(ReportService::costField); }
            if(type!=ReportType.GROSS_MARGIN) { row.remove("gross_margin"); }
            if(type==ReportType.RECEIVABLE_AGING || type==ReportType.PAYABLE_AGING) { agingBuckets(row); }
            row.forEach((key,value)->{ if(value instanceof BigDecimal decimal) { totals.merge(key,decimal,BigDecimal::add); } });
            var item=FinanceSupport.row(row);source(item);
            if(aggregate) {
                String day=item.get("businessDate").toString();
                String url="/reports/"+type.code().replace('_','-')+"?detail=true&partnerId="+item.get("partnerId")+"&dateFrom="+day+"&dateTo="+day;
                url+=item.get("skuId")==null?"&feesOnly=true":"&skuId="+item.get("skuId");
                if(query.warehouseId()!=null) { url+="&warehouseId="+query.warehouseId(); }
                item.put("detailUrl",url);
            }
            if(type==ReportType.INVENTORY_FLOW && !Boolean.TRUE.equals(query.detail())) {
                item.put("detailUrl","/reports/inventory-flow?detail=true&warehouseId="+item.get("warehouseId")+"&skuId="+item.get("skuId")+"&dateFrom="+query.dateFrom()+"&dateTo="+query.dateTo());
            }
            if(type==ReportType.INVENTORY_SUMMARY) { item.put("detailUrl","/reports/inventory-flow?warehouseId="+item.get("warehouseId")+"&skuId="+item.get("skuId")); }
            items.add(item);
        }
        var summary=new LinkedHashMap<String,Object>();totals.forEach((key,value)->summary.put(camel(key),value.toPlainString()));
        if(type==ReportType.PARTNER_STATEMENT || type==ReportType.CASH_JOURNAL) {
            var closing=new HashMap<Object,BigDecimal>();
            for(var row:raw) { closing.put(row.get(type==ReportType.CASH_JOURNAL?"cash_account_id":"partner_id"),(BigDecimal)row.get("running_balance")); }
            summary.remove("runningBalance");summary.put("closingBalance",closing.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add).toPlainString());
        }
        summary.put("dateFrom",query.dateFrom().toString());summary.put("dateTo",query.dateTo().toString());
        summary.put("basis",switch(type) {
            case INVENTORY_SUMMARY,UNSIGNED_SHIPMENTS,RECEIVABLE_AGING,PAYABLE_AGING -> "current_snapshot";
            default -> "business_date";
        });
        return new Snapshot(asOf,List.copyOf(items),summary);
    }
    private void names(CompanyAccess actor,List<Map<String,Object>> rows) {
        for(String table:List.of("warehouse","sku","business_partner","cash_account")) {
            String field=table.equals("business_partner")?"partner":table;
            var ids=new HashSet<Object>();for(var row:rows) { if(row.get(field+"_id")!=null) { ids.add(row.get(field+"_id")); } }
            if(ids.isEmpty()) { continue; }
            String sql="SELECT id,name"+(table.equals("sku")?",code":"")+" FROM "+table+" WHERE tenant_id=:t AND id IN (:ids)";
            var params=new HashMap<String,Object>();params.put("t",actor.tenantId());params.put("ids",ids);
            if(Set.of("warehouse","cash_account").contains(table)) { sql+=" AND company_id=:c";params.put("c",actor.companyId()); }
            var labels=new HashMap<String,Map<String,Object>>();
            for(var label:jdbc.queryForList(sql,params)) { labels.put(label.get("id").toString(),label); }
            for(var row:rows) {
                var label=labels.get(Objects.toString(row.get(field+"_id")));if(label==null) { continue; }
                row.put(field+"_name",label.get("name"));if(table.equals("sku")) { row.put("sku_code",label.get("code")); }
            }
        }
    }
    private void documentNumbers(CompanyAccess actor,List<Map<String,Object>> rows) {
        var types=new LinkedHashMap<String,String>();
        types.put("shipment","shipment_no");types.put("sales_return","return_no");types.put("goods_receipt","receipt_no");types.put("purchase_return","return_no");
        types.put("receipt","receipt_no");types.put("payment","payment_no");types.put("opening_entry","entry_no");types.put("account_transfer","transfer_no");types.put("document_reversal","reversal_no");
        types.put("stock_adjustment","adjust_no");types.put("stock_count","count_no");types.put("stock_transfer","transfer_no");types.put("refund_request","refund_no");
        for(var type:types.entrySet()) {
            var ids=new HashSet<Object>();
            for(var row:rows) { if(row.get("source_no")==null && type.getKey().equals(documentType(row.get("source_type"))) && row.get("source_id")!=null) { ids.add(row.get("source_id")); } }
            if(ids.isEmpty()) { continue; }
            var params=Map.<String,Object>of("t",actor.tenantId(),"c",actor.companyId(),"ids",ids);
            var numbers=new HashMap<String,Object>();
            for(var value:jdbc.queryForList("SELECT id,"+type.getValue()+" no FROM "+type.getKey()+" WHERE tenant_id=:t AND company_id=:c AND id IN (:ids)",params)) { numbers.put(value.get("id").toString(),value.get("no")); }
            for(var row:rows) { if(type.getKey().equals(documentType(row.get("source_type")))) { row.put("source_no",numbers.get(Objects.toString(row.get("source_id")))); } }
        }
    }
    private static String documentType(Object value) {
        if(value==null) { return ""; }
        return switch(value.toString()) {
            case "shipment_post","shipment_sign","shipment_diff","shipment_line","sales_order_fee" -> "shipment";
            case "sales_return_line" -> "sales_return";case "goods_receipt_line","purchase_order_fee" -> "goods_receipt";
            case "purchase_return_line" -> "purchase_return";
            case "stock_transfer_out","stock_transfer_in","stock_transfer_diff" -> "stock_transfer";
            default -> value.toString();
        };
    }
    private List<Map<String,Object>> balances(List<Map<String,Object>> rows,ReportQuery query,ReportType type) {
        var balances=new LinkedHashMap<String,BigDecimal>();var openings=new LinkedHashMap<String,BigDecimal>();
        var samples=new LinkedHashMap<String,Map<String,Object>>();var result=new ArrayList<Map<String,Object>>();
        for(var row:rows) {
            String key=Objects.toString(row.get(type==ReportType.CASH_JOURNAL?"cash_account_id":"partner_id"));
            samples.putIfAbsent(key,row);BigDecimal before=(BigDecimal)row.get("opening_amount"),delta=(BigDecimal)row.get("period_amount");
            openings.merge(key,before,BigDecimal::add);balances.merge(key,before.add(delta),BigDecimal::add);
            LocalDate day=row.get("business_date") instanceof java.sql.Date date?date.toLocalDate():LocalDate.parse(row.get("business_date").toString());
            if(!day.isBefore(query.dateFrom())) {
                row.put("opening_balance",openings.get(key));row.put("running_balance",balances.get(key));result.add(row);
            }
        }
        for(var entry:samples.entrySet()) {
            String key=entry.getKey();
            if(result.stream().noneMatch(r->Objects.toString(r.get(type==ReportType.CASH_JOURNAL?"cash_account_id":"partner_id")).equals(key))) {
                var row=new LinkedHashMap<String,Object>();row.put(type==ReportType.CASH_JOURNAL?"cash_account_id":"partner_id",entry.getValue().get(type==ReportType.CASH_JOURNAL?"cash_account_id":"partner_id"));
                row.put("business_date",query.dateFrom());row.put("opening_balance",openings.get(key));row.put("running_balance",balances.get(key));row.put("row_type","opening");result.add(row);
            }
        }
        // 每个维度仅在首行展示期初，避免多行汇总重复计算余额。
        var seen=new HashSet<String>();
        for(var row:result) {
            String key=Objects.toString(row.get(type==ReportType.CASH_JOURNAL?"cash_account_id":"partner_id"));
            if(!seen.add(key)) { row.put("opening_balance",BigDecimal.ZERO); }
            row.remove("opening_amount");row.remove("period_amount");
        }
        return result;
    }
    private static void agingBuckets(Map<String,Object> row) {
        int age=((Number)row.get("age_days")).intValue();
        String bucket=age<=30?"age_0_30":age<=60?"age_31_60":age<=90?"age_61_90":age<=180?"age_91_180":"age_over_180";
        for(String key:List.of("age_0_30","age_31_60","age_61_90","age_91_180","age_over_180")) { row.put(key,key.equals(bucket)?row.get("open_amount"):BigDecimal.ZERO); }
    }
    private static boolean costField(String key) { return key.contains("cost") || key.equals("gross_margin"); }
    private static String camel(String key) { return FinanceSupport.row(Map.of(key,0)).keySet().iterator().next(); }
    private static void source(Map<String,Object> row) {
        Object type=row.get("sourceType"),id=row.get("sourceId");if(type==null || id==null) { return; }
        String name=documentType(type);
        String route=switch(name) {
            case "shipment_post","shipment_sign","shipment_diff","shipment" -> "shipments";
            case "goods_receipt","goods_receipt_fee" -> "goods-receipts";
            case "sales_return" -> "sales-returns";case "purchase_return" -> "purchase-returns";
            case "receipt" -> "receipts";case "payment" -> "payments";case "document_reversal" -> "document-reversals";
            case "stock_transfer","stock_transfer_out","stock_transfer_in","stock_transfer_diff" -> "stock-transfers";
            case "stock_adjustment" -> "stock-adjustments";case "stock_count" -> "stock-counts";
            case "opening_entry" -> "opening-entries";case "account_transfer" -> "account-transfers";
            default -> null;
        };
        if(route!=null) { row.put("sourceUrl","/"+route+"/"+id); }
    }
    private static ApiException invalid(String message) { return new ApiException(400,"VALIDATION_FAILED",message); }
}
