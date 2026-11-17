CREATE TABLE ai_provider (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL,
  provider_type VARCHAR(32) NOT NULL,
  endpoint_key VARCHAR(64) NOT NULL,
  data_region VARCHAR(16) NOT NULL,
  outbound_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
  api_key_cipher VARBINARY(8192),
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  PRIMARY KEY(id), UNIQUE KEY uk_ai_provider_tenant_id(tenant_id,id),
  UNIQUE KEY uk_ai_provider_name(tenant_id,name),
  CHECK(provider_type IN ('openai_compatible','anthropic')),
  CHECK(data_region IN ('domestic','overseas'))
);

CREATE TABLE ai_model (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  provider_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL,
  model_key VARCHAR(160) NOT NULL,
  purpose VARCHAR(16) NOT NULL,
  context_window INT NOT NULL,
  price_in DECIMAL(16,6) NOT NULL,
  price_out DECIMAL(16,6) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  PRIMARY KEY(id), UNIQUE KEY uk_ai_model_tenant_id(tenant_id,id),
  UNIQUE KEY uk_ai_model_key(tenant_id,provider_id,model_key,purpose),
  CONSTRAINT fk_ai_model_provider FOREIGN KEY(tenant_id,provider_id) REFERENCES ai_provider(tenant_id,id),
  CHECK(purpose IN ('chat','embedding','rerank','vision')),
  CHECK(context_window BETWEEN 1024 AND 2000000 AND price_in >= 0 AND price_out >= 0)
);

CREATE TABLE ai_route (
  tenant_id BIGINT NOT NULL,
  scene VARCHAR(32) NOT NULL,
  model_id BIGINT NOT NULL,
  fallback_model_id BIGINT NULL,
  max_tokens INT NOT NULL,
  temperature DECIMAL(4,3) NULL,
  PRIMARY KEY(tenant_id,scene),
  CONSTRAINT fk_ai_route_model FOREIGN KEY(tenant_id,model_id) REFERENCES ai_model(tenant_id,id),
  CONSTRAINT fk_ai_route_fallback FOREIGN KEY(tenant_id,fallback_model_id) REFERENCES ai_model(tenant_id,id),
  CHECK(scene IN ('kb_chat','wiki_maintain')),
  CHECK(max_tokens BETWEEN 64 AND 4096 AND (temperature IS NULL OR temperature BETWEEN 0 AND 1))
);

CREATE TABLE ai_quota (
  tenant_id BIGINT NOT NULL,
  daily_token_limit BIGINT NOT NULL,
  monthly_cost_limit DECIMAL(20,6) NOT NULL,
  PRIMARY KEY(tenant_id),
  CHECK(daily_token_limit >= 0 AND monthly_cost_limit >= 0)
);

CREATE TABLE ai_quota_window (
  tenant_id BIGINT NOT NULL,
  window_type CHAR(1) NOT NULL,
  window_start DATE NOT NULL,
  tokens BIGINT NOT NULL DEFAULT 0,
  cost DECIMAL(20,6) NOT NULL DEFAULT 0,
  PRIMARY KEY(tenant_id,window_type,window_start)
);

CREATE TABLE ai_usage_daily (
  tenant_id BIGINT NOT NULL,
  usage_date DATE NOT NULL,
  model_id BIGINT NOT NULL,
  scene VARCHAR(32) NOT NULL,
  input_tokens BIGINT NOT NULL DEFAULT 0,
  output_tokens BIGINT NOT NULL DEFAULT 0,
  cost DECIMAL(20,6) NOT NULL DEFAULT 0,
  requests BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY(tenant_id,usage_date,model_id,scene)
);

CREATE TABLE ai_request_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  scene VARCHAR(32) NOT NULL,
  model_id BIGINT NOT NULL,
  latency_ms BIGINT NOT NULL,
  input_tokens BIGINT NOT NULL,
  output_tokens BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL,
  error_code VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(id), KEY idx_ai_request_tenant(tenant_id,id)
);

CREATE TABLE ai_assistant (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  code VARCHAR(40) NOT NULL,
  name VARCHAR(100) NOT NULL,
  system_prompt VARCHAR(4000) NOT NULL,
  library_ids JSON NOT NULL,
  role_ids JSON NOT NULL,
  tool_codes JSON NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY(id), UNIQUE KEY uk_ai_assistant_tenant_code(tenant_id,code)
);

CREATE TABLE ai_conversation (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  company_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  assistant_code VARCHAR(40) NOT NULL,
  title VARCHAR(100) NOT NULL,
  busy_token CHAR(36) NULL,
  busy_until DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(id),
  UNIQUE KEY uk_ai_conversation_tenant_id(tenant_id,id),
  KEY idx_ai_conversation_owner(tenant_id,company_id,user_id,updated_at,id)
);

CREATE TABLE ai_message (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  conversation_id BIGINT NOT NULL,
  role VARCHAR(16) NOT NULL,
  content MEDIUMTEXT NOT NULL,
  citations JSON NOT NULL,
  source_receipts JSON NOT NULL,
  tools JSON NOT NULL,
  model_id BIGINT NULL,
  tokens BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(id),
  UNIQUE KEY uk_ai_message_tenant_id(tenant_id,id),
  KEY idx_ai_message_conversation(tenant_id,conversation_id,id),
  CONSTRAINT fk_ai_message_conversation FOREIGN KEY(tenant_id,conversation_id)
    REFERENCES ai_conversation(tenant_id,id) ON DELETE CASCADE,
  CHECK(role IN ('user','assistant'))
);

CREATE TABLE ai_feedback (
  tenant_id BIGINT NOT NULL,
  message_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  rating VARCHAR(8) NOT NULL,
  reason VARCHAR(500) NULL,
  correction VARCHAR(2000) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(tenant_id,message_id,user_id),
  CONSTRAINT fk_ai_feedback_message FOREIGN KEY(tenant_id,message_id)
    REFERENCES ai_message(tenant_id,id) ON DELETE CASCADE,
  CHECK(rating IN ('up','down'))
);

CREATE TABLE ai_uncovered_question (
  tenant_id BIGINT NOT NULL,
  company_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  question_hash CHAR(64) NOT NULL,
  requests INT NOT NULL,
  last_seen DATETIME(3) NOT NULL,
  PRIMARY KEY(tenant_id,company_id,user_id,question_hash)
);

CREATE TABLE eval_run (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  company_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  set_code VARCHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL,
  configuration JSON NOT NULL,
  total INT NOT NULL DEFAULT 0,
  correct_rate DECIMAL(8,4),
  citation_rate DECIMAL(8,4),
  refusal_rate DECIMAL(8,4),
  security_rate DECIMAL(8,4),
  passed BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at DATETIME(3) NULL,
  PRIMARY KEY(id), UNIQUE KEY uk_eval_run_tenant_id(tenant_id,id),
  KEY idx_eval_company(tenant_id,company_id,set_code,id)
);

CREATE TABLE eval_result (
  tenant_id BIGINT NOT NULL,
  run_id BIGINT NOT NULL,
  case_id VARCHAR(64) NOT NULL,
  category VARCHAR(32) NOT NULL,
  correct BOOLEAN NOT NULL,
  citation_correct BOOLEAN NOT NULL,
  refused BOOLEAN NOT NULL,
  expected_refusal BOOLEAN NOT NULL,
  reason VARCHAR(200) NOT NULL,
  PRIMARY KEY(tenant_id,run_id,case_id),
  CONSTRAINT fk_eval_result_run FOREIGN KEY(tenant_id,run_id) REFERENCES eval_run(tenant_id,id) ON DELETE CASCADE
);
