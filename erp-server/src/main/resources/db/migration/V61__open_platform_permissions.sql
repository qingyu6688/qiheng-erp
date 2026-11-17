INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
 (14031,'open_platform:view','查看开放平台','menu',14031),
 (14032,'open_platform:manage','管理应用与 Webhook','action',14032);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT existing.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN tenant t ON t.id=r.tenant_id
JOIN sys_permission p ON p.id IN (14031,14032)
CROSS JOIN (SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) existing
WHERE t.status='active' AND r.status='active'
  AND (r.code='SYSTEM_ADMIN' OR (r.code='WAREHOUSE_ADMIN' AND t.name='演示企业'))
  AND (SELECT COUNT(*) FROM sys_role_permission rp JOIN sys_permission previous ON previous.id=rp.permission_id
       WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id
         AND previous.code IN ('user:update','role:assign_permission','user:assign_scope'))=3;

INSERT IGNORE INTO job_definition(tenant_id,code,name,schedule,next_run_at) VALUES
 (0,'open.webhook','开放平台推送','interval:1000',UTC_TIMESTAMP(3)),
 (0,'open.cleanup','开放平台记录清理','interval:3600000',UTC_TIMESTAMP(3));
