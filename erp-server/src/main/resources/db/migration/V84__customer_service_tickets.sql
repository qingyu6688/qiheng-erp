CREATE TABLE cs_ticket (
    id BIGINT UNSIGNED NOT NULL,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    no VARCHAR(64) NOT NULL,
    partner_id BIGINT UNSIGNED NOT NULL,
    portal_user_id BIGINT UNSIGNED NULL,
    channel VARCHAR(12) NOT NULL,
    category VARCHAR(16) NOT NULL,
    priority VARCHAR(12) NOT NULL,
    title VARCHAR(120) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'open',
    assignee_id BIGINT UNSIGNED NULL,
    owner_user_id BIGINT UNSIGNED NULL,
    owner_department_id BIGINT UNSIGNED NULL,
    ai_conversation_id VARCHAR(64) NULL,
    first_reply_due_at DATETIME(3) NOT NULL,
    sla_due_at DATETIME(3) NOT NULL,
    first_reply_at DATETIME(3) NULL,
    first_sla_notified_at DATETIME(3) NULL,
    resolution_sla_notified_at DATETIME(3) NULL,
    resolved_at DATETIME(3) NULL,
    closed_at DATETIME(3) NULL,
    satisfaction TINYINT UNSIGNED NULL,
    version INT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_cs_ticket_scope (tenant_id,company_id,id),
    UNIQUE KEY uk_cs_ticket_no (tenant_id,company_id,no),
    UNIQUE KEY uk_cs_ticket_ai (tenant_id,company_id,portal_user_id,ai_conversation_id),
    KEY idx_cs_portal_tickets (tenant_id,company_id,partner_id,portal_user_id,id),
    KEY idx_cs_assignee (tenant_id,company_id,assignee_id,id),
    KEY idx_cs_sla (tenant_id,status,sla_due_at,id),
    CONSTRAINT fk_cs_ticket_company FOREIGN KEY (tenant_id,company_id) REFERENCES company(tenant_id,id),
    CONSTRAINT fk_cs_ticket_partner FOREIGN KEY (tenant_id,partner_id) REFERENCES business_partner(tenant_id,id),
    CONSTRAINT fk_cs_ticket_portal_user FOREIGN KEY (tenant_id,company_id,partner_id,portal_user_id)
        REFERENCES portal_user(tenant_id,company_id,partner_id,id),
    CONSTRAINT ck_cs_ticket_channel CHECK (channel IN ('portal','mp','wecom','phone')),
    CONSTRAINT ck_cs_ticket_priority CHECK (priority IN ('normal','high','urgent')),
    CONSTRAINT ck_cs_ticket_status CHECK (status IN ('open','assigned','processing','waiting_customer','resolved','closed')),
    CONSTRAINT ck_cs_satisfaction CHECK (satisfaction BETWEEN 1 AND 5)
);

CREATE TABLE cs_ticket_reply (
    id BIGINT UNSIGNED NOT NULL,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    ticket_id BIGINT UNSIGNED NOT NULL,
    author_type VARCHAR(12) NOT NULL,
    author_id BIGINT UNSIGNED NOT NULL,
    content TEXT NOT NULL,
    internal BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_cs_reply_scope (tenant_id,company_id,ticket_id,id),
    CONSTRAINT fk_cs_reply_ticket FOREIGN KEY (tenant_id,company_id,ticket_id) REFERENCES cs_ticket(tenant_id,company_id,id),
    CONSTRAINT ck_cs_reply_author CHECK (author_type IN ('customer','agent','system'))
);

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
    (14710,'cs_ticket:view','查看客服工单','menu',14710),
    (14711,'cs_ticket:assign','分配客服工单','action',14711),
    (14712,'cs_ticket:reply','回复客服工单','action',14712),
    (14713,'cs_ticket:resolve','解决客服工单','action',14713),
    (14714,'cs_ticket:close','关闭客服工单','action',14714);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT m.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN sys_permission p ON p.id BETWEEN 14710 AND 14714
CROSS JOIN (SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) m
WHERE r.code='SYSTEM_ADMIN' AND r.status='active'
  AND EXISTS (SELECT 1 FROM sys_role_permission rp JOIN sys_permission prior ON prior.id=rp.permission_id
    WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id AND prior.code='role:assign_permission');

INSERT INTO job_definition(tenant_id,code,name,schedule) VALUES (0,'cs.sla','客服时限提醒','interval:60000');
