CREATE TABLE sys_notice (
 id BIGINT UNSIGNED PRIMARY KEY, tenant_id BIGINT UNSIGNED NOT NULL, title VARCHAR(200) NOT NULL, content MEDIUMTEXT NOT NULL,
 level VARCHAR(16) NOT NULL DEFAULT 'normal', status VARCHAR(16) NOT NULL DEFAULT 'draft', publish_at DATETIME(3) NULL, expire_at DATETIME(3) NULL,
 version BIGINT NOT NULL DEFAULT 0, created_by BIGINT UNSIGNED NOT NULL, updated_by BIGINT UNSIGNED NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 KEY idx_notice_list(tenant_id,status,id), CHECK(level IN('normal','important')), CHECK(status IN('draft','published','withdrawn'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='经过白名单清洗的企业公告';
CREATE TABLE sys_notice_target (
 tenant_id BIGINT UNSIGNED NOT NULL, notice_id BIGINT UNSIGNED NOT NULL, target_type VARCHAR(16) NOT NULL, target_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), PRIMARY KEY(tenant_id,notice_id,target_type,target_id),
 CHECK(target_type IN('all','company','department','role','user'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE sys_notice_read (
 tenant_id BIGINT UNSIGNED NOT NULL, notice_id BIGINT UNSIGNED NOT NULL, user_id BIGINT UNSIGNED NOT NULL,
 read_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), PRIMARY KEY(tenant_id,notice_id,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE notify_template (
 id BIGINT UNSIGNED PRIMARY KEY, tenant_id BIGINT UNSIGNED NOT NULL, code VARCHAR(64) NOT NULL, channel VARCHAR(16) NOT NULL,
 title VARCHAR(200) NOT NULL, content TEXT NOT NULL, params JSON NOT NULL, sms_template_id VARCHAR(64) NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'active', version BIGINT NOT NULL DEFAULT 0,
 created_by BIGINT UNSIGNED NULL, updated_by BIGINT UNSIGNED NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_notify_template(tenant_id,code,channel), CHECK(channel IN('site','email','sms')), CHECK(status IN('active','disabled'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE notify_message (
 id BIGINT UNSIGNED PRIMARY KEY, tenant_id BIGINT UNSIGNED NOT NULL, company_id BIGINT UNSIGNED NOT NULL DEFAULT 0, user_id BIGINT UNSIGNED NOT NULL,
 template_code VARCHAR(64) NOT NULL, title VARCHAR(200) NOT NULL, content TEXT NOT NULL, link VARCHAR(255) NULL,
 category VARCHAR(16) NOT NULL, source_type VARCHAR(32) NULL, source_id BIGINT UNSIGNED NULL, source_action VARCHAR(32) NULL,
 source_version BIGINT NOT NULL DEFAULT 0, notice_id BIGINT UNSIGNED NULL, event_key VARCHAR(128) NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'open', read_at DATETIME(3) NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_notify_message(tenant_id,company_id,user_id,event_key),
 KEY idx_notify_unread(tenant_id,user_id,company_id,state,read_at,id), KEY idx_notify_source(tenant_id,company_id,source_type,source_id),
 CHECK(category IN('todo','alert','notice','system')), CHECK(state IN('open','resolved'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收件人专属消息，读取时仍复核来源权限';
CREATE TABLE notify_subscription (
 tenant_id BIGINT UNSIGNED NOT NULL, user_id BIGINT UNSIGNED NOT NULL, category VARCHAR(16) NOT NULL, channel VARCHAR(16) NOT NULL,
 enabled TINYINT NOT NULL DEFAULT 1, version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 PRIMARY KEY(tenant_id,user_id,category,channel), CHECK(category IN('todo','alert','notice','system')), CHECK(channel IN('email','sms')), CHECK(enabled IN(0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE notify_send_log (
 id BIGINT UNSIGNED PRIMARY KEY, tenant_id BIGINT UNSIGNED NOT NULL, company_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
 user_id BIGINT UNSIGNED NOT NULL DEFAULT 0, message_id BIGINT UNSIGNED NULL, channel VARCHAR(16) NOT NULL, template_code VARCHAR(64) NOT NULL,
 receiver VARCHAR(100) NOT NULL, payload_ciphertext MEDIUMTEXT NULL, status VARCHAR(16) NOT NULL DEFAULT 'pending', error VARCHAR(200) NULL,
 attempts INT NOT NULL DEFAULT 0, next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 lease_owner VARCHAR(64) NULL, lease_until DATETIME(3) NULL, finished_at DATETIME(3) NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_notify_delivery(tenant_id,message_id,channel), KEY idx_notify_delivery_poll(status,next_attempt_at,id), KEY idx_notify_delivery_list(tenant_id,id),
 CHECK(channel IN('email','sms','wecom')), CHECK(status IN('pending','sending','sent','failed','skipped'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='脱敏发送日志及加密投递载荷';
INSERT INTO notify_template(id,tenant_id,code,channel,title,content,params) VALUES
 (1,0,'business.todo','site','{title}','请进入单据查看并处理。',JSON_ARRAY('title')),
 (2,0,'file.import.finished','site','导入任务已完成','请到文件任务查看处理结果。',JSON_ARRAY()),
 (3,0,'file.export.finished','site','导出文件已生成','请到文件任务下载；下载时会重新校验权限。',JSON_ARRAY()),
 (4,0,'notice.published','site','{title}','有新的公告，请查看并确认。',JSON_ARRAY('title')),
 (5,0,'system.alert','site','系统提醒','{message}',JSON_ARRAY('message'));
