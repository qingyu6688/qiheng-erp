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
    }

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
            s.jdbc.update(
                    "INSERT INTO gl_balance(id,tenant_id,company_id,period_id,subject_id,"
                            + AUX_COLUMNS
                            + ",opening_signed,debit,credit,opening_quantity,quantity_debit,quantity_credit)"
                            + " VALUES("
                            + String.join(",", Collections.nCopies(args.size(), "?"))
                            + ")",
                    args.toArray());
        }
    }

    void posted(ActorScope a, long period) {
        // 当前期间和已形成缓存的后续期间一起重建，覆盖迟到记账对后期的期初影响。
        var ids =
                s.jdbc.queryForList(
                        "SELECT DISTINCT b.period_id FROM gl_balance b JOIN accounting_period p ON"
                            + " p.tenant_id=b.tenant_id AND p.company_id=b.company_id AND"
                            + " p.id=b.period_id JOIN accounting_period c ON"
                            + " c.tenant_id=p.tenant_id AND c.company_id=p.company_id AND c.id=?"
                            + " WHERE b.tenant_id=? AND b.company_id=? AND"
                            + " p.start_date>c.start_date ORDER BY b.period_id",
                        Long.class,
                        period,
                        a.tenantId(),
                        a.companyId());
        refresh(a, period);
        for (long id : ids) refresh(a, id);
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
