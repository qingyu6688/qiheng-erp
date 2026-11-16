-- 业务员用于业绩统计，与权限归属独立；历史无法确定的人员保持0。

ALTER TABLE sales_order ADD COLUMN sales_user_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '业务员，0表示历史未分配', ADD KEY idx_sales_order_business_user(tenant_id,company_id,sales_user_id,business_date);

UPDATE sales_order SET sales_user_id=COALESCE(NULLIF(owner_user_id,0),created_by,0);

ALTER TABLE shipment ADD COLUMN sales_user_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '业务员，0表示历史未分配', ADD KEY idx_shipment_business_user(tenant_id,company_id,sales_user_id,business_date);

UPDATE shipment SET sales_user_id=COALESCE(NULLIF(owner_user_id,0),created_by,0);

ALTER TABLE sales_return ADD COLUMN sales_user_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '业务员，0表示历史未分配', ADD KEY idx_sales_return_business_user(tenant_id,company_id,sales_user_id,business_date);

UPDATE sales_return SET sales_user_id=COALESCE(NULLIF(owner_user_id,0),created_by,0);

ALTER TABLE receipt ADD COLUMN sales_user_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '业务员，0表示历史未分配', ADD KEY idx_receipt_business_user(tenant_id,company_id,sales_user_id,business_date);

UPDATE receipt SET sales_user_id=COALESCE(NULLIF(owner_user_id,0),created_by,0);

ALTER TABLE purchase_order ADD COLUMN buyer_user_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '业务员，0表示历史未分配', ADD KEY idx_purchase_order_business_user(tenant_id,company_id,buyer_user_id,business_date);

UPDATE purchase_order SET buyer_user_id=COALESCE(NULLIF(owner_user_id,0),created_by,0);

ALTER TABLE goods_receipt ADD COLUMN buyer_user_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '业务员，0表示历史未分配', ADD KEY idx_goods_receipt_business_user(tenant_id,company_id,buyer_user_id,business_date);

UPDATE goods_receipt SET buyer_user_id=COALESCE(NULLIF(owner_user_id,0),created_by,0);

ALTER TABLE purchase_return ADD COLUMN buyer_user_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '业务员，0表示历史未分配', ADD KEY idx_purchase_return_business_user(tenant_id,company_id,buyer_user_id,business_date);

UPDATE purchase_return SET buyer_user_id=COALESCE(NULLIF(owner_user_id,0),created_by,0);

ALTER TABLE payment ADD COLUMN buyer_user_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '业务员，0表示历史未分配', ADD KEY idx_payment_business_user(tenant_id,company_id,buyer_user_id,business_date);

UPDATE payment SET buyer_user_id=COALESCE(NULLIF(owner_user_id,0),created_by,0);
