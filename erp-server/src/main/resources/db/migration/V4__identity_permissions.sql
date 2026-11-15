-- M2 组织与权限目录，用户角色由管理员显式分配。
INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
(100,'company:view','查看公司','action',100),
(101,'company:create','新增公司','action',101),
(102,'company:update','修改公司','action',102),
(103,'company_policy:view','查看公司参数','action',103),
(104,'company_policy:update','修改公司参数','action',104),
(105,'user:view','查看用户','action',105),
(106,'user:create','新增用户','action',106),
(107,'user:update','修改用户','action',107),
(108,'user:reset_password','重置用户密码','action',108),
(109,'user:assign_role','分配用户角色','action',109),
(110,'user:assign_scope','分配用户数据范围','action',110),
(111,'user:view_mobile','查看用户手机号','field',111),
(112,'role:view','查看角色和权限','action',112),
(113,'role:create','新增角色','action',113),
(114,'role:update','修改角色','action',114),
(115,'role:assign_permission','分配角色权限','action',115),
(116,'audit:view','查看审计日志','action',116);
