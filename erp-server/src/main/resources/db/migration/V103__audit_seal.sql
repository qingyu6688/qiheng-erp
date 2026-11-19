ALTER TABLE audit_log ADD KEY idx_audit_seal_source (tenant_id, id);

CREATE TABLE audit_seal (
    tenant_id BIGINT UNSIGNED NOT NULL,
    batch_no BIGINT UNSIGNED NOT NULL,
    row_count INT UNSIGNED NOT NULL,
    previous_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    batch_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sealed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (tenant_id, batch_no)
) ENGINE=InnoDB;

CREATE TABLE audit_seal_member (
    tenant_id BIGINT UNSIGNED NOT NULL,
    audit_id BIGINT UNSIGNED NOT NULL,
    batch_no BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (tenant_id, audit_id),
    KEY idx_audit_seal_batch (tenant_id, batch_no, audit_id),
    CONSTRAINT fk_audit_seal_member_batch FOREIGN KEY (tenant_id, batch_no)
        REFERENCES audit_seal (tenant_id, batch_no)
) ENGINE=InnoDB;

CREATE SQL SECURITY INVOKER VIEW audit_seal_source AS
SELECT tenant_id, id AS audit_id,
    SHA2(CAST(JSON_ARRAY(tenant_id,id,company_id,operator_id,action,object_type,object_id,
        before_data,after_data,reason,request_id,client_ip,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s.%f'))
        AS CHAR CHARACTER SET utf8mb4),256) AS row_hash
FROM audit_log;

INSERT INTO job_definition(tenant_id, code, name, schedule, enabled)
VALUES (0, 'audit.seal', '审计日志封存', 'interval:300000', 1);
