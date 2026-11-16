ALTER TABLE cash_account
    ADD COLUMN default_for_receipt TINYINT(1) NOT NULL DEFAULT 0 COMMENT '默认收款账户',
    ADD COLUMN default_for_payment TINYINT(1) NOT NULL DEFAULT 0 COMMENT '默认付款账户',
    ADD COLUMN default_receipt_key BIGINT UNSIGNED GENERATED ALWAYS AS (IF(default_for_receipt=1,company_id,NULL)) STORED,
    ADD COLUMN default_payment_key BIGINT UNSIGNED GENERATED ALWAYS AS (IF(default_for_payment=1,company_id,NULL)) STORED,
    ADD UNIQUE KEY uk_cash_account_default_receipt (tenant_id,default_receipt_key),
    ADD UNIQUE KEY uk_cash_account_default_payment (tenant_id,default_payment_key),
    ADD CONSTRAINT ck_cash_account_defaults CHECK (
        default_for_receipt IN (0,1) AND default_for_payment IN (0,1)
        AND (status='active' OR (default_for_receipt=0 AND default_for_payment=0))
    );
