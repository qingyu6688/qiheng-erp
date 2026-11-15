-- 仅在受控运维连接执行，不要给应用账号全局 PROCESS、系统库写权限。
SELECT NOW(3) AS checked_at;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Threads_running','Innodb_deadlocks','Innodb_row_lock_current_waits','Innodb_row_lock_time','Aborted_connects');
SELECT trx_started,trx_state,trx_wait_started FROM information_schema.innodb_trx ORDER BY trx_started LIMIT 20;
-- 下列监控摘要不包含查询正文、SQL 参数、密码或用户业务数据。
SELECT EVENT_NAME,COUNT_STAR,SUM_TIMER_WAIT,MAX_TIMER_WAIT
FROM performance_schema.events_waits_summary_global_by_event_name
WHERE EVENT_NAME LIKE 'wait/lock/%' ORDER BY SUM_TIMER_WAIT DESC LIMIT 10;
