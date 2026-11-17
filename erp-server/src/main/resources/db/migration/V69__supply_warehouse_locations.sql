ALTER TABLE warehouse ADD COLUMN location_managed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN default_receiving_location_id BIGINT UNSIGNED NULL,
    ADD COLUMN default_shipping_location_id BIGINT UNSIGNED NULL;

CREATE TABLE wms_zone (
    id BIGINT UNSIGNED NOT NULL,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    warehouse_id BIGINT UNSIGNED NOT NULL,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    version INT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_wms_zone_code (tenant_id,company_id,warehouse_id,code),
    UNIQUE KEY uk_wms_zone_scope (tenant_id,company_id,warehouse_id,id),
    CONSTRAINT fk_wms_zone_warehouse FOREIGN KEY (tenant_id,company_id,warehouse_id) REFERENCES warehouse(tenant_id,company_id,id),
    CONSTRAINT ck_wms_zone_type CHECK (type IN ('storage','receiving','shipping','quarantine','return')),
    CONSTRAINT ck_wms_zone_status CHECK (status IN ('active','disabled'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE wms_location (
    id BIGINT UNSIGNED NOT NULL,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    warehouse_id BIGINT UNSIGNED NOT NULL,
    zone_id BIGINT UNSIGNED NOT NULL,
    code VARCHAR(48) NOT NULL,
    name VARCHAR(100) NOT NULL,
    aisle VARCHAR(16) NULL,
    shelf VARCHAR(16) NULL,
    layer VARCHAR(16) NULL,
    position VARCHAR(16) NULL,
    capacity_qty DECIMAL(24,6) NULL,
    mixed_sku_allowed BOOLEAN NOT NULL DEFAULT TRUE,
    mixed_batch_allowed BOOLEAN NOT NULL DEFAULT TRUE,
    pick_sequence INT UNSIGNED NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    version INT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_wms_location_code (tenant_id,company_id,warehouse_id,code),
    UNIQUE KEY uk_wms_location_scope (tenant_id,company_id,warehouse_id,id),
    KEY idx_wms_location_pick (tenant_id,company_id,warehouse_id,zone_id,status,pick_sequence),
    CONSTRAINT fk_wms_location_zone FOREIGN KEY (tenant_id,company_id,warehouse_id,zone_id) REFERENCES wms_zone(tenant_id,company_id,warehouse_id,id),
    CONSTRAINT ck_wms_location_capacity CHECK (capacity_qty IS NULL OR capacity_qty>0),
    CONSTRAINT ck_wms_location_status CHECK (status IN ('active','disabled'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 启用库位时记录物理余额的计算起点，过账历史及移动加权成本保持原口径。
CREATE TABLE inv_location_opening (
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    warehouse_id BIGINT UNSIGNED NOT NULL,
    sku_id BIGINT UNSIGNED NOT NULL,
    location_key VARCHAR(64) NOT NULL,
    lot_key VARCHAR(64) NOT NULL DEFAULT '',
    on_hand_qty DECIMAL(24,6) NOT NULL,
    blocked_qty DECIMAL(24,6) NOT NULL,
    operator_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (tenant_id,company_id,warehouse_id,sku_id,location_key,lot_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
  (14120,'wms_location:view','查看库区与库位','menu',14120),
  (14121,'wms_location:manage','维护库区与库位','action',14121);
INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT base.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN tenant t ON t.id=r.tenant_id JOIN sys_permission p ON p.id IN (14120,14121)
CROSS JOIN (SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) base
WHERE r.status='active' AND (r.code='SYSTEM_ADMIN' OR (r.code='WAREHOUSE_ADMIN' AND t.name='演示企业'))
  AND EXISTS (SELECT 1 FROM sys_role_permission rp JOIN sys_permission old ON old.id=rp.permission_id
              WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id AND old.code='role:assign_permission');
