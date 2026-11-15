-- 收货税身份为过账时快照，后续公司参数变化不改写原入库及退货成本。
ALTER TABLE goods_receipt ADD COLUMN cost_tax_inclusive TINYINT(1) NULL,
  ADD CONSTRAINT ck_goods_receipt_cost_tax CHECK(cost_tax_inclusive IS NULL OR cost_tax_inclusive IN (0,1));
INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
(640,'goods_receipt:view','查看采购收货','action',640),
(641,'goods_receipt:create','新增采购收货','action',641),
(642,'goods_receipt:update','修改采购收货','action',642),
(643,'goods_receipt:submit','提交采购收货','action',643),
(644,'goods_receipt:void','作废采购收货','action',644),
(645,'goods_receipt:post','采购收货过账','action',645),
(646,'purchase_return:view','查看采购退货','action',646),
(647,'purchase_return:create','新增采购退货','action',647),
(648,'purchase_return:update','修改采购退货','action',648),
(649,'purchase_return:submit','提交采购退货','action',649),
(650,'purchase_return:void','作废采购退货','action',650),
(651,'purchase_return:post','采购退货过账','action',651);
