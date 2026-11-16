CREATE TABLE sys_dict_type (
    id BIGINT UNSIGNED PRIMARY KEY, tenant_id BIGINT UNSIGNED NOT NULL,
    code VARCHAR(64) NOT NULL, name VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'active', version BIGINT NOT NULL DEFAULT 0,
    created_by BIGINT UNSIGNED NULL, updated_by BIGINT UNSIGNED NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_dict_type(tenant_id,code), CHECK(status IN ('active','disabled'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字典定义，业务流转状态不进入此表';
CREATE TABLE sys_dict_item (
    id BIGINT UNSIGNED PRIMARY KEY, tenant_id BIGINT UNSIGNED NOT NULL, type_code VARCHAR(64) NOT NULL,
    value VARCHAR(64) NOT NULL, label VARCHAR(100) NOT NULL, color VARCHAR(16) NOT NULL DEFAULT 'neutral',
    sort_no INT NOT NULL DEFAULT 0, status VARCHAR(16) NOT NULL DEFAULT 'active', version BIGINT NOT NULL DEFAULT 0,
    created_by BIGINT UNSIGNED NULL, updated_by BIGINT UNSIGNED NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_dict_item(tenant_id,type_code,value), KEY idx_dict_items(tenant_id,type_code,sort_no,id),
    CHECK(color IN ('neutral','primary','success','warning','danger')), CHECK(status IN ('active','disabled'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字典值，历史引用不删除';
CREATE TABLE sys_dict_item_override (
    tenant_id BIGINT UNSIGNED NOT NULL, item_id BIGINT UNSIGNED NOT NULL, disabled TINYINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY(tenant_id,item_id), FOREIGN KEY(item_id) REFERENCES sys_dict_item(id), CHECK(disabled IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='企业对系统预置项的停用覆盖';
CREATE TABLE sys_dict_revision (
    tenant_id BIGINT UNSIGNED PRIMARY KEY, version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO sys_dict_type(id,tenant_id,code,name) VALUES(1,0,'customer_source','客户来源'),(2,0,'contact_type','联系人类型');
INSERT INTO sys_dict_item(id,tenant_id,type_code,value,label,color,sort_no) VALUES
 (1,0,'customer_source','referral','客户推荐','primary',1),(2,0,'customer_source','exhibition','展会','neutral',2),(3,0,'customer_source','online','线上咨询','success',3),
 (4,0,'contact_type','business','业务联系人','primary',1),(5,0,'contact_type','finance','财务联系人','neutral',2);
INSERT INTO sys_dict_revision(tenant_id,version) VALUES(0,1);
CREATE TABLE sys_config (
    tenant_id BIGINT UNSIGNED NOT NULL, config_key VARCHAR(64) NOT NULL,
    config_value MEDIUMTEXT NOT NULL, value_type VARCHAR(16) NOT NULL, is_sensitive TINYINT NOT NULL DEFAULT 0,
    description VARCHAR(200) NOT NULL DEFAULT '', version BIGINT NOT NULL DEFAULT 0,
    created_by BIGINT UNSIGNED NULL, updated_by BIGINT UNSIGNED NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY(tenant_id,config_key), CHECK(value_type IN ('string','number','boolean','json')), CHECK(is_sensitive IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='企业参数，敏感内容只保存密文';
CREATE TABLE sys_area (
    id INT UNSIGNED PRIMARY KEY, parent_id INT UNSIGNED NOT NULL DEFAULT 0, name VARCHAR(100) NOT NULL,
    level TINYINT NOT NULL, data_version VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_area_parent(parent_id,id), CHECK(level BETWEEN 1 AND 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行政区划基础数据，按已公布版本留存';
ALTER TABLE business_partner ADD COLUMN area_id INT UNSIGNED NULL, ADD COLUMN source_code VARCHAR(64) NULL;
ALTER TABLE partner_contact ADD COLUMN area_id INT UNSIGNED NULL;
ALTER TABLE sales_order ADD COLUMN delivery_area_id INT UNSIGNED NULL, ADD COLUMN delivery_address VARCHAR(200) NULL;
