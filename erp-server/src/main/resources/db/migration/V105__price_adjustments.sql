CREATE TABLE price_adjustment (
  id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  adjust_no VARCHAR(32) NOT NULL,
  kind VARCHAR(16) NOT NULL,
  partner_id BIGINT UNSIGNED NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  business_date DATE NOT NULL,
  reason VARCHAR(500) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'draft',
  wf_instance_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  amount DECIMAL(24,2) NOT NULL DEFAULT 0,
  tax_amount DECIMAL(24,2) NOT NULL DEFAULT 0,
  untaxed_amount DECIMAL(24,2) NOT NULL DEFAULT 0,
  stock_cost DECIMAL(24,2) NOT NULL DEFAULT 0,
  issued_cost DECIMAL(24,2) NOT NULL DEFAULT 0,
  period_id BIGINT UNSIGNED NULL,
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
  UNIQUE KEY uk_price_adjustment_scope(tenant_id,company_id,id),
  UNIQUE KEY uk_price_adjustment_no(tenant_id,company_id,adjust_no),
  KEY idx_price_adjustment_list(tenant_id,company_id,kind,status,id),
  CONSTRAINT fk_price_adjustment_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),
  CONSTRAINT fk_price_adjustment_partner FOREIGN KEY(tenant_id,partner_id) REFERENCES business_partner(tenant_id,id),
  CONSTRAINT fk_price_adjustment_warehouse FOREIGN KEY(tenant_id,company_id,warehouse_id) REFERENCES warehouse(tenant_id,company_id,id),
  CONSTRAINT fk_price_adjustment_period FOREIGN KEY(tenant_id,company_id,period_id) REFERENCES accounting_period(tenant_id,company_id,id),
  CONSTRAINT ck_price_adjustment_kind CHECK(kind IN('purchase','sales')),
  CONSTRAINT ck_price_adjustment_status CHECK(status IN('draft','pending','approved','rejected','posted','voided','reversed')),
  CONSTRAINT ck_price_adjustment_amount CHECK(amount=untaxed_amount+tax_amount)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE price_adjustment_line (
  id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  adjustment_id BIGINT UNSIGNED NOT NULL,
  line_no INT UNSIGNED NOT NULL,
  receipt_line_id BIGINT UNSIGNED NULL,
  shipment_line_id BIGINT UNSIGNED NULL,
  source_version BIGINT UNSIGNED NOT NULL,
  sku_id BIGINT UNSIGNED NOT NULL,
  unit_id BIGINT UNSIGNED NOT NULL,
  source_qty DECIMAL(36,18) NOT NULL,
  source_base_qty DECIMAL(24,6) NOT NULL,
  source_returned_qty DECIMAL(24,6) NOT NULL,
  input_unit_price DECIMAL(24,6) NULL,
  input_amount DECIMAL(24,2) NULL,
  old_unit_price DECIMAL(24,6) NOT NULL,
  new_unit_price DECIMAL(24,6) NOT NULL,
  old_amount DECIMAL(24,2) NOT NULL,
  old_tax DECIMAL(24,2) NOT NULL,
  tax_rate DECIMAL(9,6) NOT NULL,
  amount DECIMAL(24,2) NOT NULL,
  untaxed_amount DECIMAL(24,2) NOT NULL,
  tax_amount DECIMAL(24,2) NOT NULL,
  cost_tax_inclusive TINYINT(1) NOT NULL,
  stock_cost DECIMAL(24,2) NOT NULL DEFAULT 0,
  issued_cost DECIMAL(24,2) NOT NULL DEFAULT 0,
  prior_return_basis JSON NULL,
  UNIQUE KEY uk_price_adjustment_line_no(adjustment_id,line_no),
  UNIQUE KEY uk_price_adjustment_line_scope(tenant_id,company_id,id),
  UNIQUE KEY uk_price_adjustment_receipt(adjustment_id,receipt_line_id),
  UNIQUE KEY uk_price_adjustment_shipment(adjustment_id,shipment_line_id),
  KEY idx_price_adjustment_receipt(tenant_id,company_id,receipt_line_id,adjustment_id),
  CONSTRAINT fk_price_adjustment_line_header FOREIGN KEY(tenant_id,company_id,adjustment_id) REFERENCES price_adjustment(tenant_id,company_id,id),
  CONSTRAINT fk_price_adjustment_line_receipt FOREIGN KEY(tenant_id,company_id,receipt_line_id) REFERENCES goods_receipt_line(tenant_id,company_id,id),
  CONSTRAINT fk_price_adjustment_line_shipment FOREIGN KEY(tenant_id,company_id,shipment_line_id) REFERENCES shipment_line(tenant_id,company_id,id),
  CONSTRAINT fk_price_adjustment_line_unit FOREIGN KEY(tenant_id,sku_id,unit_id) REFERENCES unit_conversion(tenant_id,sku_id,unit_id),
  CONSTRAINT ck_price_adjustment_source CHECK((receipt_line_id IS NULL)<>(shipment_line_id IS NULL)),
  CONSTRAINT ck_price_adjustment_input CHECK((input_unit_price IS NULL)<>(input_amount IS NULL)),
  CONSTRAINT ck_price_adjustment_line_qty CHECK(source_qty>0 AND source_base_qty>0 AND source_returned_qty>=0),
  CONSTRAINT ck_price_adjustment_line_amount CHECK(amount<>0 AND amount=untaxed_amount+tax_amount AND old_amount>=0 AND old_amount+amount>=0 AND old_tax>=0 AND old_tax+tax_amount>=0 AND tax_rate>=0 AND tax_rate<1 AND old_unit_price>=0 AND new_unit_price>=0 AND (input_unit_price IS NULL OR input_unit_price>=0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE price_adjustment_cost (
  id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  adjustment_id BIGINT UNSIGNED NOT NULL,
  adjustment_line_id BIGINT UNSIGNED NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  sku_id BIGINT UNSIGNED NOT NULL,
  in_stock_qty DECIMAL(24,12) NOT NULL,
  stock_amount DECIMAL(24,2) NOT NULL,
  issued_amount DECIMAL(24,2) NOT NULL,
  movement_cutoff_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  movement_cutoff_at DATETIME(3) NULL,
  UNIQUE KEY uk_price_adjustment_cost(adjustment_line_id,warehouse_id),
  KEY idx_price_adjustment_cost_document(tenant_id,company_id,adjustment_id),
  CONSTRAINT fk_price_adjustment_cost_header FOREIGN KEY(tenant_id,company_id,adjustment_id) REFERENCES price_adjustment(tenant_id,company_id,id),
  CONSTRAINT fk_price_adjustment_cost_line FOREIGN KEY(tenant_id,company_id,adjustment_line_id) REFERENCES price_adjustment_line(tenant_id,company_id,id),
  CONSTRAINT fk_price_adjustment_cost_sku FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id),
  CONSTRAINT fk_price_adjustment_cost_warehouse FOREIGN KEY(tenant_id,company_id,warehouse_id) REFERENCES warehouse(tenant_id,company_id,id),
  CONSTRAINT ck_price_adjustment_cost_qty CHECK(in_stock_qty>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE goods_receipt_line
  ADD price_adjusted_amount DECIMAL(24,2) NOT NULL DEFAULT 0,
  ADD price_adjusted_tax DECIMAL(24,2) NOT NULL DEFAULT 0,
  ADD pricing_return_basis JSON NULL,
  DROP CHECK ck_goods_receipt_line_debit,
  ADD CONSTRAINT ck_goods_receipt_line_debit CHECK(
    debited_amount>=0 AND debited_amount<=COALESCE(payable_amount,0)+price_adjusted_amount
    AND debited_tax>=0 AND debited_tax<=COALESCE(tax_amount,0)+price_adjusted_tax
    AND COALESCE(payable_amount,0)+price_adjusted_amount>=COALESCE(tax_amount,0)+price_adjusted_tax);

ALTER TABLE cost_adjustment DROP CHECK ck_cost_adjustment_type,
  ADD CONSTRAINT ck_cost_adjustment_type CHECK(adjust_type IN
    ('zero_qty_residue','purchase_return_diff','revaluation','production_variance','purchase_price_stock','purchase_price_issued'));

CREATE INDEX idx_inventory_movement_cost_trace ON inventory_movement(tenant_id,company_id,sku_id,posted_at,id);

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
  (18200,'price_adjustment:view','查看调价单','menu',18200),
  (18201,'price_adjustment:create','新建调价单','action',18201),
  (18202,'price_adjustment:update','修改调价单','action',18202),
  (18203,'price_adjustment:submit','提交调价单','action',18203),
  (18204,'price_adjustment:approve','审批调价单','action',18204),
  (18205,'price_adjustment:post','调价单过账','action',18205),
  (18206,'price_adjustment:void','作废调价单','action',18206);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT base.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN sys_permission p ON p.code LIKE 'price_adjustment:%'
CROSS JOIN(SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) base
WHERE r.code='SYSTEM_ADMIN'
  AND EXISTS(SELECT 1 FROM sys_role_permission member JOIN sys_permission prior ON prior.id=member.permission_id
    WHERE member.tenant_id=r.tenant_id AND member.role_id=r.id AND prior.code='role:assign_permission');
