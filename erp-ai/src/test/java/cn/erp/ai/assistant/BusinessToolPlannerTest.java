package cn.erp.ai.assistant;

import cn.erp.ai.common.AiActor;
import cn.erp.ai.common.AiException;
import cn.erp.ai.common.BusinessToolSpec;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessToolPlannerTest {
    private final JsonMapper json=JsonMapper.builder().build();
    private final BusinessToolPlanner planner=new BusinessToolPlanner(null,null,json);
    private final AiActor actor=new AiActor("101","201","301",
            Set.of("ai:use","inventory:view","sales_order:view"),Set.of(),Set.of());
    private final Set<String> allowed=Set.of("available_stock","document_search");

    @Test void publishesOnlyFixedFieldsAndDocumentsVisibleToCurrentUser() {
        assertThat(BusinessToolSpec.all()).hasSize(9);
        var document=json.valueToTree(BusinessToolSpec.require("document_search").schema(actor));
        assertThat(document.path("additionalProperties").asBoolean()).isFalse();
        assertThat(document.path("properties").path("type").path("enum").toString()).isEqualTo("[\"sales-orders\"]");
        assertThat(planner.parse(actor,allowed,"{\"toolCalls\":[]}")).isEmpty();
        assertThat(planner.parse(actor,allowed,stock()))
                .containsExactly(new BusinessToolPlanner.Call("available_stock",json.readTree("{\"sku\":\"冷链商品\",\"warehouse\":\"杭州仓\"}")));
    }

    @Test void rejectsUnknownExtraWrongTypedAndDuplicateFieldsAndTooManyCalls() {
        rejects(stock().replace("\"杭州仓\"","17"));
        rejects(stock().replace("\"杭州仓\"","null"));
        rejects(stock().replace("\"warehouse\"","\"companyId\""));
        rejects(stock().replace("\"warehouse\"","\"sql\""));
        rejects(stock().replace("\"warehouse\":\"杭州仓\"","\"sku\":\"另一个商品\""));
        rejects(stock()+" {}");
        rejects(stock().replace("\"toolCalls\":","\"untrusted\":1,\"toolCalls\":"));
        String call=stock().substring("{\"toolCalls\":[".length(),stock().length()-2);
        rejects("{\"toolCalls\":["+String.join(",",java.util.Collections.nCopies(6,call))+"]}");
        rejects("{\"toolCalls\":["+call+","+call+"]}");
    }

    @Test void rejectsWriteToolsAndUnauthorizedDocumentKinds() {
        assertThatThrownBy(()->planner.parse(actor,allowed,stock().replace("available_stock","adjust_stock")))
                .isInstanceOf(AiException.class).extracting(e->((AiException)e).status()).isEqualTo(403);
        var badKind=stock().replace("available_stock","document_search")
                .replace("\"sku\":\"冷链商品\",\"warehouse\":\"杭州仓\"","\"type\":\"payments\"");
        assertThatThrownBy(()->planner.parse(actor,allowed,badKind))
                .isInstanceOf(AiException.class).extracting(e->((AiException)e).status()).isEqualTo(403);
        var invalidDate=badKind.replace("payments","sales-orders").replace("\"sales-orders\"","\"sales-orders\",\"dateFrom\":\"2026-02-30\"");
        rejects(invalidDate);
    }

    @Test void companyWideBalanceToolRequiresExactReportPermissionAndCompleteWarehouseScope() {
        var tool=BusinessToolSpec.require("receivable_balance");
        var permissions=Set.of("ai:use","report:receivable_aging");
        assertThat(tool.allowed(new AiActor("101","201","301",permissions,Set.of(),Set.of(),false))).isFalse();
        assertThat(tool.allowed(new AiActor("101","201","301",permissions,Set.of(),Set.of(),true))).isTrue();
    }

    private void rejects(String value) {
        assertThatThrownBy(()->planner.parse(actor,allowed,value)).isInstanceOf(AiException.class)
                .extracting(e->((AiException)e).status()).isEqualTo(400);
    }

    private String stock() {
        return """
                {"toolCalls":[{"code":"available_stock","arguments":{"sku":"冷链商品","warehouse":"杭州仓"}}]}
                """.strip();
    }
}
