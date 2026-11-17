ALTER TABLE sku ADD COLUMN safety_stock DECIMAL(24,6) NOT NULL DEFAULT 0,
  ADD CONSTRAINT ck_sku_safety_stock CHECK (safety_stock>=0);

CREATE TABLE alert_sku_safety_stock (
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  sku_id BIGINT UNSIGNED NOT NULL,
  quantity DECIMAL(24,6) NOT NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY(tenant_id,company_id,sku_id),
  CONSTRAINT fk_alert_safety_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),
  CONSTRAINT fk_alert_safety_sku FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id),
  CONSTRAINT ck_alert_safety_stock CHECK(quantity>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE alert_rule (
  id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  code VARCHAR(40) NOT NULL,
  threshold DECIMAL(24,6) NULL,
  severity VARCHAR(16) NOT NULL,
  schedule VARCHAR(16) NOT NULL,
  receivers JSON NOT NULL,
  channels JSON NOT NULL,
  enabled TINYINT NOT NULL DEFAULT 1,
  next_run_at DATETIME(3) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_alert_rule_scope(tenant_id,company_id,code),
  KEY idx_alert_rule_due(tenant_id,company_id,enabled,next_run_at),
  CONSTRAINT fk_alert_rule_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),
  CONSTRAINT ck_alert_rule CHECK (enabled IN (0,1) AND severity IN ('info','warning','critical')
    AND schedule IN ('realtime','hourly','daily') AND (threshold IS NULL OR threshold>=0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE alert_event (
  id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  rule_code VARCHAR(40) NOT NULL,
  target_type VARCHAR(32) NOT NULL,
  target_id BIGINT UNSIGNED NOT NULL,
  identity_key VARCHAR(96) NOT NULL,
  warehouse_id BIGINT UNSIGNED NULL,
  owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  owner_user_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  level VARCHAR(16) NOT NULL,
  title VARCHAR(100) NOT NULL,
  detail VARCHAR(400) NOT NULL,
  target_url VARCHAR(180) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'open',
  fingerprint CHAR(64) NOT NULL,
  first_seen_at DATETIME(3) NOT NULL,
  last_seen_at DATETIME(3) NOT NULL,
  scan_id BIGINT UNSIGNED NOT NULL,
  occurrence INT UNSIGNED NOT NULL DEFAULT 1,
  handled_by BIGINT UNSIGNED NULL,
  handled_at DATETIME(3) NULL,
  handle_reason VARCHAR(300) NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_alert_event_fingerprint(tenant_id,company_id,fingerprint),
  KEY idx_alert_event_status(tenant_id,company_id,status,id),
  KEY idx_alert_event_scan(tenant_id,company_id,rule_code,status,scan_id),
  CONSTRAINT fk_alert_event_rule FOREIGN KEY(tenant_id,company_id,rule_code) REFERENCES alert_rule(tenant_id,company_id,code),
  CONSTRAINT ck_alert_event CHECK(level IN ('info','warning','critical') AND status IN ('open','acknowledged','resolved','ignored'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
  (17370,'alert:view','查看业务预警','menu',17370),
  (17371,'alert:handle','处理业务预警','action',17371),
  (17372,'alert:configure','配置预警规则和安全库存','action',17372);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT m.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN tenant t ON t.id=r.tenant_id JOIN sys_permission p ON p.id BETWEEN 17370 AND 17372
CROSS JOIN (SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) m
WHERE r.status='active' AND (r.code='SYSTEM_ADMIN' OR (r.code='WAREHOUSE_ADMIN' AND t.name='演示企业' AND p.code='alert:view'))
  AND EXISTS (SELECT 1 FROM sys_role_permission member JOIN sys_permission prior ON prior.id=member.permission_id
              WHERE member.tenant_id=r.tenant_id AND member.role_id=r.id AND prior.code='role:assign_permission');

INSERT INTO job_definition(tenant_id,code,name,schedule,next_run_at) VALUES
  (0,'alert.scan','扫描业务预警','interval:60000',UTC_TIMESTAMP(3));
UPDATE job_definition SET enabled=0,version=version+1 WHERE code='ar.collection_remind' AND enabled=1;

UPDATE notify_message SET state='resolved' WHERE state='open' AND source_type IN ('inv_batch','ar_collection_plan');
INSERT INTO notify_template(id,tenant_id,code,channel,title,content,params) VALUES
  (17370,0,'alert.center','site','{title}','{message}',JSON_ARRAY('title','message')),
  (17371,0,'alert.center','email','{title}','{message}',JSON_ARRAY('title','message'));
