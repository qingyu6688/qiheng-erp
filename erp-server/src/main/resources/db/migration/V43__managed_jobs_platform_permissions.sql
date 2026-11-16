CREATE TABLE job_definition (
 tenant_id BIGINT UNSIGNED NOT NULL, code VARCHAR(64) NOT NULL, name VARCHAR(100) NOT NULL, schedule VARCHAR(128) NOT NULL,
 enabled TINYINT NOT NULL DEFAULT 1, last_run_at DATETIME(3) NULL, next_run_at DATETIME(3) NULL,
 last_result VARCHAR(16) NULL, lease_owner VARCHAR(64) NULL, lease_until DATETIME(3) NULL, version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 PRIMARY KEY(tenant_id,code), KEY idx_job_due(enabled,next_run_at), CHECK(enabled IN(0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='按企业维护的固定登记任务，不接收脚本或类名';
CREATE TABLE job_run_log (
 id BIGINT UNSIGNED PRIMARY KEY, tenant_id BIGINT UNSIGNED NOT NULL, job_code VARCHAR(64) NOT NULL,
 trigger_kind VARCHAR(16) NOT NULL, operator_id BIGINT UNSIGNED NULL, node_id VARCHAR(64) NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'pending', lease_owner VARCHAR(64) NULL, lease_until DATETIME(3) NULL,
 started_at DATETIME(3) NULL, finished_at DATETIME(3) NULL, message VARCHAR(200) NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 KEY idx_job_requests(status,created_at,id), KEY idx_job_logs(tenant_id,job_code,id),
 CHECK(trigger_kind IN('schedule','manual')), CHECK(status IN('pending','running','success','failed','skipped'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO job_definition(tenant_id,code,name,schedule) VALUES
 (0,'outbox.poll','业务事件处理','interval:1000'),(0,'import.poll','文件导入处理','interval:1000'),(0,'export.poll','报表导出处理','interval:1000'),
 (0,'reservation.expire','库存预留到期释放','interval:60000'),(0,'files.cleanup','私有文件清理','interval:60000'),
 (0,'commands.cleanup','过期命令清理','interval:3600000'),(0,'notify.dispatch','消息渠道投递','interval:1000'),
 (0,'notify.todo.refresh','消息待办核对','interval:1000'),(0,'platform.cleanup','消息与日志保留期清理','0 0 3 * * *');
INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
 (124,'dict:view','查看数据字典','menu',124),(125,'dict:create','新增字典与字典项','action',125),(126,'dict:update','修改字典与停用字典项','action',126),(127,'dict:delete','删除未引用字典项','action',127),
 (128,'notice:view','查看公告管理','menu',128),(129,'notice:create','新建公告','action',129),(130,'notice:update','编辑公告','action',130),(131,'notice:publish','发布与撤回公告','action',131),
 (132,'notify_template:view','查看消息模板','menu',132),(133,'notify_template:create','新增消息模板','action',133),(134,'notify_template:update','修改消息模板','action',134),(135,'notify_log:view','查看消息发送日志','menu',135),
 (136,'notify_robot:manage','设置企业微信机器人','action',136),(137,'login_log:view','查看登录日志','menu',137),(138,'online_user:view','查看在线用户','menu',138),(139,'online_user:kick','强制会话下线','action',139),
 (140,'sys_config:view','查看系统参数','menu',140),(141,'sys_config:update','维护系统参数','action',141),(142,'job:view','查看定时任务','menu',142),(143,'job:update','配置定时任务','action',143),(144,'job:run','手动执行任务','action',144),
 (145,'api_log:view','查看访问日志','menu',145),(146,'api_error:view','查看异常日志','menu',146),(147,'api_error:update','标记异常已处理','action',147);
INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT existing.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN tenant t ON t.id=r.tenant_id JOIN sys_permission p ON p.id BETWEEN 124 AND 147
CROSS JOIN(SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) existing
WHERE r.status='active' AND (r.code='SYSTEM_ADMIN' OR (r.code='WAREHOUSE_ADMIN' AND t.name='演示企业'))
 AND (SELECT COUNT(*) FROM sys_role_permission rp JOIN sys_permission previous ON previous.id=rp.permission_id WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id AND previous.code IN('user:update','role:assign_permission','user:assign_scope'))=3;
