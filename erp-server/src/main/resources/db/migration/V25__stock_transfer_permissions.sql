-- 分批调入与差异事实各自保留来源、业务日期和期间，重启后可继续处理在途。
CREATE TABLE stock_transfer_event_line (
  id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  transfer_id BIGINT UNSIGNED NOT NULL,
  transfer_line_id BIGINT UNSIGNED NOT NULL,
  event_type VARCHAR(16) NOT NULL,
  base_qty DECIMAL(24,6) NOT NULL,
  cost_amount DECIMAL(24,2) NOT NULL,
  business_date DATE NOT NULL,
  period_id BIGINT UNSIGNED NOT NULL,
  reason VARCHAR(200) NULL,
  created_by BIGINT UNSIGNED NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_stock_transfer_event_header (tenant_id,company_id,transfer_id,id),
  CONSTRAINT fk_stock_transfer_event_head FOREIGN KEY (tenant_id,company_id,transfer_id) REFERENCES stock_transfer(tenant_id,company_id,id),
  CONSTRAINT fk_stock_transfer_event_line FOREIGN KEY (tenant_id,company_id,transfer_line_id) REFERENCES stock_transfer_line(tenant_id,company_id,id),
  CONSTRAINT fk_stock_transfer_event_period FOREIGN KEY (tenant_id,company_id,period_id) REFERENCES accounting_period(tenant_id,company_id,id),
  CONSTRAINT ck_stock_transfer_event_type CHECK(event_type IN ('ship','receive','returned','lost')),
  CONSTRAINT ck_stock_transfer_event_amount CHECK(base_qty>0 AND cost_amount>=0),
  CONSTRAINT ck_stock_transfer_event_reason CHECK(event_type NOT IN ('returned','lost') OR CHAR_LENGTH(TRIM(reason))>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='调拨分批收货及差异事实';
INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
(740,'stock_transfer:view','查看仓库调拨','action',740),
(741,'stock_transfer:create','新增仓库调拨','action',741),
(742,'stock_transfer:update','修改仓库调拨','action',742),
(743,'stock_transfer:submit','提交仓库调拨','action',743),
(744,'stock_transfer:ship','仓库调出','action',744),
(745,'stock_transfer:receive','仓库调入','action',745),
(746,'stock_transfer:resolve_diff','处理调拨差异','action',746),
(747,'stock_transfer:void','作废仓库调拨','action',747);
