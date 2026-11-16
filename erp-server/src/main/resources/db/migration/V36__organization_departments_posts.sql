CREATE TABLE org_department (
    id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    parent_id BIGINT UNSIGNED NULL,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    leader_user_id BIGINT UNSIGNED NULL,
    sort_no INT UNSIGNED NOT NULL DEFAULT 0,
    path VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    depth SMALLINT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    version INT UNSIGNED NOT NULL DEFAULT 0,
    created_by BIGINT UNSIGNED NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT UNSIGNED NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_org_department_code (tenant_id,company_id,code),
    UNIQUE KEY uk_org_department_scope (tenant_id,company_id,id),
    KEY idx_org_department_path (tenant_id,company_id,path),
    KEY idx_org_department_parent (tenant_id,company_id,parent_id,sort_no),
    CONSTRAINT fk_org_department_company FOREIGN KEY (tenant_id,company_id) REFERENCES company(tenant_id,id),
    CONSTRAINT fk_org_department_parent FOREIGN KEY (tenant_id,company_id,parent_id) REFERENCES org_department(tenant_id,company_id,id),
    CONSTRAINT fk_org_department_leader FOREIGN KEY (tenant_id,leader_user_id) REFERENCES sys_user(tenant_id,id),
    CONSTRAINT ck_org_department_status CHECK (status IN ('active','disabled')),
    CONSTRAINT ck_org_department_depth CHECK (depth BETWEEN 1 AND 20)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='公司部门树';

CREATE TABLE org_post (
    id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    sort_no INT UNSIGNED NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    version INT UNSIGNED NOT NULL DEFAULT 0,
    created_by BIGINT UNSIGNED NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT UNSIGNED NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_org_post_code (tenant_id,code),
    UNIQUE KEY uk_org_post_scope (tenant_id,id),
    CONSTRAINT fk_org_post_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    CONSTRAINT ck_org_post_status CHECK (status IN ('active','disabled'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='企业共享岗位定义';

CREATE TABLE org_user_company (
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    version INT UNSIGNED NOT NULL DEFAULT 0,
    created_by BIGINT UNSIGNED NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by BIGINT UNSIGNED NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (tenant_id,company_id,user_id),
    CONSTRAINT fk_org_user_company_company FOREIGN KEY (tenant_id,company_id) REFERENCES company(tenant_id,id),
    CONSTRAINT fk_org_user_company_user FOREIGN KEY (tenant_id,user_id) REFERENCES sys_user(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户在公司内的组织配置版本';

CREATE TABLE org_user_department (
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    department_id BIGINT UNSIGNED NOT NULL,
    is_primary TINYINT(1) NOT NULL DEFAULT 0,
    primary_company_key BIGINT UNSIGNED GENERATED ALWAYS AS (IF(is_primary=1,company_id,NULL)) STORED,
    PRIMARY KEY (tenant_id,company_id,user_id,department_id),
    UNIQUE KEY uk_org_user_primary_company (tenant_id,user_id,primary_company_key),
    KEY idx_org_department_members (tenant_id,company_id,department_id,user_id),
    CONSTRAINT fk_org_user_department_config FOREIGN KEY (tenant_id,company_id,user_id) REFERENCES org_user_company(tenant_id,company_id,user_id),
    CONSTRAINT fk_org_user_department_department FOREIGN KEY (tenant_id,company_id,department_id) REFERENCES org_department(tenant_id,company_id,id),
    CONSTRAINT ck_org_user_department_primary CHECK (is_primary IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='公司内用户部门关系';

CREATE TABLE org_user_post (
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    post_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (tenant_id,company_id,user_id,post_id),
    KEY idx_org_post_members (tenant_id,post_id,company_id,user_id),
    CONSTRAINT fk_org_user_post_config FOREIGN KEY (tenant_id,company_id,user_id) REFERENCES org_user_company(tenant_id,company_id,user_id),
    CONSTRAINT fk_org_user_post_post FOREIGN KEY (tenant_id,post_id) REFERENCES org_post(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='公司内用户岗位任职';

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
    (118,'org_department:view','查看部门与成员','menu',118),
    (119,'org_department:create','新建部门','action',119),
    (120,'org_department:update','修改与移动部门','action',120),
    (121,'org_post:view','查看岗位','menu',121),
    (122,'org_post:create','新建岗位','action',122),
    (123,'org_post:update','修改岗位','action',123);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT existing.max_id + ROW_NUMBER() OVER (ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN tenant t ON t.id=r.tenant_id
JOIN sys_permission p ON p.id BETWEEN 118 AND 123
CROSS JOIN (SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) existing
WHERE r.status='active'
  AND (r.code='SYSTEM_ADMIN' OR (r.code='WAREHOUSE_ADMIN' AND t.name='演示企业'))
  AND (SELECT COUNT(*) FROM sys_role_permission rp JOIN sys_permission existing_permission ON existing_permission.id=rp.permission_id
       WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id
         AND existing_permission.code IN ('user:update','role:assign_permission','user:assign_scope'))=3;
