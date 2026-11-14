-- P01 可用量充足时条件更新命中一行
UPDATE inventory_balance SET reserved_qty=reserved_qty+5 WHERE id=71901 AND on_hand_qty-reserved_qty-blocked_qty>=5;
SELECT ROW_COUNT()=1 AND reserved_qty=9 FROM inventory_balance WHERE id=71901;

-- P02 缺货时条件更新未命中，预留量不变
UPDATE inventory_balance SET reserved_qty=reserved_qty+6 WHERE id=71901 AND on_hand_qty-reserved_qty-blocked_qty>=6;
SELECT ROW_COUNT()=0 AND reserved_qty=4 FROM inventory_balance WHERE id=71901;

-- P03 释放后允许同一行重新预留
UPDATE inventory_reservation SET status='released',remaining_qty=0 WHERE id=72101;
INSERT INTO inventory_reservation(id,tenant_id,company_id,order_line_id,warehouse_id,sku_id,reserved_qty,remaining_qty,expire_at)
VALUES(1,70101,70201,71301,70301,71001,4,4,'2026-12-15');
SELECT COUNT(*)=1 FROM inventory_reservation WHERE order_line_id=71301 AND status='active';

-- P06 允许按审批时冻结的数量上限收货
UPDATE purchase_order_line SET received_base_qty=12 WHERE id=71801;
SELECT received_base_qty=receive_limit_base_qty FROM purchase_order_line WHERE id=71801;

-- P07 已驳回的冲销单允许再次申请
UPDATE document_reversal SET status='rejected' WHERE id=73201;
INSERT INTO document_reversal(id,tenant_id,company_id,reversal_no,source_type,source_id,business_date,reason)
VALUES(1,70101,70201,'RV2','receipt',72501,'2026-11-15','重新申请');
SELECT COUNT(*)=1 FROM document_reversal WHERE source_id=72501 AND active_source_key IS NOT NULL;

-- P08 记录关账时间后可关闭期间
UPDATE accounting_period SET status='closed',closed_at=CURRENT_TIMESTAMP,closed_by=1 WHERE id=70601;
SELECT status='closed' AND closed_at IS NOT NULL FROM accounting_period WHERE id=70601;

-- P09 顺序号能取到最后一个，下一次写入受约束保护
UPDATE document_sequence SET next_value=next_value+1 WHERE id=73301;
SELECT next_value=1000000 FROM document_sequence WHERE id=73301;

-- P11 有差异的整次签收可以保存
UPDATE shipment_line SET signed_base_qty=3,diff_base_qty=1,diff_disposition='returned' WHERE id=71601;
SELECT signed_base_qty+diff_base_qty=base_qty AND diff_disposition='returned' FROM shipment_line WHERE id=71601;

-- 补充P1 验证 SO-4 三条商品行和买方运费含税、未税和税额的实际写入
SELECT o.total_amount=1130 AND o.total_amount=l.net+f.amount AND o.tax_amount=l.tax+f.tax_amount
 AND o.untaxed_amount=l.untaxed+f.untaxed_amount AND o.tax_amount=129.99 AND l.discount=o.order_discount_amount
 FROM sales_order o JOIN (SELECT order_id,SUM(receivable_net) net,SUM(tax_amount) tax,
 SUM(untaxed_amount) untaxed,SUM(order_discount_alloc) discount FROM sales_order_line GROUP BY order_id) l
 ON o.id=l.order_id JOIN sales_order_fee f ON o.id=f.order_id WHERE o.id=71201;

-- 补充P2 空库存余额与零成本可以同时结清
UPDATE inventory_cost SET qty=0,total_cost=0 WHERE id=72001;
SELECT qty=0 AND total_cost=0 FROM inventory_cost WHERE id=72001;

-- 补充P3 销售拒收退回在库且冻结
INSERT INTO inventory_movement(id,tenant_id,company_id,warehouse_id,sku_id,movement_type,on_hand_delta,blocked_delta,
    source_type,source_id,source_line_id,business_date,period_id,posted_at)
VALUES(1,70101,70201,70301,71001,'sales_reject_in',1,1,'shipment',71501,71601,'2026-11-15',70601,NOW());
SELECT on_hand_delta=blocked_delta AND on_hand_delta=1 FROM inventory_movement WHERE id=1;

-- 补充P4 发货行签收数量作为退货上限
UPDATE shipment_line SET signed_base_qty=3,diff_base_qty=1,diff_disposition='lost',returned_base_qty=2,pending_return_base_qty=1 WHERE id=71601;
SELECT returned_base_qty+pending_return_base_qty=signed_base_qty FROM shipment_line WHERE id=71601;

-- 补充P5 已过账与签收日期、期间分别保存
UPDATE shipment SET status='signed',period_id=70601,posted_at=NOW(),sign_date='2026-11-16',sign_period_id=70601,signed_at=NOW() WHERE id=71501;
SELECT status='signed' AND sign_date>business_date FROM shipment WHERE id=71501;
