-- 订单退役保留既有预留与下游引用，不改写历史流水。
ALTER TABLE sales_order ADD COLUMN is_deleted TINYINT(1) NOT NULL DEFAULT 0,
  ADD CONSTRAINT ck_sales_order_deleted CHECK(is_deleted IN (0,1));
ALTER TABLE sales_order_line ADD COLUMN is_deleted TINYINT(1) NOT NULL DEFAULT 0,
  ADD CONSTRAINT ck_sales_order_line_deleted CHECK(is_deleted IN (0,1));

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
(500,'sales_order:view','查看销售订单','action',500),
(501,'sales_order:update','修改销售订单','action',501),
(502,'sales_order:delete','删除销售草稿','action',502),
(503,'sales_order:submit','提交销售订单','action',503),
(504,'sales_order:withdraw','撤回销售订单','action',504),
(505,'sales_order:approve','审批销售订单','action',505),
(506,'sales_order:reject','驳回销售订单','action',506),
(507,'sales_order:revoke_approval','撤销销售审批','action',507),
(508,'sales_order:close_lines','关闭订单剩余数量','action',508),
(509,'sales_order:cancel','取消销售订单','action',509),
(510,'sales_order:change','变更销售订单','action',510),
(511,'sales_order:reserve','重新预留销售订单','action',511),
(512,'sales_order:change_approve','批准销售订单变更','action',512),
(513,'sales_order:change_reject','驳回销售订单变更','action',513),
(514,'inventory:view','查看实时库存和待审核需求','action',514);
