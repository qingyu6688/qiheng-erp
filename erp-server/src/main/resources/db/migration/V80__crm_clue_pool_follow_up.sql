CREATE TABLE crm_clue (
    id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    name VARCHAR(100) NOT NULL,
    contact_name VARCHAR(50) NOT NULL DEFAULT '',
    mobile_cipher VARCHAR(240) NULL,
    mobile_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    mobile_last4 CHAR(4) CHARACTER SET ascii COLLATE ascii_bin NULL,
    email VARCHAR(100) NOT NULL DEFAULT '',
    tax_no VARCHAR(32) NOT NULL DEFAULT '',
    source VARCHAR(64) NOT NULL DEFAULT '',
    industry VARCHAR(64) NOT NULL DEFAULT '',
    area_id INT UNSIGNED NULL,
    owner_user_id BIGINT UNSIGNED NOT NULL,
    owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'new',
    converted_partner_id BIGINT UNSIGNED NULL,
    next_contact_at DATETIME(3) NULL,
    last_follow_at DATETIME(3) NULL,
    ext_json JSON NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_crm_clues_company (tenant_id,company_id,id),
    KEY idx_crm_clues_owner (tenant_id,company_id,owner_user_id,id),
    KEY idx_crm_clues_duplicate (tenant_id,company_id,mobile_hash,status),
    CONSTRAINT fk_crm_clue_company FOREIGN KEY(tenant_id,company_id) REFERENCES company (tenant_id,id),
    CONSTRAINT fk_crm_clue_partner FOREIGN KEY(tenant_id,converted_partner_id) REFERENCES business_partner (tenant_id,id),
    CHECK (status IN ('new','following','converted','invalid')),
    CHECK ((status='converted')=(converted_partner_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE crm_pool_rule (
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 0,
    no_follow_days SMALLINT UNSIGNED NOT NULL DEFAULT 30,
    no_deal_days SMALLINT UNSIGNED NOT NULL DEFAULT 90,
    notify_before_days SMALLINT UNSIGNED NOT NULL DEFAULT 3,
    apply_to_levels JSON NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (tenant_id,company_id),
    CONSTRAINT fk_crm_rule_company FOREIGN KEY(tenant_id,company_id) REFERENCES company (tenant_id,id),
    CHECK (enabled IN (0,1)),
    CHECK (no_follow_days BETWEEN 1 AND 3650 AND no_deal_days BETWEEN 1 AND 3650),
    CHECK (notify_before_days<LEAST(no_follow_days,no_deal_days)),
    CHECK (JSON_TYPE(apply_to_levels)='ARRAY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE crm_owner_limit (
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    target_type VARCHAR(12) NOT NULL,
    target_id BIGINT UNSIGNED NOT NULL,
    max_owned INT UNSIGNED NOT NULL,
    max_locked INT UNSIGNED NOT NULL,
    include_sold TINYINT(1) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (tenant_id,company_id,target_type,target_id),
    CONSTRAINT fk_crm_limit_company FOREIGN KEY(tenant_id,company_id) REFERENCES company (tenant_id,id),
    CHECK (target_type IN ('user','department')),
    CHECK (include_sold IN (0,1) AND max_locked<=max_owned)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE crm_follow_up (
    id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    biz_type VARCHAR(16) NOT NULL,
    biz_id BIGINT UNSIGNED NOT NULL,
    method VARCHAR(64) NOT NULL,
    content VARCHAR(5000) NOT NULL,
    next_contact_at DATETIME(3) NULL,
    mentioned_user_ids JSON NOT NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_crm_follow_subject (tenant_id,company_id,biz_type,biz_id,id),
    CONSTRAINT fk_crm_follow_company FOREIGN KEY(tenant_id,company_id) REFERENCES company (tenant_id,id),
    CHECK (biz_type IN ('clue','customer','business','contract')),
    CHECK (JSON_TYPE(mentioned_user_ids)='ARRAY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
    (14500,'crm_clue:view','查看线索','menu',14500),
    (14501,'crm_clue:create','建立线索','action',14501),
    (14502,'crm_clue:update','编辑线索','action',14502),
    (14503,'crm_clue:assign','分配线索','action',14503),
    (14504,'crm_clue:convert','转化线索','action',14504),
    (14505,'crm_clue:invalidate','作废线索','action',14505),
    (14506,'crm_clue:import','导入线索','action',14506),
    (14507,'crm_customer:view','查看客户','menu',14507),
    (14508,'crm_customer:view_all','查看公司范围内全部客户','action',14508),
    (14509,'crm_customer:manage_all','管理公司范围内全部客户','action',14509),
    (14510,'crm_customer:create','建立客户档案','action',14510),
    (14511,'crm_customer:update','维护客户档案','action',14511),
    (14512,'crm_customer:transfer','转移客户','action',14512),
    (14513,'crm_customer:lock','锁定客户','action',14513),
    (14514,'crm_customer:release','放回公海','action',14514),
    (14515,'crm_customer:team','维护客户团队','action',14515),
    (14516,'crm_customer:contact','维护客户联系人','action',14516),
    (14517,'crm_pool:view','查看客户公海','menu',14517),
    (14518,'crm_pool:claim','从公海领取客户','action',14518),
    (14519,'crm_pool:assign','分配公海客户','action',14519),
    (14520,'crm_pool:manage','维护公海规则与领取限制','action',14520);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT m.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN sys_permission p ON p.id BETWEEN 14500 AND 14520
CROSS JOIN (SELECT COALESCE(MAX(id),0) AS max_id FROM sys_role_permission) m
WHERE r.status='active' AND r.code='SYSTEM_ADMIN'
  AND EXISTS(SELECT 1 FROM sys_role_permission rp JOIN sys_permission prior ON prior.id=rp.permission_id
             WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id AND prior.code='role:assign_permission');

INSERT INTO sys_dict_type(id,tenant_id,code,name) VALUES
    (15001,0,'crm_industry','客户行业'),(15002,0,'crm_level','客户级别'),(15003,0,'crm_follow_method','跟进方式');
INSERT INTO sys_dict_item(id,tenant_id,type_code,value,label,color,sort_no) VALUES
    (15001,0,'crm_industry','manufacturing','制造业','neutral',10),
    (15002,0,'crm_industry','retail','批发零售','neutral',20),
    (15003,0,'crm_industry','services','服务业','neutral',30),
    (15004,0,'crm_industry','other','其他','neutral',40),
    (15005,0,'crm_level','a','重点客户','primary',10),
    (15006,0,'crm_level','b','成长客户','success',20),
    (15007,0,'crm_level','c','普通客户','neutral',30),
    (15008,0,'crm_follow_method','call','电话','neutral',10),
    (15009,0,'crm_follow_method','visit','拜访','primary',20),
    (15010,0,'crm_follow_method','wechat','微信','success',30),
    (15011,0,'crm_follow_method','email','邮件','neutral',40);
UPDATE sys_dict_revision SET version=version+1 WHERE tenant_id=0;

INSERT INTO job_definition(tenant_id,code,name,schedule) VALUES
    (0,'crm.pool_reclaim','CRM 公海回收和提前提醒','0 0 2 * * *');
