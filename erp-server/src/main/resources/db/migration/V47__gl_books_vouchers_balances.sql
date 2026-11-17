-- M12财务总账：公司独立账套、复合范围约束与只追加业务来源。

CREATE TABLE gl_standard_preset(standard VARCHAR(24) NOT NULL,kind VARCHAR(32) NOT NULL,code VARCHAR(64) NOT NULL,name VARCHAR(100) NOT NULL,payload JSON NOT NULL,source_ref VARCHAR(1000) NOT NULL,PRIMARY KEY(standard,kind,code)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE gl_account_book (
 id BIGINT UNSIGNED NOT NULL,
 tenant_id BIGINT UNSIGNED NOT NULL,
 company_id BIGINT UNSIGNED NOT NULL,
 standard VARCHAR(24) NOT NULL,report_basis VARCHAR(32) NOT NULL,currency CHAR(3) NOT NULL DEFAULT 'CNY',start_period_id BIGINT UNSIGNED NOT NULL,status VARCHAR(16) NOT NULL DEFAULT 'setup',config_revision BIGINT UNSIGNED NOT NULL DEFAULT 0,confirmed_hash CHAR(64) NULL,confirmed_by BIGINT UNSIGNED NULL,confirmed_at DATETIME(3) NULL,opening_mapping_json JSON NULL,activated_at DATETIME(3) NULL,created_by BIGINT UNSIGNED NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 PRIMARY KEY(id),
 UNIQUE KEY uk_gl_account_book_scope(tenant_id,company_id,id),
 CONSTRAINT fk_gl_account_book_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),UNIQUE KEY uk_gl_book_company(tenant_id,company_id),CONSTRAINT fk_gl_book_start FOREIGN KEY(tenant_id,company_id,start_period_id) REFERENCES accounting_period(tenant_id,company_id,id),CONSTRAINT ck_gl_book_values CHECK(standard IN('enterprise','small_enterprise') AND currency='CNY' AND status IN('setup','active'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE gl_posting_lock (
 id BIGINT UNSIGNED NOT NULL,
 tenant_id BIGINT UNSIGNED NOT NULL,
 company_id BIGINT UNSIGNED NOT NULL,
 marker BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 PRIMARY KEY(id),
 UNIQUE KEY uk_gl_posting_lock_scope(tenant_id,company_id,id),
 CONSTRAINT fk_gl_posting_lock_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),UNIQUE KEY uk_gl_posting_lock(tenant_id,company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE gl_aux_type (
 id BIGINT UNSIGNED NOT NULL,
 tenant_id BIGINT UNSIGNED NOT NULL,
 company_id BIGINT UNSIGNED NOT NULL,
 code VARCHAR(40) NOT NULL,name VARCHAR(100) NOT NULL,source VARCHAR(16) NOT NULL,status VARCHAR(16) NOT NULL DEFAULT 'active',built_in TINYINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 PRIMARY KEY(id),
 UNIQUE KEY uk_gl_aux_type_scope(tenant_id,company_id,id),
 CONSTRAINT fk_gl_aux_type_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),UNIQUE KEY uk_gl_aux_type(tenant_id,company_id,code),CONSTRAINT ck_gl_aux_source CHECK(source IN('customer','supplier','dept','employee','project','sku','custom'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE gl_aux_item (
 id BIGINT UNSIGNED NOT NULL,
 tenant_id BIGINT UNSIGNED NOT NULL,
 company_id BIGINT UNSIGNED NOT NULL,
 aux_type_id BIGINT UNSIGNED NOT NULL,code VARCHAR(40) NOT NULL,name VARCHAR(100) NOT NULL,status VARCHAR(16) NOT NULL DEFAULT 'active',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 PRIMARY KEY(id),
 UNIQUE KEY uk_gl_aux_item_scope(tenant_id,company_id,id),
 CONSTRAINT fk_gl_aux_item_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),UNIQUE KEY uk_gl_aux_item(tenant_id,company_id,aux_type_id,code),CONSTRAINT fk_gl_aux_item_type FOREIGN KEY(tenant_id,company_id,aux_type_id) REFERENCES gl_aux_type(tenant_id,company_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE gl_subject (
 id BIGINT UNSIGNED NOT NULL,
 tenant_id BIGINT UNSIGNED NOT NULL,
 company_id BIGINT UNSIGNED NOT NULL,
 code VARCHAR(32) NOT NULL,name VARCHAR(100) NOT NULL,parent_id BIGINT UNSIGNED NULL,level INT NOT NULL DEFAULT 1,category VARCHAR(24) NOT NULL,profit_role VARCHAR(16) NOT NULL DEFAULT 'none',direction CHAR(1) NOT NULL,is_cash TINYINT NOT NULL DEFAULT 0,is_bank TINYINT NOT NULL DEFAULT 0,is_leaf TINYINT NOT NULL DEFAULT 1,aux_types JSON NOT NULL,custom_aux_type_id BIGINT UNSIGNED NULL,quantity_unit VARCHAR(20) NOT NULL DEFAULT '',status VARCHAR(16) NOT NULL DEFAULT 'active',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 PRIMARY KEY(id),
 UNIQUE KEY uk_gl_subject_scope(tenant_id,company_id,id),
 CONSTRAINT fk_gl_subject_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),UNIQUE KEY uk_gl_subject_code(tenant_id,company_id,code),KEY idx_gl_subject_parent(tenant_id,company_id,parent_id),CONSTRAINT fk_gl_subject_parent FOREIGN KEY(tenant_id,company_id,parent_id) REFERENCES gl_subject(tenant_id,company_id,id),CONSTRAINT fk_gl_subject_custom FOREIGN KEY(tenant_id,company_id,custom_aux_type_id) REFERENCES gl_aux_type(tenant_id,company_id,id),CONSTRAINT ck_gl_subject CHECK(category IN('asset','liability','equity','cost','income_expense','common') AND direction IN('D','C') AND level BETWEEN 1 AND 10 AND NOT(is_cash=1 AND is_bank=1) AND status IN('active','disabled'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE gl_cash_flow_item (
 id BIGINT UNSIGNED NOT NULL,
 tenant_id BIGINT UNSIGNED NOT NULL,
 company_id BIGINT UNSIGNED NOT NULL,
 code VARCHAR(40) NOT NULL,name VARCHAR(100) NOT NULL,direction VARCHAR(8) NOT NULL,category VARCHAR(16) NOT NULL,status VARCHAR(16) NOT NULL DEFAULT 'active',sort_no INT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 PRIMARY KEY(id),
 UNIQUE KEY uk_gl_cash_flow_item_scope(tenant_id,company_id,id),
 CONSTRAINT fk_gl_cash_flow_item_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),UNIQUE KEY uk_gl_cash_code(tenant_id,company_id,code),CONSTRAINT ck_gl_cash_item CHECK(direction IN('in','out','neutral') AND category IN('operating','investing','financing','internal'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE gl_business_mapping (
 id BIGINT UNSIGNED NOT NULL,
 tenant_id BIGINT UNSIGNED NOT NULL,
 company_id BIGINT UNSIGNED NOT NULL,
 ledger_account_code VARCHAR(32) NOT NULL,condition_json JSON NULL,subject_id BIGINT UNSIGNED NOT NULL,aux_rules JSON NOT NULL,cash_flow_item_id BIGINT UNSIGNED NULL,priority INT NOT NULL DEFAULT 0,enabled TINYINT NOT NULL DEFAULT 1,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 PRIMARY KEY(id),
 UNIQUE KEY uk_gl_business_mapping_scope(tenant_id,company_id,id),
 CONSTRAINT fk_gl_business_mapping_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),UNIQUE KEY uk_gl_mapping_priority(tenant_id,company_id,ledger_account_code,priority),CONSTRAINT fk_gl_mapping_subject FOREIGN KEY(tenant_id,company_id,subject_id) REFERENCES gl_subject(tenant_id,company_id,id),CONSTRAINT fk_gl_mapping_cash FOREIGN KEY(tenant_id,company_id,cash_flow_item_id) REFERENCES gl_cash_flow_item(tenant_id,company_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE gl_voucher_word (
 id BIGINT UNSIGNED NOT NULL,
 tenant_id BIGINT UNSIGNED NOT NULL,
 company_id BIGINT UNSIGNED NOT NULL,
 code VARCHAR(8) NOT NULL,print_title VARCHAR(100) NOT NULL,is_default TINYINT NOT NULL DEFAULT 0,default_key TINYINT GENERATED ALWAYS AS(IF(is_default=1,1,NULL)) STORED,status VARCHAR(16) NOT NULL DEFAULT 'active',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 PRIMARY KEY(id),
 UNIQUE KEY uk_gl_voucher_word_scope(tenant_id,company_id,id),
 CONSTRAINT fk_gl_voucher_word_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),UNIQUE KEY uk_gl_word_code(tenant_id,company_id,code),UNIQUE KEY uk_gl_word_default(tenant_id,company_id,default_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE gl_digest (
 id BIGINT UNSIGNED NOT NULL,
 tenant_id BIGINT UNSIGNED NOT NULL,
 company_id BIGINT UNSIGNED NOT NULL,
 content VARCHAR(200) NOT NULL,status VARCHAR(16) NOT NULL DEFAULT 'active',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 PRIMARY KEY(id),
 UNIQUE KEY uk_gl_digest_scope(tenant_id,company_id,id),
 CONSTRAINT fk_gl_digest_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),UNIQUE KEY uk_gl_digest(tenant_id,company_id,content)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE gl_voucher_template (
 id BIGINT UNSIGNED NOT NULL,
 tenant_id BIGINT UNSIGNED NOT NULL,
 company_id BIGINT UNSIGNED NOT NULL,
 code VARCHAR(40) NOT NULL,name VARCHAR(100) NOT NULL,word_id BIGINT UNSIGNED NOT NULL,lines_json JSON NOT NULL,status VARCHAR(16) NOT NULL DEFAULT 'active',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 PRIMARY KEY(id),
 UNIQUE KEY uk_gl_voucher_template_scope(tenant_id,company_id,id),
 CONSTRAINT fk_gl_voucher_template_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),UNIQUE KEY uk_gl_voucher_template(tenant_id,company_id,code),CONSTRAINT fk_gl_template_word FOREIGN KEY(tenant_id,company_id,word_id) REFERENCES gl_voucher_word(tenant_id,company_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE gl_voucher_seq (
 id BIGINT UNSIGNED NOT NULL,
 tenant_id BIGINT UNSIGNED NOT NULL,
 company_id BIGINT UNSIGNED NOT NULL,
 period_id BIGINT UNSIGNED NOT NULL,word_id BIGINT UNSIGNED NOT NULL,next_no BIGINT UNSIGNED NOT NULL DEFAULT 1,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 PRIMARY KEY(id),
 UNIQUE KEY uk_gl_voucher_seq_scope(tenant_id,company_id,id),
 CONSTRAINT fk_gl_voucher_seq_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),UNIQUE KEY uk_gl_seq(tenant_id,company_id,period_id,word_id),CONSTRAINT fk_gl_seq_period FOREIGN KEY(tenant_id,company_id,period_id) REFERENCES accounting_period(tenant_id,company_id,id),CONSTRAINT fk_gl_seq_word FOREIGN KEY(tenant_id,company_id,word_id) REFERENCES gl_voucher_word(tenant_id,company_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE gl_voucher (
 id BIGINT UNSIGNED NOT NULL,
 tenant_id BIGINT UNSIGNED NOT NULL,
 company_id BIGINT UNSIGNED NOT NULL,
 period_id BIGINT UNSIGNED NOT NULL,word_id BIGINT UNSIGNED NOT NULL,number BIGINT UNSIGNED NOT NULL,voucher_date DATE NOT NULL,source VARCHAR(16) NOT NULL,source_type VARCHAR(40) NULL,source_id BIGINT UNSIGNED NULL,source_no VARCHAR(100) NULL,journal_id BIGINT UNSIGNED NULL,attachment_count INT NOT NULL DEFAULT 0,debit_total DECIMAL(24,2) NOT NULL,credit_total DECIMAL(24,2) NOT NULL,status VARCHAR(16) NOT NULL DEFAULT 'draft',maker_id BIGINT UNSIGNED NOT NULL,approver_id BIGINT UNSIGNED NULL,poster_id BIGINT UNSIGNED NULL,reversal_of_id BIGINT UNSIGNED NULL,is_deleted TINYINT NOT NULL DEFAULT 0,active_reversal_key BIGINT UNSIGNED GENERATED ALWAYS AS(IF(is_deleted=0,reversal_of_id,NULL)) STORED,posted_at DATETIME(3) NULL,remark VARCHAR(500) NULL,closing_cycle_id BIGINT UNSIGNED NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 PRIMARY KEY(id),
 UNIQUE KEY uk_gl_voucher_scope(tenant_id,company_id,id),
 CONSTRAINT fk_gl_voucher_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),UNIQUE KEY uk_gl_voucher_number(tenant_id,company_id,period_id,word_id,number),UNIQUE KEY uk_gl_voucher_journal(tenant_id,company_id,journal_id),UNIQUE KEY uk_gl_reversal(tenant_id,company_id,active_reversal_key),KEY idx_gl_voucher_query(tenant_id,company_id,period_id,status,id),KEY idx_gl_voucher_date(tenant_id,company_id,voucher_date,id),CONSTRAINT fk_gl_voucher_period FOREIGN KEY(tenant_id,company_id,period_id) REFERENCES accounting_period(tenant_id,company_id,id),CONSTRAINT fk_gl_voucher_word FOREIGN KEY(tenant_id,company_id,word_id) REFERENCES gl_voucher_word(tenant_id,company_id,id),CONSTRAINT fk_gl_voucher_reverse FOREIGN KEY(tenant_id,company_id,reversal_of_id) REFERENCES gl_voucher(tenant_id,company_id,id),CONSTRAINT fk_gl_voucher_journal FOREIGN KEY(tenant_id,company_id,journal_id) REFERENCES journal(tenant_id,company_id,id),CONSTRAINT ck_gl_voucher CHECK(source IN('manual','business','closing','opening') AND status IN('draft','pending','approved','posted','reversed') AND number>0 AND attachment_count BETWEEN 0 AND 9999 AND (status NOT IN('approved','posted','reversed') OR debit_total=credit_total) AND (source='manual' OR is_deleted=0) AND (approver_id IS NULL OR approver_id<>maker_id OR source<>'manual'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE gl_voucher_line (
 id BIGINT UNSIGNED NOT NULL,
 tenant_id BIGINT UNSIGNED NOT NULL,
 company_id BIGINT UNSIGNED NOT NULL,
 voucher_id BIGINT UNSIGNED NOT NULL,line_no INT NOT NULL,digest VARCHAR(200) NOT NULL,subject_id BIGINT UNSIGNED NOT NULL,direction CHAR(1) NOT NULL,amount DECIMAL(24,2) NOT NULL,quantity DECIMAL(24,6) NOT NULL DEFAULT 0,unit_price DECIMAL(24,8) NULL,aux_customer_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
 aux_supplier_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
 aux_dept_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
 aux_employee_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
 aux_project_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
 aux_sku_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
 aux_custom_id BIGINT UNSIGNED NOT NULL DEFAULT 0,cash_flow_item_id BIGINT UNSIGNED NULL,journal_line_id BIGINT UNSIGNED NULL,ledger_account_code VARCHAR(32) NULL,mapping_snapshot JSON NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 PRIMARY KEY(id),
 UNIQUE KEY uk_gl_voucher_line_scope(tenant_id,company_id,id),
 CONSTRAINT fk_gl_voucher_line_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),UNIQUE KEY uk_gl_line_no(tenant_id,company_id,voucher_id,line_no),KEY idx_gl_line_subject(tenant_id,company_id,subject_id,voucher_id),CONSTRAINT fk_gl_line_voucher FOREIGN KEY(tenant_id,company_id,voucher_id) REFERENCES gl_voucher(tenant_id,company_id,id),CONSTRAINT fk_gl_line_subject FOREIGN KEY(tenant_id,company_id,subject_id) REFERENCES gl_subject(tenant_id,company_id,id),CONSTRAINT fk_gl_line_cash FOREIGN KEY(tenant_id,company_id,cash_flow_item_id) REFERENCES gl_cash_flow_item(tenant_id,company_id,id),CONSTRAINT ck_gl_line CHECK(direction IN('D','C') AND amount<>0 AND line_no BETWEEN 1 AND 1000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE gl_opening_balance (
 id BIGINT UNSIGNED NOT NULL,
 tenant_id BIGINT UNSIGNED NOT NULL,
 company_id BIGINT UNSIGNED NOT NULL,
 subject_id BIGINT UNSIGNED NOT NULL,aux_customer_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
 aux_supplier_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
 aux_dept_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
 aux_employee_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
 aux_project_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
 aux_sku_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
 aux_custom_id BIGINT UNSIGNED NOT NULL DEFAULT 0,opening_signed DECIMAL(24,2) NOT NULL DEFAULT 0,year_debit DECIMAL(24,2) NOT NULL DEFAULT 0,year_credit DECIMAL(24,2) NOT NULL DEFAULT 0,annual_opening_signed DECIMAL(24,2) NOT NULL DEFAULT 0,opening_quantity DECIMAL(24,6) NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 PRIMARY KEY(id),
 UNIQUE KEY uk_gl_opening_balance_scope(tenant_id,company_id,id),
 CONSTRAINT fk_gl_opening_balance_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),UNIQUE KEY uk_gl_opening_dim(tenant_id,company_id,subject_id,aux_customer_id,aux_supplier_id,aux_dept_id,aux_employee_id,aux_project_id,aux_sku_id,aux_custom_id),CONSTRAINT fk_gl_opening_subject FOREIGN KEY(tenant_id,company_id,subject_id) REFERENCES gl_subject(tenant_id,company_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE gl_balance (
 id BIGINT UNSIGNED NOT NULL,
 tenant_id BIGINT UNSIGNED NOT NULL,
 company_id BIGINT UNSIGNED NOT NULL,
 period_id BIGINT UNSIGNED NOT NULL,subject_id BIGINT UNSIGNED NOT NULL,aux_customer_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
 aux_supplier_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
 aux_dept_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
 aux_employee_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
 aux_project_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
 aux_sku_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
 aux_custom_id BIGINT UNSIGNED NOT NULL DEFAULT 0,opening_signed DECIMAL(24,2) NOT NULL DEFAULT 0,debit DECIMAL(24,2) NOT NULL DEFAULT 0,credit DECIMAL(24,2) NOT NULL DEFAULT 0,opening_quantity DECIMAL(24,6) NOT NULL DEFAULT 0,quantity_debit DECIMAL(24,6) NOT NULL DEFAULT 0,quantity_credit DECIMAL(24,6) NOT NULL DEFAULT 0,opening_debit DECIMAL(24,2) GENERATED ALWAYS AS(GREATEST(opening_signed,0)) STORED,opening_credit DECIMAL(24,2) GENERATED ALWAYS AS(GREATEST(-opening_signed,0)) STORED,closing_debit DECIMAL(24,2) GENERATED ALWAYS AS(GREATEST(opening_signed+debit-credit,0)) STORED,closing_credit DECIMAL(24,2) GENERATED ALWAYS AS(GREATEST(-opening_signed-debit+credit,0)) STORED,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 PRIMARY KEY(id),
 UNIQUE KEY uk_gl_balance_scope(tenant_id,company_id,id),
 CONSTRAINT fk_gl_balance_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),UNIQUE KEY uk_gl_balance_dim(tenant_id,company_id,period_id,subject_id,aux_customer_id,aux_supplier_id,aux_dept_id,aux_employee_id,aux_project_id,aux_sku_id,aux_custom_id),CONSTRAINT fk_gl_balance_period FOREIGN KEY(tenant_id,company_id,period_id) REFERENCES accounting_period(tenant_id,company_id,id),CONSTRAINT fk_gl_balance_subject FOREIGN KEY(tenant_id,company_id,subject_id) REFERENCES gl_subject(tenant_id,company_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE gl_closing_scheme (
 id BIGINT UNSIGNED NOT NULL,
 tenant_id BIGINT UNSIGNED NOT NULL,
 company_id BIGINT UNSIGNED NOT NULL,
 code VARCHAR(40) NOT NULL,name VARCHAR(100) NOT NULL,sort_no INT NOT NULL DEFAULT 0,source_codes JSON NOT NULL,target_subject_id BIGINT UNSIGNED NOT NULL,enabled TINYINT NOT NULL DEFAULT 1,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 PRIMARY KEY(id),
 UNIQUE KEY uk_gl_closing_scheme_scope(tenant_id,company_id,id),
 CONSTRAINT fk_gl_closing_scheme_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),UNIQUE KEY uk_gl_scheme(tenant_id,company_id,code),CONSTRAINT fk_gl_scheme_target FOREIGN KEY(tenant_id,company_id,target_subject_id) REFERENCES gl_subject(tenant_id,company_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE gl_report_template (
 id BIGINT UNSIGNED NOT NULL,
 tenant_id BIGINT UNSIGNED NOT NULL,
 company_id BIGINT UNSIGNED NOT NULL,
 report_type VARCHAR(24) NOT NULL,name VARCHAR(100) NOT NULL,rows_json JSON NOT NULL,checks_json JSON NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 PRIMARY KEY(id),
 UNIQUE KEY uk_gl_report_template_scope(tenant_id,company_id,id),
 CONSTRAINT fk_gl_report_template_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),UNIQUE KEY uk_gl_report_type(tenant_id,company_id,report_type),CONSTRAINT ck_gl_report_type CHECK(report_type IN('balance_sheet','income_statement','cash_flow'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE gl_period_close (
 id BIGINT UNSIGNED NOT NULL,
 tenant_id BIGINT UNSIGNED NOT NULL,
 company_id BIGINT UNSIGNED NOT NULL,
 period_id BIGINT UNSIGNED NOT NULL,cycle INT NOT NULL,status VARCHAR(16) NOT NULL,config_hash CHAR(64) NOT NULL,reports_snapshot JSON NOT NULL,closed_by BIGINT UNSIGNED NOT NULL,closed_at DATETIME(3) NOT NULL,reopened_by BIGINT UNSIGNED NULL,reopened_at DATETIME(3) NULL,reopen_reason VARCHAR(200) NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 PRIMARY KEY(id),
 UNIQUE KEY uk_gl_period_close_scope(tenant_id,company_id,id),
 CONSTRAINT fk_gl_period_close_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),UNIQUE KEY uk_gl_close_cycle(tenant_id,company_id,period_id,cycle),CONSTRAINT fk_gl_close_period FOREIGN KEY(tenant_id,company_id,period_id) REFERENCES accounting_period(tenant_id,company_id,id),CONSTRAINT ck_gl_close_state CHECK(status IN('prepared','closed','reopened'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE gl_sync_error (
 id BIGINT UNSIGNED NOT NULL,
 tenant_id BIGINT UNSIGNED NOT NULL,
 company_id BIGINT UNSIGNED NOT NULL,
 journal_id BIGINT UNSIGNED NOT NULL,error_code VARCHAR(64) NOT NULL,error_message VARCHAR(500) NOT NULL,status VARCHAR(16) NOT NULL DEFAULT 'pending',attempts INT NOT NULL DEFAULT 1,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 PRIMARY KEY(id),
 UNIQUE KEY uk_gl_sync_error_scope(tenant_id,company_id,id),
 CONSTRAINT fk_gl_sync_error_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),UNIQUE KEY uk_gl_sync_journal(tenant_id,company_id,journal_id),CONSTRAINT fk_gl_sync_journal FOREIGN KEY(tenant_id,company_id,journal_id) REFERENCES journal(tenant_id,company_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE gl_export_format (
 id BIGINT UNSIGNED NOT NULL,
 tenant_id BIGINT UNSIGNED NOT NULL,
 company_id BIGINT UNSIGNED NOT NULL,
 code VARCHAR(40) NOT NULL,name VARCHAR(100) NOT NULL,file_type VARCHAR(8) NOT NULL,columns_json JSON NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 PRIMARY KEY(id),
 UNIQUE KEY uk_gl_export_format_scope(tenant_id,company_id,id),
 CONSTRAINT fk_gl_export_format_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),UNIQUE KEY uk_gl_export_code(tenant_id,company_id,code),CONSTRAINT ck_gl_export_file CHECK(file_type IN('csv','xlsx'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
