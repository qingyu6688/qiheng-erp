-- 原单退货保留旧状态和金额；无源退货使用独立审批与成本快照。
ALTER TABLE sales_return
  ADD return_mode VARCHAR(16) NOT NULL DEFAULT 'original' COMMENT 'original 原单退货 / sourceless 无原单退货',
  ADD legacy_reference VARCHAR(500) NULL COMMENT '历史单号、票号或凭证说明，不代替实际发票',
  ADD submitted_by BIGINT UNSIGNED NULL,
  ADD approved_by BIGINT UNSIGNED NULL,
  ADD approved_at DATETIME(3) NULL,
  ADD wf_instance_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
  ADD UNIQUE KEY uk_sales_return_mode_scope(tenant_id,company_id,id,return_mode),
  DROP CHECK ck_sales_return_status,
  ADD CONSTRAINT ck_sales_return_mode CHECK(return_mode IN('original','sourceless')),
  ADD CONSTRAINT ck_sales_return_status CHECK(
    (return_mode='original' AND status IN('draft','submitted','posted','reversed','voided'))
    OR (return_mode='sourceless' AND status IN('draft','pending','approved','rejected','posted','reversed','voided'))),
  ADD CONSTRAINT ck_sales_return_approval CHECK(
    (approved_by IS NULL)=(approved_at IS NULL)
    AND (return_mode='original' OR (
      created_by IS NOT NULL
      AND (approved_by IS NULL OR approved_by<>created_by)
      AND (status NOT IN('pending','rejected','approved','posted','reversed')
        OR (submitted_by IS NOT NULL AND submitted_at IS NOT NULL))
      AND (status NOT IN('approved','posted','reversed') OR approved_by IS NOT NULL)
      AND (status NOT IN('draft','pending','rejected') OR approved_by IS NULL)
      AND (status NOT IN('posted','reversed') OR posted_by IS NOT NULL))));

ALTER TABLE sales_return_line DROP FOREIGN KEY fk_sales_return_line_source;

ALTER TABLE sales_return_line
  MODIFY shipment_line_id BIGINT UNSIGNED NULL COMMENT '原单退货必须引用；无源退货必须为空',
  ADD return_mode VARCHAR(16) NOT NULL DEFAULT 'original',
  ADD unit_price DECIMAL(24,8) NULL COMMENT '无源退货的交易单位含税单价',
  ADD tax_rate DECIMAL(7,6) NULL COMMENT '无源退货税率',
  ADD manual_unit_cost DECIMAL(24,8) NULL COMMENT '仅无自动成本来源时填写，按基本单位',
  ADD manual_cost_reason VARCHAR(200) NULL,
  ADD cost_basis JSON NULL COMMENT '提交冻结的成本来源、取价依据及版本',
  ADD CONSTRAINT fk_sales_return_line_source FOREIGN KEY(tenant_id,company_id,shipment_line_id)
    REFERENCES shipment_line(tenant_id,company_id,id),
  ADD CONSTRAINT fk_sales_return_line_mode FOREIGN KEY(tenant_id,company_id,return_id,return_mode)
    REFERENCES sales_return(tenant_id,company_id,id,return_mode),
  ADD CONSTRAINT ck_sales_return_line_mode CHECK(
    (return_mode='original' AND shipment_line_id IS NOT NULL AND unit_price IS NULL AND tax_rate IS NULL
      AND manual_unit_cost IS NULL AND manual_cost_reason IS NULL AND cost_basis IS NULL)
    OR (return_mode='sourceless' AND shipment_line_id IS NULL AND unit_price IS NOT NULL AND unit_price>=0
      AND tax_rate IS NOT NULL AND tax_rate BETWEEN 0 AND 1 AND (unit_cost IS NULL OR unit_cost>=0))),
  ADD CONSTRAINT ck_sales_return_manual_cost CHECK(
    (manual_unit_cost IS NULL AND manual_cost_reason IS NULL)
    OR (manual_unit_cost IS NOT NULL AND manual_unit_cost>=0 AND manual_cost_reason IS NOT NULL
      AND CHAR_LENGTH(TRIM(manual_cost_reason))>0)),
  ADD CONSTRAINT ck_sales_return_cost_basis CHECK(cost_basis IS NULL OR (
    JSON_TYPE(cost_basis)='OBJECT' AND JSON_CONTAINS_PATH(cost_basis,'one','$.source')
    AND JSON_UNQUOTE(JSON_EXTRACT(cost_basis,'$.source')) IN('average','last_in','manual')));

-- 原收款模式仍强制有效来源；无源信用额模式不能夹带一笔伪造原收款。
ALTER TABLE refund_request DROP FOREIGN KEY fk_refund_request_receipt;

ALTER TABLE refund_request
  MODIFY original_receipt_id BIGINT UNSIGNED NULL COMMENT '原收款退款必须引用；无源信用额退款为空',
  ADD refund_mode VARCHAR(24) NOT NULL DEFAULT 'receipt',
  ADD CONSTRAINT fk_refund_request_receipt FOREIGN KEY(tenant_id,company_id,customer_id,original_receipt_id)
    REFERENCES receipt(tenant_id,company_id,partner_id,id),
  ADD CONSTRAINT ck_refund_request_mode CHECK(
    (refund_mode='receipt' AND original_receipt_id IS NOT NULL)
    OR (refund_mode='sourceless_credit' AND original_receipt_id IS NULL AND credit_item_ids IS NOT NULL
      AND JSON_TYPE(credit_item_ids)='ARRAY' AND JSON_LENGTH(credit_item_ids)>0));

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
  (18700,'sales_return:sourceless','办理无原单销售退货','action',18700),
  (18701,'sales_return:approve','审批无原单销售退货','action',18701);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT base.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN sys_permission p ON p.id BETWEEN 18700 AND 18701
CROSS JOIN(SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) base
WHERE r.code='SYSTEM_ADMIN' AND EXISTS(SELECT 1 FROM sys_role_permission member JOIN sys_permission prior
  ON prior.id=member.permission_id WHERE member.tenant_id=r.tenant_id AND member.role_id=r.id
    AND prior.code='role:assign_permission');
