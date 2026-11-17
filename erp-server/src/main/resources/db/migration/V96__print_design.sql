CREATE TABLE print_template (
  id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  document_type VARCHAR(32) NOT NULL,
  name VARCHAR(100) NOT NULL,
  layout_json JSON NOT NULL,
  is_default TINYINT NOT NULL DEFAULT 0,
  status VARCHAR(12) NOT NULL DEFAULT 'draft',
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  created_by BIGINT UNSIGNED NOT NULL,
  updated_by BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  default_key TINYINT GENERATED ALWAYS AS (CASE WHEN status='active' AND is_default=1 THEN 1 END) STORED,
  INDEX idx_print_template_list(tenant_id,company_id,document_type,id),
  UNIQUE KEY uk_print_template_default(tenant_id,company_id,document_type,default_key),
  UNIQUE KEY uk_print_template_name(tenant_id,company_id,document_type,name),
  CONSTRAINT fk_print_template_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
  CONSTRAINT ck_print_template_status CHECK (status IN ('draft','active','disabled') AND is_default IN (0,1) AND (is_default=0 OR status='active'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE print_log (
  id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  document_type VARCHAR(32) NOT NULL,
  document_id BIGINT UNSIGNED NOT NULL,
  template_id BIGINT UNSIGNED NULL,
  template_version BIGINT UNSIGNED NULL,
  export_job_id BIGINT UNSIGNED NULL,
  printed_by BIGINT UNSIGNED NOT NULL,
  printed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  copies TINYINT UNSIGNED NOT NULL,
  INDEX idx_print_log_document(tenant_id,company_id,document_type,document_id,id),
  UNIQUE KEY uk_print_log_export(tenant_id,company_id,export_job_id,document_id),
  CONSTRAINT fk_print_log_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),
  CONSTRAINT ck_print_log_copies CHECK (copies BETWEEN 1 AND 10)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
  (17220,'print_template:view','查看打印模板设计','menu',17220),
  (17221,'print_template:manage','维护公司打印模板','action',17221),
  (17222,'print_template:tenant_manage','维护全企业打印模板','action',17222);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT base.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN sys_permission p ON p.id BETWEEN 17220 AND 17222
CROSS JOIN (SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) base
WHERE r.status='active' AND r.code='SYSTEM_ADMIN'
  AND EXISTS (SELECT 1 FROM sys_role_permission rp JOIN sys_permission old ON old.id=rp.permission_id
              WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id AND old.code='role:assign_permission');
