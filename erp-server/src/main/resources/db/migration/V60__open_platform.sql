CREATE TABLE open_app (
  id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  name VARCHAR(100) NOT NULL,
  app_key CHAR(35) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  secret_cipher VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  scopes JSON NOT NULL,
  ip_whitelist JSON NOT NULL,
  rate_limit_per_minute SMALLINT UNSIGNED NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  expires_at DATETIME(3) NULL,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_open_app_key (app_key),
  UNIQUE KEY uk_open_app_tenant_id (tenant_id,id),
  UNIQUE KEY uk_open_app_user (tenant_id,user_id),
  FOREIGN KEY (tenant_id,user_id) REFERENCES sys_user (tenant_id,id),
  CHECK (rate_limit_per_minute BETWEEN 1 AND 3000)
);

CREATE TABLE open_app_company (
  tenant_id BIGINT UNSIGNED NOT NULL,
  app_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (tenant_id,app_id,company_id),
  KEY idx_open_company_app (tenant_id,company_id,app_id),
  FOREIGN KEY (tenant_id,app_id) REFERENCES open_app (tenant_id,id),
  FOREIGN KEY (tenant_id,company_id) REFERENCES company (tenant_id,id)
);

CREATE TABLE open_call_log (
  id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  app_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NULL,
  method VARCHAR(8) CHARACTER SET ascii NOT NULL,
  path_template VARCHAR(120) NOT NULL,
  status SMALLINT UNSIGNED NOT NULL,
  latency_ms INT UNSIGNED NOT NULL,
  request_id VARCHAR(64) CHARACTER SET ascii NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_open_call_app (tenant_id,app_id,id),
  KEY idx_open_call_cleanup (tenant_id,created_at),
  FOREIGN KEY (tenant_id,app_id) REFERENCES open_app (tenant_id,id)
);

CREATE TABLE open_webhook (
  id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  app_id BIGINT UNSIGNED NOT NULL,
  url VARCHAR(500) NOT NULL,
  events JSON NOT NULL,
  secret_cipher VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  consecutive_failures SMALLINT UNSIGNED NOT NULL DEFAULT 0,
  version INT UNSIGNED NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_open_hook_tenant_id (tenant_id,id),
  KEY idx_open_hook_app (tenant_id,app_id,enabled),
  FOREIGN KEY (tenant_id,app_id) REFERENCES open_app (tenant_id,id)
);

CREATE TABLE open_webhook_delivery (
  id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  webhook_id BIGINT UNSIGNED NOT NULL,
  event_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NULL,
  event_type VARCHAR(64) CHARACTER SET ascii NOT NULL,
  payload JSON NOT NULL,
  status VARCHAR(16) CHARACTER SET ascii NOT NULL DEFAULT 'pending',
  attempts SMALLINT UNSIGNED NOT NULL DEFAULT 0,
  last_status_code SMALLINT UNSIGNED NULL,
  next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  lease_owner CHAR(36) CHARACTER SET ascii NULL,
  lease_until DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_open_delivery_event (tenant_id,webhook_id,event_id),
  KEY idx_open_delivery_queue (tenant_id,status,next_attempt_at,lease_until),
  KEY idx_open_delivery_hook (tenant_id,webhook_id,id),
  FOREIGN KEY (tenant_id,webhook_id) REFERENCES open_webhook (tenant_id,id),
  CHECK (status IN ('pending','delivered','failed'))
);

CREATE TABLE open_inventory_event_slot (
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  sku_id BIGINT UNSIGNED NOT NULL,
  minute_key BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (tenant_id,company_id,sku_id,minute_key),
  KEY idx_open_inventory_slot_cleanup (tenant_id,created_at)
);
