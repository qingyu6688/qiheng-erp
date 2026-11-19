package cn.erp.audit;

import cn.erp.shared.ManagedJobs;
import org.springframework.stereotype.Component;

@Component
class AuditSealRunner {
    private final AuditSealService seals;

    AuditSealRunner(ManagedJobs jobs, AuditSealService seals) {
        this.seals = seals;
        jobs.register(AuditSealService.JOB, this::seal);
    }

    private void seal(long tenant) {
        // 分批提交，避免积压时单次任务占用长事务。
        for (int batch = 0; batch < 10; batch++) {
            if (seals.seal(tenant) == 0) {
                break;
            }
        }
    }
}
