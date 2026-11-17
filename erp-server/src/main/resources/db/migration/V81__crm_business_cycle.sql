CREATE TABLE crm_business_stage_group (
  id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  name VARCHAR(80) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_crm_stage_group_scope (tenant_id,company_id,id),
  UNIQUE KEY uk_crm_stage_group_name (tenant_id,company_id,name),
  FOREIGN KEY (tenant_id,company_id) REFERENCES company (tenant_id,id)
) ENGINE=InnoDB;

CREATE TABLE crm_stage_department (
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  group_id BIGINT UNSIGNED NOT NULL,
  department_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (tenant_id,company_id,group_id,department_id),
  FOREIGN KEY (tenant_id,company_id,group_id) REFERENCES crm_business_stage_group (tenant_id,company_id,id)
) ENGINE=InnoDB;

CREATE TABLE crm_business_stage (
  id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  group_id BIGINT UNSIGNED NOT NULL,
  name VARCHAR(80) NOT NULL,
  win_rate DECIMAL(7,6) NOT NULL,
  sort INT UNSIGNED NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_crm_stage_scope (tenant_id,company_id,group_id,id),
  UNIQUE KEY uk_crm_stage_name (tenant_id,company_id,group_id,name),
  FOREIGN KEY (tenant_id,company_id,group_id) REFERENCES crm_business_stage_group (tenant_id,company_id,id),
  CHECK (win_rate BETWEEN 0 AND 1)
) ENGINE=InnoDB;

CREATE TABLE crm_business (
  id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  name VARCHAR(120) NOT NULL,
  partner_id BIGINT UNSIGNED NOT NULL,
  stage_group_id BIGINT UNSIGNED NOT NULL,
  stage_id BIGINT UNSIGNED NOT NULL,
  expected_amount DECIMAL(24,2) NOT NULL,
  expected_date DATE NOT NULL,
  owner_user_id BIGINT UNSIGNED NOT NULL,
  owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  result VARCHAR(12) NOT NULL DEFAULT 'open',
  lost_reason VARCHAR(300),
  version INT UNSIGNED NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_crm_business_scope (tenant_id,company_id,id),
  KEY idx_crm_business_customer (tenant_id,company_id,partner_id,id),
  KEY idx_crm_business_statistics (tenant_id,company_id,expected_date,stage_id),
  FOREIGN KEY (tenant_id,partner_id) REFERENCES business_partner (tenant_id,id),
  FOREIGN KEY (tenant_id,company_id,stage_group_id,stage_id) REFERENCES crm_business_stage (tenant_id,company_id,group_id,id),
  CHECK (result IN ('open','won','lost','invalid'))
) ENGINE=InnoDB;

CREATE TABLE crm_business_line (
  id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  business_id BIGINT UNSIGNED NOT NULL,
  line_no INT UNSIGNED NOT NULL,
  sku_id BIGINT UNSIGNED NOT NULL,
  unit_id BIGINT UNSIGNED NOT NULL,
  qty DECIMAL(24,6) NOT NULL,
  unit_price DECIMAL(24,8) NOT NULL,
  discount_rate DECIMAL(7,6) NOT NULL,
  amount DECIMAL(24,2) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_crm_business_line (tenant_id,company_id,business_id,line_no),
  FOREIGN KEY (tenant_id,company_id,business_id) REFERENCES crm_business (tenant_id,company_id,id),
  FOREIGN KEY (tenant_id,sku_id) REFERENCES sku (tenant_id,id),
  FOREIGN KEY (tenant_id,unit_id) REFERENCES unit (tenant_id,id)
) ENGINE=InnoDB;

CREATE TABLE crm_business_stage_event (
  id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  business_id BIGINT UNSIGNED NOT NULL,
  from_stage_id BIGINT UNSIGNED,
  to_stage_id BIGINT UNSIGNED NOT NULL,
  advanced BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_crm_stage_event_statistics (tenant_id,company_id,created_at),
  KEY idx_crm_stage_event_business (tenant_id,company_id,business_id,to_stage_id,created_at),
  FOREIGN KEY (tenant_id,company_id,business_id) REFERENCES crm_business (tenant_id,company_id,id)
) ENGINE=InnoDB;

CREATE TABLE crm_contract (
  id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  no VARCHAR(64) NOT NULL,
  name VARCHAR(120) NOT NULL,
  partner_id BIGINT UNSIGNED NOT NULL,
  business_id BIGINT UNSIGNED,
  signer_contact_id BIGINT UNSIGNED,
  owner_user_id BIGINT UNSIGNED NOT NULL,
  owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  sign_date DATE NOT NULL,
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  price_tax_inclusive BOOLEAN NOT NULL,
  total_amount DECIMAL(24,2) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'draft',
  wf_instance_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  created_by BIGINT UNSIGNED NOT NULL,
  approved_at DATETIME(3),
  version INT UNSIGNED NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_crm_contract_scope (tenant_id,company_id,id),
  UNIQUE KEY uk_crm_contract_no (tenant_id,company_id,no),
  KEY idx_crm_contract_customer (tenant_id,company_id,partner_id,id),
  KEY idx_crm_contract_statistics (tenant_id,company_id,sign_date,owner_user_id),
  FOREIGN KEY (tenant_id,partner_id) REFERENCES business_partner (tenant_id,id),
  FOREIGN KEY (tenant_id,company_id,business_id) REFERENCES crm_business (tenant_id,company_id,id),
  CHECK (start_date <= end_date),
  CHECK (status IN ('draft','pending','approved','rejected','cancelled','finished'))
) ENGINE=InnoDB;

CREATE TABLE crm_contract_line (
  id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  contract_id BIGINT UNSIGNED NOT NULL,
  line_no INT UNSIGNED NOT NULL,
  sku_id BIGINT UNSIGNED NOT NULL,
  unit_id BIGINT UNSIGNED NOT NULL,
  qty DECIMAL(24,6) NOT NULL,
  unit_price DECIMAL(24,8) NOT NULL,
  tax_rate DECIMAL(7,6) NOT NULL,
  discount_rate DECIMAL(7,6) NOT NULL,
  total_amount DECIMAL(24,2) NOT NULL,
  below_min_price BOOLEAN NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_crm_contract_line (tenant_id,company_id,contract_id,line_no),
  UNIQUE KEY uk_crm_contract_line_scope (tenant_id,company_id,contract_id,id),
  FOREIGN KEY (tenant_id,company_id,contract_id) REFERENCES crm_contract (tenant_id,company_id,id),
  FOREIGN KEY (tenant_id,sku_id) REFERENCES sku (tenant_id,id),
  FOREIGN KEY (tenant_id,unit_id) REFERENCES unit (tenant_id,id)
) ENGINE=InnoDB;

ALTER TABLE sales_order ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT '',
  ADD COLUMN source_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  ADD KEY idx_sales_order_source (tenant_id,company_id,source_type,source_id);

CREATE TABLE crm_contract_order (
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  contract_id BIGINT UNSIGNED NOT NULL,
  order_id BIGINT UNSIGNED NOT NULL,
  request_key VARCHAR(64) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  operator_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (tenant_id,company_id,contract_id,order_id),
  UNIQUE KEY uk_crm_contract_order_request (tenant_id,company_id,contract_id,request_key),
  FOREIGN KEY (tenant_id,company_id,contract_id) REFERENCES crm_contract (tenant_id,company_id,id),
  FOREIGN KEY (tenant_id,company_id,order_id) REFERENCES sales_order (tenant_id,company_id,id)
) ENGINE=InnoDB;

CREATE TABLE crm_contract_order_line (
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  contract_id BIGINT UNSIGNED NOT NULL,
  order_id BIGINT UNSIGNED NOT NULL,
  contract_line_id BIGINT UNSIGNED NOT NULL,
  qty DECIMAL(24,6) NOT NULL,
  amount DECIMAL(24,2) NOT NULL,
  PRIMARY KEY (tenant_id,company_id,contract_id,order_id,contract_line_id),
  FOREIGN KEY (tenant_id,company_id,contract_id,order_id) REFERENCES crm_contract_order (tenant_id,company_id,contract_id,order_id),
  FOREIGN KEY (tenant_id,company_id,contract_id,contract_line_id) REFERENCES crm_contract_line (tenant_id,company_id,contract_id,id)
) ENGINE=InnoDB;

CREATE TABLE crm_receivable_plan (
  id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  contract_id BIGINT UNSIGNED NOT NULL,
  period_no INT UNSIGNED NOT NULL,
  plan_date DATE NOT NULL,
  plan_amount DECIMAL(24,2) NOT NULL,
  remind_days INT UNSIGNED NOT NULL DEFAULT 3,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_crm_plan_scope (tenant_id,company_id,contract_id,id),
  UNIQUE KEY uk_crm_plan_period (tenant_id,company_id,contract_id,period_no),
  KEY idx_crm_plan_date (tenant_id,company_id,plan_date),
  FOREIGN KEY (tenant_id,company_id,contract_id) REFERENCES crm_contract (tenant_id,company_id,id),
  CHECK (plan_amount > 0)
) ENGINE=InnoDB;

ALTER TABLE receipt ADD COLUMN crm_contract_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  ADD KEY idx_receipt_crm_contract (tenant_id,company_id,crm_contract_id);

CREATE TABLE crm_receivable (
  id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  contract_id BIGINT UNSIGNED NOT NULL,
  plan_id BIGINT UNSIGNED NOT NULL,
  receipt_id BIGINT UNSIGNED NOT NULL,
  amount DECIMAL(24,2) NOT NULL,
  created_by BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_crm_receivable_receipt (tenant_id,company_id,receipt_id),
  KEY idx_crm_receivable_plan (tenant_id,company_id,contract_id,plan_id),
  FOREIGN KEY (tenant_id,company_id,contract_id,plan_id) REFERENCES crm_receivable_plan (tenant_id,company_id,contract_id,id),
  FOREIGN KEY (tenant_id,company_id,receipt_id) REFERENCES receipt (tenant_id,company_id,id),
  CHECK (amount > 0)
) ENGINE=InnoDB;

CREATE TABLE crm_performance_target (
  id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  target_type VARCHAR(12) NOT NULL,
  target_id BIGINT UNSIGNED NOT NULL,
  year SMALLINT UNSIGNED NOT NULL,
  month TINYINT UNSIGNED NOT NULL,
  kind VARCHAR(12) NOT NULL,
  amount DECIMAL(24,2) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_crm_target_scope (tenant_id,company_id,target_type,target_id,year,month,kind),
  FOREIGN KEY (tenant_id,company_id) REFERENCES company (tenant_id,id),
  CHECK (target_type IN ('user','department')),
  CHECK (kind IN ('contract','receivable')),
  CHECK (month BETWEEN 1 AND 12),
  CHECK (amount >= 0)
) ENGINE=InnoDB;
