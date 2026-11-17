ALTER TABLE inventory_movement DROP CHECK ck_inventory_movement_type;
ALTER TABLE inventory_movement ADD CONSTRAINT ck_inventory_movement_type CHECK (movement_type IN (
    'opening_in','purchase_in','purchase_return_out','sales_out','sales_return_in','sales_reject_in',
    'transfer_out','transfer_in','count_gain','count_loss','adjust_in','adjust_out',
    'block','unblock','scrap','stock_misc_in','stock_misc_out','stock_misc_return_in'));

ALTER TABLE inv_serial DROP CHECK ck_inv_serial_status;
ALTER TABLE inv_serial ADD CONSTRAINT ck_inv_serial_status CHECK (status IN
    ('in_stock','quality_hold','reserved','shipped','returned','supplier_returned','scrapped','in_transit','reversed'));

CREATE TABLE inv_movement_hold (
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    movement_id BIGINT UNSIGNED NOT NULL,
    reason VARCHAR(24) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id BIGINT UNSIGNED NOT NULL,
    source_line_id BIGINT UNSIGNED NOT NULL,
    qty_delta DECIMAL(24,6) NOT NULL,
    PRIMARY KEY (movement_id,reason,source_type,source_id,source_line_id),
    KEY idx_inv_move_hold_scope (tenant_id,company_id,movement_id),
    CONSTRAINT fk_inv_move_hold FOREIGN KEY (movement_id) REFERENCES inventory_movement(id)
);

ALTER TABLE stock_adjustment_line ADD COLUMN batch_id BIGINT UNSIGNED NULL,
    ADD COLUMN location_key VARCHAR(64) NOT NULL DEFAULT '', ADD COLUMN stock_selection JSON NULL;
ALTER TABLE stock_transfer_line ADD COLUMN batch_id BIGINT UNSIGNED NULL,
    ADD COLUMN location_key VARCHAR(64) NOT NULL DEFAULT '', ADD COLUMN stock_selection JSON NULL;
ALTER TABLE stock_count_line ADD COLUMN batch_id BIGINT UNSIGNED NULL,
    ADD COLUMN stock_selection JSON NULL;

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
    (14101,'batch:view','查看批次余额','action',14101),
    (14102,'batch:trace','查批次来源和去向','action',14102),
    (14103,'serial:trace','查序列号流转历史','action',14103),
    (14104,'inventory_trace:customer','按客户查询发货批次','action',14104);
INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT m.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN tenant t ON t.id=r.tenant_id
JOIN sys_permission p ON p.id BETWEEN 14101 AND 14104
CROSS JOIN (SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) m
WHERE (r.code='SYSTEM_ADMIN' OR (r.code='WAREHOUSE_ADMIN' AND t.name='演示企业'))
  AND EXISTS(SELECT 1 FROM sys_role_permission rp JOIN sys_permission old ON old.id=rp.permission_id
             WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id AND old.code='inventory_trace:view');
