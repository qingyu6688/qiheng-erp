-- 采购行保留已有收货和请购引用，草稿退役不改写历史单号。
ALTER TABLE purchase_order ADD COLUMN is_deleted TINYINT(1) NOT NULL DEFAULT 0,
  ADD COLUMN over_receipt_pct DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '审批时超收容差百分比快照',
  ADD CONSTRAINT ck_purchase_order_deleted CHECK(is_deleted IN(0,1)),
  ADD CONSTRAINT ck_purchase_order_over_receipt CHECK(over_receipt_pct BETWEEN 0 AND 10);
ALTER TABLE purchase_order_line ADD COLUMN is_deleted TINYINT(1) NOT NULL DEFAULT 0,
  ADD CONSTRAINT ck_purchase_order_line_deleted CHECK(is_deleted IN(0,1));
ALTER TABLE purchase_requisition ADD COLUMN is_deleted TINYINT(1) NOT NULL DEFAULT 0,
  ADD CONSTRAINT ck_purchase_requisition_deleted CHECK(is_deleted IN(0,1));

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
(600,'purchase_requisition:view','查看请购单','action',600),
(601,'purchase_requisition:create','创建请购单','action',601),
(602,'purchase_requisition:update','修改请购草稿','action',602),
(603,'purchase_requisition:delete','删除请购草稿','action',603),
(604,'purchase_requisition:submit','提交请购单','action',604),
(605,'purchase_requisition:withdraw','撤回请购单','action',605),
(606,'purchase_requisition:approve','审批请购单','action',606),
(607,'purchase_requisition:reject','驳回请购单','action',607),
(608,'purchase_requisition:close','关闭请购剩余','action',608),
(609,'purchase_requisition:cancel','取消请购单','action',609),
(610,'purchase_order:view','查看采购订单','action',610),
(611,'purchase_order:update','修改采购订单','action',611),
(612,'purchase_order:delete','删除采购草稿','action',612),
(613,'purchase_order:submit','提交采购订单','action',613),
(614,'purchase_order:withdraw','撤回采购订单','action',614),
(615,'purchase_order:approve','审批采购订单','action',615),
(616,'purchase_order:reject','驳回采购订单','action',616),
(617,'purchase_order:revoke_approval','撤销采购审批','action',617),
(618,'purchase_order:close_lines','关闭采购剩余','action',618),
(619,'purchase_order:cancel','取消采购订单','action',619),
(620,'purchase_order:change','变更采购订单','action',620),
(621,'purchase_order:change_approve','批准采购变更','action',621),
(622,'purchase_order:change_reject','驳回采购变更','action',622);
