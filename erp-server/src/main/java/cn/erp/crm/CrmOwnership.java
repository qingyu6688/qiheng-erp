package cn.erp.crm;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class CrmOwnership {
    private final JdbcTemplate jdbc;

    CrmOwnership(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // 分配、领取和锁定命令先锁公司，避免不同客户并发进入相同拥有数限制。
    void enforce(CompanyAccess actor, long user, long department, String status, boolean locked, long excludePartner) {
        var limits = jdbc.queryForList("""
                SELECT target_type,target_id,max_owned,max_locked,include_sold FROM crm_owner_limit
                WHERE tenant_id=? AND company_id=? AND ((target_type='user' AND target_id=?) OR (target_type='department' AND target_id=?))
                """, actor.tenantId(), actor.companyId(), user, department);
        for (var limit : limits) {
            boolean includeSold = CrmScope.flag(limit.get("include_sold"));
            if (!includeSold && "sold".equals(status)) continue;
            String field = "user".equals(limit.get("target_type")) ? "owner_user_id" : "owner_department_id";
            var counts = jdbc.queryForMap("""
                    SELECT COUNT(*) AS owned, COALESCE(SUM(c.`locked`),0) AS locked_count
                    FROM crm_customer_profile c WHERE c.tenant_id=? AND c.company_id=?
                    AND c.owner_user_id>0 AND c.partner_id<>? AND c.%s=? AND (? OR c.deal_status='unsold')
                    """.formatted(field), actor.tenantId(), actor.companyId(), excludePartner,
                    CrmScope.number(limit, "target_id"), includeSold);
            if (CrmScope.number(counts, "owned") >= CrmScope.number(limit, "max_owned")) {
                throw new ApiException(422, "CRM_OWNER_LIMIT", "员工或部门已达到客户拥有数限制");
            }
            if (locked && CrmScope.number(counts, "locked_count") >= CrmScope.number(limit, "max_locked")) {
                throw new ApiException(422, "CRM_LOCK_LIMIT", "员工或部门已达到已锁定客户数限制");
            }
        }
    }
}
