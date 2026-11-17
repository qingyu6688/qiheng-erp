CREATE TABLE cf_definition (
    id BIGINT UNSIGNED NOT NULL,
    tenant_id BIGINT UNSIGNED NOT NULL,
    entity VARCHAR(40) NOT NULL,
    field_key VARCHAR(36) NOT NULL,
    label VARCHAR(80) NOT NULL,
    field_type VARCHAR(16) NOT NULL,
    choices JSON NOT NULL,
    dictionary_code VARCHAR(64) NULL,
    default_value JSON NULL,
    required BOOLEAN NOT NULL DEFAULT FALSE,
    index_slot TINYINT UNSIGNED NULL,
    list_visible BOOLEAN NOT NULL DEFAULT FALSE,
    print_visible BOOLEAN NOT NULL DEFAULT FALSE,
    import_export BOOLEAN NOT NULL DEFAULT FALSE,
    sort_no INT NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY(id),
    UNIQUE KEY uk_cf_key(tenant_id,entity,field_key),
    UNIQUE KEY uk_cf_slot(tenant_id,entity,index_slot),
    CONSTRAINT fk_cf_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    CONSTRAINT ck_cf_slot CHECK (index_slot IS NULL OR index_slot BETWEEN 1 AND 5)
);

ALTER TABLE sales_order
    ADD COLUMN ext_json JSON NOT NULL DEFAULT (JSON_OBJECT()),
    ADD COLUMN cf_idx_1 VARCHAR(128) COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN cf_idx_2 VARCHAR(128) COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN cf_idx_3 VARCHAR(128) COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN cf_idx_4 VARCHAR(128) COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN cf_idx_5 VARCHAR(128) COLLATE utf8mb4_0900_bin NULL,
    ADD KEY idx_sales_order_cf1(tenant_id,cf_idx_1),
    ADD KEY idx_sales_order_cf2(tenant_id,cf_idx_2),
    ADD KEY idx_sales_order_cf3(tenant_id,cf_idx_3),
    ADD KEY idx_sales_order_cf4(tenant_id,cf_idx_4),
    ADD KEY idx_sales_order_cf5(tenant_id,cf_idx_5);

ALTER TABLE sales_order_line
    ADD COLUMN ext_json JSON NOT NULL DEFAULT (JSON_OBJECT()),
    ADD COLUMN cf_idx_1 VARCHAR(128) COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN cf_idx_2 VARCHAR(128) COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN cf_idx_3 VARCHAR(128) COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN cf_idx_4 VARCHAR(128) COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN cf_idx_5 VARCHAR(128) COLLATE utf8mb4_0900_bin NULL,
    ADD KEY idx_sales_order_line_cf1(tenant_id,cf_idx_1),
    ADD KEY idx_sales_order_line_cf2(tenant_id,cf_idx_2),
    ADD KEY idx_sales_order_line_cf3(tenant_id,cf_idx_3),
    ADD KEY idx_sales_order_line_cf4(tenant_id,cf_idx_4),
    ADD KEY idx_sales_order_line_cf5(tenant_id,cf_idx_5);

ALTER TABLE purchase_order
    ADD COLUMN ext_json JSON NOT NULL DEFAULT (JSON_OBJECT()),
    ADD COLUMN cf_idx_1 VARCHAR(128) COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN cf_idx_2 VARCHAR(128) COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN cf_idx_3 VARCHAR(128) COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN cf_idx_4 VARCHAR(128) COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN cf_idx_5 VARCHAR(128) COLLATE utf8mb4_0900_bin NULL,
    ADD KEY idx_purchase_order_cf1(tenant_id,cf_idx_1),
    ADD KEY idx_purchase_order_cf2(tenant_id,cf_idx_2),
    ADD KEY idx_purchase_order_cf3(tenant_id,cf_idx_3),
    ADD KEY idx_purchase_order_cf4(tenant_id,cf_idx_4),
    ADD KEY idx_purchase_order_cf5(tenant_id,cf_idx_5);

ALTER TABLE business_partner
    ADD COLUMN ext_json JSON NOT NULL DEFAULT (JSON_OBJECT()),
    ADD COLUMN cf_idx_1 VARCHAR(128) COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN cf_idx_2 VARCHAR(128) COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN cf_idx_3 VARCHAR(128) COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN cf_idx_4 VARCHAR(128) COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN cf_idx_5 VARCHAR(128) COLLATE utf8mb4_0900_bin NULL,
    ADD KEY idx_business_partner_cf1(tenant_id,cf_idx_1),
    ADD KEY idx_business_partner_cf2(tenant_id,cf_idx_2),
    ADD KEY idx_business_partner_cf3(tenant_id,cf_idx_3),
    ADD KEY idx_business_partner_cf4(tenant_id,cf_idx_4),
    ADD KEY idx_business_partner_cf5(tenant_id,cf_idx_5);

ALTER TABLE sku
    ADD COLUMN ext_json JSON NOT NULL DEFAULT (JSON_OBJECT()),
    ADD COLUMN cf_idx_1 VARCHAR(128) COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN cf_idx_2 VARCHAR(128) COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN cf_idx_3 VARCHAR(128) COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN cf_idx_4 VARCHAR(128) COLLATE utf8mb4_0900_bin NULL,
    ADD COLUMN cf_idx_5 VARCHAR(128) COLLATE utf8mb4_0900_bin NULL,
    ADD KEY idx_sku_cf1(tenant_id,cf_idx_1),
    ADD KEY idx_sku_cf2(tenant_id,cf_idx_2),
    ADD KEY idx_sku_cf3(tenant_id,cf_idx_3),
    ADD KEY idx_sku_cf4(tenant_id,cf_idx_4),
    ADD KEY idx_sku_cf5(tenant_id,cf_idx_5);
