-- 报表与导出各自授权，任务记录提交时与成品的服务端数据范围。
INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
(820,'report:inventory_summary','查看库存汇总','action',820),(821,'export:inventory_summary','导出库存汇总','action',821),
(822,'report:inventory_flow','查看库存收发存','action',822),(823,'export:inventory_flow','导出库存收发存','action',823),
(824,'report:unsigned_shipments','查看发出未签收','action',824),(825,'export:unsigned_shipments','导出发出未签收','action',825),
(826,'report:sales_summary','查看销售汇总','action',826),(827,'export:sales_summary','导出销售汇总','action',827),
(828,'report:purchase_summary','查看采购汇总','action',828),(829,'export:purchase_summary','导出采购汇总','action',829),
(830,'report:receivable_aging','查看应收账龄','action',830),(831,'export:receivable_aging','导出应收账龄','action',831),
(832,'report:payable_aging','查看应付账龄','action',832),(833,'export:payable_aging','导出应付账龄','action',833),
(834,'report:partner_statement','查看往来对账单','action',834),(835,'export:partner_statement','导出往来对账单','action',835),
(836,'report:cash_journal','查看资金日记账','action',836),(837,'export:cash_journal','导出资金日记账','action',837),
(838,'report:gross_margin','查看毛利报表','action',838),(839,'export:gross_margin','导出毛利报表','action',839);

ALTER TABLE export_job
  ADD COLUMN request_scope JSON NULL COMMENT '提交时服务端捕获的仓库及成本权限',
  ADD COLUMN result_scope JSON NULL COMMENT '成品实际采用的数据范围',
  ADD COLUMN attempts INT UNSIGNED NOT NULL DEFAULT 0,
  ADD COLUMN error_message VARCHAR(500) NULL,
  ADD COLUMN checksum CHAR(64) NULL,
  ADD COLUMN as_of DATETIME(3) NULL,
  ADD COLUMN object_deleted_at DATETIME(3) NULL,
  ADD KEY idx_export_job_lease(status,lease_until);

UPDATE export_job SET status='failed',error_message='旧导出任务没有可信数据范围，请重新导出',finished_at=UTC_TIMESTAMP(3),lease_owner=NULL,lease_until=NULL
WHERE request_scope IS NULL AND status IN ('pending','running');
