INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
(860,'import:sku','导入 SKU','action',860),
(861,'import:partner','导入往来单位','action',861),
(862,'import:opening_stock','导入库存期初草稿','action',862);

ALTER TABLE import_job
  ADD COLUMN file_fingerprint CHAR(64) NULL,
  ADD COLUMN options_json JSON NULL,
  ADD COLUMN warehouse_snapshot JSON NULL,
  ADD COLUMN requires_cost TINYINT(1) NOT NULL DEFAULT 0,
  ADD COLUMN error_message VARCHAR(500) NULL,
  ADD UNIQUE KEY uk_import_fingerprint(tenant_id,company_id,file_fingerprint),
  ADD KEY idx_import_actor(tenant_id,company_id,created_by,id);

CREATE TABLE import_job_target (
  id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  job_id BIGINT UNSIGNED NOT NULL,
  row_no INT UNSIGNED NOT NULL,
  target_type VARCHAR(32) NOT NULL,
  target_id BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(id),
  UNIQUE KEY uk_import_row(tenant_id,company_id,job_id,row_no),
  CONSTRAINT fk_import_target_job FOREIGN KEY(tenant_id,company_id,job_id)
    REFERENCES import_job(tenant_id,company_id,id)
);
