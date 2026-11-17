CREATE TABLE price_list (
  id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  name VARCHAR(100) NOT NULL,
  currency CHAR(3) NOT NULL DEFAULT 'CNY',
  price_includes_tax BOOLEAN NOT NULL,
  valid_from DATE NOT NULL,
  valid_to DATE NOT NULL,
  priority INT UNSIGNED NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_price_list_scope (tenant_id,company_id,id),
  KEY idx_price_list_validity (tenant_id,company_id,status,valid_from,valid_to),
  CONSTRAINT fk_price_list_company FOREIGN KEY (tenant_id,company_id) REFERENCES company(tenant_id,id),
  CONSTRAINT ck_price_list_currency CHECK (currency='CNY'),
  CONSTRAINT ck_price_list_period CHECK (valid_from<=valid_to),
  CONSTRAINT ck_price_list_status CHECK (status IN ('active','disabled'))
);

CREATE TABLE price_list_item (
  id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  price_list_id BIGINT UNSIGNED NOT NULL,
  sku_id BIGINT UNSIGNED NOT NULL,
  unit_id BIGINT UNSIGNED NOT NULL,
  min_qty DECIMAL(24,6) NOT NULL,
  unit_price DECIMAL(24,8) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_price_list_tier (tenant_id,company_id,price_list_id,sku_id,unit_id,min_qty),
  KEY idx_price_list_item_quote (tenant_id,company_id,sku_id,unit_id,min_qty,price_list_id),
  CONSTRAINT fk_price_list_item_list FOREIGN KEY (tenant_id,company_id,price_list_id) REFERENCES price_list(tenant_id,company_id,id),
  CONSTRAINT fk_price_list_item_sku FOREIGN KEY (tenant_id,sku_id) REFERENCES sku(tenant_id,id),
  CONSTRAINT fk_price_list_item_unit FOREIGN KEY (tenant_id,unit_id) REFERENCES unit(tenant_id,id),
  CONSTRAINT ck_price_list_amount CHECK (min_qty>=0 AND unit_price>=0)
);

CREATE TABLE price_list_target (
  id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  price_list_id BIGINT UNSIGNED NOT NULL,
  target_type VARCHAR(20) NOT NULL,
  target_value VARCHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_price_list_target (tenant_id,company_id,price_list_id,target_type,target_value),
  CONSTRAINT fk_price_list_target_list FOREIGN KEY (tenant_id,company_id,price_list_id) REFERENCES price_list(tenant_id,company_id,id),
  CONSTRAINT ck_price_list_target CHECK ((target_type='all' AND target_value='') OR
    (target_type IN ('customer','customer_level') AND target_value<>''))
);

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
  (14880,'price_list:view','查看价格表','menu',14880),
  (14881,'price_list:manage','维护价格表','action',14881);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT m.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN sys_permission p ON p.id IN (14880,14881)
CROSS JOIN (SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) m
WHERE r.status='active' AND r.code='SYSTEM_ADMIN'
  AND EXISTS (SELECT 1 FROM sys_role_permission rp JOIN sys_permission prior ON prior.id=rp.permission_id
              WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id AND prior.code='role:assign_permission');
