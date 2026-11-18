package cn.erp.workflow;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import org.flowable.engine.ProcessEngine;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import cn.erp.identity.*;
import cn.erp.salesorder.*;
import cn.erp.scheduler.JobEngine;
import cn.erp.shared.*;

import static cn.erp.workflow.WorkflowTestData.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

@SpringBootTest
@ActiveProfiles("test")
@Import(WorkflowOperationsIT.FaultConfiguration.class)
class WorkflowOperationsIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessService access;
    @Autowired SalesOrderService sales;
    @Autowired WorkflowModels models;
    @Autowired WorkflowTasks tasks;
    @Autowired WorkflowTimeout timeout;
    @Autowired WorkflowRuntime runtime;
    @Autowired WorkflowState state;
    @Autowired WorkflowQueries queries;
    @Autowired JobEngine jobs;
    @Autowired ProcessEngine engine;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Fault fault;
    WorkflowTestData d;
    @Autowired cn.erp.pricing.MinimumSalePrice prices;

    @TestConfiguration(proxyBeanMethods = false)
    static class FaultConfiguration {
        @Bean
        Fault workflowFault() {
            return new Fault();
        }
    }

    static class Fault {
        final AtomicBoolean enabled = new AtomicBoolean();

        @EventListener
        public void notice(WorkflowNotice event) {
            if (enabled.get() && event.kind().equals("done"))
                throw new IllegalStateException("流程完成后事务故障注入");
        }
    }

    @BeforeEach
    void prepare() {
        d = new WorkflowTestData(jdbc, access, sales, models);
        fault.enabled.set(false);
    }

    @AfterEach
    void cleanup() {
        fault.enabled.set(false);
        if (d != null) d.cleanup(engine);
    }

    long task(long instance, long user) {
        return jdbc.queryForObject(
                "SELECT id FROM wf_task_ext WHERE tenant_id=? AND instance_id=? AND assignee_id=?"
                    + " AND status='pending'",
                Long.class,
                d.tenant,
                instance,
                user);
    }

    WorkflowTasks.Decision input(long id) {
        return new WorkflowTasks.Decision(
                jdbc.queryForObject("SELECT version FROM wf_task_ext WHERE id=?", Long.class, id),
                "人工确认",
                null,
                null,
                null,
                null,
                null);
    }

    @Test
    void finalFailureRollsBackBusinessTaskEngineHistoryOutboxAndCommandAndSameKeyCanRetry() {
        d.bind(root(approve("review", List.of(d.manager), "and", null)));
        long order = d.order("1", "100"),
                instance = d.submit(order),
                task = task(instance, d.manager);
        String key = key();
        var body = input(task);
        long before =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM outbox_event WHERE tenant_id=?",
                        Long.class,
                        d.tenant);
        fault.enabled.set(true);
        assertThatThrownBy(() -> tasks.decide(d.actor(d.manager), task, "approve", key, body))
                .isInstanceOf(RuntimeException.class);
        fault.enabled.set(false);
        assertThat(d.status(order)).isEqualTo("pending");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM wf_task_ext WHERE id=?", String.class, task))
                .isEqualTo("pending");
        assertThat(
                        engine.getTaskService()
                                .createTaskQuery()
                                .processInstanceId(
                                        jdbc.queryForObject(
                                                "SELECT flowable_instance_id FROM wf_instance WHERE"
                                                    + " id=?",
                                                String.class,
                                                instance))
                                .count())
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM inventory_reservation WHERE tenant_id=?",
                                Long.class,
                                d.tenant))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM outbox_event WHERE tenant_id=?",
                                Long.class,
                                d.tenant))
                .isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM wf_task_action WHERE tenant_id=? AND"
                                    + " action='approve'",
                                Long.class,
                                d.tenant))
                .isZero();
        assertThat(WorkflowContext.allows(d.actor(d.manager), "sales_order", order)).isFalse();
        tasks.decide(d.actor(d.manager), task, "approve", key, body);
        assertThat(d.status(order)).isEqualTo("approved");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM inventory_reservation WHERE tenant_id=?",
                                Long.class,
                                d.tenant))
                .isEqualTo(1);
    }

    @Test
    void twoConcurrentApprovalsApplyTheBusinessFactOnce() throws Exception {
        d.bind(root(approve("review", List.of(d.manager), "and", null)));
        long order = d.order("1", "100"),
                instance = d.submit(order),
                task = task(instance, d.manager);
        var body = input(task);
        var start = new CountDownLatch(1);
        var outcomes = new ArrayList<Future<Boolean>>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++)
                outcomes.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    try {
                                        tasks.decide(
                                                d.actor(d.manager), task, "approve", key(), body);
                                        return true;
                                    } catch (ApiException error) {
                                        assertThat(error.getStatus()).isEqualTo(409);
                                        return false;
                                    }
                                }));
            start.countDown();
            int success = 0;
            for (var outcome : outcomes) if (outcome.get(20, TimeUnit.SECONDS)) success++;
            assertThat(success).isEqualTo(1);
        }
        assertThat(d.status(order)).isEqualTo("approved");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM inventory_reservation WHERE tenant_id=?",
                                Long.class,
                                d.tenant))
                .isEqualTo(1);
    }

    @Test
    void twoWorkersOnlySendOneDueReminder() throws Exception {
        var base = approve("review", List.of(d.manager), "and", null);
        d.bind(
                root(
                        new SimpleModel.Node(
                                base.id(),
                                base.type(),
                                base.name(),
                                base.assignee(),
                                base.multi(),
                                new SimpleModel.Timeout(1, "remind", 1),
                                null,
                                null,
                                false)));
        long instance = d.submit(d.order("1", "100")), id = task(instance, d.manager);
        jdbc.update(
                "UPDATE wf_task_ext SET due_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1"
                    + " MINUTE),next_remind_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 MINUTE) WHERE"
                    + " id=?",
                id);
        var second =
                new WorkflowTimeout(
                        mock(ManagedJobs.class),
                        runtime,
                        state,
                        transactions,
                        engine);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a =
                    executor.submit(
                            () -> {
                                start.await();
                                return timeout.runOne(d.tenant, id);
                            });
            var b =
                    executor.submit(
                            () -> {
                                start.await();
                                return second.runOne(d.tenant, id);
                            });
            start.countDown();
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM wf_task_action WHERE tenant_id=? AND"
                                    + " action='timeout_remind' AND actor_id=0",
                                Long.class,
                                d.tenant))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM outbox_event WHERE tenant_id=? AND"
                                    + " event_type='notify.workflow' AND"
                                    + " JSON_UNQUOTE(JSON_EXTRACT(payload,'$.kind'))='remind'",
                                Long.class,
                                d.tenant))
                .isEqualTo(1);
    }

    @Test
    void timeoutCanCompleteANonReviewHandlerButNeverInventsAReviewer() {
        var handler =
                new SimpleModel.Node(
                        "handler",
                        "handler",
                        "归档办理",
                        new SimpleModel.Assignee(
                                "users",
                                List.of(d.financeA),
                                null,
                                null,
                                null,
                                null,
                                null,
                                "admin"),
                        "and",
                        new SimpleModel.Timeout(1, "approve", 1),
                        null,
                        null,
                        false);
        d.bind(root(approve("review", List.of(d.manager), "and", handler)));
        long order = d.order("1", "100"),
                instance = d.submit(order),
                review = task(instance, d.manager);
        tasks.decide(d.actor(d.manager), review, "approve", key(), input(review));
        long handle = task(instance, d.financeA);
        jdbc.update(
                "UPDATE wf_task_ext SET next_remind_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 MINUTE)"
                    + " WHERE id=?",
                handle);
        assertThat(timeout.runOne(d.tenant, handle)).isTrue();
        assertThat(d.status(order)).isEqualTo("approved");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT approved_by FROM sales_order WHERE id=?",
                                Long.class,
                                order))
                .isEqualTo(d.manager);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM wf_task_ext WHERE id=?", String.class, handle))
                .isEqualTo("auto_approved");
    }

    @Test
    void timeoutFailureRollsBackOneItemAndBacksOffWithoutSkippingOtherTasks() {
        var handler = new SimpleModel.Node("handler", "handler", "归档办理",
                new SimpleModel.Assignee("users", List.of(d.financeA), null, null, null, null, null, null),
                "and", new SimpleModel.Timeout(1, "approve", 1), null, null, false);
        d.bind(root(approve("review", List.of(d.manager), "and", handler)));
        long instance = d.submit(d.order("1", "100"));
        long review = task(instance, d.manager);
        tasks.decide(d.actor(d.manager), review, "approve", key(), input(review));
        long failed = task(instance, d.financeA);
        long successA = task(d.submit(d.order("1", "100")), d.manager);
        long successB = task(d.submit(d.order("1", "100")), d.manager);
        jdbc.update("UPDATE sys_user SET status='disabled' WHERE tenant_id=? AND id=?", d.tenant, d.manager);
        due(failed, 2);
        due(successA, 1);
        due(successB, 1);

        jobs.ensure(d.tenant);
        runTimeout();
        assertThat(jdbc.queryForObject("SELECT last_result FROM job_definition WHERE tenant_id=? AND code='workflow.timeout'",
                String.class, d.tenant)).isEqualTo("partial_failed");
        assertThat(jdbc.queryForList("SELECT task_id FROM wf_task_action WHERE tenant_id=? AND action='timeout_remind'",
                Long.class, d.tenant)).containsExactlyInAnyOrder(successA, successB);
        assertThat(jdbc.queryForObject("SELECT status FROM wf_task_ext WHERE id=?", String.class, failed)).isEqualTo("pending");
        assertThat(jdbc.queryForMap("SELECT failure_count,error_code FROM job_item_failure WHERE tenant_id=? AND company_id=?",
                d.tenant, d.company)).containsEntry("failure_count", 1).containsEntry("error_code", "WORKFLOW_REVIEW_INVALID");
        assertThat(backoff(failed)).isBetween(290, 300);
        runTimeout();
        assertThat(jdbc.queryForObject("SELECT failure_count FROM job_item_failure WHERE tenant_id=?", Integer.class, d.tenant)).isEqualTo(1);
        for (int minutes : List.of(15, 60)) {
            due(failed, 1);
            runTimeout();
            assertThat(backoff(failed)).isBetween(minutes * 60 - 10, minutes * 60);
        }

        var admin = d.actor(d.maker);
        assertThat(queries.detail(admin, instance)).containsEntry("automaticProcessingFailed", true);
        assertThat(queries.list(admin, "all", null, 20, new WorkflowQueries.Filter(null, null, null, null, null)).get("items"))
                .asList().anySatisfy(row -> assertThat(row.toString()).contains("automaticProcessingFailed=true"));
        var permissions = new HashSet<>(admin.permissions());
        permissions.remove("workflow_instance:view_all");
        var maker = new CompanyAccess(d.tenant, d.maker, d.company, true, Set.of(), permissions);
        assertThat(queries.detail(maker, instance)).doesNotContainKey("automaticProcessingFailed");
        for (var other : List.of(new CompanyAccess(Ids.next(), d.maker, d.company, true, Set.of(), admin.permissions()),
                new CompanyAccess(d.tenant, d.maker, Ids.next(), true, Set.of(), admin.permissions())))
            assertThatThrownBy(() -> queries.detail(other, instance)).isInstanceOf(ApiException.class);

        jdbc.update("UPDATE sys_user SET status='active' WHERE tenant_id=? AND id=?", d.tenant, d.manager);
        due(failed, 1);
        runTimeout();
        assertThat(jdbc.queryForObject("SELECT status FROM wf_task_ext WHERE id=?", String.class, failed)).isEqualTo("auto_approved");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job_item_failure WHERE tenant_id=?", Integer.class, d.tenant)).isZero();
        assertThat(queries.detail(admin, instance)).containsEntry("automaticProcessingFailed", false);
    }

    private void due(long task, int minutes) {
        jdbc.update("UPDATE wf_task_ext SET next_remind_at=TIMESTAMPADD(MINUTE,?,UTC_TIMESTAMP(3)) WHERE id=?", -minutes, task);
    }

    private int backoff(long task) {
        return jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(3),next_remind_at) FROM wf_task_ext WHERE id=?",
                Integer.class, task);
    }

    private void runTimeout() {
        jdbc.update("UPDATE job_definition SET next_run_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE tenant_id=? AND code='workflow.timeout'", d.tenant);
        assertThat(jobs.runOne(d.tenant, "workflow.timeout")).isTrue();
    }

    @Test
    void timeoutRejectionDoesNotCountAsHumanApproval() {
        var base = approve("review", List.of(d.manager), "and", null);
        d.bind(
                root(
                        new SimpleModel.Node(
                                base.id(),
                                base.type(),
                                base.name(),
                                base.assignee(),
                                base.multi(),
                                new SimpleModel.Timeout(1, "reject", 1),
                                null,
                                null,
                                false)));
        long order = d.order("1", "100"),
                instance = d.submit(order),
                id = task(instance, d.manager);
        jdbc.update(
                "UPDATE wf_task_ext SET next_remind_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 MINUTE)"
                    + " WHERE id=?",
                id);
        assertThat(timeout.runOne(d.tenant, id)).isTrue();
        assertThat(d.status(order)).isEqualTo("draft");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM wf_task_ext WHERE instance_id=? AND"
                                    + " status='approved'",
                                Long.class,
                                instance))
                .isZero();
    }

    @Test
    void changedMinimumPriceCannotReuseOldManualPriceEvidence() {
        jdbc.update(
                "UPDATE sku SET min_sale_price=101 WHERE tenant_id=? AND id=?", d.tenant, d.sku);
        jdbc.update(
                "UPDATE company_policy SET below_min_price_policy='approval' WHERE tenant_id=?",
                d.tenant);
        var price =
                approve(
                        "price",
                        List.of(d.manager),
                        "and",
                        approve("finance", List.of(d.financeA), "and", null));
        d.bind(
                root(
                        new SimpleModel.Node(
                                price.id(),
                                price.type(),
                                "价格复核",
                                price.assignee(),
                                price.multi(),
                                null,
                                null,
                                price.child(),
                                true)));
        long order = d.order("1", "100"),
                instance = d.submit(order),
                first = task(instance, d.manager);
        tasks.decide(d.actor(d.manager), first, "approve", key(), input(first));
        jdbc.update("UPDATE sku SET min_sale_price=201 WHERE id=?", d.sku);
        long last = task(instance, d.financeA);
        assertThatThrownBy(
                        () ->
                                tasks.decide(
                                        d.actor(d.financeA), last, "approve", key(), input(last)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error ->
                                assertThat(error.getErrorCode())
                                        .isEqualTo("PRICE_APPROVAL_REQUIRED"));
        assertThat(d.status(order)).isEqualTo("pending");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM wf_task_ext WHERE id=?", String.class, last))
                .isEqualTo("pending");
    }

    @Test
    void concurrentPolicyChangeIsReadUnderTheFinalApprovalLockInsteadOfAnOldSnapshot() {
        jdbc.update("UPDATE sku SET min_sale_price=101 WHERE tenant_id=?", d.tenant);
        long order = d.order("1", "100");
        var outer = new org.springframework.transaction.support.TransactionTemplate(transactions);
        var fresh = new org.springframework.transaction.support.TransactionTemplate(transactions);
        fresh.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThatThrownBy(
                        () ->
                                outer.executeWithoutResult(
                                        status -> {
                                            assertThat(
                                                            jdbc.queryForObject(
                                                                    "SELECT below_min_price_policy"
                                                                        + " FROM company_policy"
                                                                        + " WHERE tenant_id=?",
                                                                    String.class,
                                                                    d.tenant))
                                                    .isEqualTo("warn");
                                            fresh.executeWithoutResult(
                                                    change ->
                                                            jdbc.update(
                                                                    "UPDATE company_policy SET"
                                                                        + " below_min_price_policy='approval'"
                                                                        + " WHERE tenant_id=?",
                                                                    d.tenant));
                                            prices.approving(d.actor(d.manager), order);
                                        }))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error ->
                                assertThat(error.getErrorCode())
                                        .isEqualTo("PRICE_APPROVAL_REQUIRED"));
    }
}
