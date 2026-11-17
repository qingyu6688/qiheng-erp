package cn.erp.openapi;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import cn.erp.shared.ManagedJobs;

@Component
@ConditionalOnProperty(name="erp.worker.enabled",havingValue="true",matchIfMissing=true)
class OpenWorkers {
    OpenWorkers(ManagedJobs jobs,OpenWebhooks hooks,JdbcTemplate jdbc) {
        jobs.register("open.webhook",hooks::processTenant);
        jobs.register("open.cleanup",tenant -> {
            jdbc.update("DELETE FROM open_call_log WHERE tenant_id=? AND created_at<UTC_TIMESTAMP(3)-INTERVAL 90 DAY LIMIT 5000",tenant);
            jdbc.update("DELETE FROM open_inventory_event_slot WHERE tenant_id=? AND created_at<UTC_TIMESTAMP(3)-INTERVAL 1 DAY LIMIT 10000",tenant);
            jdbc.update("""
                    DELETE FROM open_webhook_delivery WHERE tenant_id=? AND status IN ('delivered','failed')
                      AND updated_at<UTC_TIMESTAMP(3)-INTERVAL 90 DAY LIMIT 5000
                    """,tenant);
        });
    }
}
