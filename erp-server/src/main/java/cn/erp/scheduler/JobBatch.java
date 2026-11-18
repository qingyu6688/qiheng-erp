package cn.erp.scheduler;

import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import cn.erp.shared.ManagedJobs;
import java.util.function.IntPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

final class JobBatch implements ManagedJobs.Batch {
    private static final Logger LOG = LoggerFactory.getLogger(JobBatch.class);
    private static final int[] RETRY_MINUTES = {5, 15, 60};
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final long runId;
    private final long tenant;
    private final String code;
    private int failures;

    JobBatch(JdbcTemplate jdbc, TransactionTemplate transaction, long runId, long tenant, String code) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.runId = runId;
        this.tenant = tenant;
        this.code = code;
    }

    int failures() { return failures; }

    @Override
    public void run(long company, String itemKey, long target, Runnable action, IntPredicate retry) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            failures++;
            String reason = reason(failure);
            LOG.warn("任务执行编号 {}，任务 {} 的公司 {}、处理项 {} 未完成，原因 {}",
                    runId, code, company, itemKey, reason);
            try {
                record(company, itemKey, target, reason, retry);
            } catch (RuntimeException recordingFailure) {
                LOG.warn("任务执行编号 {}，任务 {} 的失败记录未保存，异常类型 {}",
                        runId, code, recordingFailure.getClass().getSimpleName());
            }
            return;
        }
        try {
            transaction.executeWithoutResult(status -> jdbc.update("""
                    DELETE FROM job_item_failure WHERE tenant_id=? AND company_id=? AND job_code=? AND item_key=?
                    """, tenant, company, code, itemKey));
        } catch (RuntimeException failure) {
            failures++;
            LOG.warn("任务执行编号 {}，任务 {} 的恢复记录未清理，异常类型 {}",
                    runId, code, failure.getClass().getSimpleName());
        }
    }

    private void record(long company, String itemKey, long target, String reason, IntPredicate retry) {
        transaction.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO job_item_failure(id,tenant_id,company_id,job_code,item_key,target_id,failure_count,error_code,last_failed_at)
                    VALUES(?,?,?,?,?,?,1,?,UTC_TIMESTAMP(3)) AS current_failure
                    ON DUPLICATE KEY UPDATE target_id=current_failure.target_id,
                      failure_count=LEAST(job_item_failure.failure_count+1,?),
                      error_code=current_failure.error_code,last_failed_at=current_failure.last_failed_at
                    """, Ids.next(), tenant, company, code, itemKey, target, reason, Integer.MAX_VALUE);
            int count = jdbc.queryForObject("""
                    SELECT failure_count FROM job_item_failure WHERE tenant_id=? AND company_id=? AND job_code=? AND item_key=?
                    """, Integer.class, tenant, company, code, itemKey);
            int delay = RETRY_MINUTES[Math.min(count, RETRY_MINUTES.length) - 1];
            if (!retry.test(delay)) status.setRollbackOnly();
        });
    }

    private static String reason(RuntimeException failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof ApiException api && api.getErrorCode() != null && api.getErrorCode().matches("[A-Z0-9_]{1,80}"))
                return api.getErrorCode();
            cause = cause.getCause();
        }
        String type = failure.getClass().getSimpleName();
        return type.matches("[A-Za-z0-9_]{1,80}") ? type : "JOB_ITEM_ERROR";
    }
}
