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
        jobs.registerBatch("workflow.delay",this::poll);
    }

    void poll(long tenant, ManagedJobs.Batch batch) {
        var due = state.jdbc.queryForList("""
                SELECT d.execution_id,d.company_id,d.instance_id,d.due_at,d.node_key FROM wf_delay d
                JOIN wf_instance i ON i.tenant_id=d.tenant_id AND i.company_id=d.company_id AND i.id=d.instance_id AND i.status='running'
                WHERE d.tenant_id=? AND d.due_at<=UTC_TIMESTAMP(3) ORDER BY d.due_at,d.execution_id LIMIT 50
                """,tenant);
        for (var row : due) {
            String execution = row.get("execution_id").toString();
            long company = number(row.get("company_id"));
            long instance = number(row.get("instance_id"));
            batch.run(company, execution, instance, () -> resume(tenant, instance, execution),
                    minutes -> state.jdbc.update("""
                            UPDATE wf_delay SET due_at=TIMESTAMPADD(MINUTE,?,UTC_TIMESTAMP(3))
                            WHERE tenant_id=? AND company_id=? AND instance_id=? AND execution_id=? AND node_key=? AND due_at=?
                            """, minutes, tenant, company, instance, execution, row.get("node_key"), row.get("due_at")) == 1);
        }
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
