ALTER TABLE warehouse ADD COLUMN wms_task_mode VARCHAR(12) NOT NULL DEFAULT 'off',
    ADD CONSTRAINT ck_warehouse_wms_task_mode CHECK (wms_task_mode IN ('off','optional','required'));

CREATE TABLE wms_task (
    id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    warehouse_id BIGINT UNSIGNED NOT NULL,
    no VARCHAR(36) NOT NULL,
    type VARCHAR(16) NOT NULL,
    source_type VARCHAR(24) NOT NULL,
    source_id BIGINT UNSIGNED NOT NULL,
    owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    owner_user_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    assignee_id BIGINT UNSIGNED NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'created',
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    started_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_wms_task_scope (tenant_id,company_id,id),
    UNIQUE KEY uk_wms_task_receipt (tenant_id,company_id,source_type,source_id),
    KEY idx_wms_task_page (tenant_id,company_id,warehouse_id,status,id),
    CONSTRAINT fk_wms_task_warehouse FOREIGN KEY (tenant_id,company_id,warehouse_id)
        REFERENCES warehouse (tenant_id,company_id,id),
    CONSTRAINT ck_wms_task_type CHECK (type IN ('putaway','pick','replenish','move','count')),
    CONSTRAINT ck_wms_task_status CHECK (status IN ('created','assigned','in_progress','done','cancelled'))
);

CREATE TABLE wms_task_line (
    id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    task_id BIGINT UNSIGNED NOT NULL,
    sku_id BIGINT UNSIGNED NOT NULL,
    batch_id BIGINT UNSIGNED NULL,
    from_location_id BIGINT UNSIGNED NOT NULL,
    source_line_id BIGINT UNSIGNED NOT NULL,
    receipt_movement_id BIGINT UNSIGNED NOT NULL,
    plan_qty DECIMAL(24,6) NOT NULL,
    done_qty DECIMAL(24,6) NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_wms_task_line_scope (tenant_id,company_id,task_id,id),
    UNIQUE KEY uk_wms_task_line_movement (receipt_movement_id),
    CONSTRAINT fk_wms_task_line_task FOREIGN KEY (tenant_id,company_id,task_id) REFERENCES wms_task (tenant_id,company_id,id),
    CONSTRAINT fk_wms_task_line_sku FOREIGN KEY (tenant_id,sku_id) REFERENCES sku (tenant_id,id),
    CONSTRAINT fk_wms_task_line_batch FOREIGN KEY (tenant_id,company_id,batch_id) REFERENCES inv_batch (tenant_id,company_id,id),
    CONSTRAINT fk_wms_task_line_location FOREIGN KEY (from_location_id) REFERENCES wms_location (id),
    CONSTRAINT fk_wms_task_line_movement FOREIGN KEY (receipt_movement_id) REFERENCES inventory_movement (id),
    CONSTRAINT ck_wms_task_line_quantity CHECK (plan_qty>0 AND done_qty>=0 AND done_qty<=plan_qty)
);

CREATE TABLE wms_task_confirmation (
    id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    task_id BIGINT UNSIGNED NOT NULL,
    task_line_id BIGINT UNSIGNED NOT NULL,
    to_location_id BIGINT UNSIGNED NOT NULL,
    qty DECIMAL(24,6) NOT NULL,
    operator_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_wms_confirmation_line FOREIGN KEY (tenant_id,company_id,task_id,task_line_id)
        REFERENCES wms_task_line (tenant_id,company_id,task_id,id),
    CONSTRAINT fk_wms_confirmation_location FOREIGN KEY (to_location_id) REFERENCES wms_location (id),
    CONSTRAINT ck_wms_confirmation_quantity CHECK (qty>0)
);

ALTER TABLE inventory_movement DROP CHECK ck_inventory_movement_type;
ALTER TABLE inventory_movement ADD CONSTRAINT ck_inventory_movement_type CHECK (movement_type IN (
    'opening_in','purchase_in','purchase_return_out','sales_out','sales_return_in','sales_reject_in',
    'transfer_out','transfer_in','count_gain','count_loss','adjust_in','adjust_out',
    'block','unblock','scrap','stock_misc_in','stock_misc_out','stock_misc_return_in',
    'location_move_out','location_move_in'));

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
    (16900,'wms_task:view','查看仓储作业任务','menu',16900),
    (16901,'wms_task:execute','执行仓储作业任务','action',16901),
    (16902,'wms_task:configure','设置仓储作业模式','action',16902);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT base.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN sys_permission p ON p.id IN (16900,16901,16902)
CROSS JOIN (SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) base
WHERE r.status='active' AND r.code='SYSTEM_ADMIN'
  AND EXISTS (SELECT 1 FROM sys_role_permission rp JOIN sys_permission old ON old.id=rp.permission_id
              WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id AND old.code='role:assign_permission');
