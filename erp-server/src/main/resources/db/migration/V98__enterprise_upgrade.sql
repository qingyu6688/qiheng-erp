RENAME TABLE sys_user_data_scope TO org_user_data_scope;

ALTER TABLE purchase_requisition ADD COLUMN wf_instance_id BIGINT UNSIGNED NOT NULL DEFAULT 0;
ALTER TABLE stock_count ADD COLUMN wf_instance_id BIGINT UNSIGNED NOT NULL DEFAULT 0;
ALTER TABLE stock_misc_doc ADD COLUMN wf_instance_id BIGINT UNSIGNED NOT NULL DEFAULT 0;
ALTER TABLE gl_voucher ADD COLUMN wf_instance_id BIGINT UNSIGNED NOT NULL DEFAULT 0;
ALTER TABLE mfg_bom ADD COLUMN wf_instance_id BIGINT UNSIGNED NOT NULL DEFAULT 0;
ALTER TABLE mfg_receipt_doc ADD COLUMN wf_instance_id BIGINT UNSIGNED NOT NULL DEFAULT 0;

CREATE TABLE upgrade_log (
    run_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    step VARCHAR(40) NOT NULL,
    details JSON NOT NULL,
    completed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (run_id, step),
    KEY idx_upgrade_log_step (step, completed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 目录中的稳定编号沿用原授权。若曾手工登记过新码，升级执行器将合并两组角色关联。
UPDATE sys_permission legacy
LEFT JOIN sys_permission current_code ON current_code.code='crm_statistic:view'
SET legacy.code='crm_statistic:view'
WHERE legacy.code='crm_statistics:view' AND current_code.id IS NULL;
