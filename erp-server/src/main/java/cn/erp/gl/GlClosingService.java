package cn.erp.gl;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import cn.erp.shared.*;

import static cn.erp.gl.GlSupport.*;

/** 由原期间服务在同一事务内调用，财务关闭与业务关闭不可拆开提交。 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
class GlClosingService implements FinancialPeriodPort {
    public void authorize(ActorScope actor, String action) {
        if (enabled(actor) != null) s.require(actor, "gl_closing:" + action);
    }

    private final GlSupport s;
    private final GlSyncService sync;
    private final GlPostingEngine engine;
    private final GlBalances balances;
    private final GlQueryService queries;

    GlClosingService(
            GlSupport s,
            GlSyncService sync,
            GlPostingEngine engine,
            GlBalances balances,
            GlQueryService queries) {
        this.s = s;
        this.sync = sync;
        this.engine = engine;
        this.balances = balances;
        this.queries = queries;
    }

    private Map<String, Object> enabled(ActorScope a) {
        var book = s.bookOrNull(a);
        return book != null && "active".equals(book.get("status")) ? book : null;
    }

    public void prepare(ActorScope a, long period) {
        if (enabled(a) == null) return;
        s.require(a, "gl_closing:close");
        for (long journal : sync.pending(a, period))
            sync.project(a.tenantId(), a.companyId(), journal);
    }

    public List<Map<String, Object>> checks(ActorScope a, long period) {
        var book = enabled(a);
        if (book == null) return List.of();
        s.require(a, "gl_closing:close");
        var p = s.period(a, period, false);
        var start = s.period(a, number(book.get("start_period_id")), false);
        if (date(p.get("start_date")).isBefore(date(start.get("start_date"))))
            return List.of(check("gl_before_start", false, 1, "不能关闭或重开账套启用前的期间"));
        var result = new ArrayList<Map<String, Object>>();
        result.add(
                check(
                        "gl_config",
                        book.get("confirmed_hash") != null,
                        book.get("confirmed_hash") == null ? 1 : 0,
                        "科目、辅助、映射和报表已经财务确认"));
        long missing = sync.pending(a, period).size();
        result.add(check("gl_business", missing == 0, missing, "业务凭证已经全部生成"));
        long pending =
                s.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM gl_voucher WHERE tenant_id=? AND company_id=? AND"
                                + " period_id=? AND is_deleted=0 AND status NOT"
                                + " IN('posted','reversed')",
                        Long.class,
                        a.tenantId(),
                        a.companyId(),
                        period);
        result.add(check("gl_manual", pending == 0, pending, "本期凭证均已复核记账"));
        var snapshot =
                balances.snapshot(a, date(p.get("start_date")), date(p.get("end_date")), false);
        BigDecimal difference =
                snapshot.stream()
                        .map(GlBalances.Balance::closing)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        result.add(
                check(
                        "gl_trial",
                        difference.signum() == 0,
                        difference.signum() == 0 ? 0 : 1,
                        "总账借贷试算平衡"));
        long unmapped =
                s.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM journal_line l JOIN journal j ON"
                            + " j.tenant_id=l.tenant_id AND j.company_id=l.company_id AND"
                            + " j.id=l.journal_id LEFT JOIN gl_voucher v ON v.tenant_id=j.tenant_id"
                            + " AND v.company_id=j.company_id AND v.journal_id=j.id LEFT JOIN"
                            + " gl_voucher_line g ON g.tenant_id=v.tenant_id AND"
                            + " g.company_id=v.company_id AND g.voucher_id=v.id AND"
                            + " g.journal_line_id=l.id WHERE j.tenant_id=? AND j.company_id=? AND"
                            + " j.period_id=? AND (g.id IS NULL OR ABS(g.amount)<>l.amount)",
                        Long.class,
                        a.tenantId(),
                        a.companyId(),
                        period);
        result.add(check("gl_operational", unmapped == 0, unmapped, "运营分录与业务凭证逐行一致"));
        boolean reports = true;
        try {
            for (String type : List.of("balance_sheet", "income_statement", "cash_flow"))
                if (!Boolean.TRUE.equals(queries.statementInternal(a, type, period).get("passed")))
                    reports = false;
        } catch (ApiException error) {
            reports = false;
        }
        result.add(check("gl_reports", reports, reports ? 0 : 1, "三大报表勾稽通过"));
        return result;
    }

    public void close(ActorScope a, long period) {
        var book = enabled(a);
        if (book == null) return;
        s.require(a, "gl_closing:close");
        s.book(a, true);
        s.active(a);
        s.lockPosting(a);
        var p = s.period(a, period, false);
        if (date(p.get("end_date")).isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))
            throw invalid("期间结束后才能执行财务结账");
        requireChecks(a, period);
        var next =
                s.jdbc.queryForList(
                        "SELECT * FROM accounting_period WHERE tenant_id=? AND company_id=? AND"
                                + " start_date>? ORDER BY start_date LIMIT 1",
                        a.tenantId(),
                        a.companyId(),
                        p.get("end_date"));
        if (next.isEmpty() || !"open".equals(next.getFirst().get("status")))
            throw invalid("请先建立下一个开放期间，以便滚入余额");
        long cycle = Ids.next();
        int sequence =
                s.jdbc.queryForObject(
                        "SELECT COALESCE(MAX(cycle),0)+1 FROM gl_period_close WHERE tenant_id=? AND"
                                + " company_id=? AND period_id=?",
                        Integer.class,
                        a.tenantId(),
                        a.companyId(),
                        period);
        var consumed = new HashSet<Long>();
        var schemes =
                s.jdbc.queryForList(
                        "SELECT * FROM gl_closing_scheme WHERE tenant_id=? AND company_id=? AND"
                                + " enabled=1 ORDER BY sort_no,id",
                        a.tenantId(),
                        a.companyId());
        var subjects =
                s.jdbc.queryForList(
                        "SELECT * FROM gl_subject WHERE tenant_id=? AND company_id=? AND"
                                + " category='income_expense' AND is_leaf=1 ORDER BY code",
                        a.tenantId(),
                        a.companyId());
        for (var scheme : schemes) {
            var codes =
                    s.json.readValue(
                            scheme.get("source_codes").toString(),
                            new tools.jackson.core.type.TypeReference<List<String>>() {});
            var eligible =
                    subjects.stream()
                            .filter(
                                    sub ->
                                            codes.stream()
                                                    .anyMatch(
                                                            code ->
                                                                    code.equals("@current_profit")
                                                                                    && sub.get(
                                                                                                    "profit_role")
                                                                                            .equals(
                                                                                                    "current")
                                                                            || code.equals(
                                                                                            "@prior_profit")
                                                                                    && sub.get(
                                                                                                    "profit_role")
                                                                                            .equals(
                                                                                                    "prior")
                                                                            || sub.get("code")
                                                                                    .toString()
                                                                                    .startsWith(
                                                                                            code)))
                            .map(sub -> number(sub.get("id")))
                            .collect(java.util.stream.Collectors.toSet());
            if (eligible.stream().anyMatch(consumed::contains))
                throw invalid("结转方案重复包含相同损益科目，请调整后重新确认");
            consumed.addAll(eligible);
            var lines = new ArrayList<GlModels.Line>();
            for (var b :
                    balances.snapshot(a, date(p.get("start_date")), date(p.get("end_date")), false))
                if (eligible.contains(b.subject()) && b.closing().signum() != 0)
                    lines.add(
                            new GlModels.Line(
                                    "期末损益结转",
                                    b.subject(),
                                    b.closing().signum() > 0 ? "C" : "D",
                                    b.closing().abs().toPlainString(),
                                    "0",
                                    null,
                                    b.aux(),
                                    null));
            for (int i = 0; i < lines.size(); i += 998) {
                var batch = new ArrayList<>(lines.subList(i, Math.min(i + 998, lines.size())));
                BigDecimal net =
                        batch.stream()
                                .map(
                                        l ->
                                                GlDimensions.signed(
                                                        l.direction(), money(l.amount(), true)))
                                .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (net.signum() != 0)
                    batch.add(
                            new GlModels.Line(
                                    "期末损益结转",
                                    number(scheme.get("target_subject_id")),
                                    net.signum() > 0 ? "C" : "D",
                                    net.abs().toPlainString(),
                                    "0",
                                    null,
                                    GlModels.Aux.empty(),
                                    null));
                long word =
                        s.jdbc.queryForObject(
                                "SELECT id FROM gl_voucher_word WHERE tenant_id=? AND company_id=?"
                                        + " AND is_default=1 AND status='active'",
                                Long.class,
                                a.tenantId(),
                                a.companyId());
                long id =
                        engine.create(
                                a,
                                new GlModels.Voucher(
                                        word, date(p.get("end_date")), 0, batch, "期末自动结转", null),
                                "closing",
                                "gl_period_close",
                                cycle,
                                null,
                                null,
                                cycle,
                                true);
                engine.post(a, s.row(a, "gl_voucher", id, true), true);
            }
        }
        for (var b :
                balances.snapshot(a, date(p.get("start_date")), date(p.get("end_date")), false))
            if (subjects.stream().anyMatch(sub -> number(sub.get("id")) == b.subject())
                    && b.closing().signum() != 0) throw invalid("仍有损益余额未结转，请检查结转方案");
        requireChecks(a, period);
        var reports = new LinkedHashMap<String, Object>();
        for (String type : List.of("balance_sheet", "income_statement", "cash_flow"))
            reports.put(type, queries.statementInternal(a, type, period));
        s.jdbc.update(
                "INSERT INTO"
                    + " gl_period_close(id,tenant_id,company_id,period_id,cycle,status,config_hash,reports_snapshot,closed_by,closed_at)"
                    + " VALUES(?,?,?,?,?,'closed',?,?,?,UTC_TIMESTAMP(3))",
                cycle,
                a.tenantId(),
                a.companyId(),
                period,
                sequence,
                book.get("confirmed_hash"),
                s.json.writeValueAsString(reports),
                a.userId());
        balances.refresh(a, number(next.getFirst().get("id")));
        s.audit.record(a, "gl_closing.close", "gl_period_close", cycle, null, reports);
    }

    public void reopen(ActorScope a, long period, String reason) {
        var book = enabled(a);
        if (book == null) return;
        s.require(a, "gl_closing:reopen");
        var p = s.period(a, period, false);
        var start = s.period(a, number(book.get("start_period_id")), false);
        if (date(p.get("start_date")).isBefore(date(start.get("start_date"))))
            throw invalid("不能重开已纳入账套期初的历史期间");
        s.book(a, true);
        s.active(a);
        s.lockPosting(a);
        var cycles =
                s.jdbc.queryForList(
                        "SELECT * FROM gl_period_close WHERE tenant_id=? AND company_id=? AND"
                                + " period_id=? AND status='closed' ORDER BY cycle DESC LIMIT 1 FOR"
                                + " UPDATE",
                        a.tenantId(),
                        a.companyId(),
                        period);
        if (cycles.isEmpty()) throw invalid("当前期间没有对应的财务关闭记录");
        long cycle = number(cycles.getFirst().get("id"));
        var originals =
                s.jdbc.queryForList(
                        "SELECT * FROM gl_voucher WHERE tenant_id=? AND company_id=? AND"
                            + " closing_cycle_id=? AND status='posted' AND reversal_of_id IS NULL"
                            + " ORDER BY id FOR UPDATE",
                        a.tenantId(),
                        a.companyId(),
                        cycle);
        for (var original : originals) {
            long id = number(original.get("id"));
            var lines =
                    engine.lines(a, id).stream()
                            .map(
                                    l ->
                                            new GlModels.Line(
                                                    "反结账：" + l.digest(),
                                                    l.subjectId(),
                                                    l.direction(),
                                                    money(l.amount(), true)
                                                            .negate()
                                                            .toPlainString(),
                                                    decimal(l.quantity(), 6, true)
                                                            .negate()
                                                            .toPlainString(),
                                                    l.unitPrice(),
                                                    l.aux(),
                                                    l.cashFlowItemId()))
                            .toList();
            long target =
                    engine.create(
                            a,
                            new GlModels.Voucher(
                                    number(original.get("word_id")),
                                    date(p.get("end_date")),
                                    0,
                                    lines,
                                    reason,
                                    null),
                            "closing",
                            "gl_period_reopen",
                            cycle,
                            null,
                            id,
                            cycle,
                            true);
            engine.post(a, s.row(a, "gl_voucher", target, true), true);
        }
        s.jdbc.update(
                "UPDATE gl_period_close SET"
                    + " status='reopened',reopened_by=?,reopened_at=UTC_TIMESTAMP(3),reopen_reason=?,version=version+1"
                    + " WHERE tenant_id=? AND company_id=? AND id=?",
                a.userId(),
                text(reason, 200, "重开原因"),
                a.tenantId(),
                a.companyId(),
                cycle);
        s.audit.record(
                a,
                "gl_closing.reopen",
                "gl_period_close",
                cycle,
                cycles.getFirst(),
                Map.of("reason", reason));
    }

    private void requireChecks(ActorScope a, long period) {
        var checks = checks(a, period);
        if (checks.stream().anyMatch(c -> !Boolean.TRUE.equals(c.get("passed"))))
            throw new ApiException(422, "GL_CLOSE_CHECK_FAILED", "财务结账检查未通过", checks);
    }

    private static Map<String, Object> check(
            String key, boolean passed, long count, String message) {
        return Map.of("check", key, "passed", passed, "count", count, "message", message);
    }
}
