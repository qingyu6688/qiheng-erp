CREATE TABLE sales_quote (
  id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  document_no VARCHAR(40) NOT NULL,
  customer_id BIGINT UNSIGNED NOT NULL,
  opportunity_id BIGINT UNSIGNED NULL,
  business_date DATE NOT NULL,
  valid_to DATE NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'draft',
  price_tax_inclusive TINYINT(1) NOT NULL,
  total_amount DECIMAL(24,2) NOT NULL,
  untaxed_amount DECIMAL(24,2) NOT NULL,
  tax_amount DECIMAL(24,2) NOT NULL,
  order_input JSON NOT NULL COMMENT '确认后的订单输入，单价及费用使用明确快照',
  pricing_snapshot JSON NOT NULL,
  remark VARCHAR(500) NULL,
  below_min_price TINYINT(1) NOT NULL DEFAULT 0,
  approved_price_fingerprint CHAR(64) NULL,
  wf_instance_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  approved_by BIGINT UNSIGNED NULL,
  approved_at DATETIME(3) NULL,
  converted_order_id BIGINT UNSIGNED NULL,
  converted_by BIGINT UNSIGNED NULL,
  converted_at DATETIME(3) NULL,
  owner_user_id BIGINT UNSIGNED NOT NULL,
  owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  created_by BIGINT UNSIGNED NOT NULL,
  updated_by BIGINT UNSIGNED NOT NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_sales_quote_scope(tenant_id,company_id,id),
  UNIQUE KEY uk_sales_quote_no(tenant_id,company_id,document_no),
  UNIQUE KEY uk_sales_quote_order(tenant_id,company_id,converted_order_id),
  KEY idx_sales_quote_list(tenant_id,company_id,status,id),
  KEY idx_sales_quote_expiry(status,valid_to,id),
  KEY idx_sales_quote_tenant_expiry(tenant_id,status,valid_to,id),
  CONSTRAINT fk_sales_quote_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),
  CONSTRAINT fk_sales_quote_customer FOREIGN KEY(tenant_id,customer_id) REFERENCES business_partner(tenant_id,id),
  CONSTRAINT fk_sales_quote_order FOREIGN KEY(tenant_id,company_id,converted_order_id) REFERENCES sales_order(tenant_id,company_id,id),
  CONSTRAINT ck_sales_quote_status CHECK(status IN('draft','pending','issued','rejected','converted','expired','voided')),
  CONSTRAINT ck_sales_quote_amount CHECK(total_amount>=0 AND untaxed_amount>=0 AND tax_amount>=0 AND total_amount=untaxed_amount+tax_amount),
  CONSTRAINT ck_sales_quote_dates CHECK(valid_to>=business_date),
  CONSTRAINT ck_sales_quote_conversion CHECK((status='converted')=(converted_order_id IS NOT NULL)
    AND (converted_order_id IS NULL OR (converted_by IS NOT NULL AND converted_at IS NOT NULL))),
  CONSTRAINT ck_sales_quote_approval CHECK((approved_by IS NULL)=(approved_at IS NULL)
    AND (approved_by IS NULL OR approved_by<>created_by))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sales_quote_line (
  id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  quote_id BIGINT UNSIGNED NOT NULL,
  line_no INT UNSIGNED NOT NULL,
  sku_id BIGINT UNSIGNED NOT NULL,
  warehouse_id BIGINT UNSIGNED NULL,
  unit_id BIGINT UNSIGNED NOT NULL,
  unit_rate DECIMAL(18,6) NOT NULL,
  qty DECIMAL(24,6) NOT NULL,
  base_qty DECIMAL(24,6) NOT NULL,
  unit_price DECIMAL(24,8) NOT NULL,
  untaxed_amount DECIMAL(24,2) NOT NULL,
  tax_amount DECIMAL(24,2) NOT NULL,
  below_min_price TINYINT(1) NOT NULL DEFAULT 0,
  min_sale_price_snapshot DECIMAL(24,8) NOT NULL DEFAULT 0,
  is_deleted TINYINT(1) NOT NULL DEFAULT 0,
  UNIQUE KEY uk_sales_quote_line(quote_id,line_no),
  KEY idx_sales_quote_line_scope(tenant_id,company_id,quote_id,id),
  CONSTRAINT fk_sales_quote_line_head FOREIGN KEY(tenant_id,company_id,quote_id) REFERENCES sales_quote(tenant_id,company_id,id),
  CONSTRAINT fk_sales_quote_line_sku FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id),
  CONSTRAINT fk_sales_quote_line_unit FOREIGN KEY(tenant_id,unit_id) REFERENCES unit(tenant_id,id),
  CONSTRAINT fk_sales_quote_line_wh FOREIGN KEY(tenant_id,company_id,warehouse_id) REFERENCES warehouse(tenant_id,company_id,id),
  CONSTRAINT ck_sales_quote_line_amount CHECK(qty>0 AND base_qty>0 AND unit_rate>0 AND unit_price>=0 AND untaxed_amount>=0 AND tax_amount>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
  (19000,'sales_quote:view','查看销售报价','menu',19000),
  (19001,'sales_quote:create','新建销售报价','action',19001),
  (19002,'sales_quote:update','修改销售报价','action',19002),
  (19003,'sales_quote:submit','确认销售报价','action',19003),
  (19004,'sales_quote:approve','审批销售报价','action',19004),
  (19005,'sales_quote:convert','报价转销售订单','action',19005),
  (19006,'sales_quote:void','作废销售报价','action',19006);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT base.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN sys_permission p ON p.id BETWEEN 19000 AND 19006
CROSS JOIN(SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) base
WHERE r.code='SYSTEM_ADMIN' AND EXISTS(SELECT 1 FROM sys_role_permission member JOIN sys_permission prior
  ON prior.id=member.permission_id WHERE member.tenant_id=r.tenant_id AND member.role_id=r.id AND prior.code='role:assign_permission');

INSERT INTO job_definition(tenant_id,code,name,schedule) VALUES(0,'sales.quote-expiry','销售报价到期失效','interval:60000');
