package cn.erp.gl;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import cn.erp.identity.CompanyAccess;

/** 同步命令持有幂等事务，逐条生成及失败记录各自通过独立服务提交。 */
@Service
class GlAdministration {
    private final GlSupport s;
    private final GlSyncService sync;
    private final GlSyncErrors errors;

    GlAdministration(GlSupport s, GlSyncService sync, GlSyncErrors errors) {
        this.s = s;
        this.sync = sync;
        this.errors = errors;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> errors(CompanyAccess a) {
        s.require(a, "gl_config:view");
        return s
                .jdbc
                .queryForList(
                        "SELECT * FROM gl_sync_error WHERE tenant_id=? AND company_id=? AND"
                                + " status='pending' ORDER BY updated_at DESC LIMIT 200",
                        a.tenantId(),
                        a.companyId())
                .stream()
                .map(GlSupport::view)
                .toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> synchronize(CompanyAccess a, String key, long period) {
        s.require(a, "gl_sync:run");
        if (period != 0) s.period(a, period, false);
        return s.execute(
                a,
                "gl_sync.run",
                key,
                Map.of("periodId", period),
                () -> {
                    int done = 0;
                    var failed = new ArrayList<String>();
                    for (long journal : sync.pending(a, period))
                        try {
                            sync.project(a.tenantId(), a.companyId(), journal);
                            done++;
                        } catch (RuntimeException e) {
                            errors.save(a.tenantId(), a.companyId(), journal, e);
                            failed.add(Long.toString(journal));
                        }
                    return Map.of(
                            "generated",
                            done,
                            "failed",
                            failed,
                            "remaining",
                            sync.pending(a, period).size());
                });
    }
}
