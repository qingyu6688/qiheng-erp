ALTER TABLE company_policy
  ADD invoice_match_tolerance DECIMAL(24,2) NOT NULL DEFAULT 1.00,
  ADD CONSTRAINT ck_invoice_match_tolerance CHECK(invoice_match_tolerance>=0);

ALTER TABLE einv_inbound
  ADD source VARCHAR(8) NOT NULL DEFAULT 'einv',
  ADD supplier_id BIGINT UNSIGNED NULL,
  ADD received_date DATE NULL,
  ADD remark VARCHAR(200) NULL,
  ADD match_notice VARCHAR(500) NULL,
  ADD UNIQUE KEY uk_inbound_scope(tenant_id,company_id,id),
  ADD KEY idx_inbound_legacy_payable(tenant_id,company_id,payable_id),
  DROP INDEX uk_einv_inbound_payable,
  ADD KEY idx_inbound_supplier(tenant_id,company_id,supplier_id,id),
  ADD CONSTRAINT fk_inbound_supplier FOREIGN KEY(tenant_id,supplier_id) REFERENCES business_partner(tenant_id,id),
  ADD CONSTRAINT ck_inbound_source CHECK(source IN('einv','manual')),
  ADD CONSTRAINT ck_inbound_manual_supplier CHECK(source<>'manual' OR(supplier_id IS NOT NULL AND received_date IS NOT NULL));

-- 旧同步时间不能被当作精确的核对时间，历史映射另设标记。
UPDATE einv_inbound SET received_date=DATE(CONVERT_TZ(created_at,'+00:00','+08:00')),updated_at=updated_at;
UPDATE einv_inbound i JOIN payable p ON p.tenant_id=i.tenant_id AND p.company_id=i.company_id AND p.id=i.payable_id
  SET i.supplier_id=p.supplier_id,i.updated_at=i.updated_at;

CREATE TABLE inbound_invoice_lock (
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY(tenant_id,company_id),
  CONSTRAINT fk_inbound_invoice_lock_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO inbound_invoice_lock(tenant_id,company_id) SELECT tenant_id,id FROM company;

CREATE TABLE einv_inbound_match (
  id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  invoice_id BIGINT UNSIGNED NOT NULL,
  payable_id BIGINT UNSIGNED NOT NULL,
  invoice_untaxed_amount DECIMAL(24,2) NOT NULL,
  invoice_tax_amount DECIMAL(24,2) NOT NULL,
  payable_untaxed_amount DECIMAL(24,2) NOT NULL,
  payable_tax_amount DECIMAL(24,2) NOT NULL,
  untaxed_difference DECIMAL(25,2) GENERATED ALWAYS AS(invoice_untaxed_amount-payable_untaxed_amount) STORED,
  tax_difference DECIMAL(25,2) GENERATED ALWAYS AS(invoice_tax_amount-payable_tax_amount) STORED,
  difference_amount DECIMAL(25,2) GENERATED ALWAYS AS(invoice_untaxed_amount+invoice_tax_amount-payable_untaxed_amount-payable_tax_amount) STORED,
  matched_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  matched_date DATE NOT NULL,
  matched_by BIGINT UNSIGNED NULL,
  legacy_migrated TINYINT(1) NOT NULL DEFAULT 0,
  released_at DATETIME(3) NULL,
  released_date DATE NULL,
  released_by BIGINT UNSIGNED NULL,
  released_reason VARCHAR(200) NULL,
  active_invoice_id BIGINT UNSIGNED GENERATED ALWAYS AS(IF(released_at IS NULL,invoice_id,NULL)) STORED,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_inbound_match_scope(tenant_id,company_id,id),
  UNIQUE KEY uk_inbound_match_active_pair(tenant_id,company_id,active_invoice_id,payable_id),
  KEY idx_inbound_match_payable(tenant_id,company_id,payable_id,released_at,id),
  KEY idx_inbound_match_history(tenant_id,company_id,invoice_id,matched_date,released_date,id),
  CONSTRAINT fk_inbound_match_invoice FOREIGN KEY(tenant_id,company_id,invoice_id) REFERENCES einv_inbound(tenant_id,company_id,id),
  CONSTRAINT fk_inbound_match_payable FOREIGN KEY(tenant_id,company_id,payable_id) REFERENCES payable(tenant_id,company_id,id),
  CONSTRAINT ck_inbound_match_amount CHECK(invoice_untaxed_amount>=0 AND invoice_tax_amount>=0 AND payable_untaxed_amount>=0
    AND payable_tax_amount>=0 AND invoice_untaxed_amount+invoice_tax_amount>0 AND payable_untaxed_amount+payable_tax_amount>0),
  CONSTRAINT ck_inbound_match_legacy CHECK(legacy_migrated IN(0,1)),
  CONSTRAINT ck_inbound_match_release CHECK((released_at IS NULL AND released_date IS NULL AND released_reason IS NULL AND released_by IS NULL)
    OR(released_at IS NOT NULL AND released_date IS NOT NULL AND released_reason IS NOT NULL AND CHAR_LENGTH(TRIM(released_reason))>0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 所有旧关联均保留；失效或金额变更的关联作为已释放历史，不冒充有效到票。
INSERT INTO einv_inbound_match(id,tenant_id,company_id,invoice_id,payable_id,
  invoice_untaxed_amount,invoice_tax_amount,payable_untaxed_amount,payable_tax_amount,
  matched_at,matched_date,legacy_migrated,released_at,released_date,released_reason)
SELECT old.id,old.tenant_id,old.company_id,old.id,old.payable_id,
  old.amount-old.tax_amount,old.tax_amount,old.payable_untaxed,old.payable_tax,
  old.updated_at,DATE(CONVERT_TZ(old.updated_at,'+00:00','+08:00')),1,
  IF(old.invalid_reason IS NULL,NULL,UTC_TIMESTAMP(3)),IF(old.invalid_reason IS NULL,NULL,DATE(CONVERT_TZ(UTC_TIMESTAMP(3),'+00:00','+08:00'))),old.invalid_reason
FROM (
  SELECT i.*,p.untaxed_amount payable_untaxed,p.tax_amount payable_tax,
    CASE WHEN i.status<>'normal' THEN '旧进项已作废或红冲，迁移后需重新核对'
      WHEN p.kind<>'charge' OR p.posting_action<>'post' OR p.source_type<>'goods_receipt_line' OR COALESCE(r.status,'')<>'posted'
        OR EXISTS(SELECT 1 FROM payable reverse_row WHERE reverse_row.tenant_id=p.tenant_id AND reverse_row.company_id=p.company_id AND reverse_row.reverses_payable_id=p.id)
        THEN '原应付来源已失效，迁移后需重新核对'
      WHEN i.amount<>p.amount OR i.tax_amount<>p.tax_amount THEN '票面或应付金额已变化，迁移后需重新核对'
      WHEN EXISTS(SELECT 1 FROM purchase_return_line l JOIN purchase_return h ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.return_id
        WHERE l.tenant_id=p.tenant_id AND l.company_id=p.company_id AND l.receipt_line_id=p.source_line_id AND h.status='posted')
        OR EXISTS(SELECT 1 FROM price_adjustment_line l JOIN price_adjustment h ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.adjustment_id
          WHERE l.tenant_id=p.tenant_id AND l.company_id=p.company_id AND l.receipt_line_id=p.source_line_id AND h.kind='purchase' AND h.status='posted')
        THEN '原收货存在退货或调价，迁移后需重新核对'
      ELSE NULL END invalid_reason
  FROM einv_inbound i JOIN payable p ON p.tenant_id=i.tenant_id AND p.company_id=i.company_id AND p.id=i.payable_id
  LEFT JOIN goods_receipt r ON r.tenant_id=p.tenant_id AND r.company_id=p.company_id AND r.id=p.source_id AND p.source_type='goods_receipt_line'
) old;

UPDATE einv_inbound i JOIN einv_inbound_match m ON m.tenant_id=i.tenant_id AND m.company_id=i.company_id AND m.invoice_id=i.id
  SET i.match_notice=m.released_reason,i.updated_at=i.updated_at WHERE m.released_at IS NOT NULL;

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
  (18400,'einv:inbound_register','手工登记及更正进项发票','action',18400),
  (18401,'einv:inbound_void','作废手工进项发票','action',18401),
  (18402,'report:uninvoiced_receipts','查看已收货未到票报表','menu',18402),
  (18403,'export:uninvoiced_receipts','导出已收货未到票报表','action',18403);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT base.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN sys_permission p ON p.id BETWEEN 18400 AND 18403
CROSS JOIN(SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) base
WHERE r.code='SYSTEM_ADMIN' AND EXISTS(SELECT 1 FROM sys_role_permission member JOIN sys_permission prior ON prior.id=member.permission_id
  WHERE member.tenant_id=r.tenant_id AND member.role_id=r.id AND prior.code='role:assign_permission');
