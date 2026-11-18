ALTER TABLE job_run_log
    DROP CHECK job_run_log_chk_2,
    ADD CONSTRAINT ck_job_run_status CHECK(status IN('pending','running','success','failed','skipped','partial_failed'));

CREATE TABLE job_item_failure (
    id BIGINT UNSIGNED PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    job_code VARCHAR(64) NOT NULL,
    item_key VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    target_id BIGINT UNSIGNED NOT NULL,
    failure_count INT NOT NULL,
    error_code VARCHAR(80) NOT NULL,
    last_failed_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_job_failure_item(tenant_id,company_id,job_code,item_key),
    KEY idx_job_failure_target(tenant_id,company_id,target_id,job_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='固定任务的待恢复处理项及连续失败次数';
