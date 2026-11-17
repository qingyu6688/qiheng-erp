CREATE TABLE portal_sensitive_word (
    id BIGINT UNSIGNED NOT NULL,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    word VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version INT UNSIGNED NOT NULL DEFAULT 0,
    created_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_portal_sensitive_word (tenant_id,company_id,word),
    KEY idx_portal_word_company (tenant_id,company_id,id),
    CONSTRAINT fk_portal_word_company FOREIGN KEY (tenant_id,company_id) REFERENCES company(tenant_id,id)
);

CREATE TABLE portal_content_hit (
    id BIGINT UNSIGNED NOT NULL,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    direction VARCHAR(8) NOT NULL,
    hit_count INT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_portal_hit_company (tenant_id,company_id,created_at,id),
    CONSTRAINT fk_portal_hit_company FOREIGN KEY (tenant_id,company_id) REFERENCES company(tenant_id,id),
    CONSTRAINT ck_portal_hit_direction CHECK (direction IN ('input','output')),
    CONSTRAINT ck_portal_hit_count CHECK (hit_count>0)
);

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
    (14720,'portal_sensitive_word:view','查看客服敏感词','menu',14720),
    (14721,'portal_sensitive_word:manage','维护客服敏感词','action',14721);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT m.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN sys_permission p ON p.id BETWEEN 14720 AND 14721
CROSS JOIN (SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) m
WHERE r.code='SYSTEM_ADMIN' AND r.status='active'
  AND EXISTS (SELECT 1 FROM sys_role_permission rp JOIN sys_permission prior ON prior.id=rp.permission_id
    WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id AND prior.code='role:assign_permission');
