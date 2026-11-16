package cn.erp.gl;

import java.util.*;

import org.springframework.stereotype.Component;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.Ids;

import static cn.erp.gl.GlSupport.*;

/** 标准模板只读，所有实际配置复制到当前公司后维护。 */
@Component
class GlPresetSeeder {
    private final GlSupport s;

    GlPresetSeeder(GlSupport support) {
        this.s = support;
    }

    @SuppressWarnings("unchecked")
    void seed(CompanyAccess actor, String standard) {
        var presets =
                s.jdbc.queryForList(
                        "SELECT * FROM gl_standard_preset WHERE standard=? ORDER BY"
                                + " kind,LENGTH(code),code",
                        standard);
        if (presets.isEmpty()) throw invalid("会计准则模板不存在");
        var subjects = new LinkedHashMap<String, Long>();
        var cash = new LinkedHashMap<String, Long>();
        for (String type : AUX)
            s.jdbc.update(
                    "INSERT INTO gl_aux_type(id,tenant_id,company_id,code,name,source,built_in)"
                            + " VALUES(?,?,?,?,?,?,1)",
                    Ids.next(),
                    actor.tenantId(),
                    actor.companyId(),
                    type,
                    new GlDimensions(s).label(type),
                    type);
        for (var row : presets)
            if (row.get("kind").equals("subject")) {
                var data = payload(row);
                String code = row.get("code").toString();
                long id = Ids.next();
                Object parentCode = data.get("parentCode");
                Long parent = parentCode == null ? null : subjects.get(parentCode.toString());
                s.jdbc.update(
                        "INSERT INTO"
                            + " gl_subject(id,tenant_id,company_id,code,name,parent_id,level,category,profit_role,direction,is_cash,is_bank,aux_types)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        id,
                        actor.tenantId(),
                        actor.companyId(),
                        code,
                        row.get("name"),
                        parent,
                        data.get("level"),
                        data.get("category"),
                        data.get("profitRole"),
                        data.get("direction"),
                        data.get("isCash"),
                        data.get("isBank"),
                        s.json.writeValueAsString(data.get("auxTypes")));
                subjects.put(code, id);
                if (parent != null)
                    s.jdbc.update(
                            "UPDATE gl_subject SET is_leaf=0 WHERE tenant_id=? AND company_id=? AND"
                                    + " id=?",
                            actor.tenantId(),
                            actor.companyId(),
                            parent);
            }
        for (var row : presets)
            if (row.get("kind").equals("cash_flow")) {
                var data = payload(row);
                long id = Ids.next();
                s.jdbc.update(
                        "INSERT INTO"
                            + " gl_cash_flow_item(id,tenant_id,company_id,code,name,direction,category,sort_no)"
                            + " VALUES(?,?,?,?,?,?,?,?)",
                        id,
                        actor.tenantId(),
                        actor.companyId(),
                        row.get("code"),
                        row.get("name"),
                        data.get("direction"),
                        data.get("category"),
                        data.get("sortNo"));
                cash.put(row.get("code").toString(), id);
            }
        for (String word : List.of("记", "收", "付", "转")) {
            long id = Ids.next();
            s.jdbc.update(
                    "INSERT INTO"
                        + " gl_voucher_word(id,tenant_id,company_id,code,print_title,is_default)"
                        + " VALUES(?,?,?,?,?,?)",
                    id,
                    actor.tenantId(),
                    actor.companyId(),
                    word,
                    Map.of("记", "记账凭证", "收", "收款凭证", "付", "付款凭证", "转", "转账凭证").get(word),
                    word.equals("记"));
        }
        for (var row : presets) {
            String kind = row.get("kind").toString();
            var data = payload(row);
            switch (kind) {
                case "mapping" ->
                        s.jdbc.update(
                                "INSERT INTO"
                                    + " gl_business_mapping(id,tenant_id,company_id,ledger_account_code,subject_id,aux_rules,priority)"
                                    + " VALUES(?,?,?,?,?,?,?)",
                                Ids.next(),
                                actor.tenantId(),
                                actor.companyId(),
                                Objects.toString(
                                        data.get("ledgerCode"), row.get("code").toString()),
                                subjects.get(data.get("subjectCode").toString()),
                                s.json.writeValueAsString(canonicalRules(data.get("auxRules"))),
                                data.get("priority"));
                case "statement" ->
                        s.jdbc.update(
                                "INSERT INTO"
                                    + " gl_report_template(id,tenant_id,company_id,report_type,name,rows_json,checks_json)"
                                    + " VALUES(?,?,?,?,?,?,?)",
                                Ids.next(),
                                actor.tenantId(),
                                actor.companyId(),
                                row.get("code"),
                                row.get("name"),
                                s.json.writeValueAsString(data.get("rows")),
                                s.json.writeValueAsString(data.get("checks")));
                case "closing" ->
                        s.jdbc.update(
                                "INSERT INTO"
                                    + " gl_closing_scheme(id,tenant_id,company_id,code,name,sort_no,source_codes,target_subject_id)"
                                    + " VALUES(?,?,?,?,?,?,?,?)",
                                Ids.next(),
                                actor.tenantId(),
                                actor.companyId(),
                                row.get("code"),
                                row.get("name"),
                                data.get("sortNo"),
                                s.json.writeValueAsString(data.get("sourceCodes")),
                                subjects.get(data.get("targetSubjectCode").toString()));
                case "export" ->
                        s.jdbc.update(
                                "INSERT INTO"
                                    + " gl_export_format(id,tenant_id,company_id,code,name,file_type,columns_json)"
                                    + " VALUES(?,?,?,?,?,?,?)",
                                Ids.next(),
                                actor.tenantId(),
                                actor.companyId(),
                                row.get("code"),
                                row.get("name"),
                                data.get("fileType"),
                                s.json.writeValueAsString(data.get("columns")));
                default -> {}
            }
        }
        seedFunds(actor, subjects, cash);
        for (String digest : List.of("收客户货款", "支付供应商货款", "计提职工薪酬", "计提折旧", "报销费用"))
            s.jdbc.update(
                    "INSERT INTO gl_digest(id,tenant_id,company_id,content) VALUES(?,?,?,?)",
                    Ids.next(),
                    actor.tenantId(),
                    actor.companyId(),
                    digest);
    }

    private void seedFunds(
            CompanyAccess actor, Map<String, Long> subjects, Map<String, Long> cash) {
        var accounts =
                s.jdbc.queryForList(
                        "SELECT id,code,name,account_type FROM cash_account WHERE tenant_id=? AND"
                            + " company_id=? AND currency='CNY' AND status='active' ORDER BY id",
                        actor.tenantId(),
                        actor.companyId());
        int priority = 100000;
        var index = new HashMap<String, Integer>();
        for (var account : accounts) {
            String parentCode = account.get("account_type").equals("cash") ? "1001" : "1002";
            long parent = subjects.get(parentCode);
            int number = index.merge(parentCode, 1, Integer::sum);
            String child = parentCode + String.format(Locale.ROOT, "%03d", number);
            long subject = Ids.next();
            s.jdbc.update(
                    "INSERT INTO"
                        + " gl_subject(id,tenant_id,company_id,code,name,parent_id,level,category,profit_role,direction,is_cash,is_bank,aux_types)"
                        + " VALUES(?,?,?,?,?,?,2,'asset','none','D',?,?,JSON_ARRAY())",
                    subject,
                    actor.tenantId(),
                    actor.companyId(),
                    child,
                    account.get("name"),
                    parent,
                    parentCode.equals("1001"),
                    parentCode.equals("1002"));
            s.jdbc.update(
                    "UPDATE gl_subject SET is_leaf=0 WHERE tenant_id=? AND company_id=? AND id=?",
                    actor.tenantId(),
                    actor.companyId(),
                    parent);
            var conditions =
                    List.of(
                            Map.of("sourceType", "receipt", "receiptType", "customer"),
                            Map.of("sourceType", "receipt", "receiptType", "supplier_refund"),
                            Map.of("sourceType", "receipt", "receiptType", "other_income"),
                            Map.of("sourceType", "payment", "paymentType", "supplier"),
                            Map.of("sourceType", "payment", "paymentType", "other_expense"),
                            Map.of("sourceType", "refund_request"),
                            Map.of("sourceType", "account_transfer"));
            var items = List.of("C01", "C04", "C03", "C04", "C07", "C01", "C07");
            for (int i = 0; i < conditions.size(); i++) {
                var condition = new LinkedHashMap<String, String>(conditions.get(i));
                condition.put("cashAccountId", account.get("id").toString());
                mapping(actor, subject, condition, cash.get(items.get(i)), priority--);
            }
            mapping(
                    actor,
                    subject,
                    Map.of("cashAccountId", account.get("id").toString()),
                    null,
                    priority--);
        }
        for (String kind : List.of("cash", "bank"))
            mapping(
                    actor,
                    subjects.get(kind.equals("cash") ? "1001" : "1002"),
                    Map.of("cashAccountType", kind),
                    null,
                    priority--);
    }

    private void mapping(
            CompanyAccess actor,
            long subject,
            Map<String, String> condition,
            Long cash,
            int priority) {
        s.jdbc.update(
                "INSERT INTO"
                    + " gl_business_mapping(id,tenant_id,company_id,ledger_account_code,condition_json,subject_id,aux_rules,cash_flow_item_id,priority)"
                    + " VALUES(?,?,?,'FUNDS',?,?,JSON_OBJECT(),?,?)",
                Ids.next(),
                actor.tenantId(),
                actor.companyId(),
                s.json.writeValueAsString(condition),
                subject,
                cash,
                priority);
    }

    private Map<String, Object> payload(Map<String, Object> row) {
        return s.json.readValue(
                row.get("payload").toString(),
                new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    private Map<String, String> canonicalRules(Object value) {
        var input =
                s.json.convertValue(
                        value, new tools.jackson.core.type.TypeReference<Map<String, String>>() {});
        var result = new LinkedHashMap<String, String>();
        input.forEach(
                (key, source) ->
                        result.put(
                                key,
                                Map.of("partner", "partnerId", "sku", "skuId")
                                        .getOrDefault(source, source)));
        return result;
    }
}
