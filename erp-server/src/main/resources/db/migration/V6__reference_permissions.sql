-- 基础资料与期间操作权限。
INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
  (300,'partner:view','查看往来单位','action',300),
  (301,'partner:create','新增往来单位','action',301),
  (302,'partner:update','修改往来单位','action',302),
  (303,'cash_account:view','查看结算账户','action',303),
  (304,'cash_account:create','新增结算账户','action',304),
  (305,'cash_account:update','修改结算账户','action',305),
  (306,'income_expense_item:view','查看收支项目','action',306),
  (307,'income_expense_item:create','新增收支项目','action',307),
  (308,'income_expense_item:update','修改收支项目','action',308),
  (309,'partner_contact:view','查看联系方式','field',309),
  (310,'partner_contact:create','新增联系人','action',310),
  (311,'partner_contact:update','修改联系人','action',311),
  (312,'partner_contact:delete','删除联系人','action',312),
  (313,'cash_account_no:view','查看完整银行账号','field',313),
  (314,'period:view','查看会计期间','action',314),
  (315,'period:open','开启会计期间','action',315);
