CREATE TABLE mfg_bom_guard (
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (tenant_id,company_id),
    CONSTRAINT fk_mfg_bom_guard_company FOREIGN KEY (tenant_id,company_id) REFERENCES company(tenant_id,id)
);

CREATE TABLE mfg_bom (
    id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    sku_id BIGINT UNSIGNED NOT NULL,
    version_no VARCHAR(40) NOT NULL,
    unit_id BIGINT UNSIGNED NOT NULL,
    base_qty DECIMAL(24,6) NOT NULL,
    base_factor DECIMAL(18,6) NOT NULL,
    valid_from DATE NOT NULL,
    valid_to DATE NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) NOT NULL DEFAULT 'draft',
    remark VARCHAR(500) NULL,
    approver_id BIGINT UNSIGNED NULL,
    approved_at DATETIME(3) NULL,
    owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    owner_user_id BIGINT UNSIGNED NOT NULL,
    version INT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_mfg_bom_scope (tenant_id,company_id,id),
    UNIQUE KEY uk_mfg_bom_version (tenant_id,company_id,sku_id,version_no),
    KEY ix_mfg_bom_effective (tenant_id,company_id,status,is_default,sku_id,valid_from,valid_to),
    CONSTRAINT fk_mfg_bom_company FOREIGN KEY (tenant_id,company_id) REFERENCES company(tenant_id,id),
    CONSTRAINT fk_mfg_bom_sku FOREIGN KEY (tenant_id,sku_id) REFERENCES sku(tenant_id,id),
    CONSTRAINT fk_mfg_bom_unit FOREIGN KEY (tenant_id,unit_id) REFERENCES unit(tenant_id,id),
    CONSTRAINT ck_mfg_bom_status CHECK (status IN ('draft','pending','approved','disabled')),
    CONSTRAINT ck_mfg_bom_range CHECK (valid_to IS NULL OR valid_to>=valid_from),
    CONSTRAINT ck_mfg_bom_qty CHECK (base_qty>0 AND base_factor>0)
);

CREATE TABLE mfg_bom_line (
    id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    bom_id BIGINT UNSIGNED NOT NULL,
    line_no SMALLINT UNSIGNED NOT NULL,
    component_sku_id BIGINT UNSIGNED NOT NULL,
    unit_id BIGINT UNSIGNED NOT NULL,
    qty DECIMAL(24,6) NOT NULL,
    base_factor DECIMAL(18,6) NOT NULL,
    scrap_rate DECIMAL(9,6) NOT NULL DEFAULT 0,
    issue_warehouse_id BIGINT UNSIGNED NULL,
    is_phantom BOOLEAN NOT NULL DEFAULT FALSE,
    remark VARCHAR(500) NULL,
    UNIQUE KEY uk_mfg_bom_line_scope (tenant_id,company_id,id),
    UNIQUE KEY uk_mfg_bom_line_no (tenant_id,company_id,bom_id,line_no),
    CONSTRAINT fk_mfg_bom_line_bom FOREIGN KEY (tenant_id,company_id,bom_id) REFERENCES mfg_bom(tenant_id,company_id,id),
    CONSTRAINT fk_mfg_bom_line_sku FOREIGN KEY (tenant_id,component_sku_id) REFERENCES sku(tenant_id,id),
    CONSTRAINT fk_mfg_bom_line_unit FOREIGN KEY (tenant_id,unit_id) REFERENCES unit(tenant_id,id),
    CONSTRAINT fk_mfg_bom_line_warehouse FOREIGN KEY (tenant_id,company_id,issue_warehouse_id) REFERENCES warehouse(tenant_id,company_id,id),
    CONSTRAINT ck_mfg_bom_line_qty CHECK (qty>0 AND base_factor>0 AND scrap_rate BETWEEN 0 AND 100)
);

CREATE TABLE mfg_bom_substitute (
    id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    bom_line_id BIGINT UNSIGNED NOT NULL,
    substitute_sku_id BIGINT UNSIGNED NOT NULL,
    unit_id BIGINT UNSIGNED NOT NULL,
    ratio DECIMAL(24,6) NOT NULL,
    base_ratio DECIMAL(24,6) NOT NULL,
    priority SMALLINT UNSIGNED NOT NULL DEFAULT 1,
    UNIQUE KEY uk_mfg_bom_substitute (tenant_id,company_id,bom_line_id,substitute_sku_id),
    CONSTRAINT fk_mfg_bom_substitute_line FOREIGN KEY (tenant_id,company_id,bom_line_id) REFERENCES mfg_bom_line(tenant_id,company_id,id),
    CONSTRAINT fk_mfg_bom_substitute_sku FOREIGN KEY (tenant_id,substitute_sku_id) REFERENCES sku(tenant_id,id),
    CONSTRAINT fk_mfg_bom_substitute_unit FOREIGN KEY (tenant_id,unit_id) REFERENCES unit(tenant_id,id),
    CONSTRAINT ck_mfg_bom_substitute_ratio CHECK (ratio>0 AND base_ratio>0 AND priority>0)
);

CREATE TABLE mfg_work_order (
    id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    document_no VARCHAR(64) NOT NULL,
    sku_id BIGINT UNSIGNED NOT NULL,
    bom_id BIGINT UNSIGNED NOT NULL,
    unit_id BIGINT UNSIGNED NOT NULL,
    plan_qty DECIMAL(24,6) NOT NULL,
    completed_qty DECIMAL(24,6) NOT NULL DEFAULT 0,
    issue_warehouse_id BIGINT UNSIGNED NOT NULL,
    receive_warehouse_id BIGINT UNSIGNED NOT NULL,
    plan_start_date DATE NOT NULL,
    plan_finish_date DATE NOT NULL,
    source_type VARCHAR(16) NOT NULL DEFAULT 'manual',
    source_id BIGINT UNSIGNED NULL,
    source_line_id BIGINT UNSIGNED NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'draft',
    over_receive_pct DECIMAL(9,6) NOT NULL DEFAULT 0,
    remark VARCHAR(500) NULL,
    owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    owner_user_id BIGINT UNSIGNED NOT NULL,
    version INT UNSIGNED NOT NULL DEFAULT 0,
    released_at DATETIME(3) NULL,
    closed_at DATETIME(3) NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    updated_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_mfg_order_scope (tenant_id,company_id,id),
    UNIQUE KEY uk_mfg_order_no (tenant_id,company_id,document_no),
    KEY ix_mfg_order_status (tenant_id,company_id,status,id),
    KEY ix_mfg_order_sales_line (tenant_id,company_id,source_line_id,status,id),
    CONSTRAINT fk_mfg_order_bom FOREIGN KEY (tenant_id,company_id,bom_id) REFERENCES mfg_bom(tenant_id,company_id,id),
    CONSTRAINT fk_mfg_order_sku FOREIGN KEY (tenant_id,sku_id) REFERENCES sku(tenant_id,id),
    CONSTRAINT fk_mfg_order_unit FOREIGN KEY (tenant_id,unit_id) REFERENCES unit(tenant_id,id),
    CONSTRAINT fk_mfg_order_issue_wh FOREIGN KEY (tenant_id,company_id,issue_warehouse_id) REFERENCES warehouse(tenant_id,company_id,id),
    CONSTRAINT fk_mfg_order_receive_wh FOREIGN KEY (tenant_id,company_id,receive_warehouse_id) REFERENCES warehouse(tenant_id,company_id,id),
    CONSTRAINT fk_mfg_order_sales FOREIGN KEY (tenant_id,company_id,source_id) REFERENCES sales_order(tenant_id,company_id,id),
    CONSTRAINT fk_mfg_order_sales_line FOREIGN KEY (tenant_id,company_id,source_line_id) REFERENCES sales_order_line(tenant_id,company_id,id),
    CONSTRAINT ck_mfg_order_source CHECK ((source_type='manual' AND source_id IS NULL AND source_line_id IS NULL)
        OR (source_type='sales_order' AND source_id IS NOT NULL AND source_line_id IS NOT NULL)),
    CONSTRAINT ck_mfg_order_status CHECK (status IN ('draft','released','in_progress','completed','closed','cancelled')),
    CONSTRAINT ck_mfg_order_qty CHECK (plan_qty>0 AND completed_qty>=0 AND over_receive_pct BETWEEN 0 AND 100),
    CONSTRAINT ck_mfg_order_dates CHECK (plan_finish_date>=plan_start_date)
);

CREATE TABLE mfg_work_order_material (
    id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    work_order_id BIGINT UNSIGNED NOT NULL,
    line_no SMALLINT UNSIGNED NOT NULL,
    bom_line_id BIGINT UNSIGNED NOT NULL,
    component_sku_id BIGINT UNSIGNED NOT NULL,
    unit_id BIGINT UNSIGNED NOT NULL,
    issue_warehouse_id BIGINT UNSIGNED NOT NULL,
    required_qty DECIMAL(24,6) NOT NULL,
    issued_qty DECIMAL(24,6) NOT NULL DEFAULT 0,
    returned_qty DECIMAL(24,6) NOT NULL DEFAULT 0,
    path_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_mfg_material_scope (tenant_id,company_id,id),
    UNIQUE KEY uk_mfg_material_line (tenant_id,company_id,work_order_id,line_no),
    KEY ix_mfg_material_warehouse (tenant_id,company_id,work_order_id,issue_warehouse_id),
    CONSTRAINT fk_mfg_material_order FOREIGN KEY (tenant_id,company_id,work_order_id) REFERENCES mfg_work_order(tenant_id,company_id,id),
    CONSTRAINT fk_mfg_material_bom_line FOREIGN KEY (tenant_id,company_id,bom_line_id) REFERENCES mfg_bom_line(tenant_id,company_id,id),
    CONSTRAINT fk_mfg_material_sku FOREIGN KEY (tenant_id,component_sku_id) REFERENCES sku(tenant_id,id),
    CONSTRAINT fk_mfg_material_unit FOREIGN KEY (tenant_id,unit_id) REFERENCES unit(tenant_id,id),
    CONSTRAINT fk_mfg_material_warehouse FOREIGN KEY (tenant_id,company_id,issue_warehouse_id) REFERENCES warehouse(tenant_id,company_id,id),
    CONSTRAINT ck_mfg_material_qty CHECK (required_qty>0 AND issued_qty>=0 AND returned_qty>=0 AND returned_qty<=issued_qty)
);

CREATE TABLE mfg_work_order_substitute (
    id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    work_order_material_id BIGINT UNSIGNED NOT NULL,
    substitute_sku_id BIGINT UNSIGNED NOT NULL,
    unit_id BIGINT UNSIGNED NOT NULL,
    ratio DECIMAL(24,6) NOT NULL,
    priority SMALLINT UNSIGNED NOT NULL,
    UNIQUE KEY uk_mfg_order_substitute (tenant_id,company_id,work_order_material_id,substitute_sku_id),
    CONSTRAINT fk_mfg_order_substitute_material FOREIGN KEY (tenant_id,company_id,work_order_material_id) REFERENCES mfg_work_order_material(tenant_id,company_id,id),
    CONSTRAINT fk_mfg_order_substitute_sku FOREIGN KEY (tenant_id,substitute_sku_id) REFERENCES sku(tenant_id,id),
    CONSTRAINT fk_mfg_order_substitute_unit FOREIGN KEY (tenant_id,unit_id) REFERENCES unit(tenant_id,id),
    CONSTRAINT ck_mfg_order_substitute_ratio CHECK (ratio>0 AND priority>0)
);

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
    (17000,'mfg_bom:view','查看物料清单','action',17000),
    (17001,'mfg_bom:create','新建物料清单','action',17001),
    (17002,'mfg_bom:update','编辑物料清单','action',17002),
    (17003,'mfg_bom:submit','提交物料清单','action',17003),
    (17004,'mfg_bom:approve','审核物料清单','action',17004),
    (17005,'mfg_bom:disable','停用物料清单','action',17005),
    (17006,'mfg_work_order:view','查看生产工单','action',17006),
    (17007,'mfg_work_order:create','新建生产工单','action',17007),
    (17008,'mfg_work_order:update','编辑生产工单','action',17008),
    (17009,'mfg_work_order:release','下达生产工单','action',17009),
    (17010,'mfg_work_order:close','关闭生产工单','action',17010),
    (17011,'mfg_work_order:cancel','取消生产工单','action',17011);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT base.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN sys_permission p ON p.id BETWEEN 17000 AND 17011
CROSS JOIN (SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) base
WHERE r.status='active' AND r.code='SYSTEM_ADMIN'
  AND EXISTS (SELECT 1 FROM sys_role_permission rp JOIN sys_permission old ON old.id=rp.permission_id
              WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id AND old.code='role:assign_permission');
