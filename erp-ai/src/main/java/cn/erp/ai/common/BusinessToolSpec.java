package cn.erp.ai.common;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** ERP 只读业务工具的入口白名单及模型参数定义。 */
public record BusinessToolSpec(String code,String label,String description,Set<String> permissions,
        Map<String,Field> fields,Set<String> required,List<Set<String>> anyRequired) {
    private static final Set<String> REPORT_DIMENSIONS=Set.of("customer","sku","business_user","department");
    private static final Set<String> COMPANY_REPORTS=Set.of("receivable_balance","payable_balance","cash_balance");
    private static final Map<String,String> DOCUMENT_PERMISSIONS=Map.ofEntries(
            Map.entry("sales-orders","sales_order:view"),Map.entry("shipments","shipment:view"),
            Map.entry("sales-returns","sales_return:view"),Map.entry("receipts","receipt:view"),
            Map.entry("refund-requests","refund_request:view"),Map.entry("invoice-registrations","invoice_registration:view"),
            Map.entry("purchase-requisitions","purchase_requisition:view"),Map.entry("purchase-orders","purchase_order:view"),
            Map.entry("goods-receipts","goods_receipt:view"),Map.entry("purchase-returns","purchase_return:view"),
            Map.entry("payments","payment:view"),Map.entry("account-transfers","account_transfer:view"),
            Map.entry("opening-entries","opening_entry:view"),Map.entry("stock-adjustments","stock_adjustment:view"),
            Map.entry("stock-transfers","stock_transfer:view"),Map.entry("stock-counts","stock_count:view"),
            Map.entry("document-reversals","reversal:view"));
    private static final List<BusinessToolSpec> TOOLS=List.of(
            single("receivable_balance","应收余额","按客户名称或编码查询截止日期的应收余额","report:receivable_aging","customer"),
            single("payable_balance","应付余额","按供应商名称或编码查询截止日期的应付余额","report:payable_aging","supplier"),
            new BusinessToolSpec("available_stock","实时可用库存","按商品名称、编码或条码查询仓库实时可用量",
                    Set.of("inventory:view"),Map.of("sku",text("商品名称、编码或条码"),"warehouse",text("仓库名称或编码")),Set.of("sku"),List.of()),
            progress("order_progress","销售订单进度","销售订单的发货、签收和单据进度","sales_order:view","customer"),
            progress("purchase_progress","采购订单进度","采购订单的收货、退货和单据进度","purchase_order:view","supplier"),
            summary("sales_summary","销售汇总","按日期查询销售金额和数量",Set.of("report:sales_summary")),
            summary("gross_margin","毛利","按日期查询收入、成本和毛利",Set.of("report:gross_margin","cost:view")),
            new BusinessToolSpec("cash_balance","资金余额","查询资金账户截至指定日期的余额",
                    Set.of("report:cash_journal"),Map.of("account",text("资金账户名称或编码"),"date",date("截止日期")),Set.of(),List.of()),
            new BusinessToolSpec("document_search","单据查询","按类型、关键字、状态或业务日期查找单据",
                    Set.of(),Map.of("type",enumeration("单据类型",DOCUMENT_PERMISSIONS.keySet()),"keyword",text("单号或相关关键字"),
                    "status",new Field("单据状态码",40,false,Set.of(),"^[a-z][a-z_]{0,39}$"),
                    "dateFrom",date("开始日期"),"dateTo",date("结束日期")),Set.of("type"),List.of()));
    private static final Map<String,BusinessToolSpec> INDEX=TOOLS.stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(BusinessToolSpec::code,tool->tool));

    record Field(String label,int maxLength,boolean date,Set<String> choices,String pattern) {
        void validate(JsonNode value) {
            if (!value.isString()) throw AiException.invalid("查数条件必须为文本");
            String input=AiInputs.text(value.stringValue(),maxLength,label);
            if (input.codePoints().anyMatch(Character::isISOControl) || !choices.isEmpty() && !choices.contains(value.stringValue())
                    || pattern!=null && !value.stringValue().matches(pattern))
                throw AiException.invalid(label+"无效");
            if (date) {
                try {
                    var day=LocalDate.parse(input);
                    if (day.getYear()<1900 || day.isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))))
                        throw AiException.invalid(label+"超出业务期间");
                }
                catch (DateTimeParseException invalid) { throw AiException.invalid(label+"应为有效的 yyyy-MM-dd 日期"); }
            }
        }

        Map<String,Object> schema() {
            var result=new LinkedHashMap<String,Object>();
            result.put("type","string");
            result.put("description",label);
            result.put("minLength",1);
            result.put("maxLength",maxLength);
            if (date) result.put("format","date");
            if (!choices.isEmpty()) result.put("enum",choices.stream().sorted().toList());
            if (pattern!=null) result.put("pattern",pattern);
            return result;
        }
    }

    public static List<BusinessToolSpec> all() { return TOOLS; }
    public static Set<String> codes() { return INDEX.keySet(); }
    public static BusinessToolSpec find(String code) { return code==null ? null : INDEX.get(code); }
    public static BusinessToolSpec require(String code) {
        var tool=find(code);
        if (tool==null) throw AiException.invalid("查数工具不在只读白名单中");
        return tool;
    }

    public boolean allowed(AiActor actor) {
        if (COMPANY_REPORTS.contains(code) && !actor.allWarehouses()) return false;
        return code.equals("document_search") ? DOCUMENT_PERMISSIONS.values().stream().anyMatch(actor::has)
                : actor.permissions().containsAll(permissions);
    }

    public void authorize(AiActor actor,JsonNode arguments) {
        actor.require("ai:use");
        if (!allowed(actor)) throw AiException.forbidden();
        if (code.equals("document_search")) actor.require(DOCUMENT_PERMISSIONS.get(arguments.path("type").asString()));
    }

    public void validate(JsonNode arguments) {
        if (arguments==null || !arguments.isObject() || arguments.size()>fields.size())
            throw AiException.invalid("查数工具参数应为 JSON 对象");
        for (String key:arguments.propertyNames()) {
            var field=fields.get(key);
            if (field==null) throw AiException.invalid("查数工具包含未知条件");
            field.validate(arguments.path(key));
        }
        for (String key:required) if (!arguments.has(key)) throw AiException.invalid("缺少"+fields.get(key).label());
        if (!anyRequired.isEmpty() && anyRequired.stream().noneMatch(group->group.stream().allMatch(arguments::has)))
            throw AiException.invalid("请提供单号或往来单位");
        if (arguments.has("dateFrom") && arguments.has("dateTo")) {
            var from=LocalDate.parse(arguments.path("dateFrom").asString());
            var to=LocalDate.parse(arguments.path("dateTo").asString());
            if (from.isAfter(to) || to.isAfter(from.plusYears(1).minusDays(1)))
                throw AiException.invalid("开始日期不能晚于结束日期，查询范围最多一年");
        }
    }

    public Map<String,Object> schema(AiActor actor) {
        var properties=new TreeMap<String,Object>();
        for (var entry:fields.entrySet()) {
            var field=entry.getValue();
            if (code.equals("document_search") && entry.getKey().equals("type"))
                field=enumeration(field.label(),DOCUMENT_PERMISSIONS.entrySet().stream().filter(e->actor.has(e.getValue()))
                        .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toUnmodifiableSet()));
            properties.put(entry.getKey(),field.schema());
        }
        var schema=new LinkedHashMap<String,Object>();
        schema.put("type","object");
        schema.put("additionalProperties",false);
        schema.put("properties",properties);
        schema.put("required",required.stream().sorted().toList());
        if (!anyRequired.isEmpty())
            schema.put("anyOf",anyRequired.stream().map(group->Map.of("required",group.stream().sorted().toList())).toList());
        return schema;
    }

    private static BusinessToolSpec single(String code,String label,String description,String permission,String party) {
        return new BusinessToolSpec(code,label,description,Set.of(permission),
                Map.of(party,text("往来单位名称或编码"),"date",date("截止日期")),Set.of(party),List.of());
    }

    private static BusinessToolSpec progress(String code,String label,String description,String permission,String party) {
        return new BusinessToolSpec(code,label,description,Set.of(permission),Map.of(
                "orderNo",text("订单编号"),party,text("往来单位名称或编码"),
                "dateFrom",date("开始日期"),"dateTo",date("结束日期")),Set.of(),List.of(Set.of("orderNo"),Set.of(party)));
    }

    private static BusinessToolSpec summary(String code,String label,String description,Set<String> permissions) {
        return new BusinessToolSpec(code,label,description,permissions,Map.of("dateFrom",date("开始日期"),"dateTo",date("结束日期"),
                "groupBy",enumeration("汇总维度",REPORT_DIMENSIONS),"customer",text("客户名称或编码"),
                "sku",text("商品名称、编码或条码"),"warehouse",text("仓库名称或编码")),Set.of("dateFrom","dateTo"),List.of());
    }

    private static Field text(String label) { return new Field(label,100,false,Set.of(),null); }
    private static Field date(String label) { return new Field(label,10,true,Set.of(),"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"); }
    private static Field enumeration(String label,Set<String> choices) { return new Field(label,40,false,choices,null); }
}
