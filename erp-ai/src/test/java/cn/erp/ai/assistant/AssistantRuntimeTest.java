package cn.erp.ai.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AssistantRuntimeTest {
    @Test void referenceNumbersMustExistAndEachParagraphMustBeGrounded() {
        assertThat(AssistantRuntime.references("收货过账确认应付。[2]\n\n资金结算另行登记。[1]",2)).containsExactly(2,1);
        assertThat(AssistantRuntime.references("正确步骤。[1]\n\n另一条无来源的说明。",2)).isEmpty();
        assertThat(AssistantRuntime.references("伪造来源。[99]",2)).isEmpty();
        assertThat(AssistantRuntime.references("<script>恶意内容</script>[1]",1)).isEmpty();
        assertThat(AssistantRuntime.references(AssistantRuntime.REFUSAL+"[1]",1)).isEmpty();
    }
}
