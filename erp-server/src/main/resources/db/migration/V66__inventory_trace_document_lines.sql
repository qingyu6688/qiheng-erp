ALTER TABLE goods_receipt_line ADD COLUMN batch_id BIGINT UNSIGNED NULL, ADD COLUMN location_key VARCHAR(64) NOT NULL DEFAULT '', ADD COLUMN stock_selection JSON NULL;
ALTER TABLE purchase_return_line ADD COLUMN batch_id BIGINT UNSIGNED NULL, ADD COLUMN location_key VARCHAR(64) NOT NULL DEFAULT '', ADD COLUMN stock_selection JSON NULL;
ALTER TABLE shipment_line ADD COLUMN batch_id BIGINT UNSIGNED NULL, ADD COLUMN location_key VARCHAR(64) NOT NULL DEFAULT '', ADD COLUMN stock_selection JSON NULL;
ALTER TABLE sales_return_line ADD COLUMN batch_id BIGINT UNSIGNED NULL, ADD COLUMN location_key VARCHAR(64) NOT NULL DEFAULT '', ADD COLUMN stock_selection JSON NULL;

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
    (14100,'inventory_trace:view','查看批次与序列号追溯','menu',14100);
INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT m.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id),r.tenant_id,r.id,14100
FROM sys_role r JOIN tenant t ON t.id=r.tenant_id
CROSS JOIN (SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) m
WHERE (r.code='SYSTEM_ADMIN' OR (r.code='WAREHOUSE_ADMIN' AND t.name='演示企业'))
  AND EXISTS(SELECT 1 FROM sys_role_permission rp JOIN sys_permission p ON p.id=rp.permission_id
             WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id AND p.code='inventory:view');
