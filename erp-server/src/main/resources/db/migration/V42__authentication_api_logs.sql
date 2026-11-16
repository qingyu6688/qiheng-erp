CREATE TABLE sys_login_log (
 id BIGINT UNSIGNED PRIMARY KEY, tenant_id BIGINT UNSIGNED NOT NULL DEFAULT 0, user_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
 username VARCHAR(64) NOT NULL, method VARCHAR(16) NOT NULL, result VARCHAR(16) NOT NULL,
 ip VARCHAR(64) NOT NULL, user_agent VARCHAR(255) NOT NULL, request_id VARCHAR(64) NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 KEY idx_login_log(tenant_id,created_at,id), KEY idx_login_user(tenant_id,user_id,id),
 CHECK(method IN('password','sms','email','wechat','sso')), CHECK(result IN('success','failed','locked','disabled'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='独立事务认证日志，不保存口令和验证码';
CREATE TABLE sys_api_access_log (
 id BIGINT UNSIGNED PRIMARY KEY, tenant_id BIGINT UNSIGNED NOT NULL DEFAULT 0, user_id BIGINT UNSIGNED NOT NULL DEFAULT 0, company_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
 request_method VARCHAR(16) NOT NULL, route_pattern VARCHAR(200) NOT NULL, status_code INT NOT NULL, duration_ms BIGINT NOT NULL, slow_request TINYINT NOT NULL,
 request_id VARCHAR(64) NOT NULL, trace_id VARCHAR(64) NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_api_access_request(request_id), KEY idx_api_access(tenant_id,created_at,id), KEY idx_api_access_company(tenant_id,company_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仅路由模板及请求元数据，禁止请求体及查询值';
CREATE TABLE sys_api_error_log (
 id BIGINT UNSIGNED PRIMARY KEY, tenant_id BIGINT UNSIGNED NOT NULL DEFAULT 0, user_id BIGINT UNSIGNED NOT NULL DEFAULT 0, company_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
 error_class VARCHAR(200) NOT NULL, error_summary VARCHAR(200) NOT NULL, stack_frames JSON NOT NULL,
 request_id VARCHAR(64) NOT NULL, trace_id VARCHAR(64) NULL, handled TINYINT NOT NULL DEFAULT 0,
 handled_by BIGINT UNSIGNED NULL, handled_at DATETIME(3) NULL, version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_api_error_request(request_id), KEY idx_api_error(tenant_id,handled,id), CHECK(handled IN(0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异常定位元数据，不保存原始异常消息';
