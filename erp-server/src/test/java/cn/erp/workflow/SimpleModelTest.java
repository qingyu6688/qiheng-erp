package cn.erp.workflow;

import java.util.*;

import org.junit.jupiter.api.Test;

import cn.erp.shared.ApiException;

import static org.assertj.core.api.Assertions.*;

class SimpleModelTest {
    SimpleModel.Assignee user() {
        return new SimpleModel.Assignee(
                "users", List.of(2L), null, null, null, null, null, "admin");
    }

    SimpleModel.Node review() {
        return new SimpleModel.Node(
                "review", "approver", "人工复核", user(), "and", null, null, null, false);
    }

    SimpleModel.Node root(SimpleModel.Node child) {
        return new SimpleModel.Node("root", "start", "发起人", null, null, null, null, child, false);
    }

    @Test
    void everyReachablePathRequiresHumanReview() {
        assertThatThrownBy(() -> SimpleModel.validate(root(null), "sales_order"))
                .isInstanceOf(ApiException.class);
        var branch =
                new SimpleModel.Node(
                        "amount",
                        "condition_branch",
                        "金额",
                        null,
                        null,
                        null,
                        List.of(
                                new SimpleModel.Branch(
                                        "大额",
                                        new SimpleModel.Condition(
                                                "totalAmount", ">", "50000", null, null),
                                        false,
                                        review()),
                                new SimpleModel.Branch("其他", null, true, null)),
                        null,
                        false);
        assertThatThrownBy(() -> SimpleModel.validate(root(branch), "sales_order"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void labelsCannotBecomeFlowableExpressionsAndIdsCannotInjectGeneratedExpressions() {
        for (String label :
                List.of("${''.getClass()}", "#{wfFinished.execute(execution)}", "名称\n控制")) {
            var node =
                    new SimpleModel.Node(
                            "review", "approver", label, user(), "and", null, null, null, false);
            assertThatThrownBy(
                            () -> SimpleCompiler.compile("safe", "测试", root(node), "sales_order"))
                    .isInstanceOf(ApiException.class);
        }
        var node =
                new SimpleModel.Node(
                        "review');evil('x",
                        "approver",
                        "安全名称",
                        user(),
                        "and",
                        null,
                        null,
                        null,
                        false);
        assertThatThrownBy(() -> SimpleModel.validate(root(node), "sales_order"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void manualNodesCannotSkipOrTimeOutAsHumanApproval() {
        var skip =
                new SimpleModel.Assignee(
                        "users", List.of(2L), null, null, null, null, null, "skip");
        assertThatThrownBy(
                        () ->
                                SimpleModel.validate(
                                        root(
                                                new SimpleModel.Node(
                                                        "review",
                                                        "approver",
                                                        "复核",
                                                        skip,
                                                        "and",
                                                        null,
                                                        null,
                                                        null,
                                                        false)),
                                        "sales_order"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(
                        () ->
                                SimpleModel.validate(
                                        root(
                                                new SimpleModel.Node(
                                                        "review",
                                                        "approver",
                                                        "复核",
                                                        user(),
                                                        "and",
                                                        new SimpleModel.Timeout(1, "approve", 1),
                                                        null,
                                                        null,
                                                        false)),
                                        "sales_order"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void numericConditionsKeepDecimalBoundaryWithoutFloatingPointConversion() {
        var condition = new SimpleModel.Condition("totalAmount", ">", "50000", null, null);
        SimpleModel.validateCondition(condition, 0);
        assertThat(SimpleModel.matches(condition, Map.of("totalAmount", "50000.00"))).isFalse();
        assertThat(SimpleModel.matches(condition, Map.of("totalAmount", "50000.00000001")))
                .isTrue();
    }

    @Test
    void unknownFieldsAndUnboundedConditionsAreRejected() {
        assertThatThrownBy(
                        () ->
                                SimpleModel.validateCondition(
                                        new SimpleModel.Condition(
                                                "${runtime}", "==", "1", null, null),
                                        0))
                .isInstanceOf(ApiException.class);
        SimpleModel.Condition condition =
                new SimpleModel.Condition("totalAmount", ">", "1", null, null);
        for (int i = 0; i < 8; i++)
            condition = new SimpleModel.Condition(null, null, null, List.of(condition), null);
        var deep = condition;
        assertThatThrownBy(() -> SimpleModel.validateCondition(deep, 0))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void compilationEmitsFixedDelegatesAndEscapesHumanNames() {
        String xml =
                SimpleCompiler.compile(
                        "safe",
                        "测试",
                        root(
                                new SimpleModel.Node(
                                        "review",
                                        "approver",
                                        "经理 & 财务 <复核>",
                                        user(),
                                        "or",
                                        null,
                                        null,
                                        null,
                                        false)),
                        "sales_order");
        assertThat(xml)
                .contains(
                        "wfPrepare",
                        "wfTaskCreated",
                        "wfFinished",
                        "multiInstanceLoopCharacteristics",
                        "nrOfCompletedInstances")
                .doesNotContain("<复核>");
    }
}
