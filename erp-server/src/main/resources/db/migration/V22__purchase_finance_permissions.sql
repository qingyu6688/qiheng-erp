-- 关联预付与采购订单，仅在收货确认应付后核销。
ALTER TABLE payment ADD COLUMN related_order_id BIGINT UNSIGNED NULL,
  ADD KEY idx_payment_related_order(tenant_id,company_id,related_order_id),
  ADD CONSTRAINT fk_payment_related_order FOREIGN KEY(tenant_id,company_id,related_order_id) REFERENCES purchase_order(tenant_id,company_id,id);

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
(670,'payment:view','查看付款单','action',670),
(671,'payment:create','新增付款单','action',671),
(672,'payment:update','修改付款单','action',672),
(673,'payment:submit','提交付款单','action',673),
(674,'payment:return','退回付款草稿','action',674),
(675,'payment:confirm','确认付款','action',675),
(676,'account_transfer:view','查看账户转账','action',676),
(677,'account_transfer:create','新增账户转账','action',677),
(678,'account_transfer:update','修改账户转账','action',678),
(679,'account_transfer:submit','提交账户转账','action',679),
(680,'account_transfer:return','退回转账草稿','action',680),
(681,'account_transfer:confirm','确认账户转账','action',681);
