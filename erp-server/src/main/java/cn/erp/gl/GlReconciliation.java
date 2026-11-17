package cn.erp.gl;

import java.math.BigDecimal;
import java.util.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import cn.erp.shared.*;

import static cn.erp.gl.GlSupport.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class GlReconciliation implements FinancialReconciliationPort {
    private final GlSupport s;
    private final GlBalances balances;

    GlReconciliation(GlSupport s, GlBalances balances) {
        this.s = s;
        this.balances = balances;
    }

    public List<Difference> check(ActorScope a, String type) {
        var book = s.bookOrNull(a);
        if (book == null || !"active".equals(book.get("status"))) return List.of();
        s.require(a, "gl_ledger:view");
        var result = new ArrayList<Difference>();
        if (type.equals("gl_operational")) {
            var first = s.period(a, number(book.get("start_period_id")), false);
            for (var row :
                    s.jdbc.queryForList(
                            "SELECT l.id,l.amount,IF(l.direction='D',l.amount,-l.amount)"
                                + " expected,g.amount gl_amount,g.direction gl_direction,v.status"
                                + " FROM journal j JOIN journal_line l ON l.tenant_id=j.tenant_id"
                                + " AND l.company_id=j.company_id AND l.journal_id=j.id LEFT JOIN"
                                + " gl_voucher v ON v.tenant_id=j.tenant_id AND"
                                + " v.company_id=j.company_id AND v.journal_id=j.id LEFT JOIN"
                                + " gl_voucher_line g ON g.tenant_id=v.tenant_id AND"
                                + " g.company_id=v.company_id AND g.voucher_id=v.id AND"
                                + " g.journal_line_id=l.id WHERE j.tenant_id=? AND j.company_id=?"
                                + " AND j.business_date>=? ORDER BY j.business_date,j.id,l.line_no",
                            a.tenantId(),
                            a.companyId(),
                            first.get("start_date"))) {
                var expected = GlBalances.decimal(row, "expected");
                var actual =
                        row.get("gl_amount") == null
                                ? BigDecimal.ZERO
                                : GlDimensions.signed(
                                        row.get("gl_direction").toString(),
                                        GlBalances.decimal(row, "gl_amount"));
                if (expected.compareTo(actual) != 0
                        || !Set.of("posted", "reversed")
                                .contains(Objects.toString(row.get("status"), "")))
                    result.add(new Difference("运营分录/" + row.get("id"), expected, actual));
            }
            return result;
        }
        if (!type.equals("gl_trial")) throw invalid("财务对账类型无效");
        var periods =
                s.jdbc.queryForList(
                        "SELECT DISTINCT p.* FROM accounting_period p JOIN gl_balance b ON"
                            + " b.tenant_id=p.tenant_id AND b.company_id=p.company_id AND"
                            + " b.period_id=p.id WHERE p.tenant_id=? AND p.company_id=? ORDER BY"
                            + " p.start_date",
                        a.tenantId(),
                        a.companyId());
        for (var period : periods) {
            long id = number(period.get("id"));
            var actual = new HashMap<String, Map<String, Object>>();
            for (var row :
                    s.jdbc.queryForList(
                            "SELECT * FROM gl_balance WHERE tenant_id=? AND company_id=? AND"
                                    + " period_id=?",
                            a.tenantId(),
                            a.companyId(),
                            id))
                actual.put(GlDimensions.key(number(row.get("subject_id")), aux(row)), row);
            for (var expected :
                    balances.snapshot(
                            a,
                            date(period.get("start_date")),
                            date(period.get("end_date")),
                            false)) {
                String key = GlDimensions.key(expected.subject(), expected.aux());
                var row = actual.remove(key);
                for (var entry :
                        Map.of(
                                        "opening_signed",
                                        expected.opening(),
                                        "debit",
                                        expected.debit(),
                                        "credit",
                                        expected.credit())
                                .entrySet()) {
                    var value =
                            row == null ? BigDecimal.ZERO : GlBalances.decimal(row, entry.getKey());
                    if (entry.getValue().compareTo(value) != 0)
                        result.add(
                                new Difference(
                                        period.get("period") + "/" + key + "/" + entry.getKey(),
                                        entry.getValue(),
                                        value));
                }
            }
            for (var row : actual.entrySet()) {
                var value =
                        GlBalances.decimal(row.getValue(), "opening_signed")
                                .add(GlBalances.decimal(row.getValue(), "debit"))
                                .subtract(GlBalances.decimal(row.getValue(), "credit"));
                if (value.signum() != 0)
                    result.add(
                            new Difference(
                                    period.get("period") + "/" + row.getKey(),
                                    BigDecimal.ZERO,
                                    value));
            }
        }
        return result;
    }
}
