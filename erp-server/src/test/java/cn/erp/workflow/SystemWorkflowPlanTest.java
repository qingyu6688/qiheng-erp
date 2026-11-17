package cn.erp.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SystemWorkflowPlanTest {
    @Test
    void everyDefaultModelCompilesWithHumanReview() {
        for (String type : SystemWorkflowPlan.LABELS.keySet()) {
            var plan = SystemWorkflowPlan.tree(type);
            SimpleModel.validate(plan, type);
            assertThat(SimpleModel.reachable(plan, Map.of()).stream()
                    .filter(node -> node.type().equals("approver"))).singleElement()
                    .satisfies(node -> assertThat(node.assignee().strategy()).isEqualTo("approval_permission"));
            assertThat(SimpleCompiler.compile("test_upgrade", "默认复核", plan, type)).contains("userTask");
        }
    }

    @Test
    void salesReviewRetainsBothPriceAndCreditRequirements() {
        var plan = SystemWorkflowPlan.tree("sales_order");
        var low = Map.<String, Object>of("priceApprovalRequired", true, "creditApprovalRequired", false);
        var over = Map.<String, Object>of("priceApprovalRequired", false, "creditApprovalRequired", true);
        var both = Map.<String, Object>of("priceApprovalRequired", true, "creditApprovalRequired", true);
        assertThat(WorkflowRuntime.pricePath(plan, low)).isTrue();
        assertThat(WorkflowRuntime.reviewPath(plan, low, true)).isFalse();
        assertThat(WorkflowRuntime.pricePath(plan, over)).isFalse();
        assertThat(WorkflowRuntime.reviewPath(plan, over, true)).isTrue();
        assertThat(WorkflowRuntime.pricePath(plan, both)).isTrue();
        assertThat(WorkflowRuntime.reviewPath(plan, both, true)).isTrue();
    }
}
