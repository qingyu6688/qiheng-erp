package cn.erp.crm;

import cn.erp.shared.ManagedJobs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class CrmPoolJobs {
    private final JdbcTemplate jdbc;
    private final CrmPoolSettings settings;

    CrmPoolJobs(JdbcTemplate jdbc, CrmPoolSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @Autowired
    void managed(ManagedJobs jobs) {
        jobs.register("crm.pool_reclaim", this::run);
    }

    private void run(long tenant) {
        for (long company : jdbc.queryForList("""
                SELECT r.company_id FROM crm_pool_rule r JOIN company c ON c.tenant_id=r.tenant_id AND c.id=r.company_id AND c.status='active'
                WHERE r.tenant_id=? AND r.enabled=1 ORDER BY r.company_id
                """, Long.class, tenant)) {
            long after = 0;
            do { after = settings.reclaimBatch(tenant, company, after); }
            while (after != 0);
        }
    }
}
