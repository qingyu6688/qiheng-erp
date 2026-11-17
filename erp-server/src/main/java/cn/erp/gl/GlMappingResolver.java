package cn.erp.gl;

import java.util.*;

import org.springframework.stereotype.Component;

import cn.erp.shared.*;

import static cn.erp.gl.GlSupport.*;

@Component
class GlMappingResolver {
    record Resolved(GlModels.Line line, Map<String, Object> mapping) {}

    private final GlSupport s;
    private final GlDimensions dimensions;
    private static final Set<String> SOURCES =
            Set.of(
                    "shipment",
                    "shipment_sign",
                    "shipment_post",
                    "shipment_diff",
                    "sales_return",
                    "goods_receipt",
                    "purchase_return",
                    "receipt",
                    "payment",
                    "refund_request",
                    "account_transfer",
                    "opening_entry",
                    "stock_adjustment",
                    "stock_misc_doc",
                    "stock_count",
                    "stock_transfer",
                    "stock_transfer_event",
                    "ar_bad_debt");

    GlMappingResolver(GlSupport s, GlDimensions dimensions) {
        this.s = s;
        this.dimensions = dimensions;
    }

    List<Map<String, Object>> mappings(ActorScope a) {
        return s.jdbc.queryForList(
                "SELECT * FROM gl_business_mapping WHERE tenant_id=? AND company_id=? AND enabled=1"
                        + " ORDER BY priority DESC,id",
                a.tenantId(),
                a.companyId());
    }

    Map<String, String> context(
            ActorScope a, Map<String, Object> journal, Map<String, Object> line) {
        var context = new LinkedHashMap<String, String>();
        String source = journal.get("source_type").toString();
        context.put("sourceType", source);
        for (String key : List.of("partner", "cash_account", "warehouse", "sku"))
            context.put(
                    key.equals("cash_account") ? "cashAccountId" : key + "Id",
                    Objects.toString(line.get(key + "_id"), "0"));
        if (line.get("cash_account_id") != null) {
            var account =
                    s.jdbc.queryForList(
                            "SELECT account_type FROM cash_account WHERE tenant_id=? AND"
                                    + " company_id=? AND id=?",
                            a.tenantId(),
                            a.companyId(),
                            line.get("cash_account_id"));
            if (!account.isEmpty())
                context.put("cashAccountType", account.getFirst().get("account_type").toString());
        }
        String table = source.startsWith("shipment_") ? "shipment" : source;
        if (SOURCES.contains(source)) {
            var rows =
                    s.jdbc.queryForList(
                            "SELECT * FROM "
                                    + table
                                    + " WHERE tenant_id=? AND company_id=? AND id=?",
                            a.tenantId(),
                            a.companyId(),
                            journal.get("source_id"));
            if (!rows.isEmpty()) {
                var header = rows.getFirst();
                for (var entry :
                        List.of(
                                Map.entry("receipt_type", "receiptType"),
                                Map.entry("payment_type", "paymentType"),
                                Map.entry("income_expense_item_id", "incomeExpenseItemId"),
                                Map.entry("owner_department_id", "deptId"),
                                Map.entry("sales_user_id", "businessUserId"),
                                Map.entry("buyer_user_id", "businessUserId")))
                    if (header.get(entry.getKey()) != null)
                        context.put(entry.getValue(), header.get(entry.getKey()).toString());
            }
        }
        context.put("partner", context.getOrDefault("partnerId", "0"));
        context.put("sku", context.getOrDefault("skuId", "0"));
        return context;
    }

    Resolved resolve(
            ActorScope a,
            Map<String, Object> journal,
            Map<String, Object> line,
            List<Map<String, Object>> mappings) {
        return resolve(a, journal, line, mappings, false);
    }

    Resolved resolve(
            ActorScope a,
            Map<String, Object> journal,
            Map<String, Object> line,
            List<Map<String, Object>> mappings,
            boolean historical) {
        var context = context(a, journal, line);
        for (var mapping : mappings) {
            if (!Objects.equals(mapping.get("ledger_account_code"), line.get("account_code")))
                continue;
            var conditions = map(mapping.get("condition_json"));
            if (!conditions.entrySet().stream()
                    .allMatch(e -> Objects.equals(context.get(e.getKey()), e.getValue()))) continue;
            long id = number(mapping.get("subject_id"));
            var sub = dimensions.subject(a, id, historical);
            var values = new LinkedHashMap<String, Long>();
            for (var rule : map(mapping.get("aux_rules")).entrySet()) {
                String source = rule.getValue();
                String value = source.matches("[1-9][0-9]{0,18}") ? source : context.get(source);
                if (value == null || !value.matches("[1-9][0-9]{0,18}"))
                    throw invalid("运营分录缺少映射要求的辅助核算：" + dimensions.label(rule.getKey()));
                values.put(rule.getKey(), Long.parseLong(value));
            }
            var aux =
                    new GlModels.Aux(
                            values.getOrDefault("customer", 0L),
                            values.getOrDefault("supplier", 0L),
                            values.getOrDefault("dept", 0L),
                            values.getOrDefault("employee", 0L),
                            values.getOrDefault("project", 0L),
                            values.getOrDefault("sku", 0L),
                            values.getOrDefault("custom", 0L));
            if (!historical) dimensions.validate(a, sub, aux);
            Long cash =
                    mapping.get("cash_flow_item_id") == null
                            ? null
                            : number(mapping.get("cash_flow_item_id"));
            return new Resolved(
                    new GlModels.Line(
                            Objects.toString(line.get("memo"), "业务自动生成"),
                            id,
                            line.get("direction").toString(),
                            line.get("amount").toString(),
                            "0",
                            null,
                            aux,
                            cash),
                    mapping);
        }
        throw new ApiException(
                422,
                "GL_MAPPING_REQUIRED",
                "运营账户“" + line.get("account_code") + "”没有匹配的财务映射，请配置后重新生成");
    }

    private Map<String, String> map(Object value) {
        return value == null
                ? Map.of()
                : s.json.readValue(
                        value.toString(),
                        new tools.jackson.core.type.TypeReference<Map<String, String>>() {});
    }
}
