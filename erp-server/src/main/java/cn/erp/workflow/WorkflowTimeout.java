package cn.erp.workflow;

import java.util.*;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.*;

import static cn.erp.workflow.WorkflowDocuments.*;

/** 固定任务由现有分布式租约调度，实例行锁保证多个Worker不会重复超时处理。 */
@Component
public class WorkflowTimeout {
    private final WorkflowRuntime runtime;
    private final WorkflowState state;
    private final TransactionTemplate transaction;
    private final org.flowable.engine.ProcessEngine engine;

    public WorkflowTimeout(
            ManagedJobs jobs,
            WorkflowRuntime runtime,
            WorkflowState state,
            PlatformTransactionManager manager,
            org.flowable.engine.ProcessEngine engine) {
        this.runtime = runtime;
        this.state = state;
        this.engine = engine;
        transaction = new TransactionTemplate(manager);
        jobs.registerBatch("workflow.timeout", this::poll);
    }

    void poll(long tenant, ManagedJobs.Batch batch) {
        for (var row : state.jdbc.queryForList("""
                SELECT t.id,t.company_id,t.instance_id,t.version,t.next_remind_at
                FROM wf_task_ext t JOIN wf_instance i ON i.tenant_id=t.tenant_id
                  AND i.company_id=t.company_id AND i.id=t.instance_id AND i.status='running'
                WHERE t.tenant_id=? AND t.status='pending' AND t.next_remind_at<=UTC_TIMESTAMP(3)
                ORDER BY t.next_remind_at,t.id LIMIT 100
                """, tenant)) {
            long task = number(row.get("id"));
            long company = number(row.get("company_id"));
            long instance = number(row.get("instance_id"));
            batch.run(company, Long.toString(task), instance, () -> runOne(tenant, task),
                    minutes -> state.jdbc.update("""
                            UPDATE wf_task_ext SET next_remind_at=TIMESTAMPADD(MINUTE,?,UTC_TIMESTAMP(3))
                            WHERE tenant_id=? AND company_id=? AND id=? AND status='pending'
                              AND version=? AND next_remind_at=?
                            """, minutes, tenant, company, task, row.get("version"), row.get("next_remind_at")) == 1);
        }
    }

    public boolean runOne(long tenant, long id) {
        return Boolean.TRUE.equals(
                transaction.execute(
                        status -> {
                            var ids =
                                    state.jdbc.queryForList(
                                            "SELECT instance_id FROM wf_task_ext WHERE tenant_id=?"
                                                + " AND id=?",
                                            Long.class,
                                            tenant,
                                            id);
                            if (ids.isEmpty()) return false;
                            var instance = state.raw(tenant, ids.getFirst(), true);
                            if (!instance.get("status").equals("running")) return false;
                            var rows =
                                    state.jdbc.queryForList(
                                            "SELECT * FROM wf_task_ext WHERE tenant_id=? AND id=?"
                                                + " AND status='pending' AND"
                                                + " next_remind_at<=UTC_TIMESTAMP(3) FOR UPDATE",
                                            tenant,
                                            id);
                            if (rows.isEmpty()) return false;
                            var task = rows.getFirst();
                            String action = Objects.toString(task.get("timeout_action"), "remind");
                            if (action.equals("approve")
                                    && task.get("node_kind").equals("handler")
                                    && task.get("owner_id") == null
                                    && task.get("parent_task_id") == null) {
                                state.jdbc.update(
                                        "UPDATE wf_task_ext SET"
                                            + " status='auto_approved',comment='超时自动办理',ended_at=UTC_TIMESTAMP(3),version=version+1"
                                            + " WHERE tenant_id=? AND id=?",
                                        tenant,
                                        id);
                                state.action(
                                        instance,
                                        id,
                                        0,
                                        "timeout_approve",
                                        "超时自动办理，不计人工复核",
                                        Map.of());
                                engine.getTaskService()
                                        .complete(task.get("flowable_task_id").toString());
                            } else if (Set.of("reject", "terminate").contains(action)) {
                                String outcome =
                                        action.equals("reject") ? "rejected" : "terminated";
                                var actor =
                                        new CompanyAccess(
                                                tenant,
                                                0,
                                                number(instance.get("company_id")),
                                                true,
                                                Set.of(),
                                                Set.of());
                                state.action(
                                        instance, id, 0, "timeout_" + action, "超时自动处理", Map.of());
                                runtime.end(actor, instance, outcome, "超时自动处理", true);
                            } else {
                                int hours =
                                        task.get("repeat_hours") == null
                                                ? 12
                                                : ((Number) task.get("repeat_hours")).intValue();
                                var due = task.get("next_remind_at");
                                long timestamp =
                                        due instanceof java.time.LocalDateTime time
                                                ? time.toInstant(java.time.ZoneOffset.UTC)
                                                        .toEpochMilli()
                                                : ((java.sql.Timestamp) due)
                                                        .toInstant()
                                                        .toEpochMilli();
                                state.notice(
                                        instance,
                                        number(task.get("assignee_id")),
                                        id,
                                        "remind",
                                        "wf:remind:" + id + ":" + timestamp);
                                state.jdbc.update(
                                        "UPDATE wf_task_ext SET"
                                            + " next_remind_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL ?"
                                            + " HOUR) WHERE tenant_id=? AND id=?",
                                        Math.max(1, Math.min(168, hours)),
                                        tenant,
                                        id);
                                state.action(
                                        instance, id, 0, "timeout_remind", "审批待办超时提醒", Map.of());
                            }
                            state.signal(instance);
                            return true;
                        }));
    }
}
