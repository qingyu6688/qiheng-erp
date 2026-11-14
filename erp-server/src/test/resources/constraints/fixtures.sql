-- 每个场景在独立的测试事务中载入这组样例，退出时由 Spring 回滚。
INSERT INTO tenant (id,code,name) VALUES
    (70101,'TEST-CONSTRAINTS-1','约束测试一'), (70102,'TEST-CONSTRAINTS-2','约束测试二');
INSERT INTO company (id,tenant_id,code,name) VALUES
    (70201,70101,'A','本公司'), (70202,70101,'B','另一公司'), (70203,70102,'C','其他租户公司');
INSERT INTO warehouse (id,tenant_id,company_id,code,name) VALUES
    (70301,70101,70201,'W1','测试仓库'), (70302,70101,70202,'W2','其他仓库');
INSERT INTO business_partner (id,tenant_id,code,name) VALUES
    (70401,70101,'A','甲客户'), (70402,70101,'B','乙客户');
INSERT INTO cash_account (id,tenant_id,company_id,code,name,account_type,currency) VALUES
    (70501,70101,70201,'C1','本公司现金','cash','CNY'),
    (70502,70101,70201,'C2','本公司银行','bank','CNY'),
    (70503,70101,70202,'C3','其他公司现金','cash','CNY'),
    (70504,70101,70201,'C4','其他币种账户','bank','USD');
INSERT INTO cash_account_balance (id,tenant_id,company_id,cash_account_id,balance) VALUES (70511,70101,70201,70501,10);
INSERT INTO accounting_period (id,tenant_id,company_id,period,start_date,end_date)
VALUES (70601,70101,70201,'202611','2026-11-01','2026-11-30');
INSERT INTO unit (id,tenant_id,name) VALUES (70701,70101,'件'), (70702,70101,'箱');
INSERT INTO product_category (id,tenant_id,code,name,path) VALUES (70801,70101,'TEST','测试商品','/TEST');
INSERT INTO product (id,tenant_id,category_id,code,name,base_unit_id)
VALUES (70901,70101,70801,'P1','商品',70701);
INSERT INTO sku (id,tenant_id,product_id,code,name) VALUES
    (71001,70101,70901,'SKU-A','商品 A'), (71002,70101,70901,'SKU-B','商品 B'), (71003,70101,70901,'SKU-C','商品 C');
INSERT INTO unit_conversion (id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES
    (71101,70101,71001,70701,1,1), (71102,70101,71002,70701,1,1), (71103,70101,71003,70701,1,1);

-- 《业务规则》第 5 节的三行商品和买方运费，保留逐行分摊的税额尾差。
INSERT INTO sales_order (id,tenant_id,company_id,order_no,customer_id,business_date,price_tax_inclusive,
    goods_amount,line_discount_amount,order_discount_amount,fee_amount,total_amount,untaxed_amount,tax_amount)
VALUES (71201,70101,70201,'SO-4',70401,'2026-11-15',1,1320,120,100,30,1130,1000.01,129.99);
INSERT INTO sales_order_line (id,tenant_id,company_id,order_id,line_no,sku_id,warehouse_id,unit_id,
    unit_rate,qty,base_qty,unit_price,amount,line_discount,line_net,order_discount_alloc,receivable_net,tax_rate,untaxed_amount,tax_amount)
VALUES (71301,70101,70201,71201,1,71001,70301,70701,1,4,4,115,460,60,400,33.34,366.66,.13,324.48,42.18),
       (71302,70101,70201,71201,2,71002,70301,70701,1,8,8,55,440,40,400,33.33,366.67,.13,324.49,42.18),
       (71303,70101,70201,71201,3,71003,70301,70701,1,2,2,210,420,20,400,33.33,366.67,.13,324.49,42.18);
INSERT INTO sales_order_fee (id,tenant_id,company_id,order_id,fee_type,bearer,amount,tax_rate,untaxed_amount,tax_amount)
VALUES (71401,70101,70201,71201,'freight','buyer',30,.13,26.55,3.45);
INSERT INTO shipment (id,tenant_id,company_id,shipment_no,customer_id,warehouse_id,business_date)
VALUES (71501,70101,70201,'S1',70401,70301,'2026-11-15');
INSERT INTO shipment_line (id,tenant_id,company_id,shipment_id,line_no,order_line_id,sku_id,unit_id,unit_rate,qty,base_qty)
VALUES (71601,70101,70201,71501,1,71301,71001,70701,1,4,4);
INSERT INTO purchase_order (id,tenant_id,company_id,order_no,supplier_id,business_date,price_tax_inclusive)
VALUES (71701,70101,70201,'PO1',70401,'2026-11-15',1);
INSERT INTO purchase_order_line (id,tenant_id,company_id,order_id,line_no,sku_id,unit_id,unit_rate,qty,base_qty,
    receive_limit_base_qty,unit_price,amount,line_net,payable_net,tax_rate,untaxed_amount,tax_amount)
VALUES (71801,70101,70201,71701,1,71001,70701,1,10,10,12,100,1000,1000,1000,0,1000,0);
INSERT INTO inventory_balance (id,tenant_id,company_id,warehouse_id,sku_id,on_hand_qty,reserved_qty,blocked_qty)
VALUES (71901,70101,70201,70301,71001,10,4,1);
INSERT INTO inventory_cost (id,tenant_id,company_id,warehouse_id,sku_id,qty,total_cost)
VALUES (72001,70101,70201,70301,71001,10,100);
INSERT INTO inventory_reservation (id,tenant_id,company_id,order_line_id,warehouse_id,sku_id,reserved_qty,remaining_qty,expire_at)
VALUES (72101,70101,70201,71301,70301,71001,4,4,'2026-12-15');
INSERT INTO inventory_reservation_change (id,tenant_id,company_id,reservation_id,change_type,qty_delta,source_type,source_id,source_line_id)
VALUES (72201,70101,70201,72101,'reserve',4,'sales_order',71201,71301);
INSERT INTO inventory_movement (id,tenant_id,company_id,warehouse_id,sku_id,movement_type,on_hand_delta,
    source_type,source_id,source_line_id,business_date,period_id,posted_at)
VALUES (72301,70101,70201,70301,71001,'sales_out',-4,'shipment',71501,71601,'2026-11-15',70601,'2026-11-15 10:00:00');
INSERT INTO receivable (id,tenant_id,company_id,customer_id,kind,source_type,source_id,source_line_id,
    amount,untaxed_amount,tax_amount,business_date,period_id)
VALUES (72401,70101,70201,70401,'charge','shipment',71501,71601,100,100,0,'2026-11-15',70601);
INSERT INTO receipt (id,tenant_id,company_id,receipt_no,receipt_type,partner_id,cash_account_id,amount,business_date)
VALUES (72501,70101,70201,'R1','customer',70401,70501,100,'2026-11-15');
INSERT INTO refund_request (id,tenant_id,company_id,refund_no,customer_id,original_receipt_id,cash_account_id,
    amount,business_date,request_key,reason,created_by)
VALUES (72601,70101,70201,'RF1',70401,72501,70501,20,'2026-11-15','RF1','测试退款',1);
INSERT INTO partner_open_item (id,tenant_id,company_id,partner_id,item_type,source_id,direction,amount,open_amount,aging_date)
VALUES (72701,70101,70201,70401,'receivable',72401,'D',100,100,'2026-11-15'),
       (72702,70101,70201,70401,'receipt',72501,'C',100,100,'2026-11-15'),
       (72703,70101,70201,70402,'receivable',72402,'D',100,100,'2026-11-15'),
       (72704,70101,70201,70401,'receivable',72403,'D',100,100,'2026-11-15');
INSERT INTO settlement_allocation (id,tenant_id,company_id,partner_id,debit_item_id,credit_item_id,amount,allocation_type)
VALUES (72801,70101,70201,70401,72701,72702,10,'manual');
INSERT INTO account_transfer (id,tenant_id,company_id,transfer_no,from_account_id,to_account_id,amount,business_date)
VALUES (72901,70101,70201,'AT1',70501,70502,10,'2026-11-15');
INSERT INTO journal (id,tenant_id,company_id,period_id,business_date,source_type,source_id,total_debit,total_credit,posted_at)
VALUES (73001,70101,70201,70601,'2026-11-15','receipt',72501,100,100,'2026-11-15 10:00:00');
INSERT INTO document_change (id,tenant_id,company_id,change_no,document_type,document_id,
    from_revision,to_revision,requires_approval,before_snapshot,after_snapshot,reason)
VALUES (73101,70101,70201,'DC1','sales_order',71201,1,2,1,'{}','{}','改量');
INSERT INTO document_reversal (id,tenant_id,company_id,reversal_no,source_type,source_id,business_date,reason,created_by)
VALUES (73201,70101,70201,'RV1','receipt',72501,'2026-11-15','录入有误',1);
INSERT INTO document_sequence (id,tenant_id,company_id,document_type,period,next_value)
VALUES (73301,70101,70201,'sales_order','202611',999999);
