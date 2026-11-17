INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
    (14170,'einv:view','查看数电发票','menu',14170),
    (14171,'einv:create','创建数电发票申请','action',14171),
    (14172,'einv:submit','确认并提交数电发票','action',14172),
    (14173,'einv:red_issue','提交红字发票申请','action',14173),
    (14174,'einv:download','下载数电发票版式文件','action',14174),
    (14175,'einv:inbound','维护进项发票台账','action',14175),
    (14176,'einv:manage','配置数电发票服务商','action',14176);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT m.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN tenant t ON t.id=r.tenant_id
JOIN sys_permission p ON p.id BETWEEN 14170 AND 14176
CROSS JOIN (SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) m
WHERE r.status='active' AND (r.code='SYSTEM_ADMIN' OR (r.code='WAREHOUSE_ADMIN' AND t.name='演示企业'))
  AND EXISTS (SELECT 1 FROM sys_role_permission rp JOIN sys_permission prior ON prior.id=rp.permission_id
              WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id AND prior.code='role:assign_permission');

INSERT INTO job_definition(tenant_id,code,name,schedule) VALUES
    (0,'einv.issue','数电发票开具及结果补偿','interval:10000'),
    (0,'einv.inbound','同步数电进项发票','0 15 5 * * *');
