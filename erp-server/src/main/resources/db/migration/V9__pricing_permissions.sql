-- 试算使用订单录入权限，税率修改单独授权。
INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
(400,'sales_order:create','创建销售订单','action',400),
(401,'purchase_order:create','创建采购订单','action',401),
(402,'document:change_tax_rate','修改单据税率','field',402);
