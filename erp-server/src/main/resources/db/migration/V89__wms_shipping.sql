CREATE TABLE wms_wave (
    id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    warehouse_id BIGINT UNSIGNED NOT NULL,
    no VARCHAR(36) NOT NULL,
    rule VARCHAR(16) NOT NULL,
    cutoff_at DATETIME(3) NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    assignee_id BIGINT UNSIGNED NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_wms_wave_scope (tenant_id,company_id,id),
    KEY idx_wms_wave_page (tenant_id,company_id,warehouse_id,id),
    CONSTRAINT fk_wms_wave_warehouse FOREIGN KEY (tenant_id,company_id,warehouse_id) REFERENCES warehouse (tenant_id,company_id,id),
    CONSTRAINT ck_wms_wave_rule CHECK (rule IN ('manual','carrier','cutoff','customer'))
);

ALTER TABLE shipment ADD COLUMN carrier_code VARCHAR(32) NULL;

ALTER TABLE wms_task
    ADD COLUMN wave_id BIGINT UNSIGNED NULL,
    ADD COLUMN priority SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    ADD COLUMN short_pick BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN required_at_submit BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT fk_wms_task_wave FOREIGN KEY (tenant_id,company_id,wave_id) REFERENCES wms_wave (tenant_id,company_id,id),
    ADD KEY idx_wms_task_wave (tenant_id,company_id,wave_id,id);

ALTER TABLE wms_task_line
    MODIFY COLUMN receipt_movement_id BIGINT UNSIGNED NULL,
    ADD COLUMN to_location_id BIGINT UNSIGNED NULL,
    ADD COLUMN scan_serials JSON NULL,
    ADD CONSTRAINT fk_wms_task_line_target FOREIGN KEY (to_location_id) REFERENCES wms_location (id);

CREATE TABLE wms_pack (
    id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    shipment_id BIGINT UNSIGNED NOT NULL,
    box_no VARCHAR(36) NOT NULL,
    weight_kg DECIMAL(18,6) NULL,
    volume DECIMAL(18,6) NULL,
    checker_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'done',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_wms_pack_scope (tenant_id,company_id,id),
    UNIQUE KEY uk_wms_pack_box (tenant_id,company_id,shipment_id,box_no),
    CONSTRAINT fk_wms_pack_shipment FOREIGN KEY (tenant_id,company_id,shipment_id) REFERENCES shipment (tenant_id,company_id,id),
    CONSTRAINT ck_wms_pack_status CHECK (status IN ('done','cancelled')),
    CONSTRAINT ck_wms_pack_dimensions CHECK ((weight_kg IS NULL OR weight_kg>0) AND (volume IS NULL OR volume>0))
);

CREATE TABLE wms_pack_line (
    id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    pack_id BIGINT UNSIGNED NOT NULL,
    pick_line_id BIGINT UNSIGNED NOT NULL,
    sku_id BIGINT UNSIGNED NOT NULL,
    batch_id BIGINT UNSIGNED NULL,
    qty DECIMAL(24,6) NOT NULL,
    serial_nos JSON NOT NULL,
    UNIQUE KEY uk_wms_pack_line (tenant_id,company_id,pack_id,pick_line_id),
    CONSTRAINT fk_wms_pack_line_pack FOREIGN KEY (tenant_id,company_id,pack_id) REFERENCES wms_pack (tenant_id,company_id,id),
    CONSTRAINT fk_wms_pack_line_pick FOREIGN KEY (pick_line_id) REFERENCES wms_task_line (id),
    CONSTRAINT ck_wms_pack_line_quantity CHECK (qty>0)
);

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
    (16910,'wms_wave:view','查看波次','menu',16910),
    (16911,'wms_wave:manage','创建或领取波次','action',16911),
    (16912,'wms_pack:view','查看装箱','menu',16912),
    (16913,'wms_pack:create','复核装箱','action',16913);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT base.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN sys_permission p ON p.id IN (16910,16911,16912,16913)
CROSS JOIN (SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) base
WHERE r.status='active' AND r.code='SYSTEM_ADMIN'
  AND EXISTS (SELECT 1 FROM sys_role_permission rp JOIN sys_permission old ON old.id=rp.permission_id
              WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id AND old.code='role:assign_permission');
