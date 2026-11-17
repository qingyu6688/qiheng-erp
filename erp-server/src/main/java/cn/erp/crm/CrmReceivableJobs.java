package cn.erp.crm;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiException;
import cn.erp.shared.CrmReminder;
import cn.erp.shared.ManagedJobs;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import static cn.erp.crm.CrmCycleData.*;

@Component
class CrmReceivableJobs {
    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final CrmScope scope;
    private final ApplicationEventPublisher events;

    CrmReceivableJobs(JdbcTemplate jdbc, AccessService access, CrmScope scope, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.access = access;
        this.scope = scope;
        this.events = events;
    }

    @Autowired
    void register(ManagedJobs jobs) { jobs.register("crm.receivable_remind", this::run); }

    void run(long tenant) {
        long after = 0;
        var day = today();
        while (true) {
            var plans = jdbc.queryForList("""
                    SELECT p.id,p.company_id,p.plan_date,c.partner_id,c.owner_user_id FROM crm_receivable_plan p
                    JOIN crm_contract k ON k.tenant_id=p.tenant_id AND k.company_id=p.company_id AND k.id=p.contract_id AND k.status='approved'
                    JOIN crm_customer_profile c ON c.tenant_id=k.tenant_id AND c.company_id=k.company_id AND c.partner_id=k.partner_id AND c.owner_user_id>0
                    JOIN company co ON co.tenant_id=p.tenant_id AND co.id=p.company_id AND co.status='active'
                    WHERE p.tenant_id=? AND p.id>? AND p.plan_date BETWEEN ? AND DATE_ADD(?,INTERVAL p.remind_days DAY)
                      AND p.plan_amount>COALESCE((SELECT SUM(a.amount) FROM crm_receivable a
                      JOIN receipt r ON r.tenant_id=a.tenant_id AND r.company_id=a.company_id AND r.id=a.receipt_id
                      WHERE a.tenant_id=p.tenant_id AND a.company_id=p.company_id AND a.plan_id=p.id AND r.status='confirmed'),0)
                    ORDER BY p.id LIMIT 200
                    """, tenant, after, day, day);
            if (plans.isEmpty()) return;
            for (var plan : plans) {
                after = number(plan, "id");
                long company = number(plan, "company_id"), owner = number(plan, "owner_user_id");
                var actor = access.forUser(tenant, owner, company);
                if (actor == null || !actor.hasPermission("crm_receivable:view")) continue;
                try {
                    scope.requireCustomer(actor, number(plan, "partner_id"), false, false);
                } catch (ApiException denied) {
                    if (!Set.of(403, 404).contains(denied.getStatus())) throw denied;
                    continue;
                }
                events.publishEvent(new CrmReminder(tenant, company, owner, "receivable",
                        "crm:receipt:" + after + ":" + plan.get("plan_date")));
            }
        }
    }
}
