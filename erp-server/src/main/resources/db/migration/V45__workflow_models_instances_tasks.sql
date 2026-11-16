CREATE TABLE wf_category (
 id BIGINT UNSIGNED PRIMARY KEY, tenant_id BIGINT UNSIGNED NOT NULL, code VARCHAR(40) NOT NULL, name VARCHAR(100) NOT NULL, sort INT NOT NULL DEFAULT 0,
 version BIGINT NOT NULL DEFAULT 0, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3), UNIQUE KEY uk_wf_category(tenant_id,code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE wf_model (
 id BIGINT UNSIGNED PRIMARY KEY, tenant_id BIGINT UNSIGNED NOT NULL, company_id BIGINT UNSIGNED NOT NULL DEFAULT 0, code VARCHAR(40) NOT NULL,
 name VARCHAR(100) NOT NULL, category_id BIGINT UNSIGNED NULL, business_type VARCHAR(32) NOT NULL, designer VARCHAR(16) NOT NULL DEFAULT 'simple', simple_json JSON NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'draft', published_version INT NOT NULL DEFAULT 0, version BIGINT NOT NULL DEFAULT 0, created_by BIGINT UNSIGNED NOT NULL, updated_by BIGINT UNSIGNED NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_wf_model(tenant_id,company_id,code), KEY idx_wf_model_list(tenant_id,business_type,id), CHECK(status IN('draft','published','disabled')), CHECK(designer='simple')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE wf_model_version (
 id BIGINT UNSIGNED PRIMARY KEY, tenant_id BIGINT UNSIGNED NOT NULL, company_id BIGINT UNSIGNED NOT NULL DEFAULT 0, model_id BIGINT UNSIGNED NOT NULL, version_no INT NOT NULL,
 flowable_definition_id VARCHAR(64) NOT NULL, deployment_id VARCHAR(64) NOT NULL, simple_json JSON NOT NULL, bpmn_xml MEDIUMTEXT NOT NULL, content_hash CHAR(64) NOT NULL,
 published_by BIGINT UNSIGNED NOT NULL, published_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), UNIQUE KEY uk_wf_version(tenant_id,model_id,version_no), UNIQUE KEY uk_wf_definition(flowable_definition_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE wf_business_binding (
 id BIGINT UNSIGNED PRIMARY KEY, tenant_id BIGINT UNSIGNED NOT NULL, company_id BIGINT UNSIGNED NOT NULL DEFAULT 0, business_type VARCHAR(32) NOT NULL,
 model_id BIGINT UNSIGNED NOT NULL, priority INT NOT NULL DEFAULT 0, condition_json JSON NULL, enabled TINYINT NOT NULL DEFAULT 1, version BIGINT NOT NULL DEFAULT 0,
 created_by BIGINT UNSIGNED NOT NULL, updated_by BIGINT UNSIGNED NULL, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_wf_binding(tenant_id,company_id,business_type,priority), CHECK(enabled IN(0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE wf_instance (
 id BIGINT UNSIGNED PRIMARY KEY, tenant_id BIGINT UNSIGNED NOT NULL, company_id BIGINT UNSIGNED NOT NULL, flowable_instance_id VARCHAR(64) NULL,
 model_id BIGINT UNSIGNED NOT NULL, model_version INT NOT NULL, business_type VARCHAR(32) NOT NULL, business_id BIGINT UNSIGNED NOT NULL, business_no VARCHAR(64) NOT NULL,
 title VARCHAR(200) NOT NULL, summary JSON NOT NULL, starter_id BIGINT UNSIGNED NOT NULL, starter_dept_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
 selections JSON NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'running', approval_epoch INT NOT NULL DEFAULT 0, version BIGINT NOT NULL DEFAULT 0,
 active_key VARCHAR(80) GENERATED ALWAYS AS (CASE WHEN status='running' THEN CONCAT(business_type,':',business_id) ELSE NULL END) STORED,
 started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), ended_at DATETIME(3) NULL, updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_wf_active(tenant_id,company_id,active_key), KEY idx_wf_mine(tenant_id,company_id,starter_id,id), KEY idx_wf_business(tenant_id,company_id,business_type,business_id,id),
 CHECK(status IN('running','approved','rejected','cancelled','terminated'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE wf_task_ext (
 id BIGINT UNSIGNED PRIMARY KEY, tenant_id BIGINT UNSIGNED NOT NULL, company_id BIGINT UNSIGNED NOT NULL, instance_id BIGINT UNSIGNED NOT NULL,
 flowable_task_id VARCHAR(64) NOT NULL, node_key VARCHAR(64) NOT NULL, node_name VARCHAR(100) NOT NULL, node_kind VARCHAR(16) NOT NULL DEFAULT 'approver',
 assignee_id BIGINT UNSIGNED NOT NULL, owner_id BIGINT UNSIGNED NULL, parent_task_id BIGINT UNSIGNED NULL, added_by BIGINT UNSIGNED NULL,
 sign_position VARCHAR(16) NULL, approval_epoch INT NOT NULL DEFAULT 0, price_approval TINYINT NOT NULL DEFAULT 0,
 status VARCHAR(20) NOT NULL DEFAULT 'pending', comment VARCHAR(1000) NULL, price_fingerprint CHAR(64) NULL, timeout_action VARCHAR(16) NULL, repeat_hours INT NULL,
 due_at DATETIME(3) NULL, next_remind_at DATETIME(3) NULL, version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), ended_at DATETIME(3) NULL, updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_wf_task(flowable_task_id), KEY idx_wf_todo(tenant_id,company_id,assignee_id,status,id), KEY idx_wf_instance_tasks(tenant_id,instance_id,id), KEY idx_wf_timeout(tenant_id,status,next_remind_at),
 CHECK(status IN('pending','approved','rejected','cancelled','removed','auto_approved','waiting_sign')), CHECK(node_kind IN('approver','handler'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE wf_task_action (
 id BIGINT UNSIGNED PRIMARY KEY, tenant_id BIGINT UNSIGNED NOT NULL, company_id BIGINT UNSIGNED NOT NULL, instance_id BIGINT UNSIGNED NOT NULL, task_id BIGINT UNSIGNED NULL,
 actor_id BIGINT UNSIGNED NOT NULL, action VARCHAR(32) NOT NULL, comment VARCHAR(1000) NULL, details JSON NULL, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 KEY idx_wf_done(tenant_id,company_id,actor_id,id), KEY idx_wf_actions(tenant_id,instance_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE wf_copy (
 id BIGINT UNSIGNED PRIMARY KEY, tenant_id BIGINT UNSIGNED NOT NULL, company_id BIGINT UNSIGNED NOT NULL, instance_id BIGINT UNSIGNED NOT NULL, user_id BIGINT UNSIGNED NOT NULL,
 node_key VARCHAR(64) NOT NULL, approval_epoch INT NOT NULL DEFAULT 0, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), read_at DATETIME(3) NULL,
 UNIQUE KEY uk_wf_copy(tenant_id,instance_id,node_key,user_id,approval_epoch), KEY idx_wf_copy_list(tenant_id,company_id,user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE sales_order ADD COLUMN wf_instance_id BIGINT UNSIGNED NOT NULL DEFAULT 0;
ALTER TABLE purchase_order ADD COLUMN wf_instance_id BIGINT UNSIGNED NOT NULL DEFAULT 0;
ALTER TABLE refund_request ADD COLUMN wf_instance_id BIGINT UNSIGNED NOT NULL DEFAULT 0;
ALTER TABLE document_reversal ADD COLUMN wf_instance_id BIGINT UNSIGNED NOT NULL DEFAULT 0;
ALTER TABLE stock_adjustment ADD COLUMN wf_instance_id BIGINT UNSIGNED NOT NULL DEFAULT 0;
