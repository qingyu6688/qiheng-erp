package cn.erp.gl;

import java.math.BigDecimal;
import java.util.*;

import org.springframework.stereotype.Component;

import cn.erp.shared.*;

import static cn.erp.gl.GlSupport.*;

@Component
class GlPostingEngine {
    private final GlSupport s;
    private final GlDimensions dimensions;
    private final GlBalances balances;

    GlPostingEngine(GlSupport s, GlDimensions dimensions, GlBalances balances) {
        this.s = s;
        this.dimensions = dimensions;
        this.balances = balances;
    }

    List<GlModels.Line> validate(
            ActorScope a, List<GlModels.Line> lines, boolean historical, boolean balanced) {
        if (lines == null
                || lines.size() < 2
                || lines.size() > 1000
                || lines.stream().anyMatch(Objects::isNull)) throw invalid("凭证须有2至1000条分录");
        BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
        var result = new ArrayList<GlModels.Line>();
        for (var line : lines) {
            if (line.subjectId() == null
                    || !Set.of("D", "C").contains(Objects.toString(line.direction(), "")))
                throw invalid("请填写科目和借贷方向");
            var sub = dimensions.subject(a, line.subjectId(), historical);
            var aux =
                    historical
                            ? (line.aux() == null ? GlModels.Aux.empty() : line.aux())
                            : dimensions.validate(a, sub, line.aux());
            dimensions.cashFlow(a, sub, line.cashFlowItemId(), historical);
            var amount = money(line.amount(), true);
            if (amount.signum() == 0) throw invalid("分录金额不能为零");
            var quantity = decimal(line.quantity(), 6, true);
            if (quantity.signum() != 0 && Objects.toString(sub.get("quantity_unit"), "").isBlank())
                throw invalid("科目未启用数量核算");
            if (line.unitPrice() != null) decimal(line.unitPrice(), 8, false);
            if (line.direction().equals("D")) debit = debit.add(amount);
            else credit = credit.add(amount);
            result.add(
                    new GlModels.Line(
                            text(line.digest(), 200, "分录摘要"),
                            line.subjectId(),
                            line.direction(),
                            amount.toPlainString(),
                            quantity.toPlainString(),
                            line.unitPrice(),
                            aux,
                            line.cashFlowItemId()));
        }
        if (debit.precision() > 24 || credit.precision() > 24) throw invalid("凭证合计超出金额范围");
        if (balanced && (debit.compareTo(credit) != 0 || debit.signum() == 0))
            throw invalid("凭证借贷须平衡且合计不能为零");
        return List.copyOf(result);
    }

    long create(
            ActorScope a,
            GlModels.Voucher input,
            String source,
            String sourceType,
            Long sourceId,
            Long journal,
            Long reversal,
            Long closingCycle,
            boolean historical) {
        long period = s.openPeriod(a, input.voucherDate());
        var book = s.active(a);
        var start = s.period(a, number(book.get("start_period_id")), false);
        if (input.voucherDate().isBefore(date(start.get("start_date"))))
            throw invalid("凭证日期不能早于账套启用期间");
        s.lockPosting(a);
        var word = s.row(a, "gl_voucher_word", input.wordId(), false);
        if (!"active".equals(word.get("status"))) throw invalid("凭证字已停用");
        if (input.attachmentCount() < 0 || input.attachmentCount() > 9999) throw invalid("附件张数无效");
        var lines = validate(a, input.lines(), historical, !source.equals("manual"));
        long id = Ids.next();
        var totals = totals(lines);
        s.jdbc.update(
                "INSERT INTO"
                    + " gl_voucher(id,tenant_id,company_id,period_id,word_id,number,voucher_date,source,source_type,source_id,journal_id,attachment_count,debit_total,credit_total,maker_id,reversal_of_id,closing_cycle_id,remark)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id,
                a.tenantId(),
                a.companyId(),
                period,
                input.wordId(),
                next(a, period, input.wordId()),
                input.voucherDate(),
                source,
                sourceType,
                sourceId,
                journal,
                input.attachmentCount(),
                totals[0],
                totals[1],
                a.userId(),
                reversal,
                closingCycle,
                input.remark());
        insertLines(a, id, lines);
        return id;
    }

    void insertLines(ActorScope a, long voucher, List<GlModels.Line> lines) {
        int index = 0;
        for (var line : lines) {
            var args =
                    new ArrayList<Object>(
                            Arrays.asList(
                                    Ids.next(),
                                    a.tenantId(),
                                    a.companyId(),
                                    voucher,
                                    ++index,
                                    line.digest(),
                                    line.subjectId(),
                                    line.direction(),
                                    money(line.amount(), true),
                                    decimal(line.quantity(), 6, true),
                                    line.unitPrice() == null
                                            ? null
                                            : decimal(line.unitPrice(), 8, false)));
            args.addAll(line.aux().values());
            args.add(line.cashFlowItemId());
            s.jdbc.update(
                    "INSERT INTO"
                        + " gl_voucher_line(id,tenant_id,company_id,voucher_id,line_no,digest,subject_id,direction,amount,quantity,unit_price,"
                            + AUX_COLUMNS
                            + ",cash_flow_item_id) VALUES("
                            + String.join(",", Collections.nCopies(args.size(), "?"))
                            + ")",
                    args.toArray());
        }
    }

    List<GlModels.Line> lines(ActorScope a, long voucher) {
        return rawLines(a, voucher).stream()
                .map(
                        r ->
                                new GlModels.Line(
                                        r.get("digest").toString(),
                                        number(r.get("subject_id")),
                                        r.get("direction").toString(),
                                        r.get("amount").toString(),
                                        r.get("quantity").toString(),
                                        r.get("unit_price") == null
                                                ? null
                                                : r.get("unit_price").toString(),
                                        aux(r),
                                        r.get("cash_flow_item_id") == null
                                                ? null
                                                : number(r.get("cash_flow_item_id"))))
                .toList();
    }

    List<Map<String, Object>> rawLines(ActorScope a, long voucher) {
        return s.jdbc.queryForList(
                "SELECT * FROM gl_voucher_line WHERE tenant_id=? AND company_id=? AND voucher_id=?"
                        + " ORDER BY line_no",
                a.tenantId(),
                a.companyId(),
                voucher);
    }

    void post(ActorScope a, Map<String, Object> voucher, boolean system) {
        if (!system && !"approved".equals(voucher.get("status"))) throw invalid("凭证复核通过后才能记账");
        long id = number(voucher.get("id"));
        validate(a, lines(a, id), true, true);
        s.jdbc.update(
                "UPDATE gl_voucher SET"
                    + " status='posted',poster_id=?,posted_at=UTC_TIMESTAMP(3),version=version+1"
                    + " WHERE tenant_id=? AND company_id=? AND id=?",
                a.userId(),
                a.tenantId(),
                a.companyId(),
                id);
        if (voucher.get("reversal_of_id") != null)
            s.jdbc.update(
                    "UPDATE gl_voucher SET status='reversed',version=version+1 WHERE tenant_id=?"
                            + " AND company_id=? AND id=? AND status='posted'",
                    a.tenantId(),
                    a.companyId(),
                    voucher.get("reversal_of_id"));
        balances.posted(a, number(voucher.get("period_id")));
    }

    private long next(ActorScope a, long period, long word) {
        s.jdbc.update(
                "INSERT INTO gl_voucher_seq(id,tenant_id,company_id,period_id,word_id)"
                        + " VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE next_no=next_no",
                Ids.next(),
                a.tenantId(),
                a.companyId(),
                period,
                word);
        long number =
                s.jdbc.queryForObject(
                        "SELECT next_no FROM gl_voucher_seq WHERE tenant_id=? AND company_id=? AND"
                                + " period_id=? AND word_id=? FOR UPDATE",
                        Long.class,
                        a.tenantId(),
                        a.companyId(),
                        period,
                        word);
        s.jdbc.update(
                "UPDATE gl_voucher_seq SET next_no=next_no+1 WHERE tenant_id=? AND company_id=? AND"
                        + " period_id=? AND word_id=?",
                a.tenantId(),
                a.companyId(),
                period,
                word);
        return number;
    }

    static BigDecimal[] totals(List<GlModels.Line> lines) {
        BigDecimal d = BigDecimal.ZERO, c = BigDecimal.ZERO;
        for (var line : lines)
            if (line.direction().equals("D")) d = d.add(money(line.amount(), true));
            else c = c.add(money(line.amount(), true));
        return new BigDecimal[] {d, c};
    }
}
