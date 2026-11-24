ALTER TABLE price_list_target DROP CHECK ck_price_list_target,
  ADD CONSTRAINT ck_price_list_target CHECK((target_type='all' AND target_value='')
    OR (target_type IN('customer','customer_level','supplier') AND target_value<>''));

ALTER TABLE company_policy
  ADD purchase_over_price_policy VARCHAR(16) NOT NULL DEFAULT 'warn' COMMENT '采购高于协议价：warn 提示 / approval 强制审批',
  ADD CONSTRAINT ck_company_purchase_price_policy CHECK(purchase_over_price_policy IN('warn','approval'));

ALTER TABLE purchase_order
  ADD above_price_list TINYINT(1) NOT NULL DEFAULT 0,
  ADD purchase_price_approval_required TINYINT(1) NOT NULL DEFAULT 0,
  ADD purchase_price_hash CHAR(64) NULL;

ALTER TABLE purchase_order_line
  ADD price_source_snapshot JSON NULL COMMENT '实际及建议来源、协议版本、历史行、税口径和超价快照';

CREATE INDEX idx_purchase_order_supplier_price ON purchase_order(tenant_id,company_id,supplier_id,business_date,status,id);

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
  (18900,'purchase_price_list:view','查看采购价格表','menu',18900),
  (18901,'purchase_price_list:manage','维护采购价格表','action',18901);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT base.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN sys_permission p ON p.id BETWEEN 18900 AND 18901
CROSS JOIN(SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) base
WHERE r.code='SYSTEM_ADMIN' AND EXISTS(SELECT 1 FROM sys_role_permission member JOIN sys_permission prior
  ON prior.id=member.permission_id WHERE member.tenant_id=r.tenant_id AND member.role_id=r.id AND prior.code='role:assign_permission');
