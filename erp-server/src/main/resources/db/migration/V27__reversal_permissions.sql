INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
(790,'reversal:view','查看冲销单','action',790),
(791,'reversal:create','申请冲销','action',791),
(792,'reversal:approve','审核冲销单','action',792),
(793,'reversal:post','过账冲销单','action',793);

-- 费用行冲销后可由新的履约单重新确认，同一来源单的同行同动作仍然防重。
ALTER TABLE receivable DROP INDEX uk_receivable_source,
  ADD UNIQUE KEY uk_receivable_source (tenant_id,company_id,source_type,source_id,source_line_id,posting_action);
ALTER TABLE payable DROP INDEX uk_payable_source,
  ADD UNIQUE KEY uk_payable_source (tenant_id,company_id,source_type,source_id,source_line_id,posting_action);
