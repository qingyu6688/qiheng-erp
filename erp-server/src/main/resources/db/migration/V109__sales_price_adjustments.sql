ALTER TABLE price_adjustment MODIFY adjust_no VARCHAR(64) NOT NULL;

ALTER TABLE shipment_line
  ADD price_adjusted_amount DECIMAL(24,2) NOT NULL DEFAULT 0,
  ADD price_adjusted_tax DECIMAL(24,2) NOT NULL DEFAULT 0,
  ADD pricing_return_basis JSON NULL,
  DROP CHECK ck_shipment_line_credit,
  ADD CONSTRAINT ck_shipment_line_credit CHECK(
    credited_amount>=0 AND credited_amount<=COALESCE(receivable_amount,0)+price_adjusted_amount
    AND credited_tax>=0 AND credited_tax<=COALESCE(tax_amount,0)+price_adjusted_tax
    AND COALESCE(receivable_amount,0)+price_adjusted_amount-credited_amount
        >=COALESCE(tax_amount,0)+price_adjusted_tax-credited_tax);

CREATE INDEX idx_price_adjustment_shipment ON price_adjustment_line(tenant_id,company_id,shipment_line_id,adjustment_id);
CREATE INDEX idx_invoice_line_receivable ON invoice_registration_line(tenant_id,company_id,receivable_id,invoice_id);

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
  (18600,'sales_price_adjustment:view','查看销售调价单','menu',18600),
  (18601,'sales_price_adjustment:create','新建销售调价单','action',18601),
  (18602,'sales_price_adjustment:update','修改销售调价单','action',18602),
  (18603,'sales_price_adjustment:submit','提交销售调价单','action',18603),
  (18604,'sales_price_adjustment:approve','审批销售调价单','action',18604),
  (18605,'sales_price_adjustment:post','销售调价单过账','action',18605),
  (18606,'sales_price_adjustment:void','作废销售调价单','action',18606);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT base.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN sys_permission p ON p.id BETWEEN 18600 AND 18606
CROSS JOIN(SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) base
WHERE r.code='SYSTEM_ADMIN' AND EXISTS(SELECT 1 FROM sys_role_permission member JOIN sys_permission prior ON prior.id=member.permission_id
  WHERE member.tenant_id=r.tenant_id AND member.role_id=r.id AND prior.code='role:assign_permission');
