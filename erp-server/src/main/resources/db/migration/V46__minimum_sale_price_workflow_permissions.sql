ALTER TABLE sku ADD COLUMN min_sale_price DECIMAL(24,8) NOT NULL DEFAULT 0 COMMENT '最低含税基本单位售价，0表示不控制', ADD CONSTRAINT chk_sku_min_sale_price CHECK(min_sale_price>=0);
ALTER TABLE company_policy ADD COLUMN below_min_price_policy VARCHAR(16) NOT NULL DEFAULT 'warn', ADD CONSTRAINT chk_min_price_policy CHECK(below_min_price_policy IN('warn','approval','block'));
ALTER TABLE sales_order_line ADD COLUMN below_min_price TINYINT NOT NULL DEFAULT 0, ADD COLUMN min_sale_price_snapshot DECIMAL(24,8) NOT NULL DEFAULT 0;

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
 (148,'workflow_model:view','查看流程模型','menu',148),(149,'workflow_model:create','新建流程模型','action',149),(150,'workflow_model:update','维护流程模型','action',150),
 (151,'workflow_model:publish','发布流程版本','action',151),(152,'workflow_model:disable','停用流程模型','action',152),(153,'workflow_binding:update','维护流程绑定','action',153),
 (154,'workflow_instance:view_all','查看授权范围全部流程','action',154),(155,'workflow_instance:terminate','终止授权范围流程','action',155),(156,'sales_order:price_approve','审批低于最低售价','action',156),
 (157,'workflow_category:update','维护流程分类','action',157);
INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
 SELECT existing.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
 FROM sys_role r JOIN tenant t ON t.id=r.tenant_id JOIN sys_permission p ON p.id BETWEEN 148 AND 157
 CROSS JOIN(SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) existing
 WHERE r.status='active' AND (r.code='SYSTEM_ADMIN' OR (r.code='WAREHOUSE_ADMIN' AND t.name='演示企业'))
 AND (SELECT COUNT(*) FROM sys_role_permission rp JOIN sys_permission previous ON previous.id=rp.permission_id WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id AND previous.code IN('user:update','role:assign_permission','user:assign_scope'))=3;
INSERT INTO job_definition(tenant_id,code,name,schedule,enabled,next_run_at) VALUES(0,'workflow.timeout','流程超时与提醒','interval:5000',1,UTC_TIMESTAMP(3));
INSERT INTO notify_template(id,tenant_id,code,channel,title,content,params) VALUES
 (6,0,'workflow.todo','site','{title}','请进入审批中心查看并处理。',JSON_ARRAY('title')),
 (7,0,'workflow.remind','site','{title}','审批待办已超时，请在当前权限范围内及时处理。',JSON_ARRAY('title')),
 (8,0,'workflow.copy','site','{title}','请到审批中心查看抄送，来源权限会重新校验。',JSON_ARRAY('title')),
 (9,0,'workflow.done','site','{title}','请到我的申请查看处理结果。',JSON_ARRAY('title'));
