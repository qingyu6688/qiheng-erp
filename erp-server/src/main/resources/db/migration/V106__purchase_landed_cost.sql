ALTER TABLE company_policy
  ADD purchase_fee_cost_enabled TINYINT(1) NOT NULL DEFAULT 1,
  ADD purchase_fee_allocation_method VARCHAR(16) NOT NULL DEFAULT 'amount',
  ADD CONSTRAINT ck_company_purchase_fee_cost CHECK(purchase_fee_cost_enabled IN(0,1)),
  ADD CONSTRAINT ck_company_purchase_fee_method CHECK(purchase_fee_allocation_method IN('amount','quantity','weight'));

ALTER TABLE purchase_order_fee
  ADD recognized_amount DECIMAL(24,2) NOT NULL DEFAULT 0,
  ADD recognized_tax DECIMAL(24,2) NOT NULL DEFAULT 0,
  ADD UNIQUE KEY uk_purchase_order_fee_scope(tenant_id,company_id,id);

-- 新增进度只映射原已确认状态，原费用、应付和库存事实保持原值。
UPDATE purchase_order_fee SET recognized_amount=total_amount,recognized_tax=tax_amount,updated_at=updated_at WHERE recognized_receipt_id IS NOT NULL;
ALTER TABLE purchase_order_fee
  ADD CONSTRAINT ck_purchase_order_fee_progress CHECK(recognized_amount>=0 AND recognized_amount<=total_amount
    AND recognized_tax>=0 AND recognized_tax<=tax_amount AND recognized_tax<=recognized_amount
    AND tax_amount-recognized_tax<=total_amount-recognized_amount),
  DROP CHECK ck_purchase_order_fee_type,
  ADD CONSTRAINT ck_purchase_order_fee_type CHECK(fee_type IN('freight','loading','other'));

CREATE TABLE purchase_fee_doc (
  id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  document_no VARCHAR(32) NOT NULL,
  source_kind VARCHAR(16) NOT NULL DEFAULT 'manual',
  source_receipt_id BIGINT UNSIGNED NULL,
  partner_id BIGINT UNSIGNED NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  business_date DATE NOT NULL,
  allocation_method VARCHAR(16) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  cost_tax_inclusive TINYINT(1) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'draft',
  amount DECIMAL(24,2) NOT NULL DEFAULT 0,
  untaxed_amount DECIMAL(24,2) NOT NULL DEFAULT 0,
  tax_amount DECIMAL(24,2) NOT NULL DEFAULT 0,
  stock_cost DECIMAL(24,2) NOT NULL DEFAULT 0,
  issued_cost DECIMAL(24,2) NOT NULL DEFAULT 0,
  term_type VARCHAR(16) NULL,
  term_value SMALLINT UNSIGNED NULL,
  due_date DATE NULL,
  period_id BIGINT UNSIGNED NULL,
  wf_instance_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  approved_by BIGINT UNSIGNED NULL,
  approved_at DATETIME(3) NULL,
  posted_by BIGINT UNSIGNED NULL,
  posted_at DATETIME(3) NULL,
  owner_user_id BIGINT UNSIGNED NOT NULL,
  owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  created_by BIGINT UNSIGNED NOT NULL,
  updated_by BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_purchase_fee_doc_scope(tenant_id,company_id,id),
  UNIQUE KEY uk_purchase_fee_doc_no(tenant_id,company_id,document_no),
  UNIQUE KEY uk_purchase_fee_doc_receipt(tenant_id,company_id,source_receipt_id),
  KEY idx_purchase_fee_doc_list(tenant_id,company_id,status,id),
  CONSTRAINT fk_purchase_fee_doc_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),
  CONSTRAINT fk_purchase_fee_doc_partner FOREIGN KEY(tenant_id,partner_id) REFERENCES business_partner(tenant_id,id),
  CONSTRAINT fk_purchase_fee_doc_warehouse FOREIGN KEY(tenant_id,company_id,warehouse_id) REFERENCES warehouse(tenant_id,company_id,id),
  CONSTRAINT fk_purchase_fee_doc_receipt FOREIGN KEY(tenant_id,company_id,source_receipt_id) REFERENCES goods_receipt(tenant_id,company_id,id),
  CONSTRAINT fk_purchase_fee_doc_period FOREIGN KEY(tenant_id,company_id,period_id) REFERENCES accounting_period(tenant_id,company_id,id),
  CONSTRAINT ck_purchase_fee_doc_source CHECK((source_kind='manual' AND source_receipt_id IS NULL) OR(source_kind='receipt' AND source_receipt_id IS NOT NULL)),
  CONSTRAINT ck_purchase_fee_doc_status CHECK(status IN('draft','pending','approved','rejected','posted','voided','reversed')),
  CONSTRAINT ck_purchase_fee_doc_method CHECK(allocation_method IN('amount','quantity','weight')),
  CONSTRAINT ck_purchase_fee_doc_tax CHECK(cost_tax_inclusive IN(0,1) AND amount>=0 AND tax_amount>=0 AND untaxed_amount>=0 AND amount=untaxed_amount+tax_amount),
  CONSTRAINT ck_purchase_fee_doc_cost CHECK(stock_cost>=0 AND issued_cost>=0),
  CONSTRAINT ck_purchase_fee_doc_posted CHECK((status IN('posted','reversed'))=(posted_at IS NOT NULL AND period_id IS NOT NULL AND posted_by IS NOT NULL)),
  CONSTRAINT ck_purchase_fee_doc_approved CHECK(source_kind='receipt' OR status NOT IN('approved','posted','reversed') OR(approved_by IS NOT NULL AND approved_at IS NOT NULL)),
  CONSTRAINT ck_purchase_fee_doc_posted_cost CHECK(status NOT IN('posted','reversed') OR stock_cost+issued_cost=IF(cost_tax_inclusive=1,amount,untaxed_amount)),
  CONSTRAINT ck_purchase_fee_doc_term CHECK((term_type IS NULL AND term_value IS NULL) OR
    (term_type IS NOT NULL AND term_value IS NOT NULL AND((term_type='days' AND term_value BETWEEN 0 AND 365) OR(term_type='monthly' AND term_value BETWEEN 1 AND 28))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE purchase_fee_doc_line (
  id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  document_id BIGINT UNSIGNED NOT NULL,
  line_no INT UNSIGNED NOT NULL,
  order_fee_id BIGINT UNSIGNED NULL,
  fee_type VARCHAR(16) NOT NULL,
  amount DECIMAL(24,2) NOT NULL,
  untaxed_amount DECIMAL(24,2) NOT NULL,
  tax_amount DECIMAL(24,2) NOT NULL,
  tax_rate DECIMAL(7,6) NOT NULL,
  remark VARCHAR(200) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_purchase_fee_line_scope(tenant_id,company_id,id),
  UNIQUE KEY uk_purchase_fee_line_no(document_id,line_no),
  UNIQUE KEY uk_purchase_fee_line_order_once(document_id,order_fee_id),
  KEY idx_purchase_fee_line_order_fee(tenant_id,company_id,order_fee_id),
  CONSTRAINT fk_purchase_fee_line_header FOREIGN KEY(tenant_id,company_id,document_id) REFERENCES purchase_fee_doc(tenant_id,company_id,id),
  CONSTRAINT fk_purchase_fee_line_order_fee FOREIGN KEY(tenant_id,company_id,order_fee_id) REFERENCES purchase_order_fee(tenant_id,company_id,id),
  CONSTRAINT ck_purchase_fee_line_type CHECK(fee_type IN('freight','loading','other')),
  CONSTRAINT ck_purchase_fee_line_amount CHECK(amount>0 AND untaxed_amount>=0 AND tax_amount>=0 AND amount=untaxed_amount+tax_amount AND tax_rate>=0 AND tax_rate<1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE purchase_fee_allocation (
  id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  document_id BIGINT UNSIGNED NOT NULL,
  receipt_line_id BIGINT UNSIGNED NOT NULL,
  source_version BIGINT UNSIGNED NOT NULL,
  sku_id BIGINT UNSIGNED NOT NULL,
  unit_id BIGINT UNSIGNED NOT NULL,
  source_base_qty DECIMAL(24,6) NOT NULL,
  source_returned_qty DECIMAL(24,6) NOT NULL,
  source_amount DECIMAL(24,2) NOT NULL,
  source_tax DECIMAL(24,2) NOT NULL,
  sku_weight_kg DECIMAL(24,6) NOT NULL DEFAULT 0,
  basis_weight DECIMAL(60,18) NOT NULL,
  amount DECIMAL(24,2) NOT NULL,
  untaxed_amount DECIMAL(24,2) NOT NULL,
  tax_amount DECIMAL(24,2) NOT NULL,
  cost_amount DECIMAL(24,2) NOT NULL,
  stock_cost DECIMAL(24,2) NOT NULL DEFAULT 0,
  issued_cost DECIMAL(24,2) NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_purchase_fee_allocation_scope(tenant_id,company_id,id),
  UNIQUE KEY uk_purchase_fee_allocation_parent(tenant_id,company_id,document_id,id),
  UNIQUE KEY uk_purchase_fee_allocation_source(document_id,receipt_line_id),
  KEY idx_purchase_fee_allocation_receipt(tenant_id,company_id,receipt_line_id),
  CONSTRAINT fk_purchase_fee_allocation_header FOREIGN KEY(tenant_id,company_id,document_id) REFERENCES purchase_fee_doc(tenant_id,company_id,id),
  CONSTRAINT fk_purchase_fee_allocation_receipt FOREIGN KEY(tenant_id,company_id,receipt_line_id) REFERENCES goods_receipt_line(tenant_id,company_id,id),
  CONSTRAINT fk_purchase_fee_allocation_sku FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id),
  CONSTRAINT fk_purchase_fee_allocation_unit FOREIGN KEY(tenant_id,unit_id) REFERENCES unit(tenant_id,id),
  CONSTRAINT ck_purchase_fee_allocation_source CHECK(source_base_qty>0 AND source_returned_qty>=0 AND source_amount>=0 AND source_tax>=0 AND source_tax<=source_amount AND sku_weight_kg>=0 AND basis_weight>=0),
  CONSTRAINT ck_purchase_fee_allocation_amount CHECK(amount>=0 AND tax_amount>=0 AND untaxed_amount>=0 AND amount=untaxed_amount+tax_amount AND cost_amount>=0 AND cost_amount<=amount AND stock_cost>=0 AND issued_cost>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE purchase_fee_cost (
  id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  document_id BIGINT UNSIGNED NOT NULL,
  allocation_id BIGINT UNSIGNED NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  sku_id BIGINT UNSIGNED NOT NULL,
  in_stock_qty DECIMAL(24,12) NOT NULL,
  stock_amount DECIMAL(24,2) NOT NULL,
  issued_amount DECIMAL(24,2) NOT NULL,
  movement_cutoff_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  movement_cutoff_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_purchase_fee_cost_allocation(allocation_id,warehouse_id),
  KEY idx_purchase_fee_cost_document(tenant_id,company_id,document_id),
  CONSTRAINT fk_purchase_fee_cost_header FOREIGN KEY(tenant_id,company_id,document_id) REFERENCES purchase_fee_doc(tenant_id,company_id,id),
  CONSTRAINT fk_purchase_fee_cost_allocation FOREIGN KEY(tenant_id,company_id,document_id,allocation_id) REFERENCES purchase_fee_allocation(tenant_id,company_id,document_id,id),
  CONSTRAINT fk_purchase_fee_cost_warehouse FOREIGN KEY(tenant_id,company_id,warehouse_id) REFERENCES warehouse(tenant_id,company_id,id),
  CONSTRAINT fk_purchase_fee_cost_sku FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id),
  CONSTRAINT ck_purchase_fee_cost_amount CHECK(in_stock_qty>=0 AND stock_amount>=0 AND issued_amount>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE cost_adjustment DROP CHECK ck_cost_adjustment_type,
  ADD CONSTRAINT ck_cost_adjustment_type CHECK(adjust_type IN('zero_qty_residue','purchase_return_diff','revaluation','production_variance',
    'purchase_price_stock','purchase_price_issued','purchase_fee_stock','purchase_fee_issued'));

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
  (18300,'purchase_fee_doc:view','查看采购费用单','menu',18300),
  (18301,'purchase_fee_doc:create','新建采购费用单','action',18301),
  (18302,'purchase_fee_doc:update','修改采购费用单','action',18302),
  (18303,'purchase_fee_doc:submit','提交采购费用单','action',18303),
  (18304,'purchase_fee_doc:approve','复核采购费用单','action',18304),
  (18305,'purchase_fee_doc:post','采购费用单过账','action',18305),
  (18306,'purchase_fee_doc:void','作废采购费用单','action',18306);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT base.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN sys_permission p ON p.code LIKE 'purchase_fee_doc:%'
CROSS JOIN(SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) base
WHERE r.code='SYSTEM_ADMIN' AND EXISTS(SELECT 1 FROM sys_role_permission member JOIN sys_permission prior ON prior.id=member.permission_id
  WHERE member.tenant_id=r.tenant_id AND member.role_id=r.id AND prior.code='role:assign_permission');
