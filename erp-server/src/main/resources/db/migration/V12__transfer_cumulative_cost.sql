-- 调拨在途成本按实际累计入库和已处理差异扣减，保留每笔分币结果。
ALTER TABLE stock_transfer_line
  ADD COLUMN received_cost_amount DECIMAL(24,2) NOT NULL DEFAULT 0 COMMENT '已入库的累计实际成本' AFTER cost_amount,
  ADD COLUMN diff_cost_amount DECIMAL(24,2) NOT NULL DEFAULT 0 COMMENT '已处理调拨差异的累计实际成本' AFTER received_cost_amount,
  ADD CONSTRAINT ck_stock_transfer_line_cumulative_cost CHECK (
    received_cost_amount >= 0 AND diff_cost_amount >= 0
    AND received_cost_amount + diff_cost_amount <= IFNULL(cost_amount, 0)),
  ADD CONSTRAINT ck_stock_transfer_line_received_cost CHECK (received_base_qty > 0 OR received_cost_amount = 0),
  ADD CONSTRAINT ck_stock_transfer_line_diff_cost CHECK (diff_status = 'resolved' OR diff_cost_amount = 0);
