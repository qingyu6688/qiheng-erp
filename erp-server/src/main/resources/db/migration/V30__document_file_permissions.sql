CREATE TABLE file_ticket (
  id BIGINT UNSIGNED NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  company_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  purpose VARCHAR(32) NOT NULL,
  resource_id BIGINT UNSIGNED NOT NULL,
  token_hash CHAR(64) NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY(id),
  UNIQUE KEY uk_file_ticket_token(token_hash),
  KEY idx_file_ticket_expiry(expires_at),
  KEY idx_file_ticket_actor(tenant_id,company_id,user_id,purpose,resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私有文件五分钟下载票据，仅保存摘要';

CREATE TABLE object_cleanup (
  id BIGINT UNSIGNED NOT NULL,
  storage_key VARCHAR(255) NOT NULL,
  reason VARCHAR(100) NOT NULL,
  attempts INT UNSIGNED NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY(id),
  UNIQUE KEY uk_object_cleanup_key(storage_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数据库关联失败后的对象清理补偿';

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
(880,'sales_order:print','打印销售订单','action',880),
(881,'shipment:print','打印发货单','action',881),
(882,'receipt:print','打印收款单','action',882),
(883,'sales_return:print','打印销售退货','action',883),
(884,'refund_request:print','打印退款申请','action',884),
(885,'purchase_requisition:print','打印请购单','action',885),
(886,'purchase_order:print','打印采购订单','action',886),
(887,'goods_receipt:print','打印采购收货','action',887),
(888,'purchase_return:print','打印采购退货','action',888),
(889,'payment:print','打印付款单','action',889),
(890,'account_transfer:print','打印账户互转','action',890),
(891,'stock_adjustment:print','打印库存调整','action',891),
(892,'opening_entry:print','打印往来资金期初','action',892),
(893,'stock_count:print','打印盘点单','action',893),
(894,'stock_transfer:print','打印调拨单','action',894),
(895,'reversal:print','打印冲销单','action',895),
(896,'invoice_registration:print','打印开票登记','action',896);
