package cn.erp.aitools;

import cn.erp.documents.DocumentAccess;
import cn.erp.identity.CompanyAccess;
import cn.erp.purchasing.PurchaseOrderQueries;
import cn.erp.reporting.ReportQuery;
import cn.erp.reporting.ReportService;
import cn.erp.reporting.ReportType;
import cn.erp.salesorder.SalesInventoryQueries;
import cn.erp.salesorder.SalesOrderQueries;
import cn.erp.shared.ApiException;
import cn.erp.workbench.DocumentBrowser;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.aitools.AiBusinessLookup.Kind.*;

@Service
class AiBusinessTools {
    private static final int LIMIT=20;
    private static final int MAX_ORDER_LINES=50;
    private static final String STOCK="/inventory-balances";
    private static final Set<String> GROUPS=Set.of("customer","sku","business_user","department");
    private static final Set<String> SUMMARY_FIELDS=Set.of("onHandQty","reservedQty","blockedQty","availableQty","shortageCount",
            "pendingDemandQty","costAmount","amount","qty","untaxedAmount","taxAmount","grossMargin","closingBalance",
            "openAmount","dateFrom","dateTo","basis","rowCount","receiptAmount","paymentAmount","statisticDate","agingDate");
    private static final Set<String> ORDER_FIELDS=Set.of("id","documentNo","status","businessDate","fulfillmentStatus",
            "settlementStatus","totalAmount","customerName","supplierName","deliveryDate","expectedDate");
    private static final Set<String> SEARCH_FIELDS=Set.of("id","documentNo","status","businessDate","invoiceDate",
            "fulfillmentStatus","settlementStatus","partnerName","customerName","supplierName","warehouseName");
    private static final Set<String> SOURCE_DEPENDENT=Set.of("invoice-registrations","document-reversals");
    private static final Set<String> REPORT_FIELDS=Set.of("id","warehouseId","warehouseName","skuId","skuName","skuCode","partnerId",
            "partnerName","businessUserId","businessUserName","departmentId","departmentName","businessDate","agingDate",
            "openAmount","ageDays","direction","aging0","aging1","aging2","aging3","aging4","cashAccountId","cashAccountName","openingBalance",
            "runningBalance","receiptAmount","paymentAmount","qty","amount","untaxedAmount","taxAmount","costAmount","grossMargin",
            "sourceCount","sourceType","sourceNo","sourceUrl","detailUrl");
    private static final Set<String> STOCK_FIELDS=Set.of("id","warehouseId","warehouseCode","warehouseName","skuId","skuCode","skuName",
            "onHandQty","reservedQty","blockedQty","availableQty","pendingDemandQty","costAmount","unitCost");
    private final AiBusinessLookup lookup;
    private final ReportService reports;
    private final SalesInventoryQueries inventory;
    private final SalesOrderQueries sales;
    private final PurchaseOrderQueries purchases;
    private final DocumentBrowser browser;
    private final DocumentAccess documents;

    AiBusinessTools(AiBusinessLookup lookup,ReportService reports,SalesInventoryQueries inventory,SalesOrderQueries sales,
                    PurchaseOrderQueries purchases,DocumentBrowser browser,DocumentAccess documents) {
        this.lookup=lookup;
        this.reports=reports;
        this.inventory=inventory;
        this.sales=sales;
        this.purchases=purchases;
        this.browser=browser;
        this.documents=documents;
    }

    enum Tool {
        RECEIVABLE_BALANCE("report:receivable_aging",Set.of("customer","date")),
        PAYABLE_BALANCE("report:payable_aging",Set.of("supplier","date")),
        AVAILABLE_STOCK("inventory:view",Set.of("sku","warehouse")),
        ORDER_PROGRESS("sales_order:view",Set.of("orderNo","customer","dateFrom","dateTo")),
        PURCHASE_PROGRESS("purchase_order:view",Set.of("orderNo","supplier","dateFrom","dateTo")),
        SALES_SUMMARY("report:sales_summary",Set.of("dateFrom","dateTo","groupBy","customer","sku","warehouse")),
        GROSS_MARGIN("report:gross_margin",Set.of("dateFrom","dateTo","groupBy","customer","sku","warehouse")),
        CASH_BALANCE("report:cash_journal",Set.of("account","date")),
        DOCUMENT_SEARCH(null,Set.of("type","keyword","status","dateFrom","dateTo"));

        final String permission;
        final Set<String> fields;
        Tool(String permission,Set<String> fields){this.permission=permission;this.fields=fields;}
        String code(){return name().toLowerCase(Locale.ROOT);}
        static Tool parse(String code) {
            return Arrays.stream(values()).filter(tool->tool.code().equals(code)).findFirst().orElseThrow(AiBusinessLookup::missing);
        }
    }

    @Transactional(readOnly=true)
    public Map<String,Object> query(CompanyAccess actor,Tool tool,AiBusinessInput input) {
        authorize(actor,tool);
        Map<String,Object> result;
        try {
            result=switch(tool) {
                case RECEIVABLE_BALANCE -> aging(actor,input,true);
                case PAYABLE_BALANCE -> aging(actor,input,false);
                case AVAILABLE_STOCK -> stock(actor,input);
                case ORDER_PROGRESS -> progress(actor,input,true);
                case PURCHASE_PROGRESS -> progress(actor,input,false);
                case SALES_SUMMARY -> commerce(actor,input,ReportType.SALES_SUMMARY);
                case GROSS_MARGIN -> commerce(actor,input,ReportType.GROSS_MARGIN);
                case CASH_BALANCE -> cash(actor,input);
                case DOCUMENT_SEARCH -> search(actor,input);
            };
        } catch (AiBusinessLookup.Ambiguous ambiguous) {
            result=empty(Instant.now().toString(),null);
            result.put("status","ambiguous");
            result.put("candidates",ambiguous.candidates());
        }
        result.put("scopeFingerprint",fingerprint(actor,tool));
        return result;
    }

    String scope(CompanyAccess actor,Tool tool) {
        authorize(actor,tool);
        return fingerprint(actor,tool);
    }

    private void authorize(CompanyAccess actor,Tool tool) {
        if (tool.permission!=null) DocumentAccess.require(actor,tool.permission);
        switch(tool) {
            case RECEIVABLE_BALANCE -> ReportType.RECEIVABLE_AGING.authorize(actor,false);
            case PAYABLE_BALANCE -> ReportType.PAYABLE_AGING.authorize(actor,false);
            case CASH_BALANCE -> ReportType.CASH_JOURNAL.authorize(actor,false);
            case GROSS_MARGIN -> ReportType.GROSS_MARGIN.authorize(actor,false);
            case DOCUMENT_SEARCH -> {
                if (DocumentBrowser.SPECS.keySet().stream().noneMatch(type->actor.hasPermission(browser.permission(type)))) {
                    throw new ApiException(403,"FORBIDDEN","没有可查询的单据权限");
                }
            }
            default -> { }
        }
    }

    private Map<String,Object> aging(CompanyAccess actor,AiBusinessInput input,boolean receivable) {
        ReportType type=receivable?ReportType.RECEIVABLE_AGING:ReportType.PAYABLE_AGING;
        String term=input.required(receivable?"customer":"supplier");
        LocalDate day=day(input,"date");
        var party=lookup.resolve(actor,receivable?CUSTOMER:SUPPLIER,term,
                candidate->actor.hasPermission("partner:view")||hasRows(reports.page(actor,type,agingQuery(day,candidate.id()),null,1)));
        var query=agingQuery(day,party.id());
        return report(actor,type,query);
    }

    private Map<String,Object> stock(CompanyAccess actor,AiBusinessInput input) {
        var warehouse=lookup.resolve(actor,WAREHOUSE,input.text("warehouse"),unused->true);
        String warehouseId=warehouse==null?null:Long.toString(warehouse.id());
        var sku=lookup.resolve(actor,SKU,input.required("sku"),candidate->actor.hasPermission("sku:view")
                || hasRows(inventory.balances(actor,warehouseId,"",null,1,false,false,candidate.id())));
        var snapshot=inventory.balances(actor,warehouseId,"",null,LIMIT,false,false,sku.id());
        var filters=new LinkedHashMap<String,Object>();
        filters.put("skuId",sku.id());
        if(warehouse!=null) filters.put("warehouseId",warehouse.id());
        return page(snapshot,STOCK+parameters(filters),STOCK_FIELDS);
    }

    private Map<String,Object> commerce(CompanyAccess actor,AiBusinessInput input,ReportType type) {
        input.required("dateFrom");
        input.required("dateTo");
        var start=input.date("dateFrom");
        var end=input.date("dateTo");
        input.range(start,end);
        String group=input.text("groupBy");
        if(group!=null&&!GROUPS.contains(group)) throw AiBusinessInput.invalid("汇总维度须为客户、商品、业务员或部门");
        Long warehouse=id(lookup.resolve(actor,WAREHOUSE,input.text("warehouse"),unused->true));
        var partner=lookup.resolve(actor,CUSTOMER,input.text("customer"),candidate->actor.hasPermission("partner:view")
                || hasReportRows(actor,type,start,end,warehouse,null,candidate.id(),null));
        var sku=lookup.resolve(actor,SKU,input.text("sku"),candidate->actor.hasPermission("sku:view")
                || hasReportRows(actor,type,start,end,warehouse,candidate.id(),id(partner),null));
        return report(actor,type,new ReportQuery(start,end,warehouse,id(sku),id(partner),null,false,false,null,group));
    }

    private Map<String,Object> cash(CompanyAccess actor,AiBusinessInput input) {
        var day=day(input,"date");
        var account=lookup.resolve(actor,CASH,input.text("account"),candidate->actor.orgScope().allDepartments()
                || hasReportRows(actor,ReportType.CASH_JOURNAL,day,day,null,null,null,candidate.id()));
        return report(actor,ReportType.CASH_JOURNAL,new ReportQuery(day,day,null,null,null,id(account),false));
    }

    private boolean hasReportRows(CompanyAccess actor,ReportType type,LocalDate from,LocalDate to,
                                  Long warehouse,Long sku,Long partner,Long account) {
        return hasRows(reports.page(actor,type,new ReportQuery(from,to,warehouse,sku,partner,account,false),null,1));
    }

    private Map<String,Object> report(CompanyAccess actor,ReportType type,ReportQuery query) {
        var filters=new LinkedHashMap<String,Object>();
        filters.put("dateFrom",query.dateFrom());filters.put("dateTo",query.dateTo());
        filters.put("partnerId",query.partnerId());filters.put("skuId",query.skuId());
        filters.put("warehouseId",query.warehouseId());filters.put("cashAccountId",query.cashAccountId());
        filters.put("groupBy",query.groupBy());
        filters.put("agingDate",query.agingDate());
        String path="/reports/"+type.code().replace('_','-')+parameters(filters);
        return page(reports.page(actor,type,query,null,LIMIT),path,REPORT_FIELDS);
    }

    private Map<String,Object> progress(CompanyAccess actor,AiBusinessInput input,boolean salesOrder) {
        String kind=salesOrder?"sales-orders":"purchase-orders";
        String number=input.text("orderNo");
        String term=input.text(salesOrder?"customer":"supplier");
        if(number==null&&term==null) throw AiBusinessInput.invalid("请输入单号或往来单位");
        LocalDate from=input.date("dateFrom"),to=input.date("dateTo");
        input.range(from,to);
        var other=lookup.resolve(actor,salesOrder?CUSTOMER:SUPPLIER,term,candidate->
                hasRows(browser.page(actor,kind,new DocumentBrowser.Filter(null,null,candidate.id(),null,from,to,null,null,false,number),null,1,false)));
        var filter=new DocumentBrowser.Filter(null,null,id(other),null,from,to,null,null,false,number);
        var snapshot=browser.page(actor,kind,filter,null,LIMIT,true);
        var output=empty(Instant.now().toString(),"/"+kind);
        var rows=rows(snapshot).stream().map(row->link(select(row,ORDER_FIELDS),kind)).toList();
        if(number!=null) {
            if(rows.isEmpty()) throw AiBusinessLookup.missing();
            if(rows.size()>1) throw AiBusinessInput.invalid("存在多张同号单据，请按往来单位和日期缩小范围");
            var header=rows.getFirst();
            long id=Long.parseLong(header.get("id").toString());
            Map<String,Object> detail=salesOrder?sales.get(actor,id):purchases.get(actor,id);
            var lines=detail.get("lines") instanceof List<?> list?list:List.of();
            header.put("lineCount",lines.size());
            if(lines.size()>MAX_ORDER_LINES) output.put("hasMore",true);
            header.put("lines",lines.stream().limit(MAX_ORDER_LINES).filter(Map.class::isInstance).map(Map.class::cast)
                    .map(line->select(line,Set.of("lineNo","sku","unit","warehouse","progress"))).toList());
            output.put("detailUrl",header.get("detailUrl"));
        }
        output.put("items",rows);
        if(snapshot.get("nextCursor")!=null) output.put("hasMore",true);
        output.put("summary",Map.of("rowCount",snapshot.get("total")));
        return output;
    }

    private Map<String,Object> search(CompanyAccess actor,AiBusinessInput input) {
        String kind=input.required("type");
        browser.spec(kind);
        DocumentAccess.require(actor,browser.permission(kind));
        String status=input.text("status");
        if(status!=null&&!status.matches("[a-z][a-z_]{0,39}")) throw AiBusinessInput.invalid("单据状态编码无效");
        var from=input.date("dateFrom");
        var to=input.date("dateTo");
        input.range(from,to);
        var filter=new DocumentBrowser.Filter(input.text("keyword"),status,null,null,from,to,null,null,false);
        var snapshot=browser.page(actor,kind,filter,null,LIMIT,false);
        var output=empty(Instant.now().toString(),"/"+kind);
        var visible=new ArrayList<Map<String,Object>>();
        for(var raw:rows(snapshot)) {
            try {
                // 开票登记和冲销的范围还取决于来源单据，复用原详情再检查交集。
                if(SOURCE_DEPENDENT.contains(kind))
                    documents.view(actor,browser.spec(kind).type(),Long.parseLong(raw.get("id").toString()));
                visible.add(link(select(raw,SEARCH_FIELDS),kind));
            } catch(ApiException error) {
                if(error.getStatus()!=404&&error.getStatus()!=403) throw error;
            }
        }
        output.put("items",visible);
        if(!SOURCE_DEPENDENT.contains(kind)&&snapshot.get("nextCursor")!=null) output.put("hasMore",true);
        return output;
    }

    private static Map<String,Object> link(Map<String,Object> row,String kind) {
        row.put("detailUrl","/"+kind+"/"+row.get("id"));
        return row;
    }

    private static Map<String,Object> page(Map<String,Object> page,String url,Set<String> fields) {
        var output=empty(page.get("asOf").toString(),url);
        output.put("items",rows(page).stream().map(row->select(row,fields)).toList());
        var summary=page.get("summary") instanceof Map<?,?> map?select(map,SUMMARY_FIELDS):new LinkedHashMap<String,Object>();
        if(page.containsKey("rowCount")) summary.put("rowCount",page.get("rowCount"));
        if(page.containsKey("statisticDate")) summary.put("statisticDate",page.get("statisticDate"));
        output.put("summary",summary);
        if(page.get("nextCursor")!=null) output.put("hasMore",true);
        return output;
    }

    private static Map<String,Object> empty(String asOf,String link) {
        var output=new LinkedHashMap<String,Object>();
        output.put("status","ok");
        output.put("asOf",asOf);
        output.put("items",List.of());
        if(link!=null) output.put("detailUrl",link);
        return output;
    }

    private static List<Map<?,?>> rows(Map<String,Object> value) {
        var result=new ArrayList<Map<?,?>>();
        if(value.get("items") instanceof List<?> rows)
            for(Object row:rows) if(row instanceof Map<?,?> fields)result.add(fields);
        return result;
    }

    private static boolean hasRows(Map<String,Object> page) {
        return page.get("items") instanceof List<?> list&&!list.isEmpty();
    }

    private static Map<String,Object> select(Map<?,?> source,Set<String> fields) {
        var selected=new LinkedHashMap<String,Object>();
        source.forEach((key,value)->{if(key instanceof String name&&fields.contains(name)&&value!=null)selected.put(name,value);});
        return selected;
    }

    private static String parameters(Map<String,Object> filters) {
        var query=new StringJoiner("&","?","").setEmptyValue("");
        filters.forEach((key,value)->{if(value!=null&&!value.toString().isEmpty())query.add(key+"="+URLEncoder.encode(value.toString(),StandardCharsets.UTF_8));});
        return query.toString();
    }

    private static Long id(AiBusinessLookup.Match match){return match==null?null:match.id();}
    private static ReportQuery agingQuery(LocalDate date,long partner) {
        return new ReportQuery(null,null,null,null,partner,null,false,false,null,null,date);
    }
    private static LocalDate day(AiBusinessInput input,String field) {return Objects.requireNonNullElseGet(input.date(field),AiBusinessInput::today);}

    private static String fingerprint(CompanyAccess actor,Tool tool) {
        String value=actor.tenantId()+":"+actor.userId()+":"+actor.companyId()+":"+tool.code()+":"+actor.allWarehouses()
                +":"+new TreeSet<>(actor.warehouseIds())+":"+actor.orgScope().signature()+":"+new TreeSet<>(actor.permissions());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException error) {
            throw new IllegalStateException("运行环境缺少授权摘要算法",error);
        }
    }
}
