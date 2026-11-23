CREATE TABLE sales_invoice_scope_lock (
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY(tenant_id,company_id),
  CONSTRAINT fk_sales_invoice_lock_company FOREIGN KEY(tenant_id,company_id)
    REFERENCES company(tenant_id,id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE invoice_red_registration (
  id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  invoice_id BIGINT UNSIGNED NOT NULL,
  red_invoice_no VARCHAR(64) NOT NULL,
  red_invoice_date DATE NOT NULL,
  amount DECIMAL(24,2) NOT NULL,
  tax_amount DECIMAL(24,2) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'pending',
  active_invoice_id BIGINT UNSIGNED GENERATED ALWAYS AS(IF(status IN('pending','confirmed'),invoice_id,NULL)) STORED,
  source_version BIGINT UNSIGNED NOT NULL,
  created_by BIGINT UNSIGNED NOT NULL,
  confirmed_by BIGINT UNSIGNED NULL,
  confirmed_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  updated_by BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_invoice_red_scope(tenant_id,company_id,id),
  UNIQUE KEY uk_invoice_red_number(tenant_id,company_id,red_invoice_no),
  UNIQUE KEY uk_invoice_red_active(tenant_id,company_id,active_invoice_id),
  KEY idx_invoice_red_list(tenant_id,company_id,status,id),
  CONSTRAINT fk_invoice_red_blue FOREIGN KEY(tenant_id,company_id,invoice_id)
    REFERENCES invoice_registration(tenant_id,company_id,id),
  CONSTRAINT ck_invoice_red_status CHECK(status IN('pending','confirmed','cancelled')),
  CONSTRAINT ck_invoice_red_amount CHECK(amount>0 AND tax_amount>=0 AND tax_amount<=amount),
  CONSTRAINT ck_invoice_red_review CHECK(
    (status='confirmed' AND confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL AND confirmed_by<>created_by)
    OR (status<>'confirmed' AND confirmed_by IS NULL AND confirmed_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
  (18500,'invoice_red:view','查看手工销项红字登记','menu',18500),
  (18501,'invoice_red:create','登记手工销项红字','action',18501),
  (18502,'invoice_red:confirm','复核手工销项红字','action',18502),
  (18503,'invoice_red:cancel','撤回手工销项红字登记','action',18503);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT base.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN sys_permission p ON p.id BETWEEN 18500 AND 18503
CROSS JOIN(SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) base
WHERE r.code='SYSTEM_ADMIN' AND EXISTS(SELECT 1 FROM sys_role_permission member JOIN sys_permission prior ON prior.id=member.permission_id
  WHERE member.tenant_id=r.tenant_id AND member.role_id=r.id AND prior.code='role:assign_permission');
