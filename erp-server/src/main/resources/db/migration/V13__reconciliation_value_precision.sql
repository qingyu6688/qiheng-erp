-- 金额可有22位整数，数量可有18位整数，对账统一保留6位小数并容纳跨行合计。
ALTER TABLE reconciliation_diff
  MODIFY COLUMN expected_value DECIMAL(38,6) NOT NULL COMMENT '由流水重算的值，支持金额及数量合计',
  MODIFY COLUMN actual_value DECIMAL(38,6) NOT NULL COMMENT '余额或投影中的值';
