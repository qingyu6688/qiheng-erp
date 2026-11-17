package cn.erp.gl;

import java.time.Instant;
import java.util.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.*;

import static cn.erp.gl.GlSupport.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class GlExportService implements FinancialExportPort {
    private final GlSupport s;
    private final GlQueryService queries;

    GlExportService(GlSupport s, GlQueryService queries) {
        this.s = s;
        this.queries = queries;
    }

    public void authorize(ActorScope a, String type) {
        if (!FinancialExportPort.supports(type)) throw invalid("财务导出类型无效");
        s.require(a, "gl_export:create");
        s.require(
                a,
                type.equals("gl-vouchers")
                        ? "gl_voucher:view"
                        : Set.of("gl-balance_sheet", "gl-income_statement", "gl-cash_flow")
                                        .contains(type)
                                ? "gl_report:view"
                                : "gl_ledger:view");
    }

    public Map<String, Object> freeze(ActorScope a, String type, Map<String, Object> input) {
        authorize(a, type);
        if (input == null) throw invalid("请填写财务导出条件");
        var query = new LinkedHashMap<>(input);
        var q = s.json.convertValue(input.get("query"), GlModels.Query.class);
        if (q == null) throw invalid("请选择导出期间");
        queries.range(a, q);
        String fileType = Objects.toString(input.get("fileType"), "csv");
        if (!Set.of("csv", "xlsx").contains(fileType)) throw invalid("导出格式须为CSV或Excel");
        query.put("fileType", fileType);
        if (type.equals("gl-vouchers")) {
            long format = number(input.get("formatId"));
            var row = s.row(a, "gl_export_format", format, false);
            query.put("fileType", row.get("file_type"));
            query.put(
                    "columns", s.json.readValue(row.get("columns_json").toString(), Object.class));
        }
        return query;
    }

    @SuppressWarnings("unchecked")
    public File render(ActorScope actor, String type, Map<String, Object> query) {
        authorize(actor, type);
        var a = (CompanyAccess) actor;
        var q = s.json.convertValue(query.get("query"), GlModels.Query.class);
        var range = queries.range(a, q);
        List<Map<String, Object>> rows;
        List<GlModels.ExportColumn> columns;
        if (type.equals("gl-vouchers")) {
            columns =
                    s.json.convertValue(
                            query.get("columns"),
                            new tools.jackson.core.type.TypeReference<
                                    List<GlModels.ExportColumn>>() {});
            if (columns == null || columns.isEmpty()) throw invalid("导出缺少可信列映射快照");
            rows =
                    s.jdbc.queryForList(
                            "SELECT v.voucher_date date,w.code word,v.number,l.line_no"
                                + " lineNo,l.digest,s.code subjectCode,s.name"
                                + " subjectName,l.direction,l.amount,IF(l.direction='D',l.amount,0)"
                                + " debit,IF(l.direction='C',l.amount,0)"
                                + " credit,l.quantity,l.unit_price unitPrice,l.aux_customer_id"
                                + " customerId,l.aux_supplier_id supplierId,l.aux_dept_id"
                                + " deptId,l.aux_employee_id employeeId,l.aux_project_id"
                                + " projectId,l.aux_sku_id skuId,l.aux_custom_id customId,c.code"
                                + " cashFlowCode,v.source_no sourceNo FROM gl_voucher v JOIN"
                                + " gl_voucher_line l ON l.tenant_id=v.tenant_id AND"
                                + " l.company_id=v.company_id AND l.voucher_id=v.id JOIN gl_subject"
                                + " s ON s.tenant_id=l.tenant_id AND s.company_id=l.company_id AND"
                                + " s.id=l.subject_id JOIN gl_voucher_word w ON"
                                + " w.tenant_id=v.tenant_id AND w.company_id=v.company_id AND"
                                + " w.id=v.word_id LEFT JOIN gl_cash_flow_item c ON"
                                + " c.tenant_id=l.tenant_id AND c.company_id=l.company_id AND"
                                + " c.id=l.cash_flow_item_id WHERE v.tenant_id=? AND v.company_id=?"
                                + " AND v.status IN('posted','reversed') AND v.is_deleted=0 AND"
                                + " v.voucher_date BETWEEN ? AND ? ORDER BY"
                                + " v.voucher_date,v.word_id,v.number,l.line_no LIMIT 10001",
                            a.tenantId(),
                            a.companyId(),
                            range.from(),
                            range.to());
        } else if (Set.of("gl-balance_sheet", "gl-income_statement", "gl-cash_flow")
                .contains(type)) {
            if (!q.periodFrom().equals(q.periodTo())) throw invalid("财务报表每次导出一个期间");
            var statement = queries.statementInternal(a, type.substring(3), q.periodFrom());
            rows =
                    ((List<Map<String, Object>>) statement.get("rows"))
                            .stream()
                                    .map(
                                            row -> {
                                                var out = new LinkedHashMap<String, Object>(row);
                                                out.put("yearNotice", statement.get("yearNotice"));
                                                return (Map<String, Object>) out;
                                            })
                                    .toList();
            columns =
                    List.of(
                            new GlModels.ExportColumn("code", "项目编码"),
                            new GlModels.ExportColumn("name", "项目"),
                            new GlModels.ExportColumn("amount", "本期或期末数"),
                            new GlModels.ExportColumn(
                                    "yearAmount", type.equals("gl-balance_sheet") ? "年初数" : "本年累计"),
                            new GlModels.ExportColumn("yearNotice", "累计说明"),
                            new GlModels.ExportColumn("formula", "取数公式"));
        } else {
            var full =
                    new GlModels.Query(
                            q.periodFrom(),
                            q.periodTo(),
                            q.subjectId(),
                            q.auxType(),
                            q.auxId(),
                            q.dateFrom(),
                            q.dateTo(),
                            q.level(),
                            null,
                            10000);
            rows =
                    (List<Map<String, Object>>)
                            queries.ledger(a, type.substring(3), full).get("items");
            var labels =
                    Map.ofEntries(
                            Map.entry("subjectCode", "科目编码"),
                            Map.entry("subjectName", "科目名称"),
                            Map.entry("period", "期间"),
                            Map.entry("openingDebit", "期初借方"),
                            Map.entry("openingCredit", "期初贷方"),
                            Map.entry("debit", "本期借方"),
                            Map.entry("credit", "本期贷方"),
                            Map.entry("closingDebit", "期末借方"),
                            Map.entry("closingCredit", "期末贷方"),
                            Map.entry("voucherDate", "日期"),
                            Map.entry("digest", "摘要"),
                            Map.entry("number", "凭证号"),
                            Map.entry("balance", "余额"),
                            Map.entry("openingQuantity", "期初数量"),
                            Map.entry("quantityDebit", "借方数量"),
                            Map.entry("quantityCredit", "贷方数量"),
                            Map.entry("closingQuantity", "期末数量"),
                            Map.entry("auxText", "辅助核算"),
                            Map.entry("columns", "分栏金额"));
            columns =
                    rows.isEmpty()
                            ? List.of(new GlModels.ExportColumn("subjectCode", "科目编码"))
                            : rows.getFirst().keySet().stream()
                                    .filter(labels::containsKey)
                                    .map(k -> new GlModels.ExportColumn(k, labels.get(k)))
                                    .toList();
        }
        if (rows.size() > 10000) throw invalid("单次财务导出最多10000行，请缩小期间或科目范围");
        String fileType = Objects.toString(query.get("fileType"), "csv");
        var normalized =
                rows.stream()
                        .map(
                                row -> {
                                    var copy = new LinkedHashMap<>(row);
                                    copy.replaceAll(
                                            (key, value) ->
                                                    value instanceof Map<?, ?>
                                                                    || value
                                                                            instanceof Collection<?>
                                                            ? s.json.writeValueAsString(value)
                                                            : value);
                                    return (Map<String, Object>) copy;
                                })
                        .toList();
        byte[] bytes =
                fileType.equals("xlsx")
                        ? GlTabularFile.xlsx(columns, normalized)
                        : GlTabularFile.csv(columns, normalized);
        return new File(
                type + "." + fileType,
                fileType.equals("xlsx")
                        ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        : "text/csv",
                bytes,
                rows.size(),
                Instant.now().toString());
    }
}
