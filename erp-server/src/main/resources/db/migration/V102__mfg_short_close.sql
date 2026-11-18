CREATE TABLE mfg_work_order_short_close (
  id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  work_order_id BIGINT UNSIGNED NOT NULL,
  period_id BIGINT UNSIGNED NOT NULL,
  order_version BIGINT UNSIGNED NOT NULL,
  business_date DATE NOT NULL,
  reason VARCHAR(500) NOT NULL,
  cost_disposition VARCHAR(16) NOT NULL,
  materials JSON NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'pending',
  requested_by BIGINT UNSIGNED NOT NULL,
  reviewed_by BIGINT UNSIGNED NULL,
  reviewed_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_mfg_short_close_order (tenant_id,company_id,work_order_id),
  KEY idx_mfg_short_close_period (tenant_id,company_id,period_id,status),
  CONSTRAINT fk_mfg_short_close_order FOREIGN KEY (tenant_id,company_id,work_order_id)
    REFERENCES mfg_work_order(tenant_id,company_id,id),
  CONSTRAINT fk_mfg_short_close_period FOREIGN KEY (tenant_id,company_id,period_id)
    REFERENCES accounting_period(tenant_id,company_id,id),
  CONSTRAINT ck_mfg_short_close_review CHECK (
    cost_disposition IN ('expense','allocate')
    AND ((status='pending' AND reviewed_by IS NULL AND reviewed_at IS NULL)
      OR (status='approved' AND reviewed_by IS NOT NULL AND reviewed_by<>requested_by AND reviewed_at IS NOT NULL)))
);

ALTER TABLE mfg_cost_item ADD COLUMN short_close_expense DECIMAL(24,2) NOT NULL DEFAULT 0;

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
  (17160,'mfg_work_order:short_close','申请工单短交关闭','action',17160),
  (17161,'mfg_work_order:approve_short_close','复核工单短交关闭','action',17161);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT base.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id), r.tenant_id,r.id,p.id
FROM sys_role r JOIN sys_permission p ON p.id IN (17160,17161)
CROSS JOIN (SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) base
WHERE r.status='active' AND r.code='SYSTEM_ADMIN'
  AND EXISTS (SELECT 1 FROM sys_role_permission rp JOIN sys_permission old ON old.id=rp.permission_id
              WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id AND old.code='mfg_work_order:close');
