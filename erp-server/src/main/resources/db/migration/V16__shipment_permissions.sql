-- 发货和签收权限，成本权限继续复用 cost:view。
INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
(530,'shipment:view','查看发货单','action',530),
(531,'shipment:create','新建发货单','action',531),
(532,'shipment:update','修改发货草稿','action',532),
(533,'shipment:submit','提交发货单','action',533),
(534,'shipment:void','作废待发货单','action',534),
(535,'shipment:post','发货出库过账','action',535),
(536,'shipment:sign','登记发货签收','action',536),
(537,'shipment:resolve_diff','处理签收差异','action',537);
