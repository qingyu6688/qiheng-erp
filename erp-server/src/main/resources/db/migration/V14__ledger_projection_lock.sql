-- 投影串行化独立于业务表外键锁，避免等待尚未提交的业务凭证。
CREATE TABLE ledger_projection_lock (
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (tenant_id,company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='公司账本投影的短事务锁';
