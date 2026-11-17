INSERT INTO ledger_account(id,code,name,category,normal_direction,needs_warehouse) VALUES
    (14160,'MISC_STOCK_IN','其他入库对方','income','C',1),
    (14161,'MISC_STOCK_OUT','其他出库对方','expense','D',1);

INSERT INTO gl_standard_preset(standard,kind,code,name,payload,source_ref) VALUES
    ('enterprise','mapping','MISC_STOCK_IN','其他入库对方','{"subjectCode":"6301","auxRules":{},"priority":0}','系统建议映射，按收支项目确认后再启用'),
    ('enterprise','mapping','MISC_STOCK_OUT','其他出库对方','{"subjectCode":"6602","auxRules":{},"priority":0}','系统建议映射，按收支项目确认后再启用'),
    ('small_enterprise','mapping','MISC_STOCK_IN','其他入库对方','{"subjectCode":"5301","auxRules":{},"priority":0}','系统建议映射，按收支项目确认后再启用'),
    ('small_enterprise','mapping','MISC_STOCK_OUT','其他出库对方','{"subjectCode":"5602","auxRules":{},"priority":0}','系统建议映射，按收支项目确认后再启用');

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
    (14160,'stock_misc_doc:view','查看其他出入库','menu',14160),
    (14161,'stock_misc_doc:create','新建其他出入库','action',14161),
    (14162,'stock_misc_doc:update','修改其他出入库','action',14162),
    (14163,'stock_misc_doc:submit','提交其他出入库','action',14163),
    (14164,'stock_misc_doc:approve','审核其他出入库','action',14164),
    (14165,'stock_misc_doc:reject','驳回其他出入库','action',14165),
    (14166,'stock_misc_doc:post','过账其他出入库','action',14166),
    (14167,'stock_misc_doc:void','作废其他出入库','action',14167);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT m.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN tenant t ON t.id=r.tenant_id
JOIN sys_permission p ON p.id BETWEEN 14160 AND 14167
CROSS JOIN (SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) m
WHERE (r.code='SYSTEM_ADMIN' OR (r.code='WAREHOUSE_ADMIN' AND t.name='演示企业'))
  AND EXISTS (SELECT 1 FROM sys_role_permission rp JOIN sys_permission prior ON prior.id=rp.permission_id
              WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id AND prior.code='role:assign_permission');
