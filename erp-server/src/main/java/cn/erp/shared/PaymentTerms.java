package cn.erp.shared;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** 统一账期校验、审批快照与到期日计算，不参与金额及核销顺序。 */
public final class PaymentTerms {
    private static final int MAX_DAYS = 365;
    private static final int MAX_MONTHLY_DAY = 28;

    private PaymentTerms() {}

    /** 两个字段均为空表示不设账期；整体为空表示调用方未提供变更。 */
    public record Term(String type, Integer value) {}

    public static Term validate(Term term) {
        if (term == null || term.type() == null && term.value() == null) return term;
        if (term.type() == null || term.value() == null
                || !Set.of("days", "monthly").contains(term.type())
                || term.value() < ("monthly".equals(term.type()) ? 1 : 0)
                || term.value() > ("monthly".equals(term.type()) ? MAX_MONTHLY_DAY : MAX_DAYS)) {
            throw new ApiException(400, "VALIDATION_FAILED", "账期须为按天0至365天，或月结次月1至28日");
        }
        return term;
    }

    public static LocalDate dueDate(LocalDate businessDate, Term term) {
        validate(term);
        if (term == null || term.type() == null) return null;
        Objects.requireNonNull(businessDate, "计算到期日需要业务日期");
        return "days".equals(term.type()) ? businessDate.plusDays(term.value())
                : businessDate.plusMonths(1).withDayOfMonth(term.value());
    }

    public static Term from(Map<String, Object> row) {
        return new Term((String) row.get("term_type"), row.get("term_value") == null
                ? null : ((Number) row.get("term_value")).intValue());
    }

    public static Term resolve(JdbcTemplate jdbc, ActorScope scope, long partner, String role) {
        if (!Set.of("customer", "supplier").contains(role)) throw invalidRole();
        // 审批采用当前读，避免事务较早的快照覆盖刚提交的账期设置。
        var roles = jdbc.queryForList("SELECT term_type,term_value FROM partner_role WHERE tenant_id=? AND partner_id=? AND role=? AND status='active' FOR SHARE",
                scope.tenantId(), partner, role);
        if (!roles.isEmpty() && roles.getFirst().get("term_type") != null) return from(roles.getFirst());
        var defaults = jdbc.queryForList("SELECT default_term_type term_type,default_term_value term_value FROM company_policy WHERE tenant_id=? AND company_id=? FOR SHARE",
                scope.tenantId(), scope.companyId());
        return defaults.isEmpty() ? new Term(null, null) : from(defaults.getFirst());
    }

    public static void saveDraft(JdbcTemplate jdbc, ActorScope scope, String table, long id,
            Term term, Boolean inherit) {
        orderTable(table);
        if (term == null && inherit == null) return;
        validate(term);
        if (Boolean.TRUE.equals(inherit) && term != null && term.type() != null)
            throw new ApiException(400, "VALIDATION_FAILED", "继承账期时不能同时指定订单账期");
        var rows = jdbc.queryForList("SELECT status,term_type,term_value,term_override FROM " + table
                + " WHERE tenant_id=? AND company_id=? AND id=?", scope.tenantId(), scope.companyId(), id);
        if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "订单不存在");
        var current = rows.getFirst();
        if (!"draft".equals(current.get("status"))) {
            if (Boolean.TRUE.equals(inherit) || !Objects.equals(term, from(current)))
                throw new ApiException(422, "CHANGE_NOT_ALLOWED", "审批后的订单不能修改账期");
            return;
        }
        boolean override = !Boolean.TRUE.equals(inherit);
        jdbc.update("UPDATE " + table + " SET term_type=?,term_value=?,term_override=? WHERE tenant_id=? AND company_id=? AND id=?",
                override && term != null ? term.type() : null, override && term != null ? term.value() : null,
                override, scope.tenantId(), scope.companyId(), id);
    }

    public static void snapshot(JdbcTemplate jdbc, ActorScope scope, String table, long id,
            long partner, String role) {
        orderTable(table);
        var row = jdbc.queryForMap("SELECT term_type,term_value,term_override FROM " + table
                + " WHERE tenant_id=? AND company_id=? AND id=?", scope.tenantId(), scope.companyId(), id);
        boolean overridden = Boolean.TRUE.equals(row.get("term_override"))
                || row.get("term_override") instanceof Number n && n.intValue() != 0;
        Term term = validate(overridden ? from(row) : resolve(jdbc, scope, partner, role));
        jdbc.update("UPDATE " + table + " SET term_type=?,term_value=? WHERE tenant_id=? AND company_id=? AND id=?",
                term.type(), term.value(), scope.tenantId(), scope.companyId(), id);
    }

    public static LocalDate forOrder(JdbcTemplate jdbc, ActorScope scope, long order,
            String role, LocalDate date) {
        return forOrders(jdbc, scope, Set.of(order), role, date).get(order);
    }

    /** 一张签收单可以合并多张订单，按订单批量读取，避免每行查询账期。 */
    public static Map<Long, LocalDate> forOrders(JdbcTemplate jdbc, ActorScope scope, Set<Long> orders,
            String role, LocalDate date) {
        if (!Set.of("customer", "supplier").contains(role)) throw invalidRole();
        if (orders.isEmpty()) return Map.of();
        String table = "customer".equals(role) ? "sales_order" : "purchase_order";
        var args = new ArrayList<Object>();
        args.add(scope.tenantId());args.add(scope.companyId());args.addAll(orders);
        var rows = jdbc.queryForList("SELECT id,term_type,term_value FROM " + table
                + " WHERE tenant_id=? AND company_id=? AND id IN (" + String.join(",", Collections.nCopies(orders.size(), "?")) + ")", args.toArray());
        if (rows.size() != orders.size()) throw new ApiException(404, "NOT_FOUND", "订单不存在或无权查看");
        var result = new HashMap<Long, LocalDate>();
        rows.forEach(row -> result.put(((Number) row.get("id")).longValue(), dueDate(date, from(row))));
        return result;
    }

    public static LocalDate original(JdbcTemplate jdbc, ActorScope scope, String fact,
            String source, long sourceLine) {
        if (!Set.of("receivable", "payable").contains(fact)) throw invalidRole();
        var rows = jdbc.queryForList("SELECT due_date FROM " + fact
                + " WHERE tenant_id=? AND company_id=? AND kind='charge' AND source_type=? AND source_line_id=?",
                scope.tenantId(), scope.companyId(), source, sourceLine);
        return rows.isEmpty() || rows.getFirst().get("due_date") == null ? null
                : ((java.sql.Date) rows.getFirst().get("due_date")).toLocalDate();
    }

    private static void orderTable(String table) {
        if (!Set.of("sales_order", "purchase_order").contains(table)) throw invalidRole();
    }

    private static ApiException invalidRole() {
        return new ApiException(400, "VALIDATION_FAILED", "账期业务类型无效");
    }
}
