CREATE TABLE dash_layout (
  id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  layout_json JSON NOT NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY(id),
  UNIQUE KEY uk_dash_layout_user(tenant_id,company_id,user_id),
  CONSTRAINT fk_dash_layout_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),
  CONSTRAINT fk_dash_layout_user FOREIGN KEY(tenant_id,user_id) REFERENCES sys_user(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
  (17200,'dashboard:view','查看经营驾驶舱','menu',17200);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT base.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN sys_permission p ON p.code='dashboard:view'
CROSS JOIN (SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) base
WHERE r.code='SYSTEM_ADMIN'
  AND EXISTS (SELECT 1 FROM sys_role_permission member JOIN sys_permission prior ON prior.id=member.permission_id
              WHERE member.tenant_id=r.tenant_id AND member.role_id=r.id AND prior.code='role:assign_permission');
