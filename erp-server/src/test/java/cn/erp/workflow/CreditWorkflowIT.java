package cn.erp.workflow;

import java.util.*;
import java.util.concurrent.*;

import org.flowable.engine.ProcessEngine;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import cn.erp.ar.*;
import cn.erp.identity.*;
import cn.erp.salesorder.*;
import cn.erp.shared.*;

import static cn.erp.workflow.WorkflowTestData.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class CreditWorkflowIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessService access;
    @Autowired SalesOrderService sales;
    @Autowired WorkflowModels models;
    @Autowired WorkflowTasks tasks;
    @Autowired WorkflowQueries queries;
    @Autowired ProcessEngine engine;
    @Autowired CreditAdministration admin;
    @Autowired CreditService credit;
    WorkflowTestData d;

    @BeforeEach
    void setup() {
        d = new WorkflowTestData(jdbc, access, sales, models);
    }

    @AfterEach
    void cleanup() {
        if (d != null) d.cleanup(engine);
    }

    void limit(String amount, String policy) {
        admin.save(
                d.actor(d.financeA),
                null,
                key(),
                new CreditAdministration.Limit(d.customer, amount, 0, policy, d.day, null, 0L));
    }

    SimpleModel.Node creditNode(String id, long user, SimpleModel.Node child) {
        var base = approve(id, List.of(user), "or", child);
        return new SimpleModel.Node(
                base.id(),
                base.type(),
                base.name(),
                base.assignee(),
                base.multi(),
                base.timeout(),
                base.branches(),
                base.child(),
                false,
                true);
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

    void approveTask(long task, long user) {
        long version =
                jdbc.queryForObject(
                        "SELECT version FROM wf_task_ext WHERE tenant_id=? AND id=?",
                        Long.class,
                        d.tenant,
                        task);
        tasks.decide(
                d.actor(user),
                task,
                "approve",
                key(),
                new WorkflowTasks.Decision(version, "信用条件已核对", null, null, null, null, null));
    }

    @Test
    void blockRejectsBeforeChangingOrderState() {
        limit("99", "block");
        long order = d.order("1", "100");
        assertThatThrownBy(() -> d.submit(order))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("CREDIT_LIMIT_EXCEEDED"));
        assertThat(d.status(order)).isEqualTo("draft");
    }

    @Test
    void concurrentSubmissionsReservePendingCommitments() throws Exception {
        limit("100", "block");
        long first = d.order("1", "60"), second = d.order("1", "60");
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<Boolean>>();
            for (long id : List.of(first, second))
                futures.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    try {
                                        d.submit(id);
                                        return true;
                                    } catch (ApiException e) {
                                        if (!e.getErrorCode().equals("CREDIT_LIMIT_EXCEEDED"))
                                            throw e;
                                        return false;
                                    }
                                }));
            gate.countDown();
            var results = new ArrayList<Boolean>();
            for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder(true, false);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sales_order WHERE tenant_id=? AND"
                                    + " status='pending'",
                                Long.class,
                                d.tenant))
                .isEqualTo(1);
    }

    @Test
    void warningIsVisibleAndDoesNotExposeCompanyDebt() {
        limit("50", "warn");
        long order = d.order("1", "100");
        var result =
                sales.command(
                        d.actor(d.maker),
                        order,
                        "submit",
                        key(),
                        new SalesOrderModels.Command(d.version(order), "提交", null));
        assertThat(result.get("warnings").toString())
                .contains("CREDIT_WARNING")
                .doesNotContain("creditExposure");
        assertThat(d.status(order)).isEqualTo("pending");
    }

    @Test
    void approvalPolicyCannotFallBackToSingleLevelApproval() {
        limit("50", "approval");
        long order = d.order("1", "100");
        assertThatThrownBy(() -> d.submit(order)).hasMessageContaining("信用审批");
        assertThat(d.status(order)).isEqualTo("draft");
    }

    @Test
    void ordinaryWorkflowNodeCannotSatisfyCreditReview() {
        limit("50", "approval");
        d.bind(root(approve("review", List.of(d.financeA), "or", null)));
        long order = d.order("1", "100");
        assertThatThrownBy(() -> d.submit(order)).hasMessageContaining("信用审批节点");
        assertThat(d.status(order)).isEqualTo("draft");
    }

    @Test
    void validCreditReviewCompletesInBusinessTransaction() {
        limit("50", "approval");
        d.bind(root(creditNode("credit", d.financeA, null)));
        long order = d.order("1", "100");
        long instance = d.submit(order);
        approveTask(task(instance, d.financeA), d.financeA);
        assertThat(d.status(order)).isEqualTo("approved");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT credit_fingerprint IS NOT NULL FROM wf_task_ext WHERE"
                                    + " tenant_id=? AND instance_id=? AND credit_approval=1",
                                Boolean.class,
                                d.tenant,
                                instance))
                .isTrue();
    }

    @Test
    void laterCommitmentInvalidatesEarlierCreditDecision() {
        limit("50", "approval");
        d.bind(
                root(
                        creditNode(
                                "credit",
                                d.financeA,
                                approve("final", List.of(d.financeB), "or", null))));
        long first = d.order("1", "100"), second = d.order("1", "100");
        long instance = d.submit(first);
        approveTask(task(instance, d.financeA), d.financeA);
        d.submit(second);
        long last = task(instance, d.financeB);
        assertThatThrownBy(() -> approveTask(last, d.financeB)).hasMessageContaining("信用审批");
        assertThat(d.status(first)).isEqualTo("pending");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM wf_task_ext WHERE tenant_id=? AND id=?",
                                String.class,
                                d.tenant,
                                last))
                .isEqualTo("pending");
    }

    @Test
    void creditReviewerNeedsCurrentPermissionAndFullScope() {
        limit("50", "approval");
        d.bind(root(creditNode("credit", d.financeA, null)));
        long order = d.order("1", "100"), instance = d.submit(order);
        jdbc.update(
                "DELETE rp FROM sys_role_permission rp JOIN sys_user_role ur ON"
                    + " ur.tenant_id=rp.tenant_id AND ur.role_id=rp.role_id JOIN sys_permission p"
                    + " ON p.id=rp.permission_id WHERE ur.tenant_id=? AND ur.user_id=? AND"
                    + " p.code='credit_limit:approve'",
                d.tenant,
                d.financeA);
        assertThatThrownBy(() -> approveTask(task(instance, d.financeA), d.financeA))
                .isInstanceOf(ApiException.class);
        assertThat(d.status(order)).isEqualTo("pending");
    }

    @Test
    void restrictedParticipantCannotReadAggregateDebtSnapshot() {
        limit("50", "approval");
        d.bind(root(creditNode("credit", d.financeA, null)));
        long instance = d.submit(d.order("1", "100"));
        jdbc.update(
                "DELETE rp FROM sys_role_permission rp JOIN sys_user_role ur ON"
                    + " ur.tenant_id=rp.tenant_id AND ur.role_id=rp.role_id JOIN sys_permission p"
                    + " ON p.id=rp.permission_id WHERE ur.tenant_id=? AND ur.user_id=? AND"
                    + " p.code='credit_limit:view'",
                d.tenant,
                d.maker);
        var detail = queries.detail(d.actor(d.maker), instance);
        assertThat((Map<String, Object>) detail.get("summary"))
                .doesNotContainKeys("creditExposure", "creditLimit", "creditFingerprint");
    }

    @Test
    void temporaryIncreaseRequiresDifferentReviewerAndExpires() {
        limit("50", "block");
        var a = d.actor(d.financeA);
        var inc =
                admin.createIncrease(
                        a,
                        key(),
                        new CreditAdministration.Increase(
                                d.customer, "100", d.day, d.day.plusDays(10), "试运行授信"));
        long id = Long.parseLong(inc.get("id").toString());
        var pending =
                admin.changeIncrease(
                        a, id, "submit", key(), new CreditAdministration.Command(0L, "提交", null));
        assertThatThrownBy(
                        () ->
                                admin.changeIncrease(
                                        a,
                                        id,
                                        "approve",
                                        key(),
                                        new CreditAdministration.Command(
                                                ((Number) pending.get("version")).longValue(),
                                                "复核",
                                                null)))
                .hasMessageContaining("另一人");
        admin.changeIncrease(
                d.actor(d.financeB),
                id,
                "approve",
                key(),
                new CreditAdministration.Command(
                        ((Number) pending.get("version")).longValue(), "独立复核", null));
        d.submit(d.order("1", "100"));
        assertThat(admin.exposure(a, d.customer)).containsEntry("creditLimit", "150.00");
        jdbc.update(
                "UPDATE credit_increase SET valid_to=? WHERE tenant_id=? AND id=?",
                d.day,
                d.tenant,
                id);
        assertThatThrownBy(() -> credit.shipment(a, d.customer)).isInstanceOf(ApiException.class);
    }

    @Test
    void increaseUsesConfiguredWorkflowAndCannotBypassIt() {
        limit("50", "block");
        d.bind(root(approve("increase", List.of(d.financeB), "or", null)), "credit_increase");
        var a = d.actor(d.financeA);
        var inc =
                admin.createIncrease(
                        a,
                        key(),
                        new CreditAdministration.Increase(
                                d.customer, "100", d.day, d.day.plusDays(10), "业务临时授信"));
        long id = Long.parseLong(inc.get("id").toString());
        admin.changeIncrease(
                a, id, "submit", key(), new CreditAdministration.Command(0L, "提交", null));
        long instance =
                jdbc.queryForObject(
                        "SELECT wf_instance_id FROM credit_increase WHERE tenant_id=? AND id=?",
                        Long.class,
                        d.tenant,
                        id);
        assertThatThrownBy(
                        () ->
                                admin.changeIncrease(
                                        d.actor(d.financeB),
                                        id,
                                        "approve",
                                        key(),
                                        new CreditAdministration.Command(1L, "直调", null)))
                .isInstanceOf(ApiException.class);
        approveTask(task(instance, d.financeB), d.financeB);
        assertThat(admin.increase(a, id)).containsEntry("status", "approved");
    }
}
