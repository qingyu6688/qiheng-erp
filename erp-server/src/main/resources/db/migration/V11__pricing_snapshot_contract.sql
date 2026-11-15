-- V2 价格快照保留录价口径，含税应收/应付及总额单独计税。
-- 旧订单头默认 pricing_version=1，旧明细及费用默认含税快照，历史勾稽保持原定义。
ALTER TABLE sales_order
  ADD COLUMN pricing_version TINYINT UNSIGNED NOT NULL DEFAULT 1 AFTER price_tax_inclusive,
  DROP CHECK ck_sales_order_total,
  ADD CONSTRAINT ck_sales_order_pricing_version CHECK (pricing_version IN (1,2)),
  ADD CONSTRAINT ck_sales_order_total CHECK (
    total_amount = goods_amount-line_discount_amount-order_discount_amount+fee_amount
      + IF(pricing_version=2 AND price_tax_inclusive=0,tax_amount,0));

ALTER TABLE purchase_order
  ADD COLUMN pricing_version TINYINT UNSIGNED NOT NULL DEFAULT 1 AFTER price_tax_inclusive,
  DROP CHECK ck_purchase_order_total,
  ADD CONSTRAINT ck_purchase_order_pricing_version CHECK (pricing_version IN (1,2)),
  ADD CONSTRAINT ck_purchase_order_total CHECK (
    total_amount = goods_amount-line_discount_amount-order_discount_amount+fee_amount
      + IF(pricing_version=2 AND price_tax_inclusive=0,tax_amount,0));

ALTER TABLE sales_order_line
  ADD COLUMN price_tax_inclusive TINYINT(1) NOT NULL DEFAULT 1 AFTER unit_price,
  DROP CHECK ck_sales_order_line_net,
  ADD CONSTRAINT ck_sales_order_line_price_flag CHECK (price_tax_inclusive IN (0,1)),
  ADD CONSTRAINT ck_sales_order_line_net CHECK (
    line_net=amount-line_discount AND receivable_net>=0
      AND receivable_net=line_net-order_discount_alloc+IF(price_tax_inclusive=0,tax_amount,0));

ALTER TABLE purchase_order_line
  ADD COLUMN price_tax_inclusive TINYINT(1) NOT NULL DEFAULT 1 AFTER unit_price,
  DROP CHECK ck_purchase_order_line_net,
  ADD CONSTRAINT ck_purchase_order_line_price_flag CHECK (price_tax_inclusive IN (0,1)),
  ADD CONSTRAINT ck_purchase_order_line_net CHECK (
    line_net=amount-line_discount AND payable_net>=0
      AND payable_net=line_net-order_discount_alloc+IF(price_tax_inclusive=0,tax_amount,0));

ALTER TABLE sales_order_fee
  ADD COLUMN price_tax_inclusive TINYINT(1) NOT NULL DEFAULT 1 AFTER amount,
  ADD COLUMN total_amount DECIMAL(24,2) GENERATED ALWAYS AS
      (amount+IF(price_tax_inclusive=0,tax_amount,0)) STORED,
  DROP CHECK ck_sales_order_fee_amount,
  ADD CONSTRAINT ck_sales_order_fee_price_flag CHECK (price_tax_inclusive IN (0,1)),
  ADD CONSTRAINT ck_sales_order_fee_amount CHECK (
    amount>0 AND tax_amount>=0 AND tax_rate>=0 AND tax_rate<1
      AND untaxed_amount+tax_amount=total_amount);

ALTER TABLE purchase_order_fee
  ADD COLUMN price_tax_inclusive TINYINT(1) NOT NULL DEFAULT 1 AFTER amount,
  ADD COLUMN total_amount DECIMAL(24,2) GENERATED ALWAYS AS
      (amount+IF(price_tax_inclusive=0,tax_amount,0)) STORED,
  DROP CHECK ck_purchase_order_fee_amount,
  ADD CONSTRAINT ck_purchase_order_fee_price_flag CHECK (price_tax_inclusive IN (0,1)),
  ADD CONSTRAINT ck_purchase_order_fee_amount CHECK (
    amount>0 AND tax_amount>=0 AND tax_rate>=0 AND tax_rate<1
      AND untaxed_amount+tax_amount=total_amount);
