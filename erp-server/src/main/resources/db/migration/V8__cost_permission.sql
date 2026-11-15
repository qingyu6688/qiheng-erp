-- 采购参考价与成本、毛利使用独立字段级权限。
INSERT INTO sys_permission(id,code,name,perm_type,sort_no)
VALUES(250,'cost:view','查看采购价格、成本和毛利','field',250);
