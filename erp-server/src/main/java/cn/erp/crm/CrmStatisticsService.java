package cn.erp.crm;

import cn.erp.identity.AccessService;
import cn.erp.identity.CompanyAccess;
import cn.erp.reporting.ReportReadDatabase;
import cn.erp.shared.ApiRows;
import cn.erp.shared.AuditService;
import cn.erp.shared.Ids;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.crm.CrmCycleData.*;

@Service
@Transactional(readOnly = true)
class CrmStatisticsService {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate primary;
    private final ReportReadDatabase reports;
    private final CrmScope scope;
    private final AccessService access;
    private final AuditService audit;

    CrmStatisticsService(JdbcTemplate jdbc, NamedParameterJdbcTemplate primary, ReportReadDatabase reports,
                         CrmScope scope, AccessService access, AuditService audit) {
        this.jdbc = jdbc;
        this.primary = primary;
        this.reports = reports;
        this.scope = scope;
        this.access = access;
        this.audit = audit;
    }

    List<Map<String, Object>> targets(CompanyAccess actor, int year, Integer month) {
        if (year < 1900 || year > 9999 || month != null && (month < 1 || month > 12)) throw invalid("目标年月无效");
        return jdbc.queryForList("""
                SELECT * FROM crm_performance_target WHERE tenant_id=? AND company_id=? AND year=? AND (? IS NULL OR month=?)
                ORDER BY month,target_type,target_id,kind
                """, actor.tenantId(), actor.companyId(), year, month, month).stream()
                .filter(target -> maySeeTarget(actor, target.get("target_type").toString(), number(target, "target_id")))
                .map(ApiRows::row).toList();
    }

    @Transactional
    public Map<String, Object> saveTarget(CompanyAccess actor, CrmCycleModels.Target input) {
        String type = input.targetType(), kind = input.kind();
        long target = id(input.targetId());
        if (!Set.of("user", "department").contains(type) || !Set.of("contract", "receivable").contains(kind))
            throw invalid("目标类型或指标无效");
        ensureTarget(actor, type, target);
        if (!maySeeTarget(actor, type, target)) throw missing();
        jdbc.update("""
                INSERT INTO crm_performance_target(id,tenant_id,company_id,target_type,target_id,year,month,kind,amount)
                VALUES(?,?,?,?,?,?,?,?,?) AS incoming ON DUPLICATE KEY UPDATE amount=incoming.amount
                """, Ids.next(), actor.tenantId(), actor.companyId(), type, target, input.year(), input.month(), kind,
                exact(input.amount(), 2, "目标金额"));
        var saved = jdbc.queryForMap("""
                SELECT * FROM crm_performance_target WHERE tenant_id=? AND company_id=? AND target_type=?
                    AND target_id=? AND year=? AND month=? AND kind=?
                """, actor.tenantId(), actor.companyId(), type, target, input.year(), input.month(), kind);
        var result = ApiRows.row(saved);
        audit.record(actor, "crm_target.save", "crm_performance_target", number(saved, "id"), null, result);
        return result;
    }

    private boolean maySeeTarget(CompanyAccess actor, String type, long target) {
        if (actor.orgScope().allDepartments()) return true;
        if (type.equals("department")) return actor.orgScope().allows(target, 0);
        var departments = jdbc.queryForList("""
                SELECT department_id FROM org_user_department WHERE tenant_id=? AND company_id=? AND user_id=? AND is_primary=1
                """, Long.class, actor.tenantId(), actor.companyId(), target);
        return actor.orgScope().allows(departments.isEmpty() ? 0 : departments.getFirst(), target);
    }

    private void ensureTarget(CompanyAccess actor, String type, long target) {
        if (type.equals("user")) {
            if (access.forUser(actor.tenantId(), target, actor.companyId()) == null) throw missing();
        } else one(jdbc.queryForList("SELECT id FROM org_department WHERE tenant_id=? AND company_id=? AND id=? AND status='active'",
                actor.tenantId(), actor.companyId(), target));
    }

    Map<String, Object> statistics(CompanyAccess actor, String kind, LocalDate start, LocalDate end,
                                   String userIds, String departmentId) {
        var to = end == null ? today() : end;
        var from = start == null ? to.withDayOfMonth(1) : start;
        if (from.isAfter(to) || to.getYear() >= 9999 || from.getYear() < 1900 || ChronoUnit.DAYS.between(from, to) > 3660)
            throw invalid("统计日期区间无效或超过十年");
        var owners = userIds == null || userIds.isBlank() ? List.<Long>of() : Arrays.stream(userIds.split(",", -1)).map(CrmCycleData::id).distinct().toList();
        if (owners.size() > 200) throw invalid("统计最多同时筛选200名员工");
        Long department = departmentId == null ? null : id(departmentId);
        var zone = java.time.ZoneId.of("Asia/Shanghai");
        var params = new MapSqlParameterSource().addValue("t", actor.tenantId()).addValue("c", actor.companyId())
                .addValue("f", from).addValue("d", to).addValue("department", department)
                .addValue("fTime", from.atStartOfDay(zone).withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime())
                .addValue("nextDay", to.plusDays(1).atStartOfDay(zone).withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime())
                .addValue("users", owners.isEmpty() ? List.of(0) : owners).addValue("filterUsers", !owners.isEmpty());
        var sql = query(kind, scope.customerFilter(actor, "c"));
        // 受限范围必须使用主库的实时客户归属，避免备库还未同步客户移交或权限收回。
        boolean replica = reports.replicaEnabled() && actor.orgScope().allDepartments()
                && (actor.hasPermission("crm_customer:view_all") || actor.hasPermission("crm_customer:manage_all"))
                && actor.hasPermission("crm_pool:view");
        var rows = replica ? reports.readJdbc(db -> db.queryForList(sql, params)) : primary.queryForList(sql, params);
        var result = new LinkedHashMap<String, Object>();
        result.put("kind", kind);
        result.put("dateFrom", from.toString());
        result.put("dateTo", to.toString());
        result.put("dataSource", replica ? "replica" : "primary");
        result.put("items", rows.stream().map(ApiRows::row).toList());
        if (kind.equals("performance") && actor.hasPermission("crm_target:view"))
            result.put("targets", java.util.stream.IntStream.rangeClosed(from.getYear(), to.getYear()).boxed()
                    .flatMap(year -> targets(actor, year, null).stream()).filter(row -> inTargetRange(row, from, to)).toList());
        return result;
    }

    private static boolean inTargetRange(Map<String, Object> row, LocalDate from, LocalDate to) {
        var month = java.time.YearMonth.of(Math.toIntExact(number(row,"year")), Math.toIntExact(number(row,"month")));
        return !month.isBefore(java.time.YearMonth.from(from)) && !month.isAfter(java.time.YearMonth.from(to));
    }

    private static String query(String kind, String visibility) {
        String visible = """
                WITH visible AS (SELECT c.* FROM crm_customer_profile c WHERE c.tenant_id=:t AND c.company_id=:c AND (%s))
                """.formatted(visibility);
        String filter = " AND (:department IS NULL OR c.owner_department_id=:department) AND (NOT :filterUsers OR c.owner_user_id IN (:users)) ";
        return visible + switch (kind) {
            case "customers" -> """
                    SELECT c.owner_user_id,c.owner_department_id,COUNT(*) customer_count,
                           COALESCE(SUM(c.created_at>=:fTime AND c.created_at<:nextDay),0) new_customer_count,
                           COALESCE(SUM(c.deal_status='sold'),0) sold_customer_count
                    FROM visible c JOIN business_partner p ON p.tenant_id=c.tenant_id AND p.id=c.partner_id
                    WHERE p.status='active' %s GROUP BY c.owner_user_id,c.owner_department_id ORDER BY customer_count DESC LIMIT 500
                    """.formatted(filter);
            case "profiles" -> """
                    SELECT c.level,c.industry,c.source,c.area_id,COUNT(*) customer_count FROM visible c
                    WHERE c.created_at<:nextDay %s GROUP BY c.level,c.industry,c.source,c.area_id ORDER BY customer_count DESC LIMIT 500
                    """.formatted(filter);
            case "funnel" -> funnel(filter);
            case "products" -> """
                    SELECT l.sku_id,s.name sku_name,l.unit_id,SUM(l.qty) qty,SUM(l.total_amount) amount
                    FROM crm_contract_line l JOIN crm_contract k ON k.tenant_id=l.tenant_id AND k.company_id=l.company_id AND k.id=l.contract_id
                    JOIN visible c ON c.partner_id=k.partner_id JOIN sku s ON s.tenant_id=l.tenant_id AND s.id=l.sku_id
                    WHERE l.tenant_id=:t AND l.company_id=:c AND k.status IN('approved','finished') AND k.sign_date BETWEEN :f AND :d %s
                    GROUP BY l.sku_id,s.name,l.unit_id ORDER BY amount DESC LIMIT 500
                    """.formatted(filter);
            case "follow-ups" -> """
                    SELECT f.method,COUNT(*) follow_up_count FROM crm_follow_up f
                    LEFT JOIN crm_business b ON f.biz_type='business' AND b.tenant_id=f.tenant_id AND b.company_id=f.company_id AND b.id=f.biz_id
                    LEFT JOIN crm_contract k ON f.biz_type='contract' AND k.tenant_id=f.tenant_id AND k.company_id=f.company_id AND k.id=f.biz_id
                    JOIN visible c ON c.partner_id=CASE f.biz_type WHEN 'customer' THEN f.biz_id WHEN 'contract' THEN k.partner_id ELSE b.partner_id END
                    WHERE f.tenant_id=:t AND f.company_id=:c AND f.created_at>=:fTime AND f.created_at<:nextDay %s
                    GROUP BY f.method ORDER BY follow_up_count DESC LIMIT 500
                    """.formatted(filter);
            case "employees", "performance" -> performance(kind, filter);
            default -> throw invalid("不支持的 CRM 统计类型");
        };
    }

    private static String funnel(String filter) {
        return """
                ,facts AS (
                  SELECT b.stage_id,1 current_count,b.expected_amount,b.result='won' won,b.result='lost' lost,
                      NULL reached,NULL advanced FROM crm_business b JOIN visible c ON c.partner_id=b.partner_id
                  WHERE b.tenant_id=:t AND b.company_id=:c AND b.expected_date BETWEEN :f AND :d AND b.result<>'invalid' %s
                  UNION ALL
                  SELECT e.to_stage_id,0,0,0,0,b.id,NULL FROM crm_business_stage_event e
                  JOIN crm_business b ON b.tenant_id=e.tenant_id AND b.company_id=e.company_id AND b.id=e.business_id
                  JOIN visible c ON c.partner_id=b.partner_id
                  WHERE e.tenant_id=:t AND e.company_id=:c AND e.created_at>=:fTime AND e.created_at<:nextDay %s
                  UNION ALL
                  SELECT e.from_stage_id,0,0,0,0,NULL,b.id FROM crm_business_stage_event e
                  JOIN crm_business b ON b.tenant_id=e.tenant_id AND b.company_id=e.company_id AND b.id=e.business_id
                  JOIN visible c ON c.partner_id=b.partner_id
                  WHERE e.tenant_id=:t AND e.company_id=:c AND e.advanced=1 AND e.created_at>=:fTime AND e.created_at<:nextDay
                    AND EXISTS(SELECT 1 FROM crm_business_stage_event entry WHERE entry.tenant_id=e.tenant_id
                        AND entry.company_id=e.company_id AND entry.business_id=e.business_id AND entry.to_stage_id=e.from_stage_id
                        AND entry.created_at>=:fTime AND entry.created_at<=e.created_at) %s
                ) SELECT s.id stage_id,s.group_id,s.name stage_name,s.sort,s.win_rate,SUM(f.current_count) business_count,
                         SUM(f.expected_amount) expected_amount,SUM(f.won) won_count,SUM(f.lost) lost_count,
                         COUNT(DISTINCT f.reached) reached_count,COUNT(DISTINCT f.advanced) advanced_count,
                         COALESCE(ROUND(COUNT(DISTINCT f.advanced)/NULLIF(COUNT(DISTINCT f.reached),0),4),0) conversion_rate
                  FROM facts f JOIN crm_business_stage s ON s.tenant_id=:t AND s.company_id=:c AND s.id=f.stage_id
                  GROUP BY s.id,s.group_id,s.name,s.sort,s.win_rate ORDER BY s.group_id,s.sort LIMIT 500
                """.formatted(filter, filter, filter);
    }

    private static String performance(String kind, String filter) {
        String dimension = kind.equals("performance") ? ",month_key" : "";
        return """
                ,facts AS (
                  SELECT k.owner_user_id,k.owner_department_id,DATE_FORMAT(k.sign_date,'%%Y-%%m') month_key,
                    k.total_amount contract_amount,0 receivable_amount,0 new_customer_count
                  FROM crm_contract k JOIN visible c ON c.partner_id=k.partner_id
                  WHERE k.tenant_id=:t AND k.company_id=:c AND k.status IN('approved','finished') AND k.sign_date BETWEEN :f AND :d %s
                  UNION ALL
                  SELECT k.owner_user_id,k.owner_department_id,DATE_FORMAT(r.business_date,'%%Y-%%m'),0,a.amount,0 FROM crm_receivable a
                  JOIN crm_contract k ON k.tenant_id=a.tenant_id AND k.company_id=a.company_id AND k.id=a.contract_id
                  JOIN receipt r ON r.tenant_id=a.tenant_id AND r.company_id=a.company_id AND r.id=a.receipt_id
                  JOIN visible c ON c.partner_id=k.partner_id
                  WHERE a.tenant_id=:t AND a.company_id=:c AND r.status='confirmed' AND r.business_date BETWEEN :f AND :d %s
                  UNION ALL
                  SELECT c.owner_user_id,c.owner_department_id,DATE_FORMAT(c.created_at,'%%Y-%%m'),0,0,1 FROM visible c
                  WHERE c.created_at>=:fTime AND c.created_at<:nextDay %s
                ) SELECT owner_user_id,owner_department_id%s,SUM(contract_amount) contract_amount,SUM(receivable_amount) receivable_amount,
                    SUM(new_customer_count) new_customer_count
                  FROM facts GROUP BY owner_user_id,owner_department_id%s ORDER BY receivable_amount DESC,contract_amount DESC LIMIT 500
                """.formatted(filter.replace("c.owner_", "k.owner_"), filter.replace("c.owner_", "k.owner_"), filter, dimension, dimension);
    }
}
