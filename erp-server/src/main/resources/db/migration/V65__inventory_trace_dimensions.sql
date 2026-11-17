ALTER TABLE sku ADD COLUMN batch_managed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN serial_managed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN shelf_life_days INT UNSIGNED NOT NULL DEFAULT 0,
    ADD COLUMN near_expiry_days INT UNSIGNED NOT NULL DEFAULT 0,
    ADD COLUMN issue_strategy VARCHAR(12) NOT NULL DEFAULT 'fifo',
    ADD CONSTRAINT ck_sku_issue_strategy CHECK (issue_strategy IN ('fifo','fefo','manual'));

CREATE TABLE inv_batch (
    id BIGINT UNSIGNED NOT NULL, tenant_id BIGINT UNSIGNED NOT NULL, company_id BIGINT UNSIGNED NOT NULL,
    sku_id BIGINT UNSIGNED NOT NULL, batch_no VARCHAR(64) NOT NULL,
    production_date DATE, expiry_date DATE, supplier_id BIGINT UNSIGNED NULL,
    source_type VARCHAR(32) NOT NULL, source_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'normal', created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY(id), UNIQUE KEY uk_inv_batch_no(tenant_id,company_id,sku_id,batch_no),
    UNIQUE KEY uk_inv_batch_scope(tenant_id,company_id,id), KEY idx_inv_batch_expiry(tenant_id,company_id,status,expiry_date,id),
    CONSTRAINT fk_inv_batch_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),
    CONSTRAINT fk_inv_batch_sku FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id),
    CONSTRAINT ck_inv_batch_status CHECK(status IN('normal','frozen','expired')),
    CONSTRAINT ck_inv_batch_dates CHECK(production_date IS NULL OR expiry_date IS NULL OR expiry_date>=production_date)
);

CREATE TABLE inv_serial (
    id BIGINT UNSIGNED NOT NULL, tenant_id BIGINT UNSIGNED NOT NULL, company_id BIGINT UNSIGNED NOT NULL,
    sku_id BIGINT UNSIGNED NOT NULL, serial_no VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
    batch_id BIGINT UNSIGNED NULL, warehouse_id BIGINT UNSIGNED NOT NULL, location_key VARCHAR(64) NOT NULL DEFAULT '',
    status VARCHAR(20) NOT NULL, last_movement_id BIGINT UNSIGNED NOT NULL, version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY(id), UNIQUE KEY uk_inv_serial_no(tenant_id,company_id,sku_id,serial_no),
    UNIQUE KEY uk_inv_serial_scope(tenant_id,company_id,id),
    KEY idx_inv_serial_pick(tenant_id,company_id,warehouse_id,sku_id,batch_id,location_key,status,id),
    CONSTRAINT fk_inv_serial_warehouse FOREIGN KEY(tenant_id,company_id,warehouse_id) REFERENCES warehouse(tenant_id,company_id,id),
    CONSTRAINT fk_inv_serial_sku FOREIGN KEY(tenant_id,sku_id) REFERENCES sku(tenant_id,id),
    CONSTRAINT fk_inv_serial_batch FOREIGN KEY(tenant_id,company_id,batch_id) REFERENCES inv_batch(tenant_id,company_id,id),
    CONSTRAINT ck_inv_serial_status CHECK(status IN('in_stock','quality_hold','reserved','shipped','returned','scrapped','in_transit','reversed'))
);

CREATE TABLE inv_movement_serial (
    tenant_id BIGINT UNSIGNED NOT NULL, company_id BIGINT UNSIGNED NOT NULL,
    movement_id BIGINT UNSIGNED NOT NULL, serial_id BIGINT UNSIGNED NOT NULL,
    prior_status VARCHAR(20), current_status VARCHAR(20) NOT NULL, prior_movement_id BIGINT UNSIGNED NULL,
    PRIMARY KEY(movement_id,serial_id), KEY idx_inv_serial_trace(tenant_id,company_id,serial_id,movement_id),
    CONSTRAINT fk_inv_mov_serial_movement FOREIGN KEY(movement_id) REFERENCES inventory_movement(id),
    CONSTRAINT fk_inv_mov_serial_serial FOREIGN KEY(tenant_id,company_id,serial_id) REFERENCES inv_serial(tenant_id,company_id,id)
);

CREATE TABLE inv_block_detail (
    balance_id BIGINT UNSIGNED NOT NULL, tenant_id BIGINT UNSIGNED NOT NULL, company_id BIGINT UNSIGNED NOT NULL,
    reason VARCHAR(24) NOT NULL, source_type VARCHAR(32) NOT NULL, source_id BIGINT UNSIGNED NOT NULL,
    source_line_id BIGINT UNSIGNED NOT NULL, qty DECIMAL(24,6) NOT NULL,
    PRIMARY KEY(balance_id,reason,source_type,source_id,source_line_id), KEY idx_inv_block_scope(tenant_id,company_id,balance_id),
    CONSTRAINT fk_inv_block_balance FOREIGN KEY(balance_id) REFERENCES inventory_balance(id) ON DELETE CASCADE,
    CONSTRAINT ck_inv_block_qty CHECK(qty>=0)
);

INSERT INTO inv_block_detail(balance_id,tenant_id,company_id,reason,source_type,source_id,source_line_id,qty)
SELECT id,tenant_id,company_id,'legacy','migration',0,0,blocked_qty FROM inventory_balance WHERE blocked_qty>0;

CREATE TABLE inv_shipment_pick (
    tenant_id BIGINT UNSIGNED NOT NULL, company_id BIGINT UNSIGNED NOT NULL,
    shipment_line_id BIGINT UNSIGNED NOT NULL, warehouse_id BIGINT UNSIGNED NOT NULL, sku_id BIGINT UNSIGNED NOT NULL,
    batch_id BIGINT UNSIGNED NULL, location_key VARCHAR(64) NOT NULL, lot_key VARCHAR(64) NOT NULL,
    qty DECIMAL(24,6) NOT NULL, serial_ids JSON NOT NULL,
    PRIMARY KEY(tenant_id,company_id,shipment_line_id),
    KEY idx_inv_shipment_pick_qty(tenant_id,company_id,warehouse_id,sku_id,location_key,lot_key),
    CONSTRAINT fk_inv_pick_line FOREIGN KEY(shipment_line_id) REFERENCES shipment_line(id) ON DELETE CASCADE,
    CONSTRAINT ck_inv_pick_qty CHECK(qty>0)
);

CREATE TABLE doc_line_serial (
    tenant_id BIGINT UNSIGNED NOT NULL, company_id BIGINT UNSIGNED NOT NULL,
    doc_type VARCHAR(32) NOT NULL, line_id BIGINT UNSIGNED NOT NULL, serial_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY(tenant_id,company_id,doc_type,line_id,serial_id),
    CONSTRAINT fk_inv_doc_line_serial FOREIGN KEY(tenant_id,company_id,serial_id) REFERENCES inv_serial(tenant_id,company_id,id)
);

ALTER TABLE inventory_movement ADD KEY idx_inv_batch_movement(tenant_id,company_id,lot_key,id);
