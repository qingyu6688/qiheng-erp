CREATE TABLE qc_item (
    id BIGINT UNSIGNED NOT NULL,
    tenant_id BIGINT UNSIGNED NOT NULL,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(80) NOT NULL,
    type VARCHAR(16) NOT NULL,
    unit VARCHAR(24) NULL,
    method VARCHAR(200) NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'active',
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY(id), UNIQUE KEY uk_qci_code(tenant_id,code), UNIQUE KEY uk_qci_scope(tenant_id,id),
    CONSTRAINT fk_qci_tenant FOREIGN KEY(tenant_id) REFERENCES tenant(id),
    CONSTRAINT ck_qci_type CHECK(type IN('quantitative','qualitative')),
    CONSTRAINT ck_qci_status CHECK(status IN('active','disabled'))
);

CREATE TABLE qc_template (
    id BIGINT UNSIGNED NOT NULL,
    tenant_id BIGINT UNSIGNED NOT NULL,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(80) NOT NULL,
    sampling VARCHAR(12) NOT NULL,
    sampling_value DECIMAL(24,6) NOT NULL DEFAULT 0,
    status VARCHAR(10) NOT NULL DEFAULT 'active',
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY(id), UNIQUE KEY uk_qct_code(tenant_id,code), UNIQUE KEY uk_qct_scope(tenant_id,id),
    CONSTRAINT fk_qct_tenant FOREIGN KEY(tenant_id) REFERENCES tenant(id),
    CONSTRAINT ck_qct_sampling CHECK(sampling IN('full','fixed_qty','percent')),
    CONSTRAINT ck_qct_status CHECK(status IN('active','disabled'))
);

CREATE TABLE qc_template_item (
    tenant_id BIGINT UNSIGNED NOT NULL,
    template_id BIGINT UNSIGNED NOT NULL,
    item_id BIGINT UNSIGNED NOT NULL,
    seq_no INT UNSIGNED NOT NULL,
    standard_value VARCHAR(160) NOT NULL,
    upper_limit DECIMAL(24,6) NULL,
    lower_limit DECIMAL(24,6) NULL,
    required BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY(tenant_id,template_id,item_id),
    KEY idx_qcti_item(tenant_id,item_id),
    CONSTRAINT fk_qcti_template FOREIGN KEY(tenant_id,template_id) REFERENCES qc_template(tenant_id,id),
    CONSTRAINT fk_qcti_item FOREIGN KEY(tenant_id,item_id) REFERENCES qc_item(tenant_id,id),
    CONSTRAINT ck_qcti_limit CHECK(upper_limit IS NULL OR lower_limit IS NULL OR upper_limit>=lower_limit)
);

CREATE TABLE qc_sku_rule (
    id BIGINT UNSIGNED NOT NULL,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    target_type VARCHAR(10) NOT NULL,
    target_id BIGINT UNSIGNED NOT NULL,
    supplier_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    template_id BIGINT UNSIGNED NULL,
    inspection_required BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(10) NOT NULL DEFAULT 'active',
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY(id), UNIQUE KEY uk_qcr_target(tenant_id,company_id,target_type,target_id,supplier_id),
    CONSTRAINT fk_qcr_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),
    CONSTRAINT fk_qcr_template FOREIGN KEY(tenant_id,template_id) REFERENCES qc_template(tenant_id,id),
    CONSTRAINT ck_qcr_type CHECK(target_type IN('sku','category')),
    CONSTRAINT ck_qcr_status CHECK(status IN('active','disabled')),
    CONSTRAINT ck_qcr_template CHECK(inspection_required=0 OR template_id IS NOT NULL)
);

CREATE TABLE qc_inspection (
    id BIGINT UNSIGNED NOT NULL,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    inspection_no VARCHAR(64) NOT NULL,
    source_id BIGINT UNSIGNED NOT NULL,
    source_line_id BIGINT UNSIGNED NOT NULL,
    sku_id BIGINT UNSIGNED NOT NULL,
    supplier_id BIGINT UNSIGNED NOT NULL,
    warehouse_id BIGINT UNSIGNED NOT NULL,
    lot_key VARCHAR(64) NOT NULL DEFAULT '',
    location_key VARCHAR(64) NOT NULL DEFAULT '',
    received_qty DECIMAL(24,6) NOT NULL,
    round_qty DECIMAL(24,6) NOT NULL,
    sample_qty DECIMAL(24,6) NOT NULL,
    qualified_qty DECIMAL(24,6) NOT NULL DEFAULT 0,
    unqualified_qty DECIMAL(24,6) NOT NULL DEFAULT 0,
    concession_qty DECIMAL(24,6) NOT NULL DEFAULT 0,
    handled_qty DECIMAL(24,6) NOT NULL DEFAULT 0,
    concession_reason VARCHAR(500) NULL,
    result VARCHAR(12) NOT NULL DEFAULT 'pending',
    status VARCHAR(12) NOT NULL DEFAULT 'draft',
    snapshot_json JSON NOT NULL,
    inspector_id BIGINT UNSIGNED NULL,
    approver_id BIGINT UNSIGNED NULL,
    approved_at DATETIME(3) NULL,
    business_date DATE NOT NULL,
    owner_user_id BIGINT UNSIGNED NOT NULL,
    owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    round_no INT UNSIGNED NOT NULL DEFAULT 1,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY(id), UNIQUE KEY uk_qcn_scope(tenant_id,company_id,id),
    UNIQUE KEY uk_qcn_no(tenant_id,company_id,inspection_no), UNIQUE KEY uk_qcn_receipt(tenant_id,company_id,source_line_id),
    KEY idx_qcn_state(tenant_id,company_id,warehouse_id,status,id),
    CONSTRAINT fk_qcn_source FOREIGN KEY(tenant_id,company_id,source_id) REFERENCES goods_receipt(tenant_id,company_id,id),
    CONSTRAINT fk_qcn_line FOREIGN KEY(tenant_id,company_id,source_line_id) REFERENCES goods_receipt_line(tenant_id,company_id,id),
    CONSTRAINT fk_qcn_sku FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id),
    CONSTRAINT fk_qcn_supplier FOREIGN KEY(tenant_id,supplier_id) REFERENCES business_partner(tenant_id,id),
    CONSTRAINT fk_qcn_wh FOREIGN KEY(tenant_id,company_id,warehouse_id) REFERENCES warehouse(tenant_id,company_id,id),
    CONSTRAINT ck_qcn_state CHECK(status IN('draft','submitted','approved','cancelled')),
    CONSTRAINT ck_qcn_result CHECK(result IN('pending','passed','failed','partial')),
    CONSTRAINT ck_qcn_qty CHECK(received_qty>0 AND round_qty>0 AND round_qty<=received_qty AND sample_qty>0 AND sample_qty<=round_qty
        AND qualified_qty>=0 AND unqualified_qty>=0 AND concession_qty>=0 AND handled_qty>=0)
);

CREATE TABLE qc_inspection_value (
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    inspection_id BIGINT UNSIGNED NOT NULL,
    round_no INT UNSIGNED NOT NULL,
    item_id BIGINT UNSIGNED NOT NULL,
    measured_value VARCHAR(160) NOT NULL,
    judgement VARCHAR(4) NOT NULL,
    PRIMARY KEY(tenant_id,company_id,inspection_id,round_no,item_id),
    CONSTRAINT fk_qcv_inspection FOREIGN KEY(tenant_id,company_id,inspection_id) REFERENCES qc_inspection(tenant_id,company_id,id),
    CONSTRAINT ck_qcv_judgement CHECK(judgement IN('ok','ng'))
);

CREATE TABLE qc_defect (
    id BIGINT UNSIGNED NOT NULL,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    inspection_id BIGINT UNSIGNED NOT NULL,
    round_no INT UNSIGNED NOT NULL,
    defect_code VARCHAR(40) NOT NULL,
    qty DECIMAL(24,6) NOT NULL,
    remark VARCHAR(200) NULL,
    PRIMARY KEY(id), KEY idx_qcd_inspection(tenant_id,company_id,inspection_id,round_no),
    CONSTRAINT fk_qcd_inspection FOREIGN KEY(tenant_id,company_id,inspection_id) REFERENCES qc_inspection(tenant_id,company_id,id),
    CONSTRAINT ck_qcd_qty CHECK(qty>0)
);

CREATE TABLE qc_disposition (
    id BIGINT UNSIGNED NOT NULL,
    tenant_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    inspection_id BIGINT UNSIGNED NOT NULL,
    round_no INT UNSIGNED NOT NULL,
    action VARCHAR(12) NOT NULL,
    qty DECIMAL(24,6) NOT NULL,
    target_type VARCHAR(24) NULL,
    target_id BIGINT UNSIGNED NULL,
    reason VARCHAR(200) NOT NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY(id), UNIQUE KEY uk_qcx_target(tenant_id,company_id,target_type,target_id),
    KEY idx_qcx_inspection(tenant_id,company_id,inspection_id,round_no),
    CONSTRAINT fk_qcx_inspection FOREIGN KEY(tenant_id,company_id,inspection_id) REFERENCES qc_inspection(tenant_id,company_id,id),
    CONSTRAINT ck_qcx_action CHECK(action IN('return','scrap','reinspect')),
    CONSTRAINT ck_qcx_qty CHECK(qty>0)
);

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
    (14140,'qc_setting:view','查看检验设置','menu',14140),
    (14141,'qc_setting:manage','维护检验设置','action',14141),
    (14142,'qc_inspection:view','查看来料检验','menu',14142),
    (14143,'qc_inspection:submit','提交来料检验','action',14143),
    (14144,'qc_inspection:approve','审核来料检验','action',14144),
    (14145,'qc_inspection:dispose','处理不合格品','action',14145);

INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
SELECT m.max_id+ROW_NUMBER() OVER(ORDER BY r.tenant_id,r.id,p.id),r.tenant_id,r.id,p.id
FROM sys_role r JOIN tenant t ON t.id=r.tenant_id
JOIN sys_permission p ON p.id BETWEEN 14140 AND 14145
CROSS JOIN (SELECT COALESCE(MAX(id),0) max_id FROM sys_role_permission) m
WHERE (r.code='SYSTEM_ADMIN' OR (r.code='WAREHOUSE_ADMIN' AND t.name='演示企业'))
  AND EXISTS (SELECT 1 FROM sys_role_permission rp JOIN sys_permission prior ON prior.id=rp.permission_id
              WHERE rp.tenant_id=r.tenant_id AND rp.role_id=r.id AND prior.code='role:assign_permission');
