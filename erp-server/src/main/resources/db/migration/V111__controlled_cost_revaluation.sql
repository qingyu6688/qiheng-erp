CREATE INDEX idx_cost_adjustment_replay ON cost_adjustment(tenant_id,company_id,warehouse_id,sku_id,created_at,id);

CREATE TABLE cost_revaluation (
  id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  document_no VARCHAR(40) NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  sku_id BIGINT UNSIGNED NOT NULL,
  start_date DATE NOT NULL,
  business_date DATE NOT NULL,
  reason VARCHAR(500) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'draft',
  preview_hash CHAR(64) NOT NULL,
  inventory_version BIGINT UNSIGNED NOT NULL,
  quantity DECIMAL(24,6) NOT NULL,
  before_cost DECIMAL(24,2) NOT NULL,
  after_cost DECIMAL(24,2) NOT NULL,
  stock_delta DECIMAL(24,2) NOT NULL,
  event_count INT UNSIGNED NOT NULL,
  wf_instance_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  period_id BIGINT UNSIGNED NULL,
  approved_by BIGINT UNSIGNED NULL,
  approved_at DATETIME(3) NULL,
  posted_by BIGINT UNSIGNED NULL,
  posted_at DATETIME(3) NULL,
  owner_user_id BIGINT UNSIGNED NOT NULL,
  owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  created_by BIGINT UNSIGNED NOT NULL,
  updated_by BIGINT UNSIGNED NOT NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_cost_revaluation_scope(tenant_id,company_id,id),
  UNIQUE KEY uk_cost_revaluation_no(tenant_id,company_id,document_no),
  KEY idx_cost_revaluation_list(tenant_id,company_id,status,id),
  CONSTRAINT fk_cost_revaluation_wh FOREIGN KEY(tenant_id,company_id,warehouse_id) REFERENCES warehouse(tenant_id,company_id,id),
  CONSTRAINT fk_cost_revaluation_sku FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id),
  CONSTRAINT fk_cost_revaluation_period FOREIGN KEY(tenant_id,company_id,period_id) REFERENCES accounting_period(tenant_id,company_id,id),
  CONSTRAINT ck_cost_revaluation_dates CHECK(start_date<=business_date),
  CONSTRAINT ck_cost_revaluation_state CHECK(status IN('draft','pending','approved','rejected','posted','voided')),
  CONSTRAINT ck_cost_revaluation_cost CHECK(quantity>=0 AND before_cost>=0 AND after_cost>=0 AND after_cost=before_cost+stock_delta),
  CONSTRAINT ck_cost_revaluation_approval CHECK((approved_by IS NULL)=(approved_at IS NULL)
    AND (approved_by IS NULL OR approved_by<>created_by)
    AND (status NOT IN('approved','posted') OR approved_by IS NOT NULL)),
  CONSTRAINT ck_cost_revaluation_post CHECK(status<>'posted' OR (posted_by IS NOT NULL AND posted_at IS NOT NULL AND period_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE cost_revaluation_line (
  id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  revaluation_id BIGINT UNSIGNED NOT NULL,
  movement_id BIGINT UNSIGNED NOT NULL,
  before_cost DECIMAL(24,2) NOT NULL COMMENT '原流水及既有有效调整的带符号成本',
  after_cost DECIMAL(24,2) NOT NULL,
  cost_delta DECIMAL(24,2) NOT NULL,
  UNIQUE KEY uk_cost_revaluation_movement(revaluation_id,movement_id),
  KEY idx_cost_revaluation_history(tenant_id,company_id,movement_id,revaluation_id),
  CONSTRAINT fk_cost_revaluation_line_head FOREIGN KEY(tenant_id,company_id,revaluation_id) REFERENCES cost_revaluation(tenant_id,company_id,id),
  CONSTRAINT fk_cost_revaluation_line_movement FOREIGN KEY(movement_id) REFERENCES inventory_movement(id),
  CONSTRAINT ck_cost_revaluation_line_delta CHECK(cost_delta<>0 AND after_cost=before_cost+cost_delta)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
  (18800,'cost_revaluation:view','查看成本重估','menu',18800),
  (18801,'cost_revaluation:create','新建成本重估','action',18801),
  (18802,'cost_revaluation:submit','提交成本重估','action',18802),
  (18803,'cost_revaluation:approve','审批成本重估','action',18803),
  (18804,'cost_revaluation:post','执行成本重估','action',18804),
  (18805,'cost_revaluation:void','作废成本重估','action',18805);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT base.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN sys_permission p ON p.id BETWEEN 18800 AND 18805
CROSS JOIN(SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) base
WHERE r.code='SYSTEM_ADMIN' AND EXISTS(SELECT 1 FROM sys_role_permission member JOIN sys_permission prior
  ON prior.id=member.permission_id WHERE member.tenant_id=r.tenant_id AND member.role_id=r.id AND prior.code='role:assign_permission');
