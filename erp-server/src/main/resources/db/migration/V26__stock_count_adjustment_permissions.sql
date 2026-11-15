ALTER TABLE stock_count_line
  ADD COLUMN input_unit_cost DECIMAL(24,8) NULL COMMENT '从未入库的盘盈商品人工录入单价，随实盘数审核',
  ADD CONSTRAINT ck_stock_count_input_unit_cost CHECK (input_unit_cost IS NULL OR input_unit_cost >= 0);

UPDATE sys_permission SET name=REPLACE(name,'库存期初','库存调整') WHERE code LIKE 'stock_adjustment:%';

INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
(760,'stock_count:view','查看盘点单','action',760),
(761,'stock_count:create','新增盘点单','action',761),
(762,'stock_count:update','修改盘点单','action',762),
(763,'stock_count:start','开始盘点','action',763),
(764,'stock_count:record_counts','录入实盘数量','action',764),
(765,'stock_count:submit','提交盘点单','action',765),
(766,'stock_count:approve','审核盘点单','action',766),
(767,'stock_count:reject','驳回盘点单','action',767),
(768,'stock_count:post','过账盘点差异','action',768),
(769,'stock_count:void','作废盘点单','action',769);
