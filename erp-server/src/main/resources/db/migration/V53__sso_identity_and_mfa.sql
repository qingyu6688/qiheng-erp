ALTER TABLE sys_user ADD COLUMN user_type ENUM('human','app') NOT NULL DEFAULT 'human';

ALTER TABLE tenant_login_provider DROP CHECK ck_login_provider_method,
    ADD CONSTRAINT ck_login_provider_method CHECK (method IN ('sms','email','wechat','oidc','ldap','wecom','dingtalk','feishu'));
ALTER TABLE user_login_identity DROP CHECK ck_login_identity_method,
    MODIFY COLUMN subject VARCHAR(600) COLLATE utf8mb4_0900_bin NOT NULL,
    ADD CONSTRAINT ck_login_identity_method CHECK (method IN ('sms','email','wechat','oidc','ldap','wecom','dingtalk','feishu'));

CREATE TABLE tenant_security_policy (
  tenant_id BIGINT UNSIGNED NOT NULL,
  mfa_required_roles JSON NOT NULL,
  password_login_allowed BOOLEAN NOT NULL DEFAULT TRUE,
  sso_auto_provision BOOLEAN NOT NULL DEFAULT FALSE,
  sso_default_role_id BIGINT UNSIGNED NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (tenant_id),
  CONSTRAINT fk_security_policy_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
  CONSTRAINT fk_security_policy_role FOREIGN KEY (tenant_id,sso_default_role_id) REFERENCES sys_role(tenant_id,id),
  CONSTRAINT ck_security_policy_roles CHECK (JSON_TYPE(mfa_required_roles)='ARRAY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_mfa (
  id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  type VARCHAR(16) NOT NULL DEFAULT 'totp',
  secret_cipher VARCHAR(2048) NOT NULL,
  recovery_codes_hash JSON NOT NULL,
  enabled_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  last_used_step BIGINT UNSIGNED NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_mfa (tenant_id,user_id),
  CONSTRAINT fk_user_mfa_user FOREIGN KEY (tenant_id,user_id) REFERENCES sys_user(tenant_id,id),
  CONSTRAINT ck_user_mfa_type CHECK (type='totp'),
  CONSTRAINT ck_user_mfa_recovery CHECK (JSON_TYPE(recovery_codes_hash)='ARRAY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO sys_permission(id,code,name,perm_type,sort_no)
VALUES (14001,'tenant:security_policy','企业身份与安全策略','action',14001);

-- 已有权限必须同时覆盖登录设置和角色授权，防止普通预设角色升级后获得身份管理权限。
INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT seq.last_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id),r.tenant_id,r.id,14001
FROM sys_role r JOIN tenant t ON t.id=r.tenant_id
CROSS JOIN (SELECT COALESCE(MAX(id),0) last_id FROM sys_role_permission) seq
WHERE r.status='active' AND (r.code='SYSTEM_ADMIN' OR (r.code='WAREHOUSE_ADMIN' AND t.name='演示企业'))
  AND (SELECT COUNT(*) FROM sys_role_permission a JOIN sys_permission p ON p.id=a.permission_id
       WHERE a.tenant_id=r.tenant_id AND a.role_id=r.id
         AND p.code IN ('tenant:login_settings','role:assign_permission','user:assign_role'))=3;
