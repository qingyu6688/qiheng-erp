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

    @Test void logicalAdjustmentTypesShareStorageAndKeepTheirOwnKind() {
        assertThat(SystemWorkflowPlan.sourceTable("sales_price_adjustment")).isEqualTo("price_adjustment");
        assertThat(SystemWorkflowPlan.sourceKind("sales_price_adjustment","d")).isEqualTo("d.kind='sales'");
        assertThat(SystemWorkflowPlan.sourceKind("price_adjustment","d")).isEqualTo("d.kind='purchase'");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->SystemWorkflowPlan.sourceTable("unknown_table")).isInstanceOf(cn.erp.shared.ApiException.class);
    }

    @Test void existingReversalSourcesRemainAvailableToWorkflowVisibility() {
        for(String type:java.util.List.of("shipment","sales_return","goods_receipt","purchase_return",
                "receipt","payment","account_transfer","stock_adjustment","stock_misc_doc",
                "mfg_issue_doc","mfg_receipt_doc","price_adjustment","purchase_fee_doc"))
            assertThat(SystemWorkflowPlan.sourceTable(type)).isEqualTo(type);
    }
}
