INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
    (14640,'crm_business:view','查看客户商机','menu',14640),
    (14641,'crm_business:manage','维护商机和阶段','action',14641),
    (14642,'crm_contract:view','查看客户合同','menu',14642),
    (14643,'crm_contract:manage','维护和提交客户合同','action',14643),
    (14644,'crm_contract:approve','审批客户合同','action',14644),
    (14645,'crm_contract:order','合同生成销售订单','action',14645),
    (14646,'crm_receivable:view','查看合同回款','menu',14646),
    (14647,'crm_receivable:manage','维护回款计划与关联','action',14647),
    (14648,'crm_target:view','查看客户业绩目标','menu',14648),
    (14649,'crm_target:manage','维护客户业绩目标','action',14649),
    (14650,'crm_statistics:view','查看客户统计','menu',14650);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT m.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN sys_permission p ON p.id BETWEEN 14640 AND 14650
CROSS JOIN (SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) m
WHERE r.status='active' AND r.code='SYSTEM_ADMIN'
  AND EXISTS (SELECT 1 FROM sys_role_permission rp JOIN sys_permission prior ON prior.id=rp.permission_id
              WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id AND prior.code='role:assign_permission');

INSERT INTO job_definition(tenant_id,code,name,schedule) VALUES (0,'crm.receivable_remind','合同回款计划到期提醒','0 0 9 * * *');
