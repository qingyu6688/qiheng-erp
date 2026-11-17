CREATE TABLE crm_tenant_guard (
    tenant_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE crm_customer_profile (
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    partner_id BIGINT UNSIGNED NOT NULL,
    owner_user_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    owner_since_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    level VARCHAR(64) NOT NULL DEFAULT '',
    source VARCHAR(64) NOT NULL DEFAULT '',
    industry VARCHAR(64) NOT NULL DEFAULT '',
    area_id INT UNSIGNED NULL,
    deal_status VARCHAR(16) NOT NULL DEFAULT 'unsold',
    `locked` TINYINT(1) NOT NULL DEFAULT 0,
    next_contact_at DATETIME(3) NULL,
    last_follow_at DATETIME(3) NULL,
    pool_entered_at DATETIME(3) NULL,
    pool_reason VARCHAR(40) NULL,
    ext_json JSON NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_by BIGINT UNSIGNED NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (tenant_id,company_id,partner_id),
    KEY idx_crm_customer_owner (tenant_id,company_id,owner_user_id,`locked`,deal_status,partner_id),
    KEY idx_crm_customer_pool (tenant_id,company_id,pool_entered_at,partner_id),
    KEY idx_crm_customer_department (tenant_id,company_id,owner_department_id,partner_id),
    CONSTRAINT fk_crm_customer_company FOREIGN KEY (tenant_id,company_id) REFERENCES company (tenant_id,id),
    CONSTRAINT fk_crm_customer_partner FOREIGN KEY (tenant_id,partner_id) REFERENCES business_partner (tenant_id,id),
    CHECK (deal_status IN ('unsold','sold')),
    CHECK (`locked` IN (0,1) AND (`locked`=0 OR owner_user_id>0)),
    CHECK (pool_entered_at IS NULL OR owner_user_id=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE crm_customer_team (
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    partner_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    permission VARCHAR(8) NOT NULL,
    expires_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (tenant_id,company_id,partner_id,user_id),
    KEY idx_crm_team_member (tenant_id,company_id,user_id,partner_id),
    CONSTRAINT fk_crm_team_customer FOREIGN KEY (tenant_id,company_id,partner_id)
        REFERENCES crm_customer_profile (tenant_id,company_id,partner_id),
    CONSTRAINT fk_crm_team_user FOREIGN KEY (tenant_id,user_id) REFERENCES sys_user (tenant_id,id),
    CHECK (permission IN ('read','write'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE crm_contact_profile (
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    partner_id BIGINT UNSIGNED NOT NULL,
    contact_id BIGINT UNSIGNED NOT NULL,
    position VARCHAR(80) NOT NULL DEFAULT '',
    is_decision_maker TINYINT(1) NOT NULL DEFAULT 0,
    birthday DATE NULL,
    wechat_cipher VARCHAR(280) NULL,
    remark VARCHAR(500) NOT NULL DEFAULT '',
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (tenant_id,company_id,partner_id,contact_id),
    KEY idx_crm_contact_ref (contact_id),
    CONSTRAINT fk_crm_contact_customer FOREIGN KEY (tenant_id,company_id,partner_id)
        REFERENCES crm_customer_profile (tenant_id,company_id,partner_id),
    CONSTRAINT fk_crm_contact_master FOREIGN KEY (contact_id) REFERENCES partner_contact (id) ON DELETE CASCADE,
    CHECK (is_decision_maker IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 历史客户优先沿用其所在公司最近的有效订单业务员。缺少合适负责人的历史档案留下待分配，避免迁移当日进入公海。
INSERT INTO crm_customer_profile(tenant_id,company_id,partner_id,owner_user_id,owner_department_id,source,area_id,deal_status,created_by,created_at)
WITH ranked_orders AS (
    SELECT o.tenant_id,o.company_id,o.customer_id,o.sales_user_id,
           MAX(CASE WHEN o.status='approved' THEN 1 ELSE 0 END) OVER(PARTITION BY o.tenant_id,o.company_id,o.customer_id) AS sold,
           ROW_NUMBER() OVER(PARTITION BY o.tenant_id,o.company_id,o.customer_id ORDER BY o.business_date DESC,o.id DESC) AS row_no
    FROM sales_order o WHERE o.is_deleted=0 AND o.status<>'cancelled'
), owner_candidates AS (
    SELECT p.tenant_id,p.id,p.source_code,p.area_id,p.created_by,p.created_at,
           COALESCE(o.company_id,(SELECT MIN(s.company_id) FROM sys_user_data_scope s WHERE s.tenant_id=p.tenant_id AND s.user_id=p.created_by),
                    (SELECT MIN(c.id) FROM company c WHERE c.tenant_id=p.tenant_id AND c.status='active')) AS company_id,
           COALESCE(o.sales_user_id,0) AS sales_user_id,COALESCE(o.sold,0) AS sold
    FROM business_partner p
    JOIN partner_role r ON r.tenant_id=p.tenant_id AND r.partner_id=p.id AND r.role='customer' AND r.status='active'
    LEFT JOIN ranked_orders o ON o.tenant_id=p.tenant_id AND o.customer_id=p.id AND o.row_no=1
), scoped_owners AS (
    SELECT x.*,COALESCE((SELECT u.id FROM sys_user u WHERE u.tenant_id=x.tenant_id AND u.id=x.sales_user_id AND u.status='active'
                                AND EXISTS(SELECT 1 FROM sys_user_data_scope s WHERE s.tenant_id=u.tenant_id AND s.user_id=u.id AND s.company_id=x.company_id)),
                        (SELECT u.id FROM sys_user u WHERE u.tenant_id=x.tenant_id AND u.id=x.created_by AND u.status='active'
                                AND EXISTS(SELECT 1 FROM sys_user_data_scope s WHERE s.tenant_id=u.tenant_id AND s.user_id=u.id AND s.company_id=x.company_id)),0) AS owner_id
    FROM owner_candidates x WHERE x.company_id IS NOT NULL
)
SELECT x.tenant_id,x.company_id,x.id,x.owner_id,COALESCE(d.department_id,0),COALESCE(x.source_code,''),x.area_id,
       IF(x.sold=1,'sold','unsold'),x.created_by,x.created_at
FROM scoped_owners x LEFT JOIN org_user_department d ON d.tenant_id=x.tenant_id AND d.company_id=x.company_id
    AND d.user_id=x.owner_id AND d.is_primary=1;
