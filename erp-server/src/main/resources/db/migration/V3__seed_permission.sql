-- 首批启用仓库权限，其余业务权限随模块迁移追加。
INSERT INTO sys_permission (id, code, name, perm_type, sort_no) VALUES
  (1, 'warehouse:view', '查看仓库', 'action', 1),
  (2, 'warehouse:create', '新增仓库', 'action', 2),
  (3, 'warehouse:update', '修改及启停仓库', 'action', 3);
