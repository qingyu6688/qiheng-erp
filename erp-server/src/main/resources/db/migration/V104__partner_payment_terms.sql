-- 空账期不计逾期。历史往来保持原值，补录由财务另行审核。
ALTER TABLE partner_role
  ADD term_type VARCHAR(16) NULL,
  ADD term_value SMALLINT UNSIGNED NULL,
  ADD CONSTRAINT ck_partner_payment_term CHECK (
    (term_type IS NULL AND term_value IS NULL) OR
    (term_type IS NOT NULL AND term_type = 'days' AND term_value IS NOT NULL AND term_value BETWEEN 0 AND 365) OR
    (term_type IS NOT NULL AND term_type = 'monthly' AND term_value IS NOT NULL AND term_value BETWEEN 1 AND 28));

ALTER TABLE company_policy
  ADD default_term_type VARCHAR(16) NULL,
  ADD default_term_value SMALLINT UNSIGNED NULL,
  ADD CONSTRAINT ck_company_payment_term CHECK (
    (default_term_type IS NULL AND default_term_value IS NULL) OR
    (default_term_type IS NOT NULL AND default_term_type = 'days' AND default_term_value IS NOT NULL AND default_term_value BETWEEN 0 AND 365) OR
    (default_term_type IS NOT NULL AND default_term_type = 'monthly' AND default_term_value IS NOT NULL AND default_term_value BETWEEN 1 AND 28));

ALTER TABLE sales_order
  ADD term_type VARCHAR(16) NULL,
  ADD term_value SMALLINT UNSIGNED NULL,
  ADD term_override TINYINT NOT NULL DEFAULT 0,
  ADD CONSTRAINT ck_sales_payment_term CHECK (
    (term_type IS NULL AND term_value IS NULL) OR
    (term_type IS NOT NULL AND term_type = 'days' AND term_value IS NOT NULL AND term_value BETWEEN 0 AND 365) OR
    (term_type IS NOT NULL AND term_type = 'monthly' AND term_value IS NOT NULL AND term_value BETWEEN 1 AND 28));

ALTER TABLE purchase_order
  ADD term_type VARCHAR(16) NULL,
  ADD term_value SMALLINT UNSIGNED NULL,
  ADD term_override TINYINT NOT NULL DEFAULT 0,
  ADD CONSTRAINT ck_purchase_payment_term CHECK (
    (term_type IS NULL AND term_value IS NULL) OR
    (term_type IS NOT NULL AND term_type = 'days' AND term_value IS NOT NULL AND term_value BETWEEN 0 AND 365) OR
    (term_type IS NOT NULL AND term_type = 'monthly' AND term_value IS NOT NULL AND term_value BETWEEN 1 AND 28));

ALTER TABLE opening_entry ADD due_date DATE NULL;

-- 原订单没有账期设置；按当前角色及公司默认值建立快照，不更新已有往来。
UPDATE sales_order o
LEFT JOIN partner_role r ON r.tenant_id=o.tenant_id AND r.partner_id=o.customer_id AND r.role='customer' AND r.status='active'
LEFT JOIN company_policy p ON p.tenant_id=o.tenant_id AND p.company_id=o.company_id
SET o.term_type=COALESCE(r.term_type,p.default_term_type),
    o.term_value=IF(r.term_type IS NOT NULL,r.term_value,p.default_term_value)
WHERE o.status IN ('approved','change_pending') AND o.is_deleted=0;

UPDATE purchase_order o
LEFT JOIN partner_role r ON r.tenant_id=o.tenant_id AND r.partner_id=o.supplier_id AND r.role='supplier' AND r.status='active'
LEFT JOIN company_policy p ON p.tenant_id=o.tenant_id AND p.company_id=o.company_id
SET o.term_type=COALESCE(r.term_type,p.default_term_type),
    o.term_value=IF(r.term_type IS NOT NULL,r.term_value,p.default_term_value)
WHERE o.status IN ('approved','change_pending') AND o.is_deleted=0;
