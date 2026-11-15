INSERT INTO sys_permission (id, code, name, perm_type, sort_no)
VALUES (117, 'tenant:login_settings', '登录方式设置', 'action', 117);

-- 初始化工具创建正式管理员角色。本机引导的演示角色也具有企业管理职责。
-- 既有授权上限按角色编码和原有管理权限一起核对，避免扩大普通角色的权限。
INSERT INTO sys_role_permission (id, tenant_id, role_id, permission_id)
SELECT existing.max_id + ROW_NUMBER() OVER (ORDER BY r.tenant_id, r.id), r.tenant_id, r.id, 117
FROM sys_role r
JOIN tenant t ON t.id = r.tenant_id
CROSS JOIN (SELECT COALESCE(MAX(id), 0) AS max_id FROM sys_role_permission) existing
WHERE r.status = 'active'
  AND (r.code = 'SYSTEM_ADMIN' OR (r.code = 'WAREHOUSE_ADMIN' AND t.name = '演示企业'))
  AND (SELECT COUNT(*) FROM sys_role_permission rp
       WHERE rp.tenant_id = r.tenant_id AND rp.role_id = r.id
         AND rp.permission_id IN (107, 109, 110, 115)) = 4;
