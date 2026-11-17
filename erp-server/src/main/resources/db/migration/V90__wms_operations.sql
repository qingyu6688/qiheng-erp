CREATE TABLE wms_location_rule (
    id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    warehouse_id BIGINT UNSIGNED NOT NULL,
    sku_id BIGINT UNSIGNED NULL,
    category_id BIGINT UNSIGNED NULL,
    zone_id BIGINT UNSIGNED NULL,
    fixed_location_id BIGINT UNSIGNED NULL,
    min_qty DECIMAL(24,6) NULL,
    max_qty DECIMAL(24,6) NULL,
    owner_user_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_wms_rule_scope (tenant_id,company_id,warehouse_id,id),
    UNIQUE KEY uk_wms_rule_sku (tenant_id,company_id,warehouse_id,sku_id),
    UNIQUE KEY uk_wms_rule_category (tenant_id,company_id,warehouse_id,category_id),
    CONSTRAINT fk_wms_rule_warehouse FOREIGN KEY (tenant_id,company_id,warehouse_id)
        REFERENCES warehouse(tenant_id,company_id,id),
    CONSTRAINT fk_wms_rule_zone FOREIGN KEY (tenant_id,company_id,warehouse_id,zone_id)
        REFERENCES wms_zone(tenant_id,company_id,warehouse_id,id),
    CONSTRAINT fk_wms_rule_fixed FOREIGN KEY (tenant_id,company_id,warehouse_id,fixed_location_id)
        REFERENCES wms_location(tenant_id,company_id,warehouse_id,id),
    CONSTRAINT fk_wms_rule_sku FOREIGN KEY (tenant_id,sku_id) REFERENCES sku(tenant_id,id),
    CONSTRAINT fk_wms_rule_category FOREIGN KEY (tenant_id,category_id) REFERENCES product_category(tenant_id,id),
    CONSTRAINT ck_wms_rule_selector CHECK ((sku_id IS NULL)<>(category_id IS NULL)),
    CONSTRAINT ck_wms_rule_threshold CHECK ((min_qty IS NULL AND max_qty IS NULL) OR
        (sku_id IS NOT NULL AND fixed_location_id IS NOT NULL AND min_qty IS NOT NULL AND max_qty IS NOT NULL
            AND min_qty>=0 AND max_qty>min_qty))
);

CREATE TABLE wms_move_doc (
    id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    warehouse_id BIGINT UNSIGNED NOT NULL,
    rule_id BIGINT UNSIGNED NULL,
    remark VARCHAR(300) NOT NULL DEFAULT '',
    status VARCHAR(12) NOT NULL DEFAULT 'draft',
    active_rule_id BIGINT UNSIGNED GENERATED ALWAYS AS (IF(status='draft',rule_id,NULL)) STORED,
    posted_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_wms_move_scope (tenant_id,company_id,warehouse_id,id),
    UNIQUE KEY uk_wms_move_open_rule (tenant_id,company_id,warehouse_id,active_rule_id),
    CONSTRAINT fk_wms_move_task FOREIGN KEY (tenant_id,company_id,id) REFERENCES wms_task(tenant_id,company_id,id),
    CONSTRAINT fk_wms_move_rule FOREIGN KEY (tenant_id,company_id,warehouse_id,rule_id)
        REFERENCES wms_location_rule(tenant_id,company_id,warehouse_id,id),
    CONSTRAINT ck_wms_move_status CHECK (status IN ('draft','posted','cancelled'))
);

CREATE TABLE wms_move_line (
    id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    warehouse_id BIGINT UNSIGNED NOT NULL,
    move_id BIGINT UNSIGNED NOT NULL,
    sku_id BIGINT UNSIGNED NOT NULL,
    batch_id BIGINT UNSIGNED NULL,
    from_location_id BIGINT UNSIGNED NOT NULL,
    to_location_id BIGINT UNSIGNED NOT NULL,
    qty DECIMAL(24,6) NOT NULL,
    serial_nos JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_wms_move_lines (tenant_id,company_id,warehouse_id,move_id),
    CONSTRAINT fk_wms_move_line_doc FOREIGN KEY (tenant_id,company_id,warehouse_id,move_id)
        REFERENCES wms_move_doc(tenant_id,company_id,warehouse_id,id),
    CONSTRAINT fk_wms_move_line_sku FOREIGN KEY (tenant_id,sku_id) REFERENCES sku(tenant_id,id),
    CONSTRAINT fk_wms_move_line_batch FOREIGN KEY (tenant_id,company_id,batch_id) REFERENCES inv_batch(tenant_id,company_id,id),
    CONSTRAINT fk_wms_move_line_from FOREIGN KEY (tenant_id,company_id,warehouse_id,from_location_id)
        REFERENCES wms_location(tenant_id,company_id,warehouse_id,id),
    CONSTRAINT fk_wms_move_line_to FOREIGN KEY (tenant_id,company_id,warehouse_id,to_location_id)
        REFERENCES wms_location(tenant_id,company_id,warehouse_id,id),
    CONSTRAINT ck_wms_move_line_dims CHECK (from_location_id<>to_location_id AND qty>0)
);

CREATE TABLE wms_count_group (
    task_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    warehouse_id BIGINT UNSIGNED NOT NULL,
    count_id BIGINT UNSIGNED NOT NULL,
    location_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_wms_count_group (tenant_id,company_id,count_id,location_id),
    CONSTRAINT fk_wms_count_group_task FOREIGN KEY (tenant_id,company_id,task_id)
        REFERENCES wms_task(tenant_id,company_id,id),
    CONSTRAINT fk_wms_count_group_location FOREIGN KEY (tenant_id,company_id,warehouse_id,location_id)
        REFERENCES wms_location(tenant_id,company_id,warehouse_id,id)
);

ALTER TABLE wms_task ADD KEY idx_wms_task_statistics (tenant_id,company_id,finished_at,warehouse_id,assignee_id,type);

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
    (16920,'wms_move:view','查看移库及补货','menu',16920),
    (16921,'wms_move:manage','维护及执行移库和补货','action',16921),
    (16922,'wms_task:assign','分配及取消仓储作业','action',16922);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT base.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN sys_permission p ON p.id IN (16920,16921,16922)
CROSS JOIN (SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) base
WHERE r.status='active' AND r.code='SYSTEM_ADMIN'
  AND EXISTS (SELECT 1 FROM sys_role_permission rp JOIN sys_permission old ON old.id=rp.permission_id
              WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id AND old.code='role:assign_permission');

INSERT INTO job_definition(tenant_id,code,name,schedule,enabled)
VALUES (0,'wms.replenishment','检查拣货位补货需求','0 */5 * * * *',1);
