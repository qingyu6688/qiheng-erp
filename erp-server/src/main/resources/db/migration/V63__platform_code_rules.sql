CREATE TABLE code_rule (
    id BIGINT UNSIGNED NOT NULL,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    target VARCHAR(64) NOT NULL,
    segments JSON NOT NULL,
    reset_cycle VARCHAR(8) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_code_rule_scope (tenant_id, company_id, target),
    UNIQUE KEY uk_code_rule_tenant_id (tenant_id, id),
    CONSTRAINT fk_code_rule_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    CONSTRAINT ck_code_rule_reset CHECK (reset_cycle IN ('none','year','month','day'))
);

CREATE TABLE code_rule_seq (
    tenant_id BIGINT UNSIGNED NOT NULL,
    rule_id BIGINT UNSIGNED NOT NULL,
    scope_key BIGINT UNSIGNED NOT NULL,
    cycle_key VARCHAR(8) NOT NULL,
    next_value BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (tenant_id, rule_id, scope_key, cycle_key),
    CONSTRAINT fk_code_rule_seq_rule FOREIGN KEY (tenant_id, rule_id) REFERENCES code_rule(tenant_id, id)
);

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
    (14046,'code_rule:manage','管理编码规则','menu',14046),
    (14047,'custom_field:manage','管理自定义字段','menu',14047);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT m.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN tenant t ON t.id=r.tenant_id
JOIN sys_permission p ON p.id IN (14046,14047)
CROSS JOIN (SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) m
WHERE (r.code='SYSTEM_ADMIN' OR (r.code='WAREHOUSE_ADMIN' AND t.name='演示企业'))
  AND EXISTS (SELECT 1 FROM sys_role_permission rp JOIN sys_permission prior ON prior.id=rp.permission_id
              WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id AND prior.code='role:assign_permission');
