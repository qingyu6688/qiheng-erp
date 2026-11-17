package cn.erp.gl;

import java.time.LocalDate;
import java.util.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.*;

import static cn.erp.gl.GlSupport.*;

@Service
@Transactional(readOnly = true)
public class GlVoucherService implements WorkflowBusiness {
    private WorkflowPort workflow;

    @org.springframework.beans.factory.annotation.Autowired
    void workflow(WorkflowPort workflow) { this.workflow = workflow; }
    private final GlSupport s;
    private final GlPostingEngine engine;

    GlVoucherService(GlSupport s, GlPostingEngine engine) {
        this.s = s;
        this.engine = engine;
    }

    public List<Map<String, Object>> list(CompanyAccess a, GlModels.VoucherFilter q) {
        s.require(a, "gl_voucher:view");
        var args = new ArrayList<Object>(List.of(a.tenantId(), a.companyId()));
        var where = new StringBuilder("tenant_id=? AND company_id=? AND is_deleted=0");
        if (q.periodId() != null) {
            s.period(a, q.periodId(), false);
            where.append(" AND period_id=?");
            args.add(q.periodId());
        }
        if (q.wordId() != null) {
            s.row(a, "gl_voucher_word", q.wordId(), false);
            where.append(" AND word_id=?");
            args.add(q.wordId());
        }
        if (q.status() != null && !q.status().isBlank()) {
            where.append(" AND status=?");
            args.add(q.status());
        }
        if (q.source() != null && !q.source().isBlank()) {
            where.append(" AND source=?");
            args.add(q.source());
        }
        if (q.cursor() != null) {
            where.append(" AND id<?");
            args.add(q.cursor());
        }
        args.add(q.limit() == null ? 50 : Math.clamp(q.limit(), 1, 200));
        return s
                .jdbc
                .queryForList(
                        "SELECT * FROM gl_voucher WHERE " + where + " ORDER BY id DESC LIMIT ?",
                        args.toArray())
                .stream()
                .map(GlSupport::view)
                .toList();
    }

    public Map<String, Object> get(CompanyAccess a, long id) {
        s.require(a, "gl_voucher:view");
        return detail(a, id);
    }

    Map<String, Object> detail(ActorScope a, long id) {
        var row = s.row(a, "gl_voucher", id, false);
        if (flag(row.get("is_deleted"))) throw missing();
        var result = view(row);
        result.put("lines", engine.rawLines(a, id).stream().map(GlSupport::view).toList());
        return result;
    }

    @Transactional
    public Map<String, Object> save(CompanyAccess a, Long id, String key, GlModels.Voucher input) {
        s.require(a, "gl_voucher:edit");
        if (id != null) s.row(a, "gl_voucher", id, false);
        return s.execute(
                a,
                "gl_voucher.save",
                key,
                Map.of("id", id == null ? 0 : id, "input", input),
                () -> {
                    long period = s.openPeriod(a, input.voucherDate());
                    s.book(a, true);
                    s.active(a);
                    s.lockPosting(a);
                    long target;
                    if (id == null)
                        target =
                                engine.create(
                                        a, input, "manual", null, null, null, null, null, false);
                    else {
                        var before = s.row(a, "gl_voucher", id, true);
                        version(input.version(), before);
                        editable(before);
                        if (number(before.get("period_id")) != period
                                || number(before.get("word_id")) != input.wordId())
                            throw invalid("修改凭证日期不得跨期间，凭证字不能更换，请另建凭证");
                        var lines = engine.validate(a, input.lines(), false, false);
                        if (input.attachmentCount() < 0 || input.attachmentCount() > 9999)
                            throw invalid("附件张数无效");
                        var totals = GlPostingEngine.totals(lines);
                        s.jdbc.update(
                                "DELETE FROM gl_voucher_line WHERE tenant_id=? AND company_id=? AND"
                                        + " voucher_id=?",
                                a.tenantId(),
                                a.companyId(),
                                id);
                        engine.insertLines(a, id, lines);
                        s.jdbc.update(
                                "UPDATE gl_voucher SET"
                                    + " voucher_date=?,attachment_count=?,debit_total=?,credit_total=?,remark=?,version=version+1"
                                    + " WHERE tenant_id=? AND company_id=? AND id=?",
                                input.voucherDate(),
                                input.attachmentCount(),
                                totals[0],
                                totals[1],
                                input.remark(),
                                a.tenantId(),
                                a.companyId(),
                                id);
                        target = id;
                    }
                    var after = detail(a, target);
                    s.audit.record(a, "gl_voucher.save", "gl_voucher", target, null, after);
                    return after;
                });
    }

    @Transactional
    public Map<String, Object> change(
            CompanyAccess a, long id, String action, String key, GlModels.Version input) {
        s.require(a, permission(action));
        s.row(a, "gl_voucher", id, false);
        return s.execute(
                a,
                "gl_voucher." + action,
                key,
                Map.of("id", id, "input", input),
                () -> changeInternal(a, id, action, input));
    }

    private Map<String, Object> changeInternal(
            CompanyAccess a, long id, String action, GlModels.Version input) {
        workflow.guard(a, "gl_voucher", id, action);
        var first = s.row(a, "gl_voucher", id, false);
        s.openPeriod(a, date(first.get("voucher_date")));
        s.book(a, true);
        s.active(a);
        s.lockPosting(a);
        var before = s.row(a, "gl_voucher", id, true);
        version(input.version(), before);
        if (flag(before.get("is_deleted")) || !"manual".equals(before.get("source")))
            throw invalid("业务凭证和结转凭证只读，请在来源业务处理");
        String status = before.get("status").toString();
        String next;
        switch (action) {
            case "submit" -> {
                state(status, "draft");
                engine.validate(a, engine.lines(a, id), before.get("reversal_of_id") != null, true);
                next = "pending";
            }
            case "withdraw" -> {
                state(status, "pending");
                if (number(before.get("maker_id")) != a.userId())
                    throw new ApiException(403, "FORBIDDEN", "只有制单人可以撤回");
                next = "draft";
            }
            case "approve" -> {
                state(status, "pending");
                if (number(before.get("maker_id")) == a.userId())
                    throw new ApiException(422, "TWO_PERSON_REQUIRED", "制单人不能复核自己的凭证");
                next = "approved";
                s.jdbc.update(
                        "UPDATE gl_voucher SET approver_id=? WHERE tenant_id=? AND company_id=? AND"
                                + " id=?",
                        a.userId(),
                        a.tenantId(),
                        a.companyId(),
                        id);
            }
            case "reject" -> {
                state(status, "pending");
                if (number(before.get("maker_id")) == a.userId())
                    throw new ApiException(422, "TWO_PERSON_REQUIRED", "制单人不能复核自己的凭证");
                text(input.reason(), 500, "驳回原因");
                next = "draft";
            }
            case "post" -> {
                state(status, "approved");
                engine.post(a, before, false);
                next = null;
            }
            case "delete" -> {
                state(status, "draft");
                s.jdbc.update(
                        "UPDATE gl_voucher SET is_deleted=1,version=version+1 WHERE tenant_id=? AND"
                                + " company_id=? AND id=?",
                        a.tenantId(),
                        a.companyId(),
                        id);
                next = null;
            }
            default -> throw invalid("凭证操作无效");
        }
        if (next != null)
            s.jdbc.update(
                    "UPDATE gl_voucher SET status=?,version=version+1 WHERE tenant_id=? AND"
                            + " company_id=? AND id=?",
                    next,
                    a.tenantId(),
                    a.companyId(),
                    id);
        if (action.equals("submit")) workflow.start(a, "gl_voucher", id, Map.of());
        var after = view(s.row(a, "gl_voucher", id, false));
        s.audit.record(
                a,
                "gl_voucher." + action,
                "gl_voucher",
                id,
                before,
                Map.of("voucher", after, "reason", Objects.toString(input.reason(), "")));
        return after;
    }

    @Override
    public String businessType() { return "gl_voucher"; }

    @Override
    public Map<String, Object> snapshot(ActorScope scope, long id) {
        var actor = (CompanyAccess) scope;
        var row = get(actor, id);
        String number = Objects.toString(row.get("number"), Long.toString(id));
        return Map.of("documentNo", number, "title", "总账凭证 " + number,
                "createdBy", row.get("makerId"), "totalAmount", Objects.toString(row.get("debitTotal"), "0"));
    }

    @Override
    @Transactional
    public void decide(ActorScope scope, long id, String outcome, String comment, String key) {
        if (!WorkflowContext.allows(scope, businessType(), id))
            throw new ApiException(403, "FORBIDDEN", "业务回调缺少流程上下文");
        var actor = (CompanyAccess) scope;
        var row = s.row(actor, "gl_voucher", id, false);
        change(actor, id, outcome.equals("approved") ? "approve" : "reject", key,
                new GlModels.Version(number(row.get("version")), Objects.toString(comment, "流程未通过")));
    }

    public record Reversal(Long version, LocalDate date, String reason) {}

    @Transactional
    public Map<String, Object> reverse(CompanyAccess a, long id, String key, Reversal input) {
        s.require(a, "gl_voucher:reverse");
        s.row(a, "gl_voucher", id, false);
        return s.execute(
                a,
                "gl_voucher.reverse",
                key,
                Map.of("id", id, "input", input),
                () -> {
                    s.openPeriod(a, input.date());
                    s.book(a, true);
                    s.active(a);
                    s.lockPosting(a);
                    var original = s.row(a, "gl_voucher", id, true);
                    version(input.version(), original);
                    if (!"manual".equals(original.get("source"))
                            || !"posted".equals(original.get("status"))
                            || original.get("reversal_of_id") != null)
                        throw invalid("只能申请冲销未被冲销的手工已记账凭证");
                    if (input.date().isBefore(date(original.get("voucher_date"))))
                        throw invalid("红字日期不能早于原凭证");
                    String reason = text(input.reason(), 500, "冲销原因");
                    var lines =
                            engine.lines(a, id).stream()
                                    .map(
                                            l ->
                                                    new GlModels.Line(
                                                            l.digest(),
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
                                            input.date(),
                                            0,
                                            lines,
                                            reason,
                                            null),
                                    "manual",
                                    null,
                                    null,
                                    null,
                                    id,
                                    null,
                                    true);
                    s.audit.record(
                            a,
                            "gl_voucher.reverse",
                            "gl_voucher",
                            target,
                            Map.of("originalId", Long.toString(id)),
                            detail(a, target));
                    return detail(a, target);
                });
    }

    @Transactional
    public Map<String, Object> batch(
            CompanyAccess a, String action, String key, GlModels.Batch input) {
        if (!Set.of("approve", "post").contains(action)) throw invalid("仅支持批量复核或记账");
        s.require(a, permission(action));
        if (input.vouchers() == null || input.vouchers().isEmpty() || input.vouchers().size() > 100)
            throw invalid("每次选择1至100张凭证");
        for (var item : input.vouchers()) s.row(a, "gl_voucher", item.id(), false);
        return s.execute(
                a,
                "gl_voucher." + action,
                key,
                input,
                () -> {
                    var rows =
                            input.vouchers().stream()
                                    .sorted(Comparator.comparing(GlModels.BatchRow::id))
                                    .toList();
                    for (var item : rows) {
                        var v = s.row(a, "gl_voucher", item.id(), false);
                        s.openPeriod(a, date(v.get("voucher_date")));
                    }
                    s.book(a, true);
                    s.lockPosting(a);
                    var result = new ArrayList<Map<String, Object>>();
                    for (var item : rows)
                        result.add(
                                changeInternal(
                                        a,
                                        item.id(),
                                        action,
                                        new GlModels.Version(item.version(), input.reason())));
                    return Map.of("items", result);
                });
    }

    @Transactional
    public Map<String, Object> organize(CompanyAccess a, long period, long word, String key) {
        s.require(a, "gl_voucher:edit");
        s.period(a, period, false);
        s.row(a, "gl_voucher_word", word, false);
        return s.execute(
                a,
                "gl_voucher.organize",
                key,
                Map.of("periodId", period, "wordId", word),
                () -> {
                    var p = s.period(a, period, true);
                    if (!"open".equals(p.get("status"))) throw invalid("已关闭期间不能整理凭证");
                    s.book(a, true);
                    s.lockPosting(a);
                    var rows =
                            s.jdbc.queryForList(
                                    "SELECT * FROM gl_voucher WHERE tenant_id=? AND company_id=?"
                                        + " AND period_id=? AND word_id=? ORDER BY voucher_date,id"
                                        + " FOR UPDATE",
                                    a.tenantId(),
                                    a.companyId(),
                                    period,
                                    word);
                    long max =
                            rows.stream().mapToLong(r -> number(r.get("number"))).max().orElse(0);
                    var fixed = new HashSet<Long>();
                    var movable = new ArrayList<Map<String, Object>>();
                    for (var r : rows)
                        if (Set.of("posted", "reversed").contains(r.get("status"))
                                || flag(r.get("is_deleted"))) fixed.add(number(r.get("number")));
                        else movable.add(r);
                    for (var r : movable)
                        s.jdbc.update(
                                "UPDATE gl_voucher SET number=? WHERE tenant_id=? AND company_id=?"
                                        + " AND id=?",
                                ++max,
                                a.tenantId(),
                                a.companyId(),
                                r.get("id"));
                    long candidate = 1;
                    var changes = new ArrayList<Map<String, Object>>();
                    for (var r : movable) {
                        while (fixed.contains(candidate)) candidate++;
                        long next = candidate++;
                        s.jdbc.update(
                                "UPDATE gl_voucher SET number=?,version=version+1 WHERE tenant_id=?"
                                        + " AND company_id=? AND id=?",
                                next,
                                a.tenantId(),
                                a.companyId(),
                                r.get("id"));
                        changes.add(
                                Map.of(
                                        "id",
                                        r.get("id").toString(),
                                        "before",
                                        r.get("number"),
                                        "after",
                                        next));
                    }
                    long next =
                            Math.max(
                                    candidate,
                                    fixed.stream().mapToLong(Long::longValue).max().orElse(0) + 1);
                    s.jdbc.update(
                            "UPDATE gl_voucher_seq SET next_no=? WHERE tenant_id=? AND company_id=?"
                                    + " AND period_id=? AND word_id=?",
                            next,
                            a.tenantId(),
                            a.companyId(),
                            period,
                            word);
                    s.audit.record(
                            a, "gl_voucher.organize", "accounting_period", period, null, changes);
                    return Map.of("items", changes);
                });
    }

    static String permission(String action) {
        return switch (action) {
            case "submit", "withdraw", "delete" -> "gl_voucher:edit";
            case "approve", "reject" -> "gl_voucher:approve";
            case "post" -> "gl_voucher:post";
            default -> throw invalid("凭证操作无效");
        };
    }

    private static void editable(Map<String, Object> row) {
        if (!"manual".equals(row.get("source"))
                || !"draft".equals(row.get("status"))
                || row.get("reversal_of_id") != null
                || flag(row.get("is_deleted"))) throw invalid("只能编辑未提交的普通手工凭证");
    }

    private static void state(String actual, String expected) {
        if (!actual.equals(expected))
            throw new ApiException(409, "STATE_CONFLICT", "凭证状态已变化，请刷新后重试");
    }
}
