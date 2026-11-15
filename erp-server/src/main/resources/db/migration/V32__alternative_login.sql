CREATE TABLE tenant_login_provider (
  tenant_id BIGINT UNSIGNED NOT NULL,
  method VARCHAR(16) NOT NULL,
  enabled TINYINT(1) NOT NULL DEFAULT 0,
  config_json JSON NOT NULL,
  secret_ciphertext VARCHAR(2048) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (tenant_id, method),
  CONSTRAINT fk_login_provider_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT ck_login_provider_method CHECK (method IN ('sms', 'email', 'wechat')),
  CONSTRAINT ck_login_provider_enabled CHECK (enabled IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='企业登录渠道配置';

CREATE TABLE user_login_identity (
  id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  method VARCHAR(16) NOT NULL,
  subject VARCHAR(254) COLLATE utf8mb4_0900_bin NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_login_method (tenant_id, user_id, method),
  UNIQUE KEY uk_login_identity_subject (tenant_id, method, subject),
  CONSTRAINT fk_login_identity_user FOREIGN KEY (tenant_id, user_id) REFERENCES sys_user (tenant_id, id),
  CONSTRAINT ck_login_identity_method CHECK (method IN ('sms', 'email', 'wechat'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户验证后绑定的登录身份';
