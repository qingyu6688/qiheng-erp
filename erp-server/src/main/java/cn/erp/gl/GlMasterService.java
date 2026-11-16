package cn.erp.gl;

import java.util.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.*;

import static cn.erp.gl.GlSupport.*;

/** 所有可编辑财务配置属于公司；写入时锁住账套并使原财务确认失效。 */
@Service
@Transactional(readOnly = true)
public class GlMasterService {
    private final GlSupport s;
    private final GlDimensions dimensions;
    private final GlPostingEngine engine;
    private static final Map<String, String> TABLES =
            Map.ofEntries(
                    Map.entry("subjects", "gl_subject"),
                    Map.entry("aux-types", "gl_aux_type"),
                    Map.entry("aux-items", "gl_aux_item"),
                    Map.entry("cash-flow-items", "gl_cash_flow_item"),
                    Map.entry("mappings", "gl_business_mapping"),
                    Map.entry("words", "gl_voucher_word"),
                    Map.entry("digests", "gl_digest"),
                    Map.entry("voucher-templates", "gl_voucher_template"),
                    Map.entry("closing-schemes", "gl_closing_scheme"),
                    Map.entry("report-templates", "gl_report_template"),
                    Map.entry("export-formats", "gl_export_format"));

    GlMasterService(GlSupport s, GlDimensions dimensions, GlPostingEngine engine) {
        this.s = s;
        this.dimensions = dimensions;
        this.engine = engine;
    }

    public List<Map<String, Object>> list(CompanyAccess actor, String kind) {
        var delegated =
                switch (kind) {
                    case "subjects" ->
                            List.of("gl_voucher:view", "gl_ledger:view", "gl_report:view");
                    case "words", "digests", "voucher-templates", "cash-flow-items" ->
                            List.of("gl_voucher:view");
                    case "export-formats" -> List.of("gl_export:create");
                    default -> List.<String>of();
                };
        String permission =
                actor.hasPermission("gl_config:view")
                        ? "gl_config:view"
                        : delegated.stream()
                                .filter(actor::hasPermission)
                                .findFirst()
                                .orElse("gl_config:view");
        s.require(actor, permission);
        return s
                .jdbc
                .queryForList(
                        "SELECT * FROM "
                                + table(kind)
                                + " WHERE tenant_id=? AND company_id=? ORDER BY id LIMIT 3000",
                        actor.tenantId(),
                        actor.companyId())
                .stream()
                .map(GlSupport::view)
                .toList();
    }

    @Transactional
    public Map<String, Object> save(
            CompanyAccess actor, String kind, Long id, String key, Map<String, Object> input) {
        s.require(actor, "gl_config:edit");
        String table = table(kind);
        if (id != null) s.row(actor, table, id, false);
        return s.execute(
                actor,
                "gl_config.save",
                key,
                Map.of("kind", kind, "id", id == null ? 0 : id, "input", input),
                () -> {
                    s.book(actor, true);
                    Map<String, Object> before = id == null ? null : s.row(actor, table, id, true);
                    if (before != null) version(number(input.get("version")), before);
                    var fields = fields(actor, kind, input, before);
                    long target = id == null ? Ids.next() : id;
                    if (id == null) {
                        var values =
                                new ArrayList<Object>(
                                        List.of(target, actor.tenantId(), actor.companyId()));
                        values.addAll(fields.values());
                        s.jdbc.update(
                                "INSERT INTO "
                                        + table
                                        + "(id,tenant_id,company_id,"
                                        + String.join(",", fields.keySet())
                                        + ") VALUES("
                                        + String.join(",", Collections.nCopies(values.size(), "?"))
                                        + ")",
                                values.toArray());
                    } else {
                        var values = new ArrayList<>(fields.values());
                        values.addAll(List.of(actor.tenantId(), actor.companyId(), id));
                        s.jdbc.update(
                                "UPDATE "
                                        + table
                                        + " SET "
                                        + String.join(
                                                ",",
                                                fields.keySet().stream()
                                                        .map(f -> f + "=?")
                                                        .toList())
                                        + ",version=version+1 WHERE tenant_id=? AND company_id=?"
                                        + " AND id=?",
                                values.toArray());
                    }
                    if (kind.equals("subjects")
                            && before != null
                            && before.get("parent_id") != null
                            && !Objects.equals(before.get("parent_id"), fields.get("parent_id"))) {
                        long previous = number(before.get("parent_id"));
                        long children =
                                s.jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM gl_subject WHERE tenant_id=? AND"
                                            + " company_id=? AND parent_id=?",
                                        Long.class,
                                        actor.tenantId(),
                                        actor.companyId(),
                                        previous);
                        s.jdbc.update(
                                "UPDATE gl_subject SET is_leaf=? WHERE tenant_id=? AND company_id=?"
                                    + " AND id=?",
                                children == 0,
                                actor.tenantId(),
                                actor.companyId(),
                                previous);
                    }
                    s.configurationChanged(actor);
                    var after = s.row(actor, table, target, false);
                    s.audit.record(actor, "gl_config.save", table, target, before, after);
                    return view(after);
                });
    }

    private LinkedHashMap<String, Object> fields(
            CompanyAccess a, String kind, Map<String, Object> in, Map<String, Object> before) {
        var f = new LinkedHashMap<String, Object>();
        String status = Objects.toString(in.get("status"), "active");
        if (!Set.of("active", "disabled").contains(status)) throw invalid("状态无效");
        switch (kind) {
            case "subjects" -> subject(a, in, before, f);
            case "aux-types" -> {
                f.put("code", code(str(in, "code")));
                f.put("name", text(str(in, "name"), 100, "辅助类型名称"));
                String source = str(in, "source");
                if (before != null
                        && flag(before.get("built_in"))
                        && !Objects.equals(source, before.get("source")))
                    throw invalid("内置辅助类型不能改变来源");
                if (!Set.of("project", "custom").contains(source)
                        && (before == null || !flag(before.get("built_in"))))
                    throw invalid("新增辅助类型仅支持项目或自定义");
                f.put("source", source);
                f.put("status", status);
            }
            case "aux-items" -> {
                long type = number(in.get("auxTypeId"));
                var t = s.row(a, "gl_aux_type", type, false);
                if (!Set.of("project", "custom").contains(t.get("source")))
                    throw invalid("基础档案辅助项目请在原模块维护");
                f.put("aux_type_id", type);
                f.put("code", code(str(in, "code")));
                f.put("name", text(str(in, "name"), 100, "辅助项目名称"));
                f.put("status", status);
            }
            case "cash-flow-items" -> {
                f.put("code", formulaCode(str(in, "code")));
                f.put("name", text(str(in, "name"), 100, "现金流项目名称"));
                f.put("direction", choice(in, "direction", Set.of("in", "out", "neutral")));
                f.put(
                        "category",
                        choice(
                                in,
                                "category",
                                Set.of("operating", "investing", "financing", "internal")));
                f.put("sort_no", number(in.get("sortNo")));
                f.put("status", status);
                if (before != null
                        && used(a, "cash_flow_item_id", number(before.get("id")))
                        && (!Objects.equals(before.get("direction"), f.get("direction"))
                                || !Objects.equals(before.get("category"), f.get("category"))
                                || !Objects.equals(before.get("code"), f.get("code"))))
                    throw invalid("已使用现金流项目不能改变编码、方向或分类");
            }
            case "words" -> {
                if (before != null
                        && !Objects.equals(before.get("code"), str(in, "code"))
                        && used(a, "word_id", number(before.get("id"))))
                    throw invalid("已有凭证的凭证字不能更换编码");
                f.put("code", text(str(in, "code"), 8, "凭证字"));
                f.put("print_title", text(str(in, "printTitle"), 100, "打印标题"));
                f.put("is_default", Boolean.TRUE.equals(in.get("isDefault")));
                f.put("status", status);
                if (Boolean.TRUE.equals(in.get("isDefault")))
                    s.jdbc.update(
                            "UPDATE gl_voucher_word SET is_default=0 WHERE tenant_id=? AND"
                                    + " company_id=?",
                            a.tenantId(),
                            a.companyId());
            }
            case "digests" -> {
                f.put("content", text(str(in, "content"), 200, "摘要"));
                f.put("status", status);
            }
            case "mappings" -> mapping(a, in, f);
            case "voucher-templates" -> {
                f.put("code", code(str(in, "code")));
                f.put("name", text(str(in, "name"), 100, "模板名称"));
                long word = number(in.get("wordId"));
                s.row(a, "gl_voucher_word", word, false);
                f.put("word_id", word);
                var voucher = s.json.convertValue(in, GlModels.VoucherTemplate.class);
                if (voucher.lines() == null || voucher.lines().size() > 1000)
                    throw invalid("凭证模板分录无效");
                var lines = engine.validate(a, voucher.lines(), false, false);
                f.put(
                        "lines_json",
                        s.json.writeValueAsString(
                                lines.stream().map(GlSupport::lineView).toList()));
                f.put("status", status);
            }
            case "closing-schemes" -> {
                f.put("code", code(str(in, "code")));
                f.put("name", text(str(in, "name"), 100, "结转方案名称"));
                f.put("sort_no", number(in.get("sortNo")));
                var codes = strings(in.get("sourceCodes"));
                if (codes.isEmpty() || codes.size() > 300) throw invalid("请选择结转来源科目");
                for (String c : codes)
                    if (!Set.of("@current_profit", "@prior_profit").contains(c)
                            && s.jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM gl_subject WHERE tenant_id=? AND"
                                                    + " company_id=? AND code=? AND"
                                                    + " category='income_expense'",
                                            Long.class,
                                            a.tenantId(),
                                            a.companyId(),
                                            c)
                                    != 1) throw invalid("结转来源须为损益科目");
                long target = number(in.get("targetSubjectId"));
                var sub = dimensions.subject(a, target, false);
                if (!dimensions.required(sub).isEmpty() || !"equity".equals(sub.get("category")))
                    throw invalid("结转目标须为不带辅助核算的权益末级科目");
                f.put("source_codes", s.json.writeValueAsString(codes));
                f.put("target_subject_id", target);
                f.put("enabled", !Boolean.FALSE.equals(in.get("enabled")));
            }
            case "report-templates" -> report(a, in, before, f);
            case "export-formats" -> {
                f.put("code", code(str(in, "code")));
                f.put("name", text(str(in, "name"), 100, "导出格式名称"));
                f.put("file_type", choice(in, "fileType", Set.of("csv", "xlsx")));
                var spec = s.json.convertValue(in, GlModels.ExportFormat.class);
                if (spec.columns() == null
                        || spec.columns().isEmpty()
                        || spec.columns().size() > 40) throw invalid("导出列数须为1至40列");
                var seen = new HashSet<String>();
                for (var column : spec.columns()) {
                    if (!Set.of(
                                            "date",
                                            "word",
                                            "number",
                                            "lineNo",
                                            "digest",
                                            "subjectCode",
                                            "subjectName",
                                            "direction",
                                            "amount",
                                            "debit",
                                            "credit",
                                            "quantity",
                                            "unitPrice",
                                            "customerId",
                                            "supplierId",
                                            "deptId",
                                            "employeeId",
                                            "projectId",
                                            "skuId",
                                            "customId",
                                            "cashFlowCode",
                                            "sourceNo")
                                    .contains(column.field())
                            || !seen.add(column.field())) throw invalid("导出列无效或重复");
                    text(column.label(), 100, "导出列标题");
                }
                f.put("columns_json", s.json.writeValueAsString(spec.columns()));
            }
            default -> throw invalid("财务配置类型无效");
        }
        return f;
    }

    private void subject(
            CompanyAccess a,
            Map<String, Object> in,
            Map<String, Object> before,
            Map<String, Object> f) {
        String code = str(in, "code");
        if (!code.matches("[0-9]{4,32}")) throw invalid("科目编码须为4至32位数字");
        String category =
                choice(
                        in,
                        "category",
                        Set.of("asset", "liability", "equity", "cost", "income_expense", "common"));
        String direction = choice(in, "direction", Set.of("D", "C"));
        long parent = number(in.get("parentId"));
        int level = 1;
        if (parent > 0) {
            var p = s.row(a, "gl_subject", parent, true);
            if (before != null && number(before.get("id")) == parent) throw invalid("科目不能作为自身上级");
            if (!code.startsWith(p.get("code").toString())
                    || code.length() <= p.get("code").toString().length()
                    || !category.equals(p.get("category"))
                    || !direction.equals(p.get("direction"))) throw invalid("下级科目须沿用上级编码前缀、类别和方向");
            level = (int) number(p.get("level")) + 1;
            if (level > 10) throw invalid("科目最多十级");
            if (used(a, "subject_id", parent)
                    || s.jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM gl_opening_balance WHERE tenant_id=? AND"
                                            + " company_id=? AND subject_id=?",
                                    Long.class,
                                    a.tenantId(),
                                    a.companyId(),
                                    parent)
                            > 0) throw invalid("已有分录或期初的科目不能增加下级");
            s.jdbc.update(
                    "UPDATE gl_subject SET is_leaf=0 WHERE tenant_id=? AND company_id=? AND id=?",
                    a.tenantId(),
                    a.companyId(),
                    parent);
        }
        var aux = strings(in.get("auxTypes"));
        if (new HashSet<>(aux).size() != aux.size() || !AUX.containsAll(aux))
            throw invalid("辅助核算类型无效");
        long custom = number(in.get("customAuxTypeId"));
        if (aux.contains("custom")) {
            var type = s.row(a, "gl_aux_type", custom, false);
            if (!"custom".equals(type.get("source"))) throw invalid("请选择自定义辅助类型");
        } else if (custom != 0) throw invalid("未启用自定义辅助核算");
        f.put("code", code);
        f.put("name", text(str(in, "name"), 100, "科目名称"));
        f.put("parent_id", parent == 0 ? null : parent);
        f.put("level", level);
        f.put("category", category);
        f.put("direction", direction);
        f.put("is_cash", Boolean.TRUE.equals(in.get("isCash")));
        f.put("is_bank", Boolean.TRUE.equals(in.get("isBank")));
        if (Boolean.TRUE.equals(f.get("is_cash")) && Boolean.TRUE.equals(f.get("is_bank")))
            throw invalid("科目不能同时属于现金和银行");
        f.put("aux_types", s.json.writeValueAsString(aux));
        f.put("custom_aux_type_id", custom == 0 ? null : custom);
        f.put("quantity_unit", Objects.toString(in.get("quantityUnit"), ""));
        f.put(
                "profit_role",
                category.equals("income_expense")
                        ? Objects.toString(in.get("profitRole"), "current")
                        : "none");
        if (!Set.of("none", "current", "prior").contains(f.get("profit_role")))
            throw invalid("损益归属无效");
        f.put("status", Objects.toString(in.get("status"), "active"));
        if (before != null) {
            long id = number(before.get("id"));
            boolean children =
                    s.jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM gl_subject WHERE tenant_id=? AND"
                                            + " company_id=? AND parent_id=?",
                                    Long.class,
                                    a.tenantId(),
                                    a.companyId(),
                                    id)
                            > 0;
            boolean hasFacts =
                    used(a, "subject_id", id)
                            || s.jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM gl_opening_balance WHERE"
                                                + " tenant_id=? AND company_id=? AND subject_id=?",
                                            Long.class,
                                            a.tenantId(),
                                            a.companyId(),
                                            id)
                                    > 0;
            for (String field :
                    List.of(
                            "code",
                            "parent_id",
                            "category",
                            "direction",
                            "is_cash",
                            "is_bank",
                            "aux_types",
                            "custom_aux_type_id",
                            "quantity_unit",
                            "profit_role"))
                if ((children || hasFacts)
                        && !Objects.toString(before.get(field), "")
                                .equals(Objects.toString(f.get(field), ""))
                        && !(Set.of("is_cash", "is_bank").contains(field)
                                && flag(before.get(field)) == Boolean.TRUE.equals(f.get(field))))
                    throw invalid("已使用或包含下级的科目不能改变核算结构");
        }
    }

    private void mapping(CompanyAccess a, Map<String, Object> in, Map<String, Object> f) {
        String ledger = str(in, "ledgerAccountCode");
        if (s.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM ledger_account WHERE code=?", Long.class, ledger)
                != 1) throw invalid("运营账户不存在");
        long id = number(in.get("subjectId"));
        var sub = dimensions.subject(a, id, false);
        var cond = stringMap(in.get("condition"));
        if (!Set.of(
                        "sourceType",
                        "cashAccountId",
                        "cashAccountType",
                        "receiptType",
                        "paymentType",
                        "partnerId",
                        "skuId",
                        "warehouseId")
                .containsAll(cond.keySet())) throw invalid("映射条件包含不支持的字段");
        for (String field : List.of("cashAccountId", "partnerId", "skuId", "warehouseId"))
            if (cond.containsKey(field) && !cond.get(field).matches("[1-9][0-9]{0,18}"))
                throw invalid("映射条件编号无效");
        if (cond.containsKey("cashAccountId")
                && s.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM cash_account WHERE tenant_id=? AND"
                                        + " company_id=? AND id=?",
                                Long.class,
                                a.tenantId(),
                                a.companyId(),
                                Long.parseLong(cond.get("cashAccountId")))
                        != 1) throw missing();
        var rules = stringMap(in.get("auxRules"));
        if (!new HashSet<>(dimensions.required(sub)).equals(rules.keySet()))
            throw invalid("映射辅助规则须与科目核算维度一致");
        for (var e : rules.entrySet())
            if (!Set.of("partnerId", "deptId", "businessUserId", "skuId").contains(e.getValue())
                    && !e.getValue().matches("[1-9][0-9]{0,18}"))
                throw invalid("辅助映射仅支持明确来源字段或固定项目编号");
        Long cash = in.get("cashFlowItemId") == null ? null : number(in.get("cashFlowItemId"));
        dimensions.cashFlow(a, sub, cash, false);
        f.put("ledger_account_code", ledger);
        f.put("condition_json", s.json.writeValueAsString(cond));
        f.put("subject_id", id);
        f.put("aux_rules", s.json.writeValueAsString(rules));
        f.put("cash_flow_item_id", cash);
        f.put("priority", number(in.get("priority")));
        f.put("enabled", !Boolean.FALSE.equals(in.get("enabled")));
    }

    private void report(
            CompanyAccess a,
            Map<String, Object> in,
            Map<String, Object> before,
            Map<String, Object> f) {
        var report = s.json.convertValue(in, GlModels.Report.class);
        if (report.rows() == null
                || report.rows().isEmpty()
                || report.rows().size() > 100
                || report.checks() == null
                || report.checks().size() > 20) throw invalid("报表须包含1至100行，校验最多20项");
        var expressions = new LinkedHashMap<String, GlFormula.Expression>();
        for (var row : report.rows()) {
            String code = formulaCode(row.code());
            if (expressions.putIfAbsent(code, GlFormula.parse(row.formula())) != null)
                throw invalid("报表行编码不能重复");
            text(row.name(), 100, "报表行名称");
            if (row.indent() < 0 || row.indent() > 5) throw invalid("报表缩进无效");
        }
        for (var expression : expressions.values())
            validateExpression(a, expression, expressions.keySet());
        for (String code : expressions.keySet())
            visit(code, expressions, new HashSet<>(), new HashSet<>());
        for (var check : report.checks()) {
            text(check.name(), 100, "校验名称");
            validateExpression(a, GlFormula.parse(check.left()), expressions.keySet());
            validateExpression(a, GlFormula.parse(check.right()), expressions.keySet());
        }
        if (before == null)
            f.put(
                    "report_type",
                    choice(
                            in,
                            "reportType",
                            Set.of("balance_sheet", "income_statement", "cash_flow")));
        f.put("name", text(report.name(), 100, "报表名称"));
        f.put("rows_json", s.json.writeValueAsString(report.rows()));
        f.put("checks_json", s.json.writeValueAsString(report.checks()));
    }

    private void validateExpression(CompanyAccess a, GlFormula.Expression ex, Set<String> rows) {
        if (!rows.containsAll(ex.rowReferences())) throw invalid("报表引用了不存在的行");
        for (String code : ex.subjectCodes())
            if (s.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM gl_subject WHERE tenant_id=? AND company_id=? AND"
                                    + " code=?",
                            Long.class,
                            a.tenantId(),
                            a.companyId(),
                            code)
                    != 1) throw invalid("公式引用了不存在的科目：" + code);
    }

    private void visit(
            String code,
            Map<String, GlFormula.Expression> all,
            Set<String> visiting,
            Set<String> done) {
        if (done.contains(code)) return;
        if (!visiting.add(code)) throw invalid("报表行存在循环引用");
        for (String child : all.get(code).rowReferences()) visit(child, all, visiting, done);
        visiting.remove(code);
        done.add(code);
    }

    private boolean used(ActorScope a, String column, long id) {
        return s.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM "
                                + (column.equals("word_id") ? "gl_voucher" : "gl_voucher_line")
                                + " WHERE tenant_id=? AND company_id=?"
                                + " AND "
                                + column
                                + "=?",
                        Long.class,
                        a.tenantId(),
                        a.companyId(),
                        id)
                > 0;
    }

    private static String table(String kind) {
        String t = TABLES.get(kind);
        if (t == null) throw invalid("财务配置类型无效");
        return t;
    }

    private static String str(Map<String, Object> in, String key) {
        return Objects.toString(in.get(key), "");
    }

    private static String choice(Map<String, Object> in, String key, Set<String> allowed) {
        String value = str(in, key);
        if (!allowed.contains(value)) throw invalid("财务配置选项无效：" + key);
        return value;
    }

    private static String formulaCode(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]{1,40}"))
            throw invalid("公式引用编码限字母、数字或下划线");
        return value;
    }

    private List<String> strings(Object value) {
        if (value == null) return List.of();
        return s.json.convertValue(
                value, new tools.jackson.core.type.TypeReference<List<String>>() {});
    }

    private Map<String, String> stringMap(Object value) {
        if (value == null) return Map.of();
        return s.json.convertValue(
                value, new tools.jackson.core.type.TypeReference<Map<String, String>>() {});
    }
}
