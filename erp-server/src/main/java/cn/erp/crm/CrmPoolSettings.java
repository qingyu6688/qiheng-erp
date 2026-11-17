package cn.erp.crm;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.AuditService;
import cn.erp.shared.CrmReminder;
import cn.erp.sysadmin.DictionaryService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
class CrmPoolSettings {
    private static final int JOB_BATCH = 200;
    private static final String RULE_FILTER = "(JSON_LENGTH(r.apply_to_levels)=0 OR JSON_CONTAINS(r.apply_to_levels,JSON_QUOTE(c.level)))";
    private static final String DUE_DATE = """
            LEAST(TIMESTAMPADD(DAY,r.no_follow_days,GREATEST(COALESCE(c.last_follow_at,c.created_at),c.owner_since_at)),
                  IF(c.deal_status='unsold',TIMESTAMPADD(DAY,r.no_deal_days,GREATEST(c.created_at,c.owner_since_at)),'9999-12-31'))
            """;
    private final JdbcTemplate jdbc;
    private final CrmScope scope;
    private final ObjectMapper json;
    private final DictionaryService dictionaries;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    CrmPoolSettings(JdbcTemplate jdbc, CrmScope scope, ObjectMapper json, DictionaryService dictionaries, AuditService audit,
                    ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.json = json;
        this.dictionaries = dictionaries;
        this.audit = audit;
        this.events = events;
    }

    Map<String, Object> rule(CompanyAccess actor) {
        requireCompanyDepartments(actor);
        var current = findRule(actor);
        if (current.isEmpty()) return Map.of("enabled", false, "noFollowDays", 30, "noDealDays", 90,
                "notifyBeforeDays", 3, "applyToLevels", List.of(), "version", 0L);
        var row = CrmCoreRows.row(current);
        var levels = new ArrayList<String>();
        json.readTree(row.get("applyToLevels").toString()).forEach(level -> levels.add(level.stringValue()));
        row.put("applyToLevels", List.copyOf(levels));
        return row;
    }

    @Transactional
    public Map<String, Object> saveRule(CompanyAccess actor, CrmCoreModels.PoolRuleInput input) {
        requireCompanyDepartments(actor);
        scope.lockCompany(actor);
        if (input.notifyBeforeDays() >= Math.min(input.noFollowDays(), input.noDealDays())) {
            throw CrmScope.invalid("提醒天数须小于两项回收天数");
        }
        var levels = input.applyToLevels() == null ? List.<String>of() : input.applyToLevels().stream().distinct().toList();
        levels.forEach(level -> dictionaries.validateValue(actor.tenantId(), "crm_level", level, false));
        var prior = findRule(actor);
        checkVersion(prior, input.version());
        jdbc.update("""
                INSERT INTO crm_pool_rule(tenant_id,company_id,enabled,no_follow_days,no_deal_days,notify_before_days,apply_to_levels,version)
                VALUES(?,?,?,?,?,?,?,1) AS staged ON DUPLICATE KEY UPDATE enabled=staged.enabled,no_follow_days=staged.no_follow_days,
                    no_deal_days=staged.no_deal_days,notify_before_days=staged.notify_before_days,apply_to_levels=staged.apply_to_levels,
                    version=crm_pool_rule.version+1
                """, actor.tenantId(), actor.companyId(), input.enabled(), input.noFollowDays(), input.noDealDays(), input.notifyBeforeDays(), json.writeValueAsString(levels));
        var after = rule(actor);
        audit.record(actor, "crm_pool_rule.save", "crm_pool_rule", actor.companyId(), CrmCoreRows.row(prior), after);
        return after;
    }

    Map<String, Object> limits(CompanyAccess actor) {
        requireCompanyDepartments(actor);
        var rows = jdbc.queryForList("""
                SELECT l.*,COALESCE(u.display_name,d.name) AS target_name FROM crm_owner_limit l
                LEFT JOIN sys_user u ON l.target_type='user' AND u.tenant_id=l.tenant_id AND u.id=l.target_id
                LEFT JOIN org_department d ON l.target_type='department' AND d.tenant_id=l.tenant_id AND d.company_id=l.company_id AND d.id=l.target_id
                WHERE l.tenant_id=? AND l.company_id=? ORDER BY l.target_type,l.target_id
                """, actor.tenantId(), actor.companyId());
        return Map.of("items", rows.stream().map(CrmCoreRows::row).toList());
    }

    List<Map<String, Object>> departmentOptions(CompanyAccess actor) {
        requireCompanyDepartments(actor);
        var rows = jdbc.queryForList("SELECT id,name FROM org_department WHERE tenant_id=? AND company_id=? AND status='active' ORDER BY path,id",
                actor.tenantId(), actor.companyId());
        return rows.stream().map(CrmCoreRows::row).toList();
    }

    @Transactional
    public Map<String, Object> saveLimit(CompanyAccess actor, CrmCoreModels.OwnerLimitInput input) {
        requireCompanyDepartments(actor);
        scope.lockCompany(actor);
        if (input.maxLocked() > input.maxOwned()) throw CrmScope.invalid("锁定数不能超过拥有数");
        if ("user".equals(input.targetType())) scope.memberDepartment(actor, input.targetId());
        else if (jdbc.queryForList("SELECT id FROM org_department WHERE tenant_id=? AND company_id=? AND id=? AND status='active'",
                Long.class, actor.tenantId(), actor.companyId(), input.targetId()).isEmpty()) {
            throw CrmScope.invalid("请选择当前公司的有效部门");
        }
        var previous = jdbc.queryForList("SELECT * FROM crm_owner_limit WHERE tenant_id=? AND company_id=? AND target_type=? AND target_id=?",
                actor.tenantId(), actor.companyId(), input.targetType(), input.targetId());
        var prior = previous.isEmpty() ? Map.<String,Object>of() : previous.getFirst();
        checkVersion(prior, input.version());
        jdbc.update("""
                INSERT INTO crm_owner_limit(tenant_id,company_id,target_type,target_id,max_owned,max_locked,include_sold,version)
                VALUES(?,?,?,?,?,?,?,1) AS staged ON DUPLICATE KEY UPDATE max_owned=staged.max_owned,max_locked=staged.max_locked,
                    include_sold=staged.include_sold,version=crm_owner_limit.version+1
                """, actor.tenantId(), actor.companyId(), input.targetType(), input.targetId(), input.maxOwned(), input.maxLocked(), input.includeSold());
        var row = CrmCoreRows.row(jdbc.queryForMap("SELECT * FROM crm_owner_limit WHERE tenant_id=? AND company_id=? AND target_type=? AND target_id=?",
                actor.tenantId(), actor.companyId(), input.targetType(), input.targetId()));
        audit.record(actor, "crm_owner_limit.save", "crm_owner_limit", input.targetId(), CrmCoreRows.row(prior), row);
        return row;
    }

    @Transactional
    public Map<String, Object> deleteLimit(CompanyAccess actor, String type, long id, long version) {
        requireCompanyDepartments(actor);
        scope.lockCompany(actor);
        if (!Set.of("user", "department").contains(type)) throw CrmScope.invalid("限制类型无效");
        var current = jdbc.queryForList("SELECT * FROM crm_owner_limit WHERE tenant_id=? AND company_id=? AND target_type=? AND target_id=? FOR UPDATE",
                actor.tenantId(), actor.companyId(), type, id);
        if (current.isEmpty()) return Map.of("deleted", false);
        CrmScope.version(current.getFirst(), version);
        jdbc.update("DELETE FROM crm_owner_limit WHERE tenant_id=? AND company_id=? AND target_type=? AND target_id=?",
                actor.tenantId(), actor.companyId(), type, id);
        audit.record(actor, "crm_owner_limit.delete", "crm_owner_limit", id, CrmCoreRows.row(current.getFirst()), null);
        return Map.of("deleted", true);
    }

    private void checkVersion(Map<String, Object> row, Long version) {
        if (!row.isEmpty()) CrmScope.version(row, version);
        else if (version != null && version != 0) CrmScope.version(Map.of("version", 0L), version);
    }

    private Map<String, Object> findRule(CompanyAccess actor) {
        var rows = jdbc.queryForList("SELECT * FROM crm_pool_rule WHERE tenant_id=? AND company_id=?", actor.tenantId(), actor.companyId());
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    private void requireCompanyDepartments(CompanyAccess actor) {
        if (!actor.orgScope().allDepartments()) throw CrmScope.forbidden("公海规则需要当前公司全部部门的权限");
    }

    long upcoming(CompanyAccess actor) {
        return jdbc.queryForObject("""
                WITH candidates AS (SELECT r.notify_before_days,%s AS due_at
                FROM crm_customer_profile c JOIN crm_pool_rule r ON r.tenant_id=c.tenant_id AND r.company_id=c.company_id AND r.enabled=1
                JOIN business_partner p ON p.tenant_id=c.tenant_id AND p.id=c.partner_id AND p.status='active'
                JOIN partner_role pr ON pr.tenant_id=p.tenant_id AND pr.partner_id=p.id AND pr.role='customer' AND pr.status='active'
                WHERE c.tenant_id=? AND c.company_id=? AND c.owner_user_id=? AND c.`locked`=0 AND %s AND (%s))
                SELECT COUNT(*) FROM candidates WHERE due_at>UTC_TIMESTAMP(3) AND due_at<=TIMESTAMPADD(DAY,notify_before_days,UTC_TIMESTAMP(3))
                """.formatted(DUE_DATE, RULE_FILTER, scope.customerFilter(actor, "c")),
                Long.class, actor.tenantId(), actor.companyId(), actor.userId());
    }

    /** 一批一个事务，配置关闭、重新跟进、锁定和自动回收按同一公司锁顺序生效。 */
    @Transactional
    public long reclaimBatch(long tenant, long company, long after) {
        var actor = new CompanyAccess(tenant, 0, company, true, Set.of(), Set.of());
        scope.lockCompany(actor);
        var rows = jdbc.queryForList("""
                WITH candidates AS (SELECT c.partner_id,c.owner_user_id,c.version,r.notify_before_days,%s AS due_at
                FROM crm_customer_profile c JOIN crm_pool_rule r ON r.tenant_id=c.tenant_id AND r.company_id=c.company_id AND r.enabled=1
                JOIN business_partner p ON p.tenant_id=c.tenant_id AND p.id=c.partner_id AND p.status='active'
                JOIN partner_role pr ON pr.tenant_id=p.tenant_id AND pr.partner_id=p.id AND pr.role='customer' AND pr.status='active'
                WHERE c.tenant_id=? AND c.company_id=? AND c.owner_user_id>0 AND c.`locked`=0 AND c.partner_id>? AND %s)
                SELECT partner_id,owner_user_id,version,(due_at<=UTC_TIMESTAMP(3)) AS expired FROM candidates
                WHERE due_at<=TIMESTAMPADD(DAY,notify_before_days,UTC_TIMESTAMP(3))
                ORDER BY c.partner_id LIMIT ?
                """.formatted(DUE_DATE, RULE_FILTER), tenant, company, after, JOB_BATCH);
        for (var row : rows) {
            long partner = CrmScope.number(row, "partner_id"), owner = CrmScope.number(row, "owner_user_id");
            boolean reached = CrmScope.flag(row.get("expired"));
            if (reached) reclaim(actor, partner, owner);
            else events.publishEvent(new CrmReminder(tenant, company, owner, "pool", "crm:pool:" + partner + ":v" + row.get("version")));
        }
        return rows.size() == JOB_BATCH ? CrmScope.number(rows.getLast(), "partner_id") : 0;
    }

    private void reclaim(CompanyAccess actor, long partner, long owner) {
        int changed = jdbc.update("""
                UPDATE crm_customer_profile SET owner_user_id=0,owner_department_id=0,pool_entered_at=UTC_TIMESTAMP(3),
                    pool_reason='automatic',version=version+1 WHERE tenant_id=? AND company_id=? AND partner_id=? AND owner_user_id=? AND `locked`=0
                """, actor.tenantId(), actor.companyId(), partner, owner);
        if (changed == 1) {
            jdbc.update("DELETE FROM crm_customer_team WHERE tenant_id=? AND company_id=? AND partner_id=?", actor.tenantId(), actor.companyId(), partner);
            audit.record(actor, "crm_customer.reclaim", "crm_customer_profile", partner, Map.of("ownerUserId", owner), Map.of("poolReason", "automatic"));
        }
    }
}
