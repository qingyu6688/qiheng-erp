CREATE TABLE wf_form (
    id BIGINT UNSIGNED PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    code VARCHAR(40) NOT NULL,
    name VARCHAR(100) NOT NULL,
    model_id BIGINT UNSIGNED NOT NULL,
    schema_json JSON NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_wf_form_code (tenant_id,company_id,code),
    UNIQUE KEY uk_wf_form_company (tenant_id,company_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE wf_generic_request (
    id BIGINT UNSIGNED PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    form_id BIGINT UNSIGNED NOT NULL,
    form_version BIGINT UNSIGNED NOT NULL,
    model_id BIGINT UNSIGNED NOT NULL,
    request_no VARCHAR(64) NOT NULL,
    title VARCHAR(100) NOT NULL,
    schema_json JSON NOT NULL,
    data_json JSON NOT NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    owner_user_id BIGINT UNSIGNED NOT NULL,
    owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    wf_instance_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_wf_request_no (tenant_id,company_id,request_no),
    KEY idx_wf_request_form (tenant_id,company_id,form_id),
    CONSTRAINT fk_wf_request_form FOREIGN KEY (tenant_id,company_id,form_id) REFERENCES wf_form(tenant_id,company_id,id),
    CHECK (status IN ('pending','approved','rejected','cancelled','terminated'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE wf_delay (
    execution_id VARCHAR(64) PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    instance_id BIGINT UNSIGNED NOT NULL,
    node_key VARCHAR(40) NOT NULL,
    due_at DATETIME(3) NOT NULL,
    KEY idx_wf_delay_due (tenant_id,due_at,execution_id),
    KEY idx_wf_delay_instance (tenant_id,instance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO job_definition(tenant_id,code,name,schedule,enabled,next_run_at)
VALUES (0,'workflow.delay','恢复到期流程延迟节点','interval:1000',1,UTC_TIMESTAMP(3));

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
    (14011,'workflow_form:manage','管理通用审批表单','menu',14011),
    (14012,'wf_generic_request:create','发起通用审批','action',14012),
    (14013,'wf_generic_request:view','查看通用审批','action',14013),
    (14014,'wf_generic_request:approve','审批通用申请','action',14014);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT existing.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r
JOIN tenant t ON t.id=r.tenant_id AND t.status='active'
JOIN sys_permission p ON p.id BETWEEN 14011 AND 14014
CROSS JOIN (SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) existing
WHERE r.status='active' AND (r.code='SYSTEM_ADMIN' OR (r.code='WAREHOUSE_ADMIN' AND t.name='演示企业'))
  AND EXISTS (SELECT 1 FROM sys_role_permission rp JOIN sys_permission previous ON previous.id=rp.permission_id
              WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id AND previous.code='workflow_model:publish');
