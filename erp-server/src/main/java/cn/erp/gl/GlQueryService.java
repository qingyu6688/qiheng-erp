package cn.erp.gl;

import java.math.*;
import java.time.LocalDate;
import java.util.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.*;

import static cn.erp.gl.GlSupport.*;
import static cn.erp.gl.GlBalances.decimal;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class GlQueryService {
    private final GlSupport s;
    private final GlBalances balances;
    @org.springframework.beans.factory.annotation.Autowired private GlDimensionViews dimensionViews;

    GlQueryService(GlSupport s, GlBalances balances) {
        this.s = s;
        this.balances = balances;
    }

    record Range(LocalDate from, LocalDate to, List<Map<String, Object>> periods) {}

    Range range(ActorScope a, GlModels.Query q) {
        var book = s.book(a, false);
        if (q.periodFrom() == null || q.periodTo() == null) throw invalid("请选择起止期间");
        var from = s.period(a, q.periodFrom(), false);
        var to = s.period(a, q.periodTo(), false);
        LocalDate begin = date(from.get("start_date")), end = date(to.get("end_date"));
        var first = s.period(a, number(book.get("start_period_id")), false);
        if (begin.isBefore(date(first.get("start_date")))
                || end.isBefore(begin)
                || begin.plusMonths(24).isBefore(end)) throw invalid("查询须在账套启用后，且一次最多24个月");
        if (q.dateFrom() != null) {
            if (q.dateFrom().isBefore(begin) || q.dateFrom().isAfter(end))
                throw invalid("起始日期不在期间范围内");
            begin = q.dateFrom();
        }
        if (q.dateTo() != null) {
            if (q.dateTo().isBefore(begin) || q.dateTo().isAfter(end)) throw invalid("截止日期不在期间范围内");
            end = q.dateTo();
        }
        if (q.subjectId() != null) s.row(a, "gl_subject", q.subjectId(), false);
        if (q.auxType() != null && !q.auxType().isBlank() && !AUX.contains(q.auxType()))
            throw invalid("辅助查询类型无效");
        return new Range(
                begin,
                end,
                s.jdbc.queryForList(
                        "SELECT * FROM accounting_period WHERE tenant_id=? AND company_id=? AND"
                                + " start_date<=? AND end_date>=? ORDER BY start_date",
                        a.tenantId(),
                        a.companyId(),
                        end,
                        begin));
    }

    public Map<String, Object> ledger(CompanyAccess a, String type, GlModels.Query q) {
        s.require(a, "gl_ledger:view");
        if (!Set.of("balance", "general", "detail", "multi_column", "quantity", "auxiliary")
                .contains(type)) throw invalid("账簿类型无效");
        var r = range(a, q);
        var subjects = subjects(a);
        String prefix =
                q.subjectId() == null ? "" : subjects.get(q.subjectId()).get("code").toString();
        if (Set.of("detail", "multi_column").contains(type)) return details(a, type, q, r, prefix);
        var rows = new ArrayList<Map<String, Object>>();
        var periods =
                type.equals("general")
                        ? r.periods()
                        : Collections.singletonList(
                                Map.of("start_date", r.from(), "end_date", r.to(), "period", "合计"));
        for (var period : periods) {
            var snapshot =
                    balances.snapshot(
                            a,
                            date(period.get("start_date")).isBefore(r.from())
                                    ? r.from()
                                    : date(period.get("start_date")),
                            date(period.get("end_date")).isAfter(r.to())
                                    ? r.to()
                                    : date(period.get("end_date")),
                            false);
            var aggregate = new TreeMap<String, Map<String, Object>>();
            var dimensionNames =
                    dimensionViews.load(a, snapshot.stream().map(GlBalances.Balance::aux).toList());
            for (var b : snapshot) {
                var sub = subjects.get(b.subject());
                if (!sub.get("code").toString().startsWith(prefix) || !matches(b.aux(), q))
                    continue;
                if (type.equals("quantity")
                        && Objects.toString(sub.get("quantity_unit"), "").isBlank()) continue;
                var targets =
                        type.equals("balance")
                                ? subjects.values().stream()
                                        .filter(
                                                p ->
                                                        sub.get("code")
                                                                        .toString()
                                                                        .startsWith(
                                                                                p.get("code")
                                                                                        .toString())
                                                                && (q.level() == null
                                                                        || number(p.get("level"))
                                                                                <= q.level())
                                                                && p.get("code")
                                                                        .toString()
                                                                        .startsWith(prefix))
                                        .toList()
                                : List.of(sub);
                for (var target : targets) {
                    String key =
                            target.get("code").toString()
                                    + (Set.of("auxiliary", "quantity").contains(type)
                                            ? ":" + GlDimensions.key(b.subject(), b.aux())
                                            : "");
                    var out =
                            aggregate.computeIfAbsent(
                                    key,
                                    k -> {
                                        var value = new LinkedHashMap<String, Object>();
                                        value.put("subjectId", target.get("id").toString());
                                        value.put("subjectCode", target.get("code"));
                                        value.put("subjectName", target.get("name"));
                                        value.put("period", period.get("period"));
                                        if (Set.of("auxiliary", "quantity").contains(type)) {
                                            value.put("aux", auxView(b.aux()));
                                            value.put(
                                                    "auxText",
                                                    dimensionViews.text(b.aux(), dimensionNames));
                                        }
                                        value.put("unit", target.get("quantity_unit"));
                                        for (String field :
                                                List.of(
                                                        "openingDebit",
                                                        "openingCredit",
                                                        "debit",
                                                        "credit",
                                                        "closingDebit",
                                                        "closingCredit",
                                                        "openingQuantity",
                                                        "quantityDebit",
                                                        "quantityCredit",
                                                        "closingQuantity"))
                                            value.put(field, BigDecimal.ZERO);
                                        return value;
                                    });
                    add(out, "openingDebit", b.opening().max(BigDecimal.ZERO));
                    add(out, "openingCredit", b.opening().negate().max(BigDecimal.ZERO));
                    add(out, "debit", b.debit());
                    add(out, "credit", b.credit());
                    add(out, "closingDebit", b.closing().max(BigDecimal.ZERO));
                    add(out, "closingCredit", b.closing().negate().max(BigDecimal.ZERO));
                    add(out, "openingQuantity", b.quantityOpening());
                    add(out, "quantityDebit", b.quantityDebit());
                    add(out, "quantityCredit", b.quantityCredit());
                    add(
                            out,
                            "closingQuantity",
                            b.quantityOpening()
                                    .add(b.quantityDebit())
                                    .subtract(b.quantityCredit()));
                }
            }
            rows.addAll(aggregate.values().stream().map(GlQueryService::stringAmounts).toList());
        }
        return Map.of(
                "type",
                type,
                "from",
                r.from(),
                "to",
                r.to(),
                "items",
                rows,
                "rowCount",
                rows.size(),
                "asOf",
                java.time.Instant.now().toString());
    }

    private Map<String, Object> details(
            CompanyAccess a, String type, GlModels.Query q, Range r, String prefix) {
        var args =
                new ArrayList<Object>(
                        List.of(a.tenantId(), a.companyId(), r.from(), r.to(), prefix + "%"));
        String extra = "";
        if (q.auxType() != null && !q.auxType().isBlank() && q.auxId() != null) {
            extra = " AND l.aux_" + q.auxType() + "_id=?";
            args.add(q.auxId());
        }
        var all =
                s.jdbc.queryForList(
                        "SELECT"
                            + " l.*,v.voucher_date,v.number,v.word_id,v.source,v.source_type,v.source_id,v.source_no,w.code"
                            + " word,s.code subject_code,s.name subject_name FROM gl_voucher v JOIN"
                            + " gl_voucher_line l ON l.tenant_id=v.tenant_id AND"
                            + " l.company_id=v.company_id AND l.voucher_id=v.id JOIN gl_subject s"
                            + " ON s.tenant_id=l.tenant_id AND s.company_id=l.company_id AND"
                            + " s.id=l.subject_id JOIN gl_voucher_word w ON w.tenant_id=v.tenant_id"
                            + " AND w.company_id=v.company_id AND w.id=v.word_id WHERE"
                            + " v.tenant_id=? AND v.company_id=? AND v.status"
                            + " IN('posted','reversed') AND v.is_deleted=0 AND v.voucher_date"
                            + " BETWEEN ? AND ? AND s.code LIKE ?"
                                + extra
                                + " ORDER BY v.voucher_date,v.word_id,v.number,l.line_no LIMIT"
                                + " 10001",
                        args.toArray());
        if (all.size() > 10000) throw invalid("明细超过10000行，请缩小期间或科目范围");
        new SalesReturnSourceMetadata(s.jdbc).annotate(a,all);
        BigDecimal opening = BigDecimal.ZERO;
        var subjects = subjects(a);
        for (var b : balances.snapshot(a, r.from(), r.to(), false))
            if (subjects.get(b.subject()).get("code").toString().startsWith(prefix)
                    && matches(b.aux(), q)) opening = opening.add(b.opening());
        BigDecimal running = opening;
        var rows = new ArrayList<Map<String, Object>>();
        for (var raw : all) {
            running =
                    running.add(
                            GlDimensions.signed(
                                    raw.get("direction").toString(), decimal(raw, "amount")));
            var row = view(raw);
            row.put("balance", running.toPlainString());
            row.put(
                    "debit",
                    raw.get("direction").equals("D") ? raw.get("amount").toString() : "0.00");
            row.put(
                    "credit",
                    raw.get("direction").equals("C") ? raw.get("amount").toString() : "0.00");
            if (type.equals("multi_column"))
                row.put(
                        "columns",
                        Map.of(
                                raw.get("subject_code").toString(),
                                GlDimensions.signed(
                                                raw.get("direction").toString(),
                                                decimal(raw, "amount"))
                                        .toPlainString()));
            rows.add(row);
        }
        if (type.equals("multi_column")) {
            var grouped = new LinkedHashMap<String, Map<String, Object>>();
            for (var line : rows) {
                String id = line.get("voucherId").toString();
                var row =
                        grouped.computeIfAbsent(
                                id,
                                k -> {
                                    var out = new LinkedHashMap<String, Object>();
                                    for (String field :
                                            List.of(
                                                    "voucherId",
                                                    "voucherDate",
                                                    "word",
                                                    "number",
                                                    "sourceType",
                                                    "sourceId",
                                                    "sourceNo",
                                                    "returnMode",
                                                    "returnSourceId",
                                                    "returnSourceNo",
                                                    "refundMode")) if(line.containsKey(field))out.put(field, line.get(field));
                                    out.put("debit", BigDecimal.ZERO);
                                    out.put("credit", BigDecimal.ZERO);
                                    out.put("columns", new TreeMap<String, BigDecimal>());
                                    return out;
                                });
                add(row, "debit", new BigDecimal(line.get("debit").toString()));
                add(row, "credit", new BigDecimal(line.get("credit").toString()));
                @SuppressWarnings("unchecked")
                var columns = (Map<String, BigDecimal>) row.get("columns");
                columns.merge(
                        line.get("subjectCode").toString(),
                        GlDimensions.signed(
                                line.get("direction").toString(),
                                new BigDecimal(line.get("amount").toString())),
                        BigDecimal::add);
                row.put("balance", line.get("balance"));
            }
            rows.clear();
            for (var raw : grouped.values()) {
                @SuppressWarnings("unchecked")
                var values = (Map<String, BigDecimal>) raw.get("columns");
                var columns = new TreeMap<String, String>();
                values.forEach((code, value) -> columns.put(code, value.toPlainString()));
                raw.put("columns", columns);
                rows.add(stringAmounts(raw));
            }
        }
        if (q.cursor() != null && (q.cursor() < 0 || q.cursor() > 10000))
            throw invalid("明细游标须在0至10000之间");
        int offset = q.cursor() == null ? 0 : Math.toIntExact(q.cursor());
        if (offset < 0 || offset > rows.size()) throw invalid("明细游标无效");
        int size = q.limit() == null ? 100 : Math.clamp(q.limit(), 1, 10000),
                end = Math.min(rows.size(), offset + size);
        return Map.of(
                "type",
                type,
                "opening",
                opening.toPlainString(),
                "closing",
                running.toPlainString(),
                "from",
                r.from(),
                "to",
                r.to(),
                "items",
                rows.subList(offset, end),
                "nextCursor",
                end < rows.size() ? Integer.toString(end) : "",
                "rowCount",
                rows.size());
    }

    public Map<String, Object> statement(CompanyAccess a, String type, long period) {
        s.require(a, "gl_report:view");
        return statementInternal(a, type, period);
    }

    Map<String, Object> statementInternal(ActorScope a, String type, long period) {
        if (!Set.of("balance_sheet", "income_statement", "cash_flow").contains(type))
            throw invalid("财务报表类型无效");
        var p = s.period(a, period, false);
        LocalDate from = date(p.get("start_date")), to = date(p.get("end_date"));
        var book = s.book(a, false);
        var start = s.period(a, number(book.get("start_period_id")), false);
        if (from.isBefore(date(start.get("start_date")))) throw invalid("查询期间早于账套启用期间");
        var templates =
                s.jdbc.queryForList(
                        "SELECT * FROM gl_report_template WHERE tenant_id=? AND company_id=? AND"
                                + " report_type=?",
                        a.tenantId(),
                        a.companyId(),
                        type);
        if (templates.isEmpty()) throw invalid("尚未配置财务报表模板");
        var template = templates.getFirst();
        var rows =
                s.json.readValue(
                        template.get("rows_json").toString(),
                        new tools.jackson.core.type.TypeReference<List<GlModels.FormulaRow>>() {});
        var checks =
                s.json.readValue(
                        template.get("checks_json").toString(),
                        new tools.jackson.core.type.TypeReference<
                                List<GlModels.FormulaCheck>>() {});
        var current = new ReportContext(a, from, to, rows, false);
        LocalDate yearStart = from.withDayOfYear(1);
        var cumulative =
                type.equals("balance_sheet")
                        ? new ReportContext(a, yearStart, to, rows, false, true)
                        : new ReportContext(
                                a,
                                yearStart.isBefore(date(start.get("start_date")))
                                        ? date(start.get("start_date"))
                                        : yearStart,
                                to,
                                rows,
                                true);
        var output = new ArrayList<Map<String, Object>>();
        for (var row : rows) {
            var parsed = GlFormula.parse(row.formula());
            var value = current.row(row.code());
            var item = new LinkedHashMap<String, Object>();
            item.put("code", row.code());
            item.put("name", row.name());
            item.put("amount", amount(value));
            item.put(
                    "yearAmount",
                    type.equals("cash_flow")
                                    && date(start.get("start_date")).getYear() == to.getYear()
                                    && date(start.get("start_date")).getMonthValue() > 1
                            ? null
                            : amount(cumulative.row(row.code())));
            item.put("references", parsed.rowReferences());
            item.put("bold", row.bold());
            item.put("indent", row.indent());
            item.put("subjectCodes", parsed.subjectCodes());
            item.put("formula", row.formula());
            output.add(item);
        }
        var validations = new ArrayList<Map<String, Object>>();
        for (var check : checks) {
            var left = GlFormula.parse(check.left()).evaluate(current);
            var right = GlFormula.parse(check.right()).evaluate(current);
            var delta = left.subtract(right).setScale(2, RoundingMode.HALF_UP);
            validations.add(
                    Map.of(
                            "name",
                            check.name(),
                            "left",
                            amount(left),
                            "right",
                            amount(right),
                            "difference",
                            delta.toPlainString(),
                            "passed",
                            delta.signum() == 0));
        }
        return Map.of(
                "type",
                type,
                "periodId",
                Long.toString(period),
                "name",
                template.get("name"),
                "rows",
                output,
                "checks",
                validations,
                "passed",
                validations.stream().allMatch(c -> Boolean.TRUE.equals(c.get("passed"))),
                "configHash",
                Objects.toString(book.get("confirmed_hash"), "未确认"),
                "currency",
                "CNY",
                "basis",
                book.get("report_basis"),
                "yearNotice",
                type.equals("cash_flow")
                                && date(start.get("start_date")).getYear() == to.getYear()
                                && date(start.get("start_date")).getMonthValue() > 1
                        ? "年中启用尚无启用前现金流分类，本年累计暂不提供；本期现金流仍按完整凭证及余额核对"
                        : "");
    }

    @org.springframework.beans.factory.annotation.Autowired private GlIndicators indicators;

    public Map<String, Object> indicators(CompanyAccess actor, long period) {
        s.require(actor, "gl_report:view");
        return indicators.calculate(
                actor,
                period,
                statementInternal(actor, "income_statement", period),
                statementInternal(actor, "balance_sheet", period));
    }

    private final class ReportContext implements GlFormula.Context {
        private final Map<Long, Map<String, Object>> subjects;
        private final List<GlBalances.Balance> actual;
        private final Map<Long, BigDecimal[]> movement = new HashMap<>(), annual = new HashMap<>();
        private final Map<String, BigDecimal> cash = new HashMap<>(), values = new HashMap<>();
        private final Map<String, GlFormula.Expression> formulas = new LinkedHashMap<>();
        private final Set<String> visiting = new HashSet<>();

        ReportContext(
                ActorScope a,
                LocalDate from,
                LocalDate to,
                List<GlModels.FormulaRow> rows,
                boolean includeOpeningYear) {
            this(a, from, to, rows, includeOpeningYear, false);
        }

        ReportContext(
                ActorScope a,
                java.time.LocalDate from,
                java.time.LocalDate to,
                List<GlModels.FormulaRow> rows,
                boolean includeOpeningYear,
                boolean annualOpening) {
            subjects = subjects(a);
            if (annualOpening) {
                var book = s.book(a, false);
                var first = s.period(a, number(book.get("start_period_id")), false);
                if (date(first.get("start_date")).getYear() == to.getYear())
                    actual =
                            s
                                    .jdbc
                                    .queryForList(
                                            "SELECT * FROM gl_opening_balance WHERE tenant_id=? AND"
                                                    + " company_id=?",
                                            a.tenantId(),
                                            a.companyId())
                                    .stream()
                                    .map(
                                            row ->
                                                    new GlBalances.Balance(
                                                            number(row.get("subject_id")),
                                                            aux(row),
                                                            decimal(row, "annual_opening_signed"),
                                                            BigDecimal.ZERO,
                                                            BigDecimal.ZERO,
                                                            BigDecimal.ZERO,
                                                            BigDecimal.ZERO,
                                                            BigDecimal.ZERO))
                                    .toList();
                else
                    actual =
                            balances.snapshot(
                                    a,
                                    to.withDayOfYear(1),
                                    to.withDayOfYear(1).minusDays(1),
                                    false);
            } else actual = balances.snapshot(a, from, to, false);
            for (var row : rows)
                if (formulas.putIfAbsent(row.code(), GlFormula.parse(row.formula())) != null)
                    throw invalid("报表行编码重复");
            if (annualOpening) return;
            for (var row :
                    s.jdbc.queryForList(
                            "SELECT l.subject_id,SUM(IF(l.direction='D',l.amount,0))"
                                + " d,SUM(IF(l.direction='C',l.amount,0)) c FROM gl_voucher v JOIN"
                                + " gl_voucher_line l ON l.tenant_id=v.tenant_id AND"
                                + " l.company_id=v.company_id AND l.voucher_id=v.id WHERE"
                                + " v.tenant_id=? AND v.company_id=? AND v.status"
                                + " IN('posted','reversed') AND v.is_deleted=0 AND"
                                + " v.source<>'closing' AND v.voucher_date BETWEEN ? AND ? GROUP BY"
                                + " l.subject_id",
                            a.tenantId(),
                            a.companyId(),
                            from,
                            to))
                movement.put(
                        number(row.get("subject_id")),
                        new BigDecimal[] {decimal(row, "d"), decimal(row, "c")});
            LocalDate year = to.withDayOfYear(1);
            for (var row :
                    s.jdbc.queryForList(
                            "SELECT l.subject_id,SUM(IF(l.direction='D',l.amount,0))"
                                + " d,SUM(IF(l.direction='C',l.amount,0)) c FROM gl_voucher v JOIN"
                                + " gl_voucher_line l ON l.tenant_id=v.tenant_id AND"
                                + " l.company_id=v.company_id AND l.voucher_id=v.id WHERE"
                                + " v.tenant_id=? AND v.company_id=? AND v.status"
                                + " IN('posted','reversed') AND v.is_deleted=0 AND"
                                + " v.source<>'closing' AND v.voucher_date BETWEEN ? AND ? GROUP BY"
                                + " l.subject_id",
                            a.tenantId(),
                            a.companyId(),
                            year,
                            to))
                annual.put(
                        number(row.get("subject_id")),
                        new BigDecimal[] {decimal(row, "d"), decimal(row, "c")});
            var book = s.book(a, false);
            var first = s.period(a, number(book.get("start_period_id")), false);
            LocalDate firstDay = date(first.get("start_date"));
            if (firstDay.getYear() == to.getYear())
                for (var row :
                        s.jdbc.queryForList(
                                "SELECT subject_id,SUM(year_debit) d,SUM(year_credit) c FROM"
                                    + " gl_opening_balance WHERE tenant_id=? AND company_id=? GROUP"
                                    + " BY subject_id",
                                a.tenantId(),
                                a.companyId())) {
                    long id = number(row.get("subject_id"));
                    var total =
                            annual.computeIfAbsent(
                                    id, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                    total[0] = total[0].add(decimal(row, "d"));
                    total[1] = total[1].add(decimal(row, "c"));
                    if (includeOpeningYear && !from.isAfter(firstDay)) {
                        var m =
                                movement.computeIfAbsent(
                                        id,
                                        k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                        m[0] = m[0].add(decimal(row, "d"));
                        m[1] = m[1].add(decimal(row, "c"));
                    }
                }
            for (var row :
                    s.jdbc.queryForList(
                            "SELECT c.code,c.direction,SUM(IF(l.direction='D',l.amount,-l.amount))"
                                + " amount FROM gl_voucher v JOIN gl_voucher_line l ON"
                                + " l.tenant_id=v.tenant_id AND l.company_id=v.company_id AND"
                                + " l.voucher_id=v.id JOIN gl_cash_flow_item c ON"
                                + " c.tenant_id=l.tenant_id AND c.company_id=l.company_id AND"
                                + " c.id=l.cash_flow_item_id WHERE v.tenant_id=? AND v.company_id=?"
                                + " AND v.status IN('posted','reversed') AND v.is_deleted=0 AND"
                                + " v.voucher_date BETWEEN ? AND ? GROUP BY c.code,c.direction",
                            a.tenantId(),
                            a.companyId(),
                            from,
                            to))
                cash.put(
                        row.get("code").toString(),
                        row.get("direction").equals("out")
                                ? decimal(row, "amount").negate()
                                : row.get("direction").equals("neutral")
                                        ? BigDecimal.ZERO
                                        : decimal(row, "amount"));
        }

        BigDecimal row(String code) {
            if (values.containsKey(code)) return values.get(code);
            if (!formulas.containsKey(code)) throw invalid("报表引用了不存在的行：" + code);
            if (!visiting.add(code)) throw invalid("报表行存在循环引用");
            var result = formulas.get(code).evaluate(this);
            values.put(code, result);
            visiting.remove(code);
            return result;
        }

        public BigDecimal value(String function, List<String> args) {
            if (function.equals("R")) return row(args.getFirst());
            if (function.equals("XJ")) return cash.getOrDefault(args.getFirst(), BigDecimal.ZERO);
            BigDecimal total = BigDecimal.ZERO;
            if (Set.of("JF", "DF", "LJ", "PL_NET").contains(function)) {
                var source = function.equals("LJ") ? annual : movement;
                for (var entry : source.entrySet()) {
                    var sub = subjects.get(entry.getKey());
                    if (function.equals("PL_NET")) {
                        if (!"current".equals(sub.get("profit_role"))) continue;
                        total = total.add(entry.getValue()[1].subtract(entry.getValue()[0]));
                    } else if (sub.get("code").toString().startsWith(args.getFirst())) {
                        total =
                                total.add(
                                        function.equals("JF")
                                                ? entry.getValue()[0]
                                                : function.equals("DF")
                                                        ? entry.getValue()[1]
                                                        : sub.get("direction").equals("D")
                                                                ? entry.getValue()[0].subtract(
                                                                        entry.getValue()[1])
                                                                : entry.getValue()[1].subtract(
                                                                        entry.getValue()[0]));
                    }
                }
                return total;
            }
            if (Set.of("QC", "QM").contains(function) && args.size() == 2) {
                var grouped = new LinkedHashMap<List<Long>, BigDecimal>();
                for (var balance : actual)
                    if (subjects.get(balance.subject())
                            .get("code")
                            .toString()
                            .startsWith(args.getFirst()))
                        grouped.merge(
                                balance.aux().values(),
                                function.equals("QC") ? balance.opening() : balance.closing(),
                                BigDecimal::add);
                return grouped.values().stream()
                        .map(value -> split(value, args.get(1)))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
            }
            for (var balance : actual) {
                var sub = subjects.get(balance.subject());
                BigDecimal signed =
                        function.equals("QC") || function.equals("CASH_QC")
                                ? balance.opening()
                                : balance.closing();
                if (function.equals("SY")) {
                    if ("income_expense".equals(sub.get("category")))
                        total = total.subtract(signed);
                    continue;
                }
                if (function.startsWith("CASH_")) {
                    if (flag(sub.get("is_cash")) || flag(sub.get("is_bank")))
                        total = total.add(signed);
                    continue;
                }
                if (function.equals("CAT")) {
                    if (!args.getFirst().equals(sub.get("category"))) continue;
                    if (args.size() == 2) total = total.add(split(signed, args.get(1)));
                    else
                        total =
                                total.add(
                                        Set.of("liability", "equity").contains(args.getFirst())
                                                ? signed.negate()
                                                : signed);
                    continue;
                }
                if (!sub.get("code").toString().startsWith(args.getFirst())) continue;
                if (args.size() == 2) total = total.add(split(signed, args.get(1)));
                else {
                    var parent =
                            subjects.values().stream()
                                    .filter(v -> v.get("code").equals(args.getFirst()))
                                    .findFirst()
                                    .orElseThrow(() -> invalid("报表引用了不存在的科目"));
                    total =
                            total.add(
                                    parent.get("direction").equals("D") ? signed : signed.negate());
                }
            }
            return total;
        }
    }

    private Map<Long, Map<String, Object>> subjects(ActorScope a) {
        var result = new LinkedHashMap<Long, Map<String, Object>>();
        for (var row :
                s.jdbc.queryForList(
                        "SELECT * FROM gl_subject WHERE tenant_id=? AND company_id=? ORDER BY code",
                        a.tenantId(),
                        a.companyId())) result.put(number(row.get("id")), row);
        return result;
    }

    private static boolean matches(GlModels.Aux aux, GlModels.Query q) {
        return q.auxType() == null
                || q.auxType().isBlank()
                || q.auxId() == null
                || aux.values().get(AUX.indexOf(q.auxType())).equals(q.auxId());
    }

    private static BigDecimal split(BigDecimal amount, String direction) {
        return Set.of("D", "借方").contains(direction)
                ? amount.max(BigDecimal.ZERO)
                : amount.negate().max(BigDecimal.ZERO);
    }

    private static String amount(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static void add(Map<String, Object> row, String key, BigDecimal value) {
        row.put(key, ((BigDecimal) row.get(key)).add(value));
    }

    private static Map<String, Object> stringAmounts(Map<String, Object> row) {
        row.replaceAll((key, value) -> value instanceof BigDecimal d ? d.toPlainString() : value);
        return row;
    }
}
