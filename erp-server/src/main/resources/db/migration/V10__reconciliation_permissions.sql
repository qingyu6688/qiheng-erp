-- 对账和可靠任务管理权限。
INSERT INTO sys_permission(id,code,name,perm_type,sort_no) VALUES
(410,'reconciliation:run','发起对账','action',410),
(411,'reconciliation:view','查看对账','action',411),
(412,'reconciliation:resolve','处理对账差异','action',412),
(413,'admin:outbox','管理失败事件','action',413);
