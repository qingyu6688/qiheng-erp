package cn.erp.workflow;

import java.util.*;

import org.flowable.engine.ProcessEngine;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import cn.erp.identity.*;
import cn.erp.notify.MessageService;
import cn.erp.salesorder.*;
import cn.erp.shared.*;

import static cn.erp.workflow.WorkflowTestData.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class WorkflowIntegrationIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessService access;
    @Autowired SalesOrderService sales;
    @Autowired WorkflowModels models;
    @Autowired WorkflowTasks tasks;
    @Autowired WorkflowQueries queries;
    @Autowired MessageService messages;
    @Autowired ProcessEngine engine;
    WorkflowTestData d;

    @BeforeEach
    void prepare() {
        d = new WorkflowTestData(jdbc, access, sales, models);
    }

    @AfterEach
    void cleanup() {
        if (d != null) d.cleanup(engine);
    }

    List<Map<String, Object>> pending(long instance) {
        return jdbc.queryForList(
                "SELECT * FROM wf_task_ext WHERE tenant_id=? AND instance_id=? AND status='pending'"
                        + " ORDER BY id",
                d.tenant,
                instance);
    }

    long task(long instance, long user) {
        return pending(instance).stream()
                .filter(row -> WorkflowDocuments.number(row.get("assignee_id")) == user)
                .mapToLong(row -> WorkflowDocuments.number(row.get("id")))
                .findFirst()
                .orElseThrow();
    }

    WorkflowTasks.Decision input(
            long id,
            String comment,
            Long user,
            List<Long> users,
            List<Long> ids,
            String position,
            String target) {
        return new WorkflowTasks.Decision(
                jdbc.queryForObject(
                        "SELECT version FROM wf_task_ext WHERE tenant_id=? AND id=?",
                        Long.class,
                        d.tenant,
                        id),
                comment,
                user,
                users,
                ids,
                position,
                target);
    }

    void approveTask(long instance, long user) {
        long id = task(instance, user);
        tasks.decide(
                d.actor(user), id, "approve", key(), input(id, "同意", null, null, null, null, null));
    }

    String flowState(long id) {
        return jdbc.queryForObject(
                "SELECT status FROM wf_instance WHERE tenant_id=? AND id=?",
                String.class,
                d.tenant,
                id);
    }

    @Test
    void amountAboveFiftyThousandManagerAndFinanceCountersignRejectModifyAndResubmit() {
        var finance = approve("finance", List.of(d.financeA, d.financeB), "and", null);
        var branch =
                new SimpleModel.Node(
                        "amount",
                        "condition_branch",
                        "金额分支",
                        null,
                        null,
                        null,
                        List.of(
                                new SimpleModel.Branch(
                                        "超过五万",
                                        new SimpleModel.Condition(
                                                "totalAmount", ">", "50000", null, null),
                                        false,
                                        finance),
                                new SimpleModel.Branch("其他", null, true, null)),
                        null,
                        false);
        d.bind(
                root(
                        new SimpleModel.Node(
                                "manager",
                                "approver",
                                "部门经理",
                                new SimpleModel.Assignee(
                                        "dept_leader", null, null, null, 1, null, null, "admin"),
                                "and",
                                null,
                                null,
                                branch,
                                false)));
        long order = d.order("600", "100"), first = d.submit(order);
        assertThat(pending(first)).hasSize(1);
        approveTask(first, d.manager);
        assertThat(pending(first)).hasSize(2);
        approveTask(first, d.financeA);
        assertThat(d.status(order)).isEqualTo("pending");
        long reject = task(first, d.financeB);
        tasks.decide(
                d.actor(d.financeB),
                reject,
                "reject",
                key(),
                input(reject, "请调整价格", null, null, null, null, "starter"));
        assertThat(d.status(order)).isEqualTo("draft");
        assertThat(flowState(first)).isEqualTo("rejected");
        var line =
                new SalesOrderModels.Line(
                        null,
                        "" + d.sku,
                        "" + d.warehouse,
                        "" + d.unit,
                        "600",
                        "90",
                        null,
                        "0",
                        null,
                        null);
        sales.edit(
                d.actor(d.maker),
                order,
                new SalesOrderModels.Input(
                        "" + d.customer,
                        d.day,
                        d.day.plusDays(2),
                        true,
                        "0",
                        List.of(line),
                        List.of(),
                        null,
                        d.version(order),
                        null));
        long second = d.submit(order);
        assertThat(second).isNotEqualTo(first);
        approveTask(second, d.manager);
        approveTask(second, d.financeA);
        approveTask(second, d.financeB);
        assertThat(d.status(order)).isEqualTo("approved");
        assertThat(flowState(second)).isEqualTo("approved");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM inventory_reservation WHERE tenant_id=?",
                                Long.class,
                                d.tenant))
                .isEqualTo(1);
        assertThat(messages.counts(d.actor(d.manager)).get("total")).isEqualTo(0L);
    }

    @Test
    void orSignCompletesOnceAndCancelsOtherReviewer() {
        d.bind(root(approve("one", List.of(d.manager, d.financeA), "or", null)));
        long order = d.order("1", "100"), instance = d.submit(order);
        var filter = new WorkflowQueries.Filter(null, null, null, null, null);
        assertThat(queries.list(d.actor(d.maker), "done", null, 20, filter).get("items"))
                .asList()
                .isEmpty();
        approveTask(instance, d.manager);
        assertThat(d.status(order)).isEqualTo("approved");
        assertThat(pending(instance)).isEmpty();
        assertThat(queries.count(d.actor(d.financeA))).isZero();
        assertThat(queries.list(d.actor(d.manager), "done", null, 20, filter).get("items"))
                .asList()
                .hasSize(1);
    }

    @Test
    void sequentialSignDoesNotExposeLaterTasksEarly() {
        d.bind(
                root(
                        approve(
                                "one",
                                List.of(d.manager, d.financeA, d.financeB),
                                "sequential",
                                null)));
        long instance = d.submit(d.order("1", "100"));
        assertThat(pending(instance))
                .extracting(row -> WorkflowDocuments.number(row.get("assignee_id")))
                .containsExactly(d.manager);
        approveTask(instance, d.manager);
        assertThat(pending(instance))
                .extracting(row -> WorkflowDocuments.number(row.get("assignee_id")))
                .containsExactly(d.financeA);
        approveTask(instance, d.financeA);
        approveTask(instance, d.financeB);
        assertThat(flowState(instance)).isEqualTo("approved");
    }

    @Test
    void parallelBranchesJoinBeforeBusinessApproval() {
        var parallel =
                new SimpleModel.Node(
                        "parallel",
                        "parallel_branch",
                        "并行复核",
                        null,
                        null,
                        null,
                        List.of(
                                new SimpleModel.Branch(
                                        "经理",
                                        null,
                                        false,
                                        approve("manager", List.of(d.manager), "and", null)),
                                new SimpleModel.Branch(
                                        "财务",
                                        null,
                                        false,
                                        approve(
                                                "finance",
                                                List.of(d.financeA, d.financeB),
                                                "and",
                                                null))),
                        null,
                        false);
        d.bind(root(parallel));
        long order = d.order("1", "100"), instance = d.submit(order);
        assertThat(pending(instance)).hasSize(3);
        approveTask(instance, d.manager);
        approveTask(instance, d.financeA);
        assertThat(d.status(order)).isEqualTo("pending");
        approveTask(instance, d.financeB);
        assertThat(d.status(order)).isEqualTo("approved");
    }

    @Test
    void boundaryAtExactlyFiftyThousandUsesDefaultBranch() {
        var branch =
                new SimpleModel.Node(
                        "amount",
                        "condition_branch",
                        "金额分支",
                        null,
                        null,
                        null,
                        List.of(
                                new SimpleModel.Branch(
                                        "大额",
                                        new SimpleModel.Condition(
                                                "totalAmount", ">", "50000", null, null),
                                        false,
                                        approve("finance", List.of(d.financeA), "and", null)),
                                new SimpleModel.Branch("其他", null, true, null)),
                        null,
                        false);
        d.bind(root(approve("manager", List.of(d.manager), "and", branch)));
        long order = d.order("500", "100"), instance = d.submit(order);
        approveTask(instance, d.manager);
        assertThat(d.status(order)).isEqualTo("approved");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM wf_task_ext WHERE tenant_id=? AND"
                                        + " instance_id=? AND node_key='finance'",
                                Long.class,
                                d.tenant,
                                instance))
                .isZero();
    }

    @Test
    void transferChecksCurrentAssigneeAndDelegationReturnsToOwner() {
        d.bind(root(approve("review", List.of(d.manager), "and", null)));
        long order = d.order("1", "100"),
                instance = d.submit(order),
                id = task(instance, d.manager);
        jdbc.update(
                "DELETE rp FROM sys_role_permission rp JOIN sys_user_role ur ON"
                    + " ur.tenant_id=rp.tenant_id AND ur.role_id=rp.role_id JOIN sys_permission p"
                    + " ON p.id=rp.permission_id WHERE ur.tenant_id=? AND ur.user_id IN(?,?,?) AND"
                    + " p.code IN('workflow_instance:view_all','workflow_instance:terminate')",
                d.tenant,
                d.manager,
                d.financeA,
                d.financeB);
        assertThatThrownBy(() -> queries.detail(d.actor(d.financeB), instance))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(404));
        tasks.decide(
                d.actor(d.manager),
                id,
                "delegate",
                key(),
                input(id, "协助核对", d.financeA, null, null, null, null));
        approveTask(instance, d.financeA);
        assertThat(d.status(order)).isEqualTo("pending");
        assertThat(WorkflowDocuments.number(pending(instance).getFirst().get("assignee_id")))
                .isEqualTo(d.manager);
        tasks.decide(
                d.actor(d.manager),
                id,
                "transfer",
                key(),
                input(id, "交财务处理", d.financeB, null, null, null, null));
        assertThat(queries.detail(d.actor(d.manager), instance).get("id")).isEqualTo("" + instance);
        assertThat(queries.detail(d.actor(d.financeA), instance).get("id"))
                .isEqualTo("" + instance);
        assertThatThrownBy(
                        () ->
                                tasks.decide(
                                        d.actor(d.manager),
                                        id,
                                        "approve",
                                        key(),
                                        input(id, "同意", null, null, null, null, null)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(403));
        approveTask(instance, d.financeB);
        assertThat(d.status(order)).isEqualTo("approved");
    }

    @Test
    void beforeSignMustFinishAndOnlyOwnAdditionalTasksCanBeRemoved() {
        d.bind(root(approve("review", List.of(d.manager), "and", null)));
        long order = d.order("1", "100"),
                instance = d.submit(order),
                id = task(instance, d.manager);
        tasks.decide(
                d.actor(d.manager),
                id,
                "add-sign",
                key(),
                input(id, "加签核对", null, List.of(d.financeA, d.financeB), null, "before", null));
        assertThatThrownBy(
                        () ->
                                tasks.decide(
                                        d.actor(d.manager),
                                        id,
                                        "approve",
                                        key(),
                                        input(id, "同意", null, null, null, null, null)))
                .isInstanceOf(ApiException.class);
        long remove = task(instance, d.financeB);
        tasks.decide(
                d.actor(d.manager),
                id,
                "remove-sign",
                key(),
                input(id, "减少重复核对", null, null, List.of(remove), null, null));
        approveTask(instance, d.financeA);
        approveTask(instance, d.manager);
        assertThat(d.status(order)).isEqualTo("approved");
    }

    @Test
    void afterSignHoldsOriginalApprovalUntilAdditionalReviewerFinishes() {
        d.bind(root(approve("review", List.of(d.manager), "and", null)));
        long order = d.order("1", "100"),
                instance = d.submit(order),
                id = task(instance, d.manager);
        tasks.decide(
                d.actor(d.manager),
                id,
                "add-sign",
                key(),
                input(id, "追加财务复核", null, List.of(d.financeA), null, "after", null));
        approveTask(instance, d.manager);
        assertThat(d.status(order)).isEqualTo("pending");
        approveTask(instance, d.financeA);
        assertThat(d.status(order)).isEqualTo("approved");
    }

    @Test
    void rejectToEarlierNodeRestartsEvidenceEpoch() {
        d.bind(
                root(
                        approve(
                                "manager",
                                List.of(d.manager),
                                "and",
                                approve("finance", List.of(d.financeA), "and", null))));
        long order = d.order("1", "100"), instance = d.submit(order);
        approveTask(instance, d.manager);
        long id = task(instance, d.financeA);
        tasks.decide(
                d.actor(d.financeA),
                id,
                "reject",
                key(),
                input(id, "请经理复核", null, null, null, null, "manager"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT approval_epoch FROM wf_instance WHERE id=?",
                                Integer.class,
                                instance))
                .isEqualTo(1);
        approveTask(instance, d.manager);
        approveTask(instance, d.financeA);
        assertThat(d.status(order)).isEqualTo("approved");
    }

    @Test
    void copyAndMessageRemainRestrictedToTheActualRecipient() {
        var copy =
                new SimpleModel.Node(
                        "copy",
                        "copy",
                        "抄送申请人",
                        new SimpleModel.Assignee(
                                "users", List.of(d.maker), null, null, null, null, null, "skip"),
                        null,
                        null,
                        null,
                        null,
                        false);
        d.bind(root(approve("review", List.of(d.manager), "and", copy)));
        long instance = d.submit(d.order("1", "100"));
        approveTask(instance, d.manager);
        assertThat(
                        queries.list(
                                        d.actor(d.maker),
                                        "copies",
                                        null,
                                        20,
                                        new WorkflowQueries.Filter(null, null, null, null, null))
                                .get("items"))
                .asList()
                .hasSize(1);
        assertThat(
                        queries.list(
                                        d.actor(d.financeA),
                                        "copies",
                                        null,
                                        20,
                                        new WorkflowQueries.Filter(null, null, null, null, null))
                                .get("items"))
                .asList()
                .isEmpty();
        assertThat(messages.counts(d.actor(d.maker)).get("total")).isEqualTo(2L);
        long copyId =
                jdbc.queryForObject(
                        "SELECT id FROM wf_copy WHERE tenant_id=? AND instance_id=?",
                        Long.class,
                        d.tenant,
                        instance);
        messages.materialize(
                new WorkflowNotice(
                        d.tenant, d.company, d.maker, instance, null, "copy", "wf:copy:" + copyId));
        assertThat(messages.counts(d.actor(d.maker)).get("total")).isEqualTo(2L);
        queries.readCopy(d.actor(d.maker), copyId);
        messages.materialize(
                new WorkflowNotice(
                        d.tenant,
                        d.company,
                        d.maker,
                        copyId,
                        null,
                        "copy_read",
                        "wf:copy:read:" + copyId));
        assertThat(messages.counts(d.actor(d.maker)).get("total")).isEqualTo(1L);
        assertThatThrownBy(() -> queries.readCopy(d.actor(d.financeA), copyId))
                .isInstanceOfSatisfying(
                        ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(404));
    }

    @Test
    void newPublishedVersionDoesNotChangeRunningInstances() {
        long model = d.bind(root(approve("first", List.of(d.manager), "and", null))),
                order = d.order("1", "100"),
                instance = d.submit(order);
        var changed = root(approve("second", List.of(d.financeA), "and", null));
        String code = models.get(d.actor(d.maker), model).get("code").toString();
        models.save(
                d.actor(d.maker),
                model,
                key(),
                new WorkflowModels.Input(
                        d.company, code, "新发布版本", null, "sales_order", changed, 1L));
        models.change(d.actor(d.maker), model, "publish", 2, key());
        approveTask(instance, d.manager);
        assertThat(d.status(order)).isEqualTo("approved");
        long next = d.submit(d.order("1", "100"));
        assertThat(pending(next))
                .extracting(row -> WorkflowDocuments.number(row.get("assignee_id")))
                .containsExactly(d.financeA);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT model_version FROM wf_instance WHERE id=?",
                                Integer.class,
                                instance))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT model_version FROM wf_instance WHERE id=?",
                                Integer.class,
                                next))
                .isEqualTo(2);
    }

    @Test
    void noBindingKeepsOriginalSingleApprovalAndRunningFlowRejectsNativeCommand() {
        long legacy = d.order("1", "100");
        assertThat(d.submit(legacy)).isZero();
        sales.command(
                d.actor(d.manager),
                legacy,
                "approve",
                key(),
                new SalesOrderModels.Command(1L, "原单级复核", null));
        assertThat(d.status(legacy)).isEqualTo("approved");
        d.bind(root(approve("review", List.of(d.manager), "and", null)));
        long order = d.order("1", "100"), instance = d.submit(order);
        assertThatThrownBy(
                        () ->
                                sales.command(
                                        d.actor(d.manager),
                                        order,
                                        "approve",
                                        key(),
                                        new SalesOrderModels.Command(1L, "直接审批", null)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("WORKFLOW_IN_PROGRESS"));
        assertThat(pending(instance)).hasSize(1);
    }

    @Test
    void taskIdempotencyAndCurrentAssignmentAreEnforced() {
        d.bind(root(approve("review", List.of(d.manager), "and", null)));
        long instance = d.submit(d.order("1", "100")), id = task(instance, d.manager);
        var input = input(id, "同意", null, null, null, null, null);
        String key = key();
        assertThatThrownBy(() -> tasks.decide(d.actor(d.financeA), id, "approve", key, input))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(403));
        var first = tasks.decide(d.actor(d.manager), id, "approve", key, input);
        assertThat(tasks.decide(d.actor(d.manager), id, "approve", key, input)).isEqualTo(first);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM wf_task_action WHERE tenant_id=? AND"
                                        + " task_id=? AND action='approve'",
                                Long.class,
                                d.tenant,
                                id))
                .isEqualTo(1);
    }

    @Test
    void crossTenantCompanyAndDepartmentCannotReadOrHandleTheInstance() {
        d.bind(root(approve("review", List.of(d.manager), "and", null)));
        long instance = d.submit(d.order("1", "100"));
        var actor = d.actor(d.manager);
        for (var other :
                List.of(
                        new CompanyAccess(
                                d.tenant + 1,
                                d.manager,
                                d.company,
                                true,
                                Set.of(),
                                actor.permissions()),
                        new CompanyAccess(
                                d.tenant,
                                d.manager,
                                d.company + 1,
                                true,
                                Set.of(),
                                actor.permissions()),
                        new CompanyAccess(
                                d.tenant,
                                d.manager,
                                d.company,
                                true,
                                Set.of(),
                                actor.permissions(),
                                OrgScope.restricted(Set.of(), Set.of(d.manager), 0))))
            assertThatThrownBy(() -> queries.detail(other, instance))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void revokedApprovalPermissionRemovesTodoMessagesAndPreventsAction() {
        d.bind(root(approve("review", List.of(d.manager), "and", null)));
        long instance = d.submit(d.order("1", "100")), id = task(instance, d.manager);
        assertThat(messages.counts(d.actor(d.manager)).get("todo")).isEqualTo(1L);
        jdbc.update(
                "DELETE rp FROM sys_role_permission rp JOIN sys_user_role ur ON"
                    + " ur.tenant_id=rp.tenant_id AND ur.role_id=rp.role_id JOIN sys_permission p"
                    + " ON p.id=rp.permission_id WHERE ur.tenant_id=? AND ur.user_id=? AND"
                    + " p.code='sales_order:approve'",
                d.tenant,
                d.manager);
        var actor = d.actor(d.manager);
        assertThat(queries.count(actor)).isZero();
        assertThat(messages.counts(actor).get("total")).isEqualTo(0L);
        assertThatThrownBy(
                        () ->
                                tasks.decide(
                                        actor,
                                        id,
                                        "approve",
                                        key(),
                                        input(id, "同意", null, null, null, null, null)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(403));
    }

    @Test
    void cancelAndTerminateReturnBusinessToDraftWithoutInventingHumanApprovals() {
        d.bind(root(approve("review", List.of(d.manager), "and", null)));
        long order = d.order("1", "100"), instance = d.submit(order);
        tasks.end(d.actor(d.maker), instance, "cancel", key(), 0, "发起人撤销");
        assertThat(d.status(order)).isEqualTo("draft");
        assertThat(flowState(instance)).isEqualTo("cancelled");
        instance = d.submit(order);
        tasks.end(d.actor(d.financeA), instance, "terminate", key(), 0, "管理员终止");
        assertThat(d.status(order)).isEqualTo("draft");
        assertThat(flowState(instance)).isEqualTo("terminated");
    }

    @Test
    void emptyReviewerRejectsInsteadOfBypassingManualReview() {
        var assignee =
                new SimpleModel.Assignee(
                        "users", List.of(d.maker), null, null, null, null, null, "reject");
        d.bind(
                root(
                        new SimpleModel.Node(
                                "review",
                                "approver",
                                "拒绝自审",
                                assignee,
                                "and",
                                null,
                                null,
                                null,
                                false)));
        long order = d.order("1", "100"), instance = d.submit(order);
        assertThat(d.status(order)).isEqualTo("draft");
        assertThat(flowState(instance)).isEqualTo("rejected");
        assertThat(pending(instance)).isEmpty();
    }

    @Test
    void minimumPriceWarnBlockAndApprovalAreDifferentServerRules() {
        jdbc.update(
                "UPDATE sku SET min_sale_price=101 WHERE tenant_id=? AND id=?", d.tenant, d.sku);
        long warn = d.order("1", "100");
        d.submit(warn);
        sales.command(
                d.actor(d.manager),
                warn,
                "approve",
                key(),
                new SalesOrderModels.Command(1L, "提示模式", null));
        assertThat(d.status(warn)).isEqualTo("approved");
        jdbc.update(
                "UPDATE company_policy SET below_min_price_policy='block' WHERE tenant_id=?",
                d.tenant);
        assertThatThrownBy(() -> d.order("1", "100"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("MIN_SALE_PRICE_BLOCKED"));
    }

    @Test
    void minimumPriceApprovalRequiresAnExplicitManualPriceNode() {
        jdbc.update(
                "UPDATE sku SET min_sale_price=101 WHERE tenant_id=? AND id=?", d.tenant, d.sku);
        jdbc.update(
                "UPDATE company_policy SET below_min_price_policy='approval' WHERE tenant_id=?",
                d.tenant);
        var base = approve("price", List.of(d.manager), "and", null);
        d.bind(
                root(
                        new SimpleModel.Node(
                                base.id(),
                                base.type(),
                                "价格人工审批",
                                base.assignee(),
                                base.multi(),
                                null,
                                null,
                                null,
                                true)));
        long order = d.order("1", "100"), instance = d.submit(order);
        approveTask(instance, d.manager);
        assertThat(d.status(order)).isEqualTo("approved");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT price_fingerprint IS NOT NULL FROM wf_task_ext WHERE"
                                        + " tenant_id=? AND instance_id=?",
                                Boolean.class,
                                d.tenant,
                                instance))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "role",
                "post",
                "dept_leader",
                "multi_level_leader",
                "field_user",
                "starter_select"
            })
    void organizationAndDocumentAssigneeStrategiesUseCurrentCompany(String strategy) {
        if (strategy.equals("field_user"))
            jdbc.update(
                    "UPDATE company_policy SET separate_order_approver=0 WHERE tenant_id=?",
                    d.tenant);
        var a =
                new SimpleModel.Assignee(
                        strategy,
                        null,
                        strategy.equals("role")
                                ? List.of("FINANCE_" + d.financeA, "FINANCE_" + d.financeB)
                                : null,
                        strategy.equals("post") ? List.of(d.post) : null,
                        strategy.equals("multi_level_leader") ? 2 : 1,
                        false,
                        strategy.equals("field_user") ? "salesUserId" : null,
                        "admin");
        d.bind(
                root(
                        new SimpleModel.Node(
                                "review",
                                "approver",
                                "组织策略",
                                a,
                                strategy.equals("multi_level_leader") ? "sequential" : "and",
                                null,
                                null,
                                null,
                                false)));
        long order = d.order("1", "100");
        sales.command(
                d.actor(d.maker),
                order,
                "submit",
                key(),
                new SalesOrderModels.Command(
                        0L,
                        "提交",
                        null,
                        strategy.equals("starter_select")
                                ? Map.of("review", List.of(d.manager))
                                : null));
        long instance =
                jdbc.queryForObject(
                        "SELECT wf_instance_id FROM sales_order WHERE id=?", Long.class, order);
        assertThat(pending(instance)).isNotEmpty();
        for (int round = 0; round < 5 && !pending(instance).isEmpty(); round++) {
            var row = pending(instance).getFirst();
            approveTask(instance, WorkflowDocuments.number(row.get("assignee_id")));
        }
        assertThat(d.status(order)).isEqualTo("approved");
    }
}
