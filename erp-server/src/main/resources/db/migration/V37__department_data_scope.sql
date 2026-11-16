ALTER TABLE import_job ADD COLUMN org_scope_snapshot JSON NULL COMMENT '发起时部门范围，执行时与即时权限取交集';

CREATE TABLE org_role_data_scope (
    tenant_id BIGINT UNSIGNED NOT NULL, company_id BIGINT UNSIGNED NOT NULL, role_id BIGINT UNSIGNED NOT NULL,
    scope_type VARCHAR(32) NOT NULL DEFAULT 'all', version BIGINT NOT NULL DEFAULT 0,
    created_by BIGINT UNSIGNED NULL, updated_by BIGINT UNSIGNED NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY(tenant_id,company_id,role_id),
    CONSTRAINT fk_org_role_scope_role FOREIGN KEY(tenant_id,role_id) REFERENCES sys_role(tenant_id,id),
    CONSTRAINT fk_org_role_scope_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),
    CONSTRAINT ck_org_role_scope_type CHECK(scope_type IN ('all','dept','dept_and_children','self','custom'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色在公司的部门数据范围';

CREATE TABLE org_role_data_scope_dept (
    tenant_id BIGINT UNSIGNED NOT NULL, company_id BIGINT UNSIGNED NOT NULL, role_id BIGINT UNSIGNED NOT NULL, department_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY(tenant_id,company_id,role_id,department_id),
    CONSTRAINT fk_org_role_scope_dept_config FOREIGN KEY(tenant_id,company_id,role_id) REFERENCES org_role_data_scope(tenant_id,company_id,role_id),
    CONSTRAINT fk_org_role_scope_dept FOREIGN KEY(tenant_id,company_id,department_id) REFERENCES org_department(tenant_id,company_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='自定义范围指定的公司部门';

ALTER TABLE sales_order
    ADD COLUMN owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单时的公司主部门，0表示未归属',
    ADD COLUMN owner_user_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单人，后续任职变化不回写',
    ADD KEY idx_sales_order_owner_dept(tenant_id,company_id,owner_department_id,business_date),
    ADD KEY idx_sales_order_owner_user(tenant_id,company_id,owner_user_id,business_date);
UPDATE sales_order h LEFT JOIN org_user_department m
    ON m.tenant_id=h.tenant_id AND m.company_id=h.company_id AND m.user_id=h.created_by AND m.is_primary=1
    SET h.owner_user_id=COALESCE(h.created_by,0),h.owner_department_id=COALESCE(m.department_id,0);

ALTER TABLE shipment
    ADD COLUMN owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单时的公司主部门，0表示未归属',
    ADD COLUMN owner_user_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单人，后续任职变化不回写',
    ADD KEY idx_shipment_owner_dept(tenant_id,company_id,owner_department_id,business_date),
    ADD KEY idx_shipment_owner_user(tenant_id,company_id,owner_user_id,business_date);
UPDATE shipment h LEFT JOIN org_user_department m
    ON m.tenant_id=h.tenant_id AND m.company_id=h.company_id AND m.user_id=h.created_by AND m.is_primary=1
    SET h.owner_user_id=COALESCE(h.created_by,0),h.owner_department_id=COALESCE(m.department_id,0);

ALTER TABLE receipt
    ADD COLUMN owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单时的公司主部门，0表示未归属',
    ADD COLUMN owner_user_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单人，后续任职变化不回写',
    ADD KEY idx_receipt_owner_dept(tenant_id,company_id,owner_department_id,business_date),
    ADD KEY idx_receipt_owner_user(tenant_id,company_id,owner_user_id,business_date);
UPDATE receipt h LEFT JOIN org_user_department m
    ON m.tenant_id=h.tenant_id AND m.company_id=h.company_id AND m.user_id=h.created_by AND m.is_primary=1
    SET h.owner_user_id=COALESCE(h.created_by,0),h.owner_department_id=COALESCE(m.department_id,0);

ALTER TABLE sales_return
    ADD COLUMN owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单时的公司主部门，0表示未归属',
    ADD COLUMN owner_user_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单人，后续任职变化不回写',
    ADD KEY idx_sales_return_owner_dept(tenant_id,company_id,owner_department_id,business_date),
    ADD KEY idx_sales_return_owner_user(tenant_id,company_id,owner_user_id,business_date);
UPDATE sales_return h LEFT JOIN org_user_department m
    ON m.tenant_id=h.tenant_id AND m.company_id=h.company_id AND m.user_id=h.created_by AND m.is_primary=1
    SET h.owner_user_id=COALESCE(h.created_by,0),h.owner_department_id=COALESCE(m.department_id,0);

ALTER TABLE refund_request
    ADD COLUMN owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单时的公司主部门，0表示未归属',
    ADD COLUMN owner_user_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单人，后续任职变化不回写',
    ADD KEY idx_refund_request_owner_dept(tenant_id,company_id,owner_department_id,business_date),
    ADD KEY idx_refund_request_owner_user(tenant_id,company_id,owner_user_id,business_date);
UPDATE refund_request h LEFT JOIN org_user_department m
    ON m.tenant_id=h.tenant_id AND m.company_id=h.company_id AND m.user_id=h.created_by AND m.is_primary=1
    SET h.owner_user_id=COALESCE(h.created_by,0),h.owner_department_id=COALESCE(m.department_id,0);

ALTER TABLE purchase_requisition
    ADD COLUMN owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单时的公司主部门，0表示未归属',
    ADD COLUMN owner_user_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单人，后续任职变化不回写',
    ADD KEY idx_purchase_requisition_owner_dept(tenant_id,company_id,owner_department_id,business_date),
    ADD KEY idx_purchase_requisition_owner_user(tenant_id,company_id,owner_user_id,business_date);
UPDATE purchase_requisition h LEFT JOIN org_user_department m
    ON m.tenant_id=h.tenant_id AND m.company_id=h.company_id AND m.user_id=h.created_by AND m.is_primary=1
    SET h.owner_user_id=COALESCE(h.created_by,0),h.owner_department_id=COALESCE(m.department_id,0);

ALTER TABLE purchase_order
    ADD COLUMN owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单时的公司主部门，0表示未归属',
    ADD COLUMN owner_user_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单人，后续任职变化不回写',
    ADD KEY idx_purchase_order_owner_dept(tenant_id,company_id,owner_department_id,business_date),
    ADD KEY idx_purchase_order_owner_user(tenant_id,company_id,owner_user_id,business_date);
UPDATE purchase_order h LEFT JOIN org_user_department m
    ON m.tenant_id=h.tenant_id AND m.company_id=h.company_id AND m.user_id=h.created_by AND m.is_primary=1
    SET h.owner_user_id=COALESCE(h.created_by,0),h.owner_department_id=COALESCE(m.department_id,0);

ALTER TABLE goods_receipt
    ADD COLUMN owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单时的公司主部门，0表示未归属',
    ADD COLUMN owner_user_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单人，后续任职变化不回写',
    ADD KEY idx_goods_receipt_owner_dept(tenant_id,company_id,owner_department_id,business_date),
    ADD KEY idx_goods_receipt_owner_user(tenant_id,company_id,owner_user_id,business_date);
UPDATE goods_receipt h LEFT JOIN org_user_department m
    ON m.tenant_id=h.tenant_id AND m.company_id=h.company_id AND m.user_id=h.created_by AND m.is_primary=1
    SET h.owner_user_id=COALESCE(h.created_by,0),h.owner_department_id=COALESCE(m.department_id,0);

ALTER TABLE purchase_return
    ADD COLUMN owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单时的公司主部门，0表示未归属',
    ADD COLUMN owner_user_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单人，后续任职变化不回写',
    ADD KEY idx_purchase_return_owner_dept(tenant_id,company_id,owner_department_id,business_date),
    ADD KEY idx_purchase_return_owner_user(tenant_id,company_id,owner_user_id,business_date);
UPDATE purchase_return h LEFT JOIN org_user_department m
    ON m.tenant_id=h.tenant_id AND m.company_id=h.company_id AND m.user_id=h.created_by AND m.is_primary=1
    SET h.owner_user_id=COALESCE(h.created_by,0),h.owner_department_id=COALESCE(m.department_id,0);

ALTER TABLE payment
    ADD COLUMN owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单时的公司主部门，0表示未归属',
    ADD COLUMN owner_user_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单人，后续任职变化不回写',
    ADD KEY idx_payment_owner_dept(tenant_id,company_id,owner_department_id,business_date),
    ADD KEY idx_payment_owner_user(tenant_id,company_id,owner_user_id,business_date);
UPDATE payment h LEFT JOIN org_user_department m
    ON m.tenant_id=h.tenant_id AND m.company_id=h.company_id AND m.user_id=h.created_by AND m.is_primary=1
    SET h.owner_user_id=COALESCE(h.created_by,0),h.owner_department_id=COALESCE(m.department_id,0);

ALTER TABLE account_transfer
    ADD COLUMN owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单时的公司主部门，0表示未归属',
    ADD COLUMN owner_user_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单人，后续任职变化不回写',
    ADD KEY idx_account_transfer_owner_dept(tenant_id,company_id,owner_department_id,business_date),
    ADD KEY idx_account_transfer_owner_user(tenant_id,company_id,owner_user_id,business_date);
UPDATE account_transfer h LEFT JOIN org_user_department m
    ON m.tenant_id=h.tenant_id AND m.company_id=h.company_id AND m.user_id=h.created_by AND m.is_primary=1
    SET h.owner_user_id=COALESCE(h.created_by,0),h.owner_department_id=COALESCE(m.department_id,0);

ALTER TABLE opening_entry
    ADD COLUMN owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单时的公司主部门，0表示未归属',
    ADD COLUMN owner_user_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单人，后续任职变化不回写',
    ADD KEY idx_opening_entry_owner_dept(tenant_id,company_id,owner_department_id,business_date),
    ADD KEY idx_opening_entry_owner_user(tenant_id,company_id,owner_user_id,business_date);
UPDATE opening_entry h LEFT JOIN org_user_department m
    ON m.tenant_id=h.tenant_id AND m.company_id=h.company_id AND m.user_id=h.created_by AND m.is_primary=1
    SET h.owner_user_id=COALESCE(h.created_by,0),h.owner_department_id=COALESCE(m.department_id,0);

ALTER TABLE stock_adjustment
    ADD COLUMN owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单时的公司主部门，0表示未归属',
    ADD COLUMN owner_user_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单人，后续任职变化不回写',
    ADD KEY idx_stock_adjustment_owner_dept(tenant_id,company_id,owner_department_id,business_date),
    ADD KEY idx_stock_adjustment_owner_user(tenant_id,company_id,owner_user_id,business_date);
UPDATE stock_adjustment h LEFT JOIN org_user_department m
    ON m.tenant_id=h.tenant_id AND m.company_id=h.company_id AND m.user_id=h.created_by AND m.is_primary=1
    SET h.owner_user_id=COALESCE(h.created_by,0),h.owner_department_id=COALESCE(m.department_id,0);

ALTER TABLE stock_count
    ADD COLUMN owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单时的公司主部门，0表示未归属',
    ADD COLUMN owner_user_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单人，后续任职变化不回写',
    ADD KEY idx_stock_count_owner_dept(tenant_id,company_id,owner_department_id,business_date),
    ADD KEY idx_stock_count_owner_user(tenant_id,company_id,owner_user_id,business_date);
UPDATE stock_count h LEFT JOIN org_user_department m
    ON m.tenant_id=h.tenant_id AND m.company_id=h.company_id AND m.user_id=h.created_by AND m.is_primary=1
    SET h.owner_user_id=COALESCE(h.created_by,0),h.owner_department_id=COALESCE(m.department_id,0);

ALTER TABLE stock_transfer
    ADD COLUMN owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单时的公司主部门，0表示未归属',
    ADD COLUMN owner_user_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单人，后续任职变化不回写',
    ADD KEY idx_stock_transfer_owner_dept(tenant_id,company_id,owner_department_id,business_date),
    ADD KEY idx_stock_transfer_owner_user(tenant_id,company_id,owner_user_id,business_date);
UPDATE stock_transfer h LEFT JOIN org_user_department m
    ON m.tenant_id=h.tenant_id AND m.company_id=h.company_id AND m.user_id=h.created_by AND m.is_primary=1
    SET h.owner_user_id=COALESCE(h.created_by,0),h.owner_department_id=COALESCE(m.department_id,0);

ALTER TABLE document_reversal
    ADD COLUMN owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单时的公司主部门，0表示未归属',
    ADD COLUMN owner_user_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单人，后续任职变化不回写',
    ADD KEY idx_document_reversal_owner_dept(tenant_id,company_id,owner_department_id,business_date),
    ADD KEY idx_document_reversal_owner_user(tenant_id,company_id,owner_user_id,business_date);
UPDATE document_reversal h LEFT JOIN org_user_department m
    ON m.tenant_id=h.tenant_id AND m.company_id=h.company_id AND m.user_id=h.created_by AND m.is_primary=1
    SET h.owner_user_id=COALESCE(h.created_by,0),h.owner_department_id=COALESCE(m.department_id,0);

ALTER TABLE invoice_registration
    ADD COLUMN owner_department_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单时的公司主部门，0表示未归属',
    ADD COLUMN owner_user_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '制单人，后续任职变化不回写',
    ADD KEY idx_invoice_registration_owner_dept(tenant_id,company_id,owner_department_id,invoice_date),
    ADD KEY idx_invoice_registration_owner_user(tenant_id,company_id,owner_user_id,invoice_date);
UPDATE invoice_registration h LEFT JOIN org_user_department m
    ON m.tenant_id=h.tenant_id AND m.company_id=h.company_id AND m.user_id=h.created_by AND m.is_primary=1
    SET h.owner_user_id=COALESCE(h.created_by,0),h.owner_department_id=COALESCE(m.department_id,0);


UPDATE document_reversal r JOIN shipment h ON h.tenant_id=r.tenant_id AND h.company_id=r.company_id AND h.id=r.source_id AND r.source_type='shipment'
    SET r.owner_department_id=h.owner_department_id,r.owner_user_id=h.owner_user_id;

UPDATE document_reversal r JOIN sales_return h ON h.tenant_id=r.tenant_id AND h.company_id=r.company_id AND h.id=r.source_id AND r.source_type='sales_return'
    SET r.owner_department_id=h.owner_department_id,r.owner_user_id=h.owner_user_id;

UPDATE document_reversal r JOIN goods_receipt h ON h.tenant_id=r.tenant_id AND h.company_id=r.company_id AND h.id=r.source_id AND r.source_type='goods_receipt'
    SET r.owner_department_id=h.owner_department_id,r.owner_user_id=h.owner_user_id;

UPDATE document_reversal r JOIN purchase_return h ON h.tenant_id=r.tenant_id AND h.company_id=r.company_id AND h.id=r.source_id AND r.source_type='purchase_return'
    SET r.owner_department_id=h.owner_department_id,r.owner_user_id=h.owner_user_id;

UPDATE document_reversal r JOIN receipt h ON h.tenant_id=r.tenant_id AND h.company_id=r.company_id AND h.id=r.source_id AND r.source_type='receipt'
    SET r.owner_department_id=h.owner_department_id,r.owner_user_id=h.owner_user_id;

UPDATE document_reversal r JOIN payment h ON h.tenant_id=r.tenant_id AND h.company_id=r.company_id AND h.id=r.source_id AND r.source_type='payment'
    SET r.owner_department_id=h.owner_department_id,r.owner_user_id=h.owner_user_id;

UPDATE document_reversal r JOIN account_transfer h ON h.tenant_id=r.tenant_id AND h.company_id=r.company_id AND h.id=r.source_id AND r.source_type='account_transfer'
    SET r.owner_department_id=h.owner_department_id,r.owner_user_id=h.owner_user_id;

UPDATE document_reversal r JOIN stock_adjustment h ON h.tenant_id=r.tenant_id AND h.company_id=r.company_id AND h.id=r.source_id AND r.source_type='stock_adjustment'
    SET r.owner_department_id=h.owner_department_id,r.owner_user_id=h.owner_user_id;
