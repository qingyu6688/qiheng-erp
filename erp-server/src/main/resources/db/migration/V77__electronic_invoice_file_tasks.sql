CREATE TABLE einv_file_task (
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    request_id BIGINT UNSIGNED NOT NULL,
    variant VARCHAR(6) NOT NULL,
    file_format VARCHAR(3) NOT NULL,
    next_attempt_at DATETIME(3) NOT NULL,
    lease_owner VARCHAR(36) NULL,
    lease_until DATETIME(3) NULL,
    attempts INT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id,company_id,request_id,variant,file_format),
    KEY idx_einv_file_task_due(tenant_id,next_attempt_at),
    CONSTRAINT fk_einv_file_task_request FOREIGN KEY (tenant_id,company_id,request_id) REFERENCES einv_issue_request(tenant_id,company_id,id),
    CONSTRAINT ck_einv_file_task_variant CHECK (variant IN ('issue','red')),
    CONSTRAINT ck_einv_file_task_format CHECK (file_format IN ('pdf','ofd','xml'))
);

ALTER TABLE einv_inbound
    ADD COLUMN version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    ADD UNIQUE KEY uk_einv_inbound_payable(tenant_id,company_id,payable_id);

INSERT INTO job_definition(tenant_id,code,name,schedule) VALUES
    (0,'einv.files','下载数电发票版式文件','interval:10000');
