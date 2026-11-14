-- 每条语句只破坏指定约束。补充项覆盖文档里有规则但没有独立历史编号的两条金额约束。

-- N01 ck_inventory_balance_available 预留超过可用量
UPDATE inventory_balance SET reserved_qty=10 WHERE id=71901;

-- N03 uk_inventory_reservation_active 同一订单行重复有效预留
INSERT INTO inventory_reservation(id,tenant_id,company_id,order_line_id,warehouse_id,sku_id,reserved_qty,remaining_qty,expire_at) VALUES(1,70101,70201,71301,70301,71001,4,4,'2026-12-15');

-- N04 ck_inventory_reservation_state 已释放的预留仍有余量
UPDATE inventory_reservation SET status='released' WHERE id=72101;

-- N05 ck_sales_order_line_progress 待发与已发合计超量
UPDATE sales_order_line SET pending_ship_base_qty=3,shipped_base_qty=2 WHERE id=71301;

-- N06 ck_sales_order_line_net 销售行优惠勾稽不平
UPDATE sales_order_line SET line_net=line_net+1 WHERE id=71301;

-- N07 ck_sales_order_tax 订单税额汇总不平
UPDATE sales_order SET tax_amount=130 WHERE id=71201;

-- N08 uk_sales_order_no 同公司重号
INSERT INTO sales_order(id,tenant_id,company_id,order_no,customer_id,business_date,price_tax_inclusive) VALUES(1,70101,70201,'SO-4',70401,'2026-11-15',1);

-- N09 fk_shipment_wh 发货单引用另一公司的仓库
UPDATE shipment SET warehouse_id=70302 WHERE id=71501;

-- N10 fk_sales_order_customer 另一租户订单引用当前租户客户
INSERT INTO sales_order(id,tenant_id,company_id,order_no,customer_id,business_date,price_tax_inclusive) VALUES(1,70102,70203,'SO-X',70401,'2026-11-15',1);

-- N11 ck_inventory_cost_zero 库存清零仍留有成本
UPDATE inventory_cost SET qty=0 WHERE id=72001;

-- N12 ck_cash_account_balance_nonneg 资金账户透支
UPDATE cash_account_balance SET balance=-1 WHERE id=70511;

-- N13a fk_settlement_allocation_debit 借方项目属于其他客户
UPDATE settlement_allocation SET debit_item_id=72703 WHERE id=72801;

-- N13b fk_settlement_allocation_credit 贷方位置放入借方项目
UPDATE settlement_allocation SET credit_item_id=72704 WHERE id=72801;

-- N13c ck_partner_open_item_amount 核销后剩余额为负
UPDATE partner_open_item SET open_amount=-1 WHERE id=72701;

-- N14 ck_receipt_refund 已退及待退金额超过实收
UPDATE receipt SET refunded_amount=60,refund_pending_amount=50 WHERE id=72501;

-- N15 fk_refund_request_receipt 退款使用其他客户原收款
UPDATE refund_request SET customer_id=70402 WHERE id=72601;

-- N16 ck_refund_request_reviewer 退款经办审核同一人
UPDATE refund_request SET reviewed_by=created_by WHERE id=72601;

-- N17a fk_account_transfer_from 资金从其他公司的账户转出
UPDATE account_transfer SET from_account_id=70503 WHERE id=72901;

-- N17b fk_account_transfer_to 资金转入不同币种账户
UPDATE account_transfer SET to_account_id=70504 WHERE id=72901;

-- N18 ck_journal_balanced 凭证借贷失衡
UPDATE journal SET total_credit=90 WHERE id=73001;

-- N19 uk_journal_source 同一来源重复记账
INSERT INTO journal(id,tenant_id,company_id,period_id,business_date,source_type,source_id,total_debit,total_credit,posted_at) VALUES(1,70101,70201,70601,'2026-11-15','receipt',72501,100,100,NOW());

-- N20 uk_receivable_source 同一签收行重复确认应收
INSERT INTO receivable(id,tenant_id,company_id,customer_id,kind,source_type,source_id,source_line_id,amount,untaxed_amount,tax_amount,business_date,period_id) VALUES(1,70101,70201,70401,'charge','shipment',71501,71601,100,100,0,'2026-11-15',70601);

-- N21 uk_inventory_movement_source 同一发货行重复扣库存
INSERT INTO inventory_movement(id,tenant_id,company_id,warehouse_id,sku_id,movement_type,on_hand_delta,source_type,source_id,source_line_id,business_date,period_id,posted_at) VALUES(1,70101,70201,70301,71001,'sales_out',-4,'shipment',71501,71601,'2026-11-15',70601,NOW());

-- N22 uk_inventory_reservation_change 同一个预留动作重复
INSERT INTO inventory_reservation_change(id,tenant_id,company_id,reservation_id,change_type,qty_delta,source_type,source_id,source_line_id) VALUES(1,70101,70201,72101,'reserve',4,'sales_order',71201,71301);

-- N23 uk_document_change_pending 已有待审变更再次发起
INSERT INTO document_change(id,tenant_id,company_id,change_no,document_type,document_id,from_revision,to_revision,requires_approval,before_snapshot,after_snapshot,reason) VALUES(1,70101,70201,'DC2','sales_order',71201,1,2,1,'{}','{}','改价');

-- N24 ck_shipment_line_return 超过已签收数量申请退货
UPDATE shipment_line SET pending_return_base_qty=1 WHERE id=71601;

-- N26 ck_accounting_period_closed 关账缺少关账时间
UPDATE accounting_period SET status='closed' WHERE id=70601;

-- N27 uk_unit_conversion_base 同一 SKU 配置两个基础单位
INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(1,70101,71001,70702,1,1);

-- N28 fk_inventory_balance_wh 库存余额引用跨公司仓库
UPDATE inventory_balance SET warehouse_id=70302 WHERE id=71901;

-- N29b fk_cash_account_balance_account 账户余额引用跨公司资金账户
UPDATE cash_account_balance SET cash_account_id=70503 WHERE id=70511;

-- N30 ck_purchase_order_line_progress 采购过量收货
UPDATE purchase_order_line SET received_base_qty=13 WHERE id=71801;

-- N31 ck_document_reversal_approver 冲销经办审核同一人
UPDATE document_reversal SET approved_by=created_by WHERE id=73201;

-- N32 ck_settlement_allocation_revoked 撤销核销缺少原因
UPDATE settlement_allocation SET status='revoked',revoked_at=NOW() WHERE id=72801;

-- N33 ck_sales_order_line_recognized 累计确认应收超过行金额
UPDATE sales_order_line SET recognized_amount=367 WHERE id=71301;

-- N34 ck_shipment_line_sign 签收及差异未覆盖整次发货
UPDATE shipment_line SET signed_base_qty=2 WHERE id=71601;

-- N35 ck_shipment_line_diff 签收差异缺少处理方式
UPDATE shipment_line SET signed_base_qty=3,diff_base_qty=1 WHERE id=71601;

-- N36 ck_shipment_signed 签收缺少日期和期间
UPDATE shipment SET status='signed',posted_at=NOW(),period_id=70601 WHERE id=71501;

-- N37 ck_shipment_sign_date 签收早于发货
UPDATE shipment SET sign_date='2026-11-14' WHERE id=71501;

-- N38 ck_sales_order_line_signed 累计签收超过已发数量
UPDATE sales_order_line SET signed_base_qty=1 WHERE id=71301;

-- 补充N1 ck_sales_order_line_tax 销售行税额勾稽不平
UPDATE sales_order_line SET tax_amount=42.19 WHERE id=71301;

-- 补充N2 ck_sales_order_total 订单总金额与优惠、运费不符
UPDATE sales_order SET fee_amount=29 WHERE id=71201;
