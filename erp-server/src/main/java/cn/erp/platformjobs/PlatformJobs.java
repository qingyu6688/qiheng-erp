package cn.erp.platformjobs;

import cn.erp.documents.*;
import cn.erp.exporting.ExportJobs;
import cn.erp.notify.*;
import cn.erp.shared.ManagedJobs;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 组合型维护动作独立放置，业务模块不互相引用工作台、消息和运维实现。 */
@Component
class PlatformJobs {
    private final JdbcTemplate jdbc;
    PlatformJobs(ManagedJobs jobs,MessageService messages,DeliveryService delivery,ObjectCompensation compensation,ExportJobs exports,ObjectFiles files,JdbcTemplate jdbc) {
        this.jdbc=jdbc;
        jobs.register("notify.todo.refresh",messages::refreshTenant);
        jobs.register("notify.dispatch",tenant->{for(int i=0;i<20&&delivery.processNext(tenant);i++) { /* 每轮限制批量，给其他企业留出执行资源。 */ }});
        jobs.register("files.cleanup",tenant->{compensation.retry(tenant);for(String key:exports.expire(tenant)) {files.delete(key);exports.removed(key);}});
        jobs.register("platform.cleanup",this::cleanup);
    }
    private void cleanup(long tenant) {
        jdbc.update("DELETE FROM sys_api_access_log WHERE tenant_id IN(0,?) AND created_at<DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 30 DAY) LIMIT 10000",tenant);
        jdbc.update("DELETE FROM sys_api_error_log WHERE tenant_id IN(0,?) AND created_at<DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 90 DAY) LIMIT 10000",tenant);
        jdbc.update("DELETE FROM sys_login_log WHERE tenant_id IN(0,?) AND created_at<DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 180 DAY) LIMIT 10000",tenant);
        jdbc.update("DELETE FROM job_run_log WHERE tenant_id=? AND status IN('success','failed','skipped') AND created_at<DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 30 DAY) LIMIT 10000",tenant);
        jdbc.update("DELETE FROM notify_send_log WHERE tenant_id=? AND status IN('sent','failed','skipped') AND created_at<DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 180 DAY) LIMIT 10000",tenant);
        jdbc.update("DELETE FROM notify_message WHERE tenant_id=? AND created_at<DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 180 DAY) LIMIT 10000",tenant);
    }
}
