-- M12：客户信用、账龄、催收和坏账。所有可写配置及记录按公司隔离。

CREATE TABLE ar_aging_config(
 id BIGINT UNSIGNED NOT NULL,
 tenant_id BIGINT UNSIGNED NOT NULL,
 company_id BIGINT UNSIGNED NOT NULL,
bounds_json JSON NOT NULL,rates_json JSON NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 PRIMARY KEY(id),
 UNIQUE KEY uk_ar_aging_config_scope(tenant_id,company_id,id),
 CONSTRAINT fk_ar_aging_config_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),
UNIQUE KEY uk_ar_aging_company(tenant_id,company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ar_credit_limit(
 id BIGINT UNSIGNED NOT NULL,
 tenant_id BIGINT UNSIGNED NOT NULL,
 company_id BIGINT UNSIGNED NOT NULL,
partner_id BIGINT UNSIGNED NOT NULL,limit_amount DECIMAL(24,2) NOT NULL,overdue_days_limit INT NOT NULL DEFAULT 0,policy VARCHAR(16) NOT NULL,valid_from DATE NOT NULL,valid_to DATE NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 PRIMARY KEY(id),
 UNIQUE KEY uk_ar_credit_limit_scope(tenant_id,company_id,id),
 CONSTRAINT fk_ar_credit_limit_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),
UNIQUE KEY uk_ar_credit_partner(tenant_id,company_id,partner_id),CONSTRAINT fk_ar_credit_partner FOREIGN KEY(tenant_id,partner_id) REFERENCES business_partner(tenant_id,id),CONSTRAINT ck_ar_credit CHECK(limit_amount>=0 AND overdue_days_limit BETWEEN 0 AND 36500 AND policy IN('warn','approval','block') AND (valid_to IS NULL OR valid_to>=valid_from))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE credit_increase(
 id BIGINT UNSIGNED NOT NULL,
 tenant_id BIGINT UNSIGNED NOT NULL,
 company_id BIGINT UNSIGNED NOT NULL,
partner_id BIGINT UNSIGNED NOT NULL,amount DECIMAL(24,2) NOT NULL,business_date DATE NOT NULL,valid_from DATE NOT NULL,valid_to DATE NOT NULL,reason VARCHAR(500) NOT NULL,status VARCHAR(16) NOT NULL DEFAULT 'draft',wf_instance_id BIGINT UNSIGNED NOT NULL DEFAULT 0,created_by BIGINT UNSIGNED NOT NULL,approved_by BIGINT UNSIGNED NULL,approved_at DATETIME(3) NULL,owner_user_id BIGINT UNSIGNED NOT NULL,owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 PRIMARY KEY(id),
 UNIQUE KEY uk_credit_increase_scope(tenant_id,company_id,id),
 CONSTRAINT fk_credit_increase_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),
KEY idx_credit_increase_effective(tenant_id,company_id,partner_id,status,valid_from,valid_to),CONSTRAINT fk_credit_increase_partner FOREIGN KEY(tenant_id,partner_id) REFERENCES business_partner(tenant_id,id),CONSTRAINT ck_credit_increase CHECK(amount>0 AND valid_to>=valid_from AND status IN('draft','pending','approved','rejected','cancelled') AND (approved_by IS NULL OR approved_by<>created_by))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ar_customer_lock(
 id BIGINT UNSIGNED NOT NULL,
 tenant_id BIGINT UNSIGNED NOT NULL,
 company_id BIGINT UNSIGNED NOT NULL,
partner_id BIGINT UNSIGNED NOT NULL,marker INT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 PRIMARY KEY(id),
 UNIQUE KEY uk_ar_customer_lock_scope(tenant_id,company_id,id),
 CONSTRAINT fk_ar_customer_lock_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),
UNIQUE KEY uk_ar_customer_lock(tenant_id,company_id,partner_id),CONSTRAINT fk_ar_customer_lock_partner FOREIGN KEY(tenant_id,partner_id) REFERENCES business_partner(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE partner_open_item ADD UNIQUE KEY uk_partner_open_scope(tenant_id,company_id,id);

CREATE TABLE ar_collection_plan(
 id BIGINT UNSIGNED NOT NULL,
 tenant_id BIGINT UNSIGNED NOT NULL,
 company_id BIGINT UNSIGNED NOT NULL,
partner_id BIGINT UNSIGNED NOT NULL,open_item_id BIGINT UNSIGNED NOT NULL,owner_id BIGINT UNSIGNED NOT NULL,plan_date DATE NOT NULL,plan_amount DECIMAL(24,2) NOT NULL,status VARCHAR(16) NOT NULL DEFAULT 'open',created_by BIGINT UNSIGNED NOT NULL,last_reminder_key VARCHAR(40) NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 PRIMARY KEY(id),
 UNIQUE KEY uk_ar_collection_plan_scope(tenant_id,company_id,id),
 CONSTRAINT fk_ar_collection_plan_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),
KEY idx_ar_collection_due(status,plan_date,tenant_id,company_id),CONSTRAINT fk_ar_collection_partner FOREIGN KEY(tenant_id,partner_id) REFERENCES business_partner(tenant_id,id),CONSTRAINT fk_ar_collection_item FOREIGN KEY(tenant_id,company_id,open_item_id) REFERENCES partner_open_item(tenant_id,company_id,id),CONSTRAINT fk_ar_collection_owner FOREIGN KEY(tenant_id,owner_id) REFERENCES sys_user(tenant_id,id),CONSTRAINT ck_ar_collection CHECK(plan_amount>0 AND status IN('open','completed','cancelled'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ar_collection_followup(
 id BIGINT UNSIGNED NOT NULL,
 tenant_id BIGINT UNSIGNED NOT NULL,
 company_id BIGINT UNSIGNED NOT NULL,
plan_id BIGINT UNSIGNED NOT NULL,content VARCHAR(1000) NOT NULL,promised_date DATE NULL,promised_amount DECIMAL(24,2) NULL,created_by BIGINT UNSIGNED NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 PRIMARY KEY(id),
 UNIQUE KEY uk_ar_collection_followup_scope(tenant_id,company_id,id),
 CONSTRAINT fk_ar_collection_followup_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),
KEY idx_ar_followup_plan(tenant_id,company_id,plan_id,id),CONSTRAINT fk_ar_followup_plan FOREIGN KEY(tenant_id,company_id,plan_id) REFERENCES ar_collection_plan(tenant_id,company_id,id),CONSTRAINT ck_ar_followup_amount CHECK(promised_amount IS NULL OR promised_amount>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ar_bad_debt(
 id BIGINT UNSIGNED NOT NULL,
 tenant_id BIGINT UNSIGNED NOT NULL,
 company_id BIGINT UNSIGNED NOT NULL,
customer_id BIGINT UNSIGNED NOT NULL,kind VARCHAR(16) NOT NULL,method VARCHAR(16) NOT NULL,open_item_id BIGINT UNSIGNED NULL,amount DECIMAL(24,2) NOT NULL,business_date DATE NOT NULL,estimate_snapshot JSON NULL,reason VARCHAR(500) NOT NULL,status VARCHAR(24) NOT NULL DEFAULT 'draft',created_by BIGINT UNSIGNED NOT NULL,approved_by BIGINT UNSIGNED NULL,journal_id BIGINT UNSIGNED NULL,allocation_id BIGINT UNSIGNED NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 PRIMARY KEY(id),
 UNIQUE KEY uk_ar_bad_debt_scope(tenant_id,company_id,id),
 CONSTRAINT fk_ar_bad_debt_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),
KEY idx_ar_bad_debt(tenant_id,company_id,customer_id,business_date),CONSTRAINT fk_ar_bad_debt_customer FOREIGN KEY(tenant_id,customer_id) REFERENCES business_partner(tenant_id,id),CONSTRAINT fk_ar_bad_debt_item FOREIGN KEY(tenant_id,company_id,open_item_id) REFERENCES partner_open_item(tenant_id,company_id,id),CONSTRAINT fk_ar_bad_debt_journal FOREIGN KEY(tenant_id,company_id,journal_id) REFERENCES journal(tenant_id,company_id,id),CONSTRAINT ck_ar_bad_debt CHECK(amount>0 AND kind IN('provision','write_off') AND method IN('aging','individual','actual') AND status IN('draft','pending','posted','rejected') AND (approved_by IS NULL OR approved_by<>created_by) AND (kind<>'write_off' OR open_item_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE partner_open_item DROP CHECK ck_partner_open_item_type,ADD CONSTRAINT ck_partner_open_item_type_v50 CHECK(item_type IN('receivable','payable','receipt','payment','refund_request','bad_debt'));

ALTER TABLE settlement_allocation DROP CHECK ck_settlement_allocation_type,MODIFY allocation_type VARCHAR(32) NOT NULL,ADD CONSTRAINT ck_settlement_allocation_type_v50 CHECK(allocation_type IN('manual','auto_order','refund','reversal','bad_debt_write_off'));

ALTER TABLE wf_task_ext ADD COLUMN credit_approval TINYINT NOT NULL DEFAULT 0,ADD COLUMN credit_fingerprint CHAR(64) NULL;

INSERT INTO ledger_account(id,code,name,category,normal_direction,needs_partner,needs_cash_account,needs_warehouse) VALUES(19,'BAD_DEBT_EXPENSE','信用减值损失','expense','D',1,0,0),(20,'BAD_DEBT_ALLOWANCE','坏账准备','asset','C',1,0,0),(21,'BAD_DEBT_LOSS','实际坏账损失','expense','D',1,0,0);

INSERT INTO job_definition(tenant_id,code,name,schedule,enabled,next_run_at) VALUES(0,'ar.collection_remind','催收计划到期提醒','interval:60000',1,UTC_TIMESTAMP(3));
