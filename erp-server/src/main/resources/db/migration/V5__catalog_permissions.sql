-- 单位与其他基础资料一样使用版本号检查并发修改。
ALTER TABLE unit ADD COLUMN version INT UNSIGNED NOT NULL DEFAULT 0 AFTER status;

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
(200,'unit:view','查看计量单位','action',200),
(201,'unit:create','新增计量单位','action',201),
(202,'unit:update','修改计量单位','action',202),
(203,'product_category:view','查看商品分类','action',203),
(204,'product_category:create','新增商品分类','action',204),
(205,'product_category:update','修改商品分类','action',205),
(206,'product:view','查看商品','action',206),
(207,'product:create','新增商品','action',207),
(208,'product:update','修改商品','action',208),
(209,'sku:view','查看 SKU','action',209),
(210,'sku:create','新增 SKU','action',210),
(211,'sku:update','修改 SKU 与条码单位','action',211);
