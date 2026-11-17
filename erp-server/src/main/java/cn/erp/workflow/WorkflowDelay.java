package cn.erp.workflow;

import java.util.Map;
import org.flowable.engine.ProcessEngine;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import cn.erp.shared.ManagedJobs;
import static cn.erp.workflow.WorkflowDocuments.number;

@Component
class WorkflowDelay {
    private final WorkflowState state;
    private final ProcessEngine engine;
    private final TransactionTemplate transaction;

    WorkflowDelay(ManagedJobs jobs,WorkflowState state,ProcessEngine engine,PlatformTransactionManager manager) {
        this.state = state;
        this.engine = engine;
        this.transaction = new TransactionTemplate(manager);
        jobs.register("workflow.delay",this::poll);
    }

    void poll(long tenant) {
        var due = state.jdbc.queryForList("""
                SELECT execution_id,instance_id FROM wf_delay WHERE tenant_id=? AND due_at<=UTC_TIMESTAMP(3)
                ORDER BY due_at,execution_id LIMIT 50
                """,tenant);
        for (var row : due) resume(tenant,number(row.get("instance_id")),row.get("execution_id").toString());
    }

    boolean resume(long tenant,long instanceId,String executionId) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var instance = state.raw(tenant,instanceId,true);
            var due = state.jdbc.queryForList("""
                    SELECT node_key FROM wf_delay WHERE tenant_id=? AND company_id=? AND instance_id=? AND execution_id=?
                      AND due_at<=UTC_TIMESTAMP(3) FOR UPDATE
                    """,tenant,instance.get("company_id"),instanceId,executionId);
            if (due.isEmpty()) return false;
            String node = due.getFirst().get("node_key").toString();
            state.jdbc.update("DELETE FROM wf_delay WHERE tenant_id=? AND instance_id=? AND execution_id=?",tenant,instanceId,executionId);
            if (!instance.get("status").equals("running")) return false;
            var service = engine.getRuntimeService();
            var execution = service.createExecutionQuery().executionId(executionId)
                    .processInstanceId(instance.get("flowable_instance_id").toString()).activityId(node).singleResult();
            if (execution == null) return false;
            state.action(instance,null,0,"delay_resumed",null,Map.of("nodeKey",node));
            service.trigger(executionId);
            state.signal(instance);
            return true;
        }));
    }
}
