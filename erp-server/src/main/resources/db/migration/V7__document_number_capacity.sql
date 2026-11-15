-- 公司编码最长 16 字符，既定单号格式最长为 33 字符。
-- 为业务单号保留后续扩展空间，税号和外部银行流水编号沿用各自定义。
ALTER TABLE sales_order MODIFY COLUMN order_no VARCHAR(64) NOT NULL COMMENT '单号（R8-08）';
ALTER TABLE shipment MODIFY COLUMN shipment_no VARCHAR(64) NOT NULL;
ALTER TABLE sales_return MODIFY COLUMN return_no VARCHAR(64) NOT NULL;
ALTER TABLE purchase_requisition MODIFY COLUMN requisition_no VARCHAR(64) NOT NULL;
ALTER TABLE purchase_order MODIFY COLUMN order_no VARCHAR(64) NOT NULL;
ALTER TABLE goods_receipt MODIFY COLUMN receipt_no VARCHAR(64) NOT NULL;
ALTER TABLE purchase_return MODIFY COLUMN return_no VARCHAR(64) NOT NULL;
ALTER TABLE stock_transfer MODIFY COLUMN transfer_no VARCHAR(64) NOT NULL;
ALTER TABLE stock_count MODIFY COLUMN count_no VARCHAR(64) NOT NULL;
ALTER TABLE stock_adjustment MODIFY COLUMN adjust_no VARCHAR(64) NOT NULL;
ALTER TABLE opening_entry MODIFY COLUMN entry_no VARCHAR(64) NOT NULL;
ALTER TABLE receipt MODIFY COLUMN receipt_no VARCHAR(64) NOT NULL;
ALTER TABLE payment MODIFY COLUMN payment_no VARCHAR(64) NOT NULL;
ALTER TABLE refund_request MODIFY COLUMN refund_no VARCHAR(64) NOT NULL;
ALTER TABLE account_transfer MODIFY COLUMN transfer_no VARCHAR(64) NOT NULL;
ALTER TABLE document_change MODIFY COLUMN change_no VARCHAR(64) NOT NULL;
ALTER TABLE document_reversal MODIFY COLUMN reversal_no VARCHAR(64) NOT NULL;
