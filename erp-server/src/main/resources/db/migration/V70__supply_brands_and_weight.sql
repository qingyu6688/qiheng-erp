CREATE TABLE catalog_brand (
    id BIGINT UNSIGNED NOT NULL,
    tenant_id BIGINT UNSIGNED NOT NULL,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    logo_object_key VARCHAR(255) NULL,
    sort_no INT UNSIGNED NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    version INT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY(id),
    UNIQUE KEY uk_catalog_brand_code(tenant_id,code),
    UNIQUE KEY uk_catalog_brand_scope(tenant_id,id),
    CONSTRAINT fk_catalog_brand_tenant FOREIGN KEY(tenant_id) REFERENCES tenant(id),
    CONSTRAINT ck_catalog_brand_status CHECK(status IN ('active','disabled'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE sku ADD COLUMN weight_kg DECIMAL(24,6) NULL,
    ADD COLUMN brand_id BIGINT UNSIGNED NULL,
    ADD KEY idx_sku_brand (tenant_id,brand_id,id),
    ADD CONSTRAINT fk_sku_brand FOREIGN KEY(tenant_id,brand_id) REFERENCES catalog_brand(tenant_id,id),
    ADD CONSTRAINT ck_sku_weight CHECK (weight_kg IS NULL OR weight_kg>=0);

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
  (14122,'brand:view','查看品牌','menu',14122),(14123,'brand:manage','维护品牌','action',14123),
  (14124,'sku_tracking:manage','维护商品批次与效期设置','action',14124);
INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT base.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN tenant t ON t.id=r.tenant_id JOIN sys_permission p ON p.id IN (14122,14123,14124)
CROSS JOIN (SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) base
WHERE r.status='active' AND (r.code='SYSTEM_ADMIN' OR (r.code='WAREHOUSE_ADMIN' AND t.name='演示企业'))
  AND EXISTS (SELECT 1 FROM sys_role_permission rp JOIN sys_permission old ON old.id=rp.permission_id
              WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id AND old.code='role:assign_permission');
