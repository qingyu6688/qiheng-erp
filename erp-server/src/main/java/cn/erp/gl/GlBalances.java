package cn.erp.gl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import org.springframework.stereotype.Component;

import cn.erp.shared.*;

import static cn.erp.gl.GlSupport.*;

/** 余额缓存与只追加的记账事实分开；按辅助明细保留借贷方向，禁止客户间净额相抵。 */
@Component
class GlBalances {
    private static final String WRITE_BALANCE =
            "INSERT INTO gl_balance(id,tenant_id,company_id,period_id,subject_id,"
                    + AUX_COLUMNS
                    + ",opening_signed,debit,credit,opening_quantity,quantity_debit,quantity_credit)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) AS delta"
                    + " ON DUPLICATE KEY UPDATE"
                    + " opening_signed=gl_balance.opening_signed+delta.opening_signed,"
                    + " debit=gl_balance.debit+delta.debit,credit=gl_balance.credit+delta.credit,"
                    + " opening_quantity=gl_balance.opening_quantity+delta.opening_quantity,"
                    + " quantity_debit=gl_balance.quantity_debit+delta.quantity_debit,"
                    + " quantity_credit=gl_balance.quantity_credit+delta.quantity_credit";
    private static final String HAS_BALANCE =
            "(SELECT b.id FROM gl_balance b WHERE b.tenant_id=p.tenant_id"
                    + " AND b.company_id=p.company_id AND b.period_id=p.id LIMIT 1) IS NOT NULL";
    private final GlSupport s;

    GlBalances(GlSupport s) {
        this.s = s;
    }

    record Balance(
            long subject,
            GlModels.Aux aux,
            BigDecimal opening,
            BigDecimal debit,
            BigDecimal credit,
            BigDecimal quantityOpening,
            BigDecimal quantityDebit,
            BigDecimal quantityCredit) {
        BigDecimal closing() {
            return opening.add(debit).subtract(credit);
        }

        Balance carry() {
            return openingBalance(
                    subject,
                    aux,
                    closing(),
                    quantityOpening.add(quantityDebit).subtract(quantityCredit));
        }

        Balance mergeMovement(Balance other) {
            return new Balance(
                    subject,
                    aux,
                    opening,
                    debit.add(other.debit),
                    credit.add(other.credit),
                    quantityOpening,
                    quantityDebit.add(other.quantityDebit),
                    quantityCredit.add(other.quantityCredit));
        }
    }

    private record BalanceKey(long subject, GlModels.Aux aux) {}

    List<Balance> snapshot(ActorScope a, LocalDate from, LocalDate to, boolean excludeClosing) {
        var result = new TreeMap<String, BigDecimal[]>();
        var dimensions = new HashMap<String, GlModels.Aux>();
        var subjects = new HashMap<String, Long>();
        for (var row :
                s.jdbc.queryForList(
                        "SELECT * FROM gl_opening_balance WHERE tenant_id=? AND company_id=?",
                        a.tenantId(),
                        a.companyId())) {
            long id = number(row.get("subject_id"));
            var aux = aux(row);
            String key = GlDimensions.key(id, aux);
            var totals = result.computeIfAbsent(key, k -> zeros());
            totals[0] = decimal(row, "opening_signed");
            totals[3] = decimal(row, "opening_quantity");
            dimensions.put(key, aux);
            subjects.put(key, id);
        }
        String cols = String.join(",", AUX.stream().map(n -> "l.aux_" + n + "_id").toList());
        for (var row :
                s.jdbc.queryForList(
                        "SELECT l.subject_id,"
                                + cols
                                + ",SUM(IF(v.voucher_date<?,IF(l.direction='D',l.amount,-l.amount),0))"
                                + " opening_delta,SUM(IF(v.voucher_date>=? AND"
                                + " l.direction='D',l.amount,0)) d,SUM(IF(v.voucher_date>=? AND"
                                + " l.direction='C',l.amount,0))"
                                + " c,SUM(IF(v.voucher_date<?,IF(l.direction='D',l.quantity,-l.quantity),0))"
                                + " q0,SUM(IF(v.voucher_date>=? AND l.direction='D',l.quantity,0))"
                                + " qd,SUM(IF(v.voucher_date>=? AND l.direction='C',l.quantity,0))"
                                + " qc FROM gl_voucher v JOIN gl_voucher_line l ON"
                                + " l.tenant_id=v.tenant_id AND l.company_id=v.company_id AND"
                                + " l.voucher_id=v.id WHERE v.tenant_id=? AND v.company_id=? AND"
                                + " v.is_deleted=0 AND v.status IN('posted','reversed') AND"
                                + " v.voucher_date<=?"
                                + (excludeClosing ? " AND v.source<>'closing'" : "")
                                + " GROUP BY l.subject_id,"
                                + cols,
                        from,
                        from,
                        from,
                        from,
                        from,
                        from,
                        a.tenantId(),
                        a.companyId(),
                        to)) {
            long id = number(row.get("subject_id"));
            var aux = aux(row);
            String key = GlDimensions.key(id, aux);
            var totals = result.computeIfAbsent(key, k -> zeros());
            for (int i = 0; i < 6; i++)
                totals[i] =
                        totals[i].add(
                                decimal(
                                        row,
                                        List.of("opening_delta", "d", "c", "q0", "qd", "qc")
                                                .get(i)));
            dimensions.put(key, aux);
            subjects.put(key, id);
        }
        return result.entrySet().stream()
                .map(
                        e -> {
                            var v = e.getValue();
                            return new Balance(
                                    subjects.get(e.getKey()),
                                    dimensions.get(e.getKey()),
                                    v[0],
                                    v[1],
                                    v[2],
                                    v[3],
                                    v[4],
                                    v[5]);
                        })
                .toList();
    }

    void refresh(ActorScope a, long period) {
        var p = s.period(a, period, false);
        var rows = snapshot(a, date(p.get("start_date")), date(p.get("end_date")), false);
        s.jdbc.update(
                "DELETE FROM gl_balance WHERE tenant_id=? AND company_id=? AND period_id=?",
                a.tenantId(),
                a.companyId(),
                period);
        write(a, period, rows);
    }

    void posted(ActorScope a, long period, LocalDate voucherDate, List<GlModels.Line> lines) {
        // 各记账入口先取得公司记账锁，再在 READ COMMITTED 下读取已提交的余额。
        var currentAndLater = s.jdbc.queryForList(
                "SELECT p.id FROM accounting_period p WHERE p.tenant_id=? AND p.company_id=?"
                        + " AND p.end_date>=? AND " + HAS_BALANCE + " ORDER BY p.start_date",
                Long.class, a.tenantId(), a.companyId(), voucherDate);
        if (!currentAndLater.contains(period)) seedOpening(a, period, voucherDate);

        var movements = new LinkedHashMap<BalanceKey, Balance>();
        for (var line : lines) {
            boolean debit = line.direction().equals("D");
            var amount = new BigDecimal(line.amount());
            var quantity = new BigDecimal(line.quantity());
            var zero = BigDecimal.ZERO;
            var key = new BalanceKey(line.subjectId(), line.aux());
            var delta = new Balance(
                    key.subject(), key.aux(), zero, debit ? amount : zero, debit ? zero : amount,
                    zero, debit ? quantity : zero, debit ? zero : quantity);
            movements.merge(key, delta, Balance::mergeMovement);
        }
        write(a, period, movements.values());

        // 红字仍是新增凭证。后期只滚入差额，并保留发生额相抵为零的辅助明细。
        var carry = movements.values().stream().map(Balance::carry).toList();
        for (long id : currentAndLater) {
            if (id != period) write(a, id, carry);
        }
    }

    private void seedOpening(ActorScope a, long period, LocalDate voucherDate) {
        // 每次过账都物化其所属期间，因此前一个已物化期间与当前期间之间没有记账事实。
        var previous = s.jdbc.queryForList(
                "SELECT p.id FROM accounting_period p WHERE p.tenant_id=? AND p.company_id=?"
                        + " AND p.end_date<? AND " + HAS_BALANCE + " ORDER BY p.start_date DESC LIMIT 1",
                Long.class, a.tenantId(), a.companyId(), voucherDate);
        var args = new ArrayList<Object>(List.of(a.tenantId(), a.companyId()));
        String sql;
        if (previous.isEmpty()) {
            sql = "SELECT subject_id," + AUX_COLUMNS + ",opening_signed,opening_quantity"
                    + " FROM gl_opening_balance WHERE tenant_id=? AND company_id=?";
        } else {
            sql = "SELECT subject_id," + AUX_COLUMNS + ",opening_signed+debit-credit AS opening_signed,"
                    + " opening_quantity+quantity_debit-quantity_credit AS opening_quantity"
                    + " FROM gl_balance WHERE tenant_id=? AND company_id=? AND period_id=?";
            args.add(previous.getFirst());
        }
        var rows = s.jdbc.queryForList(sql, args.toArray()).stream()
                .map(row -> openingBalance(
                        number(row.get("subject_id")), aux(row),
                        decimal(row, "opening_signed"), decimal(row, "opening_quantity")))
                .toList();
        write(a, period, rows);
    }

    private static Balance openingBalance(long subject, GlModels.Aux aux, BigDecimal amount, BigDecimal quantity) {
        var zero = BigDecimal.ZERO;
        return new Balance(subject, aux, amount, zero, zero, quantity, zero, zero);
    }

    private void write(ActorScope a, long period, Collection<Balance> rows) {
        var entries = new ArrayList<Object[]>(rows.size());
        for (var row : rows) {
            var args =
                    new ArrayList<Object>(
                            List.of(
                                    Ids.next(),
                                    a.tenantId(),
                                    a.companyId(),
                                    period,
                                    row.subject()));
            args.addAll(row.aux().values());
            args.addAll(
                    List.of(
                            row.opening(),
                            row.debit(),
                            row.credit(),
                            row.quantityOpening(),
                            row.quantityDebit(),
                            row.quantityCredit()));
            entries.add(args.toArray());
        }
        if (!entries.isEmpty()) s.jdbc.batchUpdate(WRITE_BALANCE, entries);
    }

    static BigDecimal decimal(Map<String, Object> row, String field) {
        Object value = row.get(field);
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    private static BigDecimal[] zeros() {
        return new BigDecimal[] {
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO
        };
    }
}
