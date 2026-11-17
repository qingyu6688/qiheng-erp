package cn.erp.crm;

import cn.erp.identity.CompanyAccess;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 独立于通用工作台查询，统计当前员工在授权范围内需要处理的事项。 */
@Service
@Transactional(readOnly = true)
class CrmWorkbench {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private final JdbcTemplate jdbc;
    private final CrmScope scope;
    private final CrmPoolSettings pool;
    private final CrmStatisticsService statistics;

    CrmWorkbench(JdbcTemplate jdbc, CrmScope scope, CrmPoolSettings pool, CrmStatisticsService statistics) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.pool = pool;
        this.statistics = statistics;
    }

    Map<String, Object> summary(CompanyAccess actor) {
        var result = new LinkedHashMap<String, Object>();
        var today = LocalDate.now(BUSINESS_ZONE);
        Timestamp until = Timestamp.from(today.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant());
        if (actor.hasPermission("crm_clue:view")) result.put("dueClues", dueClues(actor, until));
        if (actor.hasPermission("crm_customer:view")) {
            result.put("dueCustomers", dueCustomers(actor, until));
            result.put("upcomingReclaims", pool.upcoming(actor));
        }
        if (actor.hasPermission("crm_contract:view")) result.put("pendingApprovals", pending(actor));
        if (actor.hasPermission("crm_statistic:view")) result.put("performance", performance(actor, today));
        if (result.isEmpty()) throw CrmScope.forbidden("没有查看客户关系工作台的权限");
        result.put("asOf", Instant.now().toString());
        return result;
    }

    private long dueClues(CompanyAccess actor, Timestamp until) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM crm_clue l WHERE l.tenant_id=? AND l.company_id=? AND l.owner_user_id=?
                AND l.status IN ('new','following') AND l.next_contact_at<? AND (%s)
                """.formatted(scope.clueFilter(actor, "l")), Long.class, actor.tenantId(), actor.companyId(), actor.userId(), until);
    }

    private long dueCustomers(CompanyAccess actor, Timestamp until) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM crm_customer_profile c
                JOIN business_partner p ON p.tenant_id=c.tenant_id AND p.id=c.partner_id AND p.status='active'
                JOIN partner_role pr ON pr.tenant_id=p.tenant_id AND pr.partner_id=p.id AND pr.role='customer' AND pr.status='active'
                WHERE c.tenant_id=? AND c.company_id=? AND c.owner_user_id=? AND c.next_contact_at<? AND (%s)
                """.formatted(scope.customerFilter(actor, "c")), Long.class, actor.tenantId(), actor.companyId(), actor.userId(), until);
    }

    private long pending(CompanyAccess actor) {
        return jdbc.queryForObject("""
                SELECT COUNT(DISTINCT k.id) FROM wf_task_ext t
                JOIN wf_instance i ON i.tenant_id=t.tenant_id AND i.company_id=t.company_id AND i.id=t.instance_id
                    AND i.status='running' AND i.approval_epoch=t.approval_epoch AND i.business_type='crm_contract'
                JOIN crm_contract k ON k.tenant_id=i.tenant_id AND k.company_id=i.company_id
                    AND k.id=i.business_id AND k.wf_instance_id=i.id AND k.status='pending'
                JOIN crm_customer_profile c ON c.tenant_id=k.tenant_id AND c.company_id=k.company_id AND c.partner_id=k.partner_id
                WHERE t.tenant_id=? AND t.company_id=? AND t.assignee_id=? AND t.status='pending' AND (%s)
                """.formatted(scope.customerFilter(actor, "c")), Long.class, actor.tenantId(), actor.companyId(), actor.userId());
    }

    private List<Goal> performance(CompanyAccess actor, LocalDate today) {
        String owner = Long.toString(actor.userId());
        var report = statistics.statistics(actor, "performance", today.withDayOfMonth(1), today, owner, null);
        var items = maps(report.get("items"));
        var targets = maps(report.get("targets")).stream().filter(row -> "user".equals(row.get("targetType")) && owner.equals(row.get("targetId"))).toList();
        return List.of(goal("contract", "contractAmount", items, targets), goal("receivable", "receivableAmount", items, targets));
    }

    record Goal(String kind, String amount, String target, String percent) {}

    private Goal goal(String kind, String field, List<Map<?, ?>> items, List<Map<?, ?>> targets) {
        BigDecimal actual = items.stream().map(row -> new BigDecimal(row.get(field).toString())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expected = targets.stream().filter(row -> kind.equals(row.get("kind")))
                .map(row -> new BigDecimal(row.get("amount").toString())).reduce(BigDecimal.ZERO, BigDecimal::add);
        String percent = expected.signum() > 0 ? actual.multiply(ONE_HUNDRED).divide(expected, 1, RoundingMode.HALF_UP).toPlainString() : null;
        return new Goal(kind, actual.toPlainString(), expected.toPlainString(), percent);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<?, ?>>) list : List.of();
    }
}
