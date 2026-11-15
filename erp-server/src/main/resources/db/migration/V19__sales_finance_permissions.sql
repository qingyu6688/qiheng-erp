-- 销售闭环资金操作及草稿额度选择。
ALTER TABLE refund_request ADD COLUMN credit_item_ids JSON NULL COMMENT '退款指定贷方未清项ID；空值按账龄选择';

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
  (550,'receipt:view','查看收款单','action',550),
  (551,'receipt:create','新增收款单','action',551),
  (552,'receipt:update','修改收款单','action',552),
  (553,'receipt:submit','提交收款单','action',553),
  (554,'receipt:return','退回收款单','action',554),
  (555,'receipt:confirm','确认收款到账','action',555),
  (556,'sales_return:view','查看销售退货','action',556),
  (557,'sales_return:create','新增销售退货','action',557),
  (558,'sales_return:update','修改销售退货','action',558),
  (559,'sales_return:submit','提交销售退货','action',559),
  (560,'sales_return:void','作废销售退货','action',560),
  (561,'sales_return:post','销售退货过账','action',561),
  (562,'refund_request:view','查看退款申请','action',562),
  (563,'refund_request:create','新增退款申请','action',563),
  (564,'refund_request:update','修改退款申请','action',564),
  (565,'refund_request:submit','提交退款申请','action',565),
  (566,'refund_request:approve','审核退款申请','action',566),
  (567,'refund_request:reject','驳回退款申请','action',567),
  (568,'refund_request:cancel','取消待退申请','action',568),
  (569,'refund_request:register_paid','登记已退款','action',569),
  (570,'settlement:view','查看往来未清项与核销','action',570),
  (571,'settlement:allocate','手工核销','action',571),
  (572,'settlement:revoke','撤销核销','action',572);
