CREATE TABLE mfg_cost_period (
  id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  period_id BIGINT UNSIGNED NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'open',
  wip_method VARCHAR(16) NOT NULL DEFAULT 'materials',
  has_allocation TINYINT NOT NULL DEFAULT 0,
  calculation_hash CHAR(64) NULL,
  posting_id BIGINT UNSIGNED NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY(id),
  UNIQUE KEY uk_mfg_cost_period (tenant_id,company_id,period_id),
  CONSTRAINT fk_mfg_cost_period FOREIGN KEY (tenant_id,company_id,period_id) REFERENCES accounting_period(tenant_id,company_id,id),
  CONSTRAINT ck_mfg_cost_period CHECK (status IN ('open','calculated','posted') AND wip_method IN ('materials','equivalent'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mfg_expense_pool (
  id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  period_id BIGINT UNSIGNED NOT NULL,
  kind VARCHAR(16) NOT NULL,
  source VARCHAR(16) NOT NULL,
  subject_code VARCHAR(64) NULL,
  allocation_basis VARCHAR(16) NOT NULL,
  amount DECIMAL(24,2) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(id),
  UNIQUE KEY uk_mfg_expense_kind (tenant_id,company_id,period_id,kind),
  CONSTRAINT fk_mfg_expense_period FOREIGN KEY (tenant_id,company_id,period_id) REFERENCES mfg_cost_period(tenant_id,company_id,period_id),
  CONSTRAINT ck_mfg_expense_pool CHECK (kind IN ('labor','overhead') AND source IN ('manual','gl')
    AND allocation_basis IN ('hours','quantity','material') AND amount>=0 AND ((source='gl')=(subject_code IS NOT NULL)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mfg_cost_input (
  id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  period_id BIGINT UNSIGNED NOT NULL,
  work_order_id BIGINT UNSIGNED NOT NULL,
  hours DECIMAL(18,6) NOT NULL DEFAULT 0,
  progress_pct DECIMAL(9,6) NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_mfg_cost_input (tenant_id,company_id,period_id,work_order_id),
  CONSTRAINT fk_mfg_cost_input_period FOREIGN KEY (tenant_id,company_id,period_id) REFERENCES mfg_cost_period(tenant_id,company_id,period_id),
  CONSTRAINT fk_mfg_cost_input_order FOREIGN KEY (tenant_id,company_id,work_order_id) REFERENCES mfg_work_order(tenant_id,company_id,id),
  CONSTRAINT ck_mfg_cost_input CHECK (hours>=0 AND (progress_pct IS NULL OR progress_pct BETWEEN 0 AND 100))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mfg_cost_item (
  id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  period_id BIGINT UNSIGNED NOT NULL,
  work_order_id BIGINT UNSIGNED NOT NULL,
  finished_qty DECIMAL(24,6) NOT NULL,
  material_cost DECIMAL(24,2) NOT NULL,
  rolled_material DECIMAL(24,2) NOT NULL,
  labor_cost DECIMAL(24,2) NOT NULL,
  overhead_cost DECIMAL(24,2) NOT NULL,
  wip_begin DECIMAL(24,2) NOT NULL,
  wip_end DECIMAL(24,2) NOT NULL,
  wip_end_material DECIMAL(24,2) NOT NULL,
  finished_cost DECIMAL(24,2) NOT NULL,
  estimated_cost DECIMAL(24,2) NOT NULL,
  variance DECIMAL(24,2) NOT NULL,
  inventory_variance DECIMAL(24,2) NOT NULL,
  issued_variance DECIMAL(24,2) NOT NULL,
  rolled_variance DECIMAL(24,2) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_mfg_cost_item (tenant_id,company_id,period_id,work_order_id),
  KEY idx_mfg_cost_item_order (tenant_id,company_id,work_order_id,period_id),
  CONSTRAINT fk_mfg_cost_item_period FOREIGN KEY (tenant_id,company_id,period_id) REFERENCES mfg_cost_period(tenant_id,company_id,period_id),
  CONSTRAINT fk_mfg_cost_item_order FOREIGN KEY (tenant_id,company_id,work_order_id) REFERENCES mfg_work_order(tenant_id,company_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mfg_cost_posting (
  id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  period_id BIGINT UNSIGNED NOT NULL,
  journal_id BIGINT UNSIGNED NULL,
  operations JSON NOT NULL,
  reversed_by BIGINT UNSIGNED NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(id),
  KEY idx_mfg_cost_posting_period(tenant_id,company_id,period_id,id),
  CONSTRAINT fk_mfg_cost_posting_period FOREIGN KEY (tenant_id,company_id,period_id) REFERENCES mfg_cost_period(tenant_id,company_id,period_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE VIEW mfg_cost_movement AS
SELECT m.id,m.tenant_id,m.company_id,m.warehouse_id,m.sku_id,m.period_id,m.business_date,m.posted_at,
       m.movement_type,m.posting_action,m.reverses_movement_id,m.on_hand_delta,m.cost_delta,
       COALESCE(i.work_order_id,r.work_order_id) AS work_order_id
FROM inventory_movement m
LEFT JOIN inventory_movement original ON original.tenant_id=m.tenant_id AND original.company_id=m.company_id
    AND original.id=m.reverses_movement_id
LEFT JOIN mfg_issue_doc i ON i.tenant_id=m.tenant_id AND i.company_id=m.company_id
    AND COALESCE(original.source_type,m.source_type)='mfg_issue_line' AND i.id=COALESCE(original.source_id,m.source_id)
LEFT JOIN mfg_receipt_doc r ON r.tenant_id=m.tenant_id AND r.company_id=m.company_id
    AND COALESCE(original.source_type,m.source_type)='mfg_receipt_line' AND r.id=COALESCE(original.source_id,m.source_id)
WHERE m.movement_type IN ('mfg_issue_out','mfg_return_in','mfg_receipt_in');

ALTER TABLE cost_adjustment DROP CHECK ck_cost_adjustment_type;
ALTER TABLE cost_adjustment ADD CONSTRAINT ck_cost_adjustment_type CHECK (adjust_type IN
  ('zero_qty_residue','purchase_return_diff','revaluation','production_variance'));

INSERT INTO ledger_account(id,code,name,category,normal_direction,needs_warehouse) VALUES
  (17150,'WIP','在制品','asset','D',0),
  (17151,'PRODUCTION_VARIANCE','生产完工已出库差异','expense','D',0),
  (17152,'PRODUCTION_LABOR','待分摊直接人工','expense','D',0),
  (17153,'PRODUCTION_OVERHEAD','待分摊制造费用','expense','D',0);

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
  (17150,'mfg_cost:view','查看生产成本','menu',17150),
  (17151,'mfg_cost:manage','归集及计算生产成本','action',17151),
  (17152,'mfg_cost:post','过账及冲回生产成本','action',17152),
  (17153,'mfg_report:view','查看生产报表','menu',17153);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT base.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN sys_permission p ON p.id BETWEEN 17150 AND 17153
CROSS JOIN (SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) base
WHERE r.code='SYSTEM_ADMIN'
  AND EXISTS (SELECT 1 FROM sys_role_permission member JOIN sys_permission prior ON prior.id=member.permission_id
              WHERE member.tenant_id=r.tenant_id AND member.role_id=r.id AND prior.code='role:assign_permission');

INSERT INTO gl_standard_preset(standard,kind,code,name,payload,source_ref) VALUES
  ('enterprise','subject','5102','待分摊直接人工','{"category":"cost","direction":"D","parentCode":null,"level":1,"isCash":false,"isBank":false,"auxTypes":[],"profitRole":"none","systemExtension":true}','ERP 生产成本扩展'),
  ('small_enterprise','subject','4102','待分摊直接人工','{"category":"cost","direction":"D","parentCode":null,"level":1,"isCash":false,"isBank":false,"auxTypes":[],"profitRole":"none","systemExtension":true}','ERP 生产成本扩展'),
  ('enterprise','mapping','WIP','在制品','{"subjectCode":"5001","auxRules":{},"priority":0}','系统建议映射，按账套复核'),
  ('enterprise','mapping','PRODUCTION_VARIANCE','生产完工已出库差异','{"subjectCode":"6401","auxRules":{},"priority":0}','系统建议映射，按账套复核'),
  ('enterprise','mapping','PRODUCTION_LABOR','待分摊直接人工','{"subjectCode":"5102","auxRules":{},"priority":0}','系统建议映射，按账套复核'),
  ('enterprise','mapping','PRODUCTION_OVERHEAD','待分摊制造费用','{"subjectCode":"5101","auxRules":{},"priority":0}','系统建议映射，按账套复核'),
  ('small_enterprise','mapping','WIP','在制品','{"subjectCode":"4001","auxRules":{},"priority":0}','系统建议映射，按账套复核'),
  ('small_enterprise','mapping','PRODUCTION_VARIANCE','生产完工已出库差异','{"subjectCode":"5401","auxRules":{},"priority":0}','系统建议映射，按账套复核'),
  ('small_enterprise','mapping','PRODUCTION_LABOR','待分摊直接人工','{"subjectCode":"4102","auxRules":{},"priority":0}','系统建议映射，按账套复核'),
  ('small_enterprise','mapping','PRODUCTION_OVERHEAD','待分摊制造费用','{"subjectCode":"4101","auxRules":{},"priority":0}','系统建议映射，按账套复核');

INSERT INTO gl_subject(id,tenant_id,company_id,code,name,level,category,profit_role,direction,is_leaf,aux_types)
SELECT base.max_id+ROW_NUMBER() OVER(ORDER BY b.tenant_id,b.company_id),b.tenant_id,b.company_id,
       IF(b.standard='enterprise','5102','4102'),'待分摊直接人工',1,'cost','none','D',1,JSON_ARRAY()
FROM gl_account_book b CROSS JOIN (SELECT COALESCE(MAX(id),0) max_id FROM gl_subject) base
WHERE NOT EXISTS (SELECT 1 FROM gl_subject s WHERE s.tenant_id=b.tenant_id AND s.company_id=b.company_id
                  AND s.code=IF(b.standard='enterprise','5102','4102'));

INSERT INTO gl_business_mapping(id,tenant_id,company_id,ledger_account_code,subject_id,aux_rules,priority)
SELECT base.max_id+ROW_NUMBER() OVER(ORDER BY b.tenant_id,b.company_id,p.code),b.tenant_id,b.company_id,p.code,s.id,JSON_OBJECT(),0
FROM gl_account_book b JOIN gl_standard_preset p ON p.standard=b.standard AND p.kind='mapping'
JOIN gl_subject s ON s.tenant_id=b.tenant_id AND s.company_id=b.company_id
    AND s.code=JSON_UNQUOTE(JSON_EXTRACT(p.payload,'$.subjectCode')) AND s.status='active' AND s.is_leaf=1 AND JSON_LENGTH(s.aux_types)=0
CROSS JOIN (SELECT COALESCE(MAX(id),0) max_id FROM gl_business_mapping) base
WHERE p.code IN ('WIP','PRODUCTION_VARIANCE','PRODUCTION_LABOR','PRODUCTION_OVERHEAD')
  AND NOT EXISTS (SELECT 1 FROM gl_business_mapping m WHERE m.tenant_id=b.tenant_id AND m.company_id=b.company_id AND m.ledger_account_code=p.code);
