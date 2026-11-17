-- 助手使用与管理、审核、评测分别授权，已有普通业务角色无需获得新增权限。
INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
    (185,'ai:use','使用 AI 助手和知识库','action',185),
    (186,'ai_admin:view','查看 AI 管理','menu',186),
    (187,'ai_admin:manage','配置 AI 助手和知识库','action',187),
    (188,'ai_admin:review','审核与发布 Wiki','action',188),
    (189,'ai_admin:evaluate','执行 AI 评测','action',189);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT existing.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r
JOIN tenant t ON t.id=r.tenant_id
JOIN sys_permission p ON p.id BETWEEN 185 AND 189
CROSS JOIN (SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) existing
WHERE t.status='active' AND r.status='active'
  AND (r.code='SYSTEM_ADMIN' OR (r.code='WAREHOUSE_ADMIN' AND t.name='演示企业'))
  AND (SELECT COUNT(*) FROM sys_role_permission rp
       JOIN sys_permission previous ON previous.id=rp.permission_id
       WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id
         AND previous.code IN ('user:update','role:assign_permission','user:assign_scope'))=3;
