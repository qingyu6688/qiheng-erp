CREATE TABLE portal_user (
    id BIGINT UNSIGNED NOT NULL,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    partner_id BIGINT UNSIGNED NOT NULL,
    contact_id BIGINT UNSIGNED NOT NULL,
    login_name VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'active',
    version INT UNSIGNED NOT NULL DEFAULT 0,
    last_login_at DATETIME(3) NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_portal_login (tenant_id,login_name),
    UNIQUE KEY uk_portal_contact (tenant_id,company_id,contact_id),
    UNIQUE KEY uk_portal_user_scope (tenant_id,company_id,partner_id,id),
    KEY idx_portal_users_company (tenant_id,company_id,id),
    CONSTRAINT fk_portal_user_company FOREIGN KEY (tenant_id,company_id) REFERENCES company(tenant_id,id),
    CONSTRAINT fk_portal_user_partner FOREIGN KEY (tenant_id,partner_id) REFERENCES business_partner(tenant_id,id),
    CONSTRAINT ck_portal_user_status CHECK (status IN ('active','disabled'))
);

CREATE TABLE portal_statement_acceptance (
    id BIGINT UNSIGNED NOT NULL,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    partner_id BIGINT UNSIGNED NOT NULL,
    portal_user_id BIGINT UNSIGNED NOT NULL,
    date_from DATE NOT NULL,
    date_to DATE NOT NULL,
    statement_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    data_as_of DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_portal_statement (tenant_id,company_id,partner_id,date_from,date_to,statement_digest),
    CONSTRAINT fk_portal_statement_user FOREIGN KEY (tenant_id,company_id,partner_id,portal_user_id)
        REFERENCES portal_user(tenant_id,company_id,partner_id,id),
    CONSTRAINT ck_portal_statement_dates CHECK (date_from<=date_to)
);

ALTER TABLE shipment ADD COLUMN logistics_no VARCHAR(64) NULL;

CREATE INDEX idx_portal_order_customer ON sales_order(tenant_id,company_id,customer_id,id);
CREATE INDEX idx_portal_shipment_customer ON shipment(tenant_id,company_id,customer_id,id);
CREATE INDEX idx_portal_invoice_customer ON einv_issue_request(tenant_id,company_id,partner_id,id);

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
    (14700,'portal_user:view','查看客户门户账号','menu',14700),
    (14701,'portal_user:manage','维护客户门户账号','action',14701);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT m.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN sys_permission p ON p.id BETWEEN 14700 AND 14701
CROSS JOIN (SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) m
WHERE r.code='SYSTEM_ADMIN' AND r.status='active'
  AND EXISTS (SELECT 1 FROM sys_role_permission rp JOIN sys_permission prior ON prior.id=rp.permission_id
    WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id AND prior.code='role:assign_permission');
