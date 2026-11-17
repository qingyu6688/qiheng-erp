package cn.erp.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;
import cn.erp.ai.assistant.AssistantCatalog;
import cn.erp.ai.assistant.BusinessToolPlanner;
import cn.erp.ai.common.AiActor;
import cn.erp.ai.common.AiException;
import cn.erp.ai.common.AiRequest;
import cn.erp.ai.common.BusinessToolSpec;
import cn.erp.ai.gateway.ModelCatalog;
import cn.erp.ai.gateway.ModelGateway;
import cn.erp.ai.gateway.ModelResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties={"erp.ai.kb-worker-enabled=false","erp.ai.search-mode=local"})
@EnabledIfEnvironmentVariable(named="ERP_AI_DB_URL",
        matches="jdbc:mysql://(?:localhost|127[.]0[.]0[.]1):[0-9]+/erp_ai_test(?:_[a-z0-9]+)?(?:[?].*)?")
class BusinessEvaluationIT {
    private static final String SCOPE="a".repeat(64);
    private static final Map<String,String> INDEPENDENT_REPORT = Map.of(
            "receivable_balance","{\"amountOpen\":\"180.01\"}",
            "payable_balance","{\"amountOpen\":\"249.97\"}",
            "available_stock","{\"onHandQty\":\"12.000000\",\"availableQty\":\"10.000001\"}",
            "order_progress","{\"orderedQty\":\"12.000000\",\"signedQty\":\"2.000000\"}",
            "purchase_progress","{\"orderedQty\":\"30.000000\",\"receivedQty\":\"27.000000\"}",
            "sales_summary","{\"amount\":\"950.00\",\"qty\":\"9.000000\"}",
            "gross_margin","{\"grossMargin\":\"260.00\"}",
            "cash_balance","{\"closingBalance\":\"9007199254740993.01\"}",
            "document_search","{\"documentNo\":\"A02-SO01\",\"status\":\"draft\"}");
    private final String tenant=Long.toString(ThreadLocalRandom.current().nextLong(100_000_000L,900_000_000L));
    private final AiActor actor=actor(tenant,"1001");
    private final AiRequest.Access access=new AiRequest.Access(actor,null);
    @Autowired EvaluationService evaluations;
    @Autowired BusinessEvaluationSet set;
    @Autowired AssistantCatalog assistants;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ModelGateway model;
    @MockitoBean ModelCatalog models;
    @MockitoBean AiRequest requests;

    @Test
    void gradesAllCasesWithControlledModelAndIndependentFixtureAndIsolatesEvaluationResults() throws Exception {
        var baseline=prepare();
        assertThatThrownBy(() -> evaluations.start(access,BusinessEvaluationSet.ID,null))
                .isInstanceOfSatisfying(AiException.class,error -> assertThat(error.status()).isEqualTo(400));
        String run=evaluations.start(access,BusinessEvaluationSet.ID,new EvaluationService.StartInput(baseline)).get("id");
        var report=finished(run);
        assertThat(report.path("status").asString()).isEqualTo("completed");
        assertThat(report.path("results").size()).isEqualTo(198);
        assertThat(report.path("toolSelectionRate").decimalValue()).isEqualByComparingTo("1");
        assertThat(report.path("parameterRate").decimalValue()).isEqualByComparingTo("1");
        assertThat(report.path("reportAgreementRate").decimalValue()).isEqualByComparingTo("1");
        assertThat(report.path("securityRate").decimalValue()).isEqualByComparingTo("1");
        assertThat(report.path("passed").asBoolean()).isTrue();
        verify(requests,times(180)).businessTool(eq(access),anyString(),any());
        assertThat(jdbc.queryForObject("SELECT configuration FROM eval_run WHERE tenant_id=? AND id=?",String.class,tenant,run))
                .contains("reportBaselineSha256").doesNotContain("9007199254740993.01","A02-SO01");
        assertIsolation(run);
    }

    @Test
    void scopeChangeAbortsBeforeStoringABusinessResult() throws Exception {
        var baseline=prepare();
        when(requests.businessTool(eq(access),anyString(),any())).thenAnswer(call ->
                queryFixture(call.getArgument(1)).put("scopeFingerprint","b".repeat(64)));
        String run=evaluations.start(access,BusinessEvaluationSet.ID,new EvaluationService.StartInput(baseline)).get("id");
        var report=finished(run);
        assertThat(report.path("status").asString()).isEqualTo("failed");
        assertThat(report.path("results").size()).isZero();
        assertThat(report.path("passed").asBoolean()).isFalse();
        verify(requests,times(1)).businessTool(eq(access),anyString(),any());
    }

    private ObjectNode prepare() {
        assistants.save(actor,new AssistantCatalog.Config(null,"internal","查数评测助手","",Set.of(),
                BusinessToolSpec.codes(),Set.of(),true));
        when(models.evaluationConfiguration(actor)).thenReturn(Map.of("modelId","1"));
        when(requests.refresh(access)).thenReturn(actor);
        when(requests.businessScope(eq(access),anyString())).thenReturn(SCOPE);
        var cases=set.cases(LocalDate.now(BusinessEvaluationSet.BUSINESS_ZONE));
        var byQuestion=cases.stream().collect(Collectors.toMap(BusinessEvaluationSet.Case::question,Function.identity()));
        when(model.planBusinessQuery(eq(actor),eq(BusinessToolPlanner.SCENE),anyString(),anyString(),any())).thenAnswer(call -> {
            var test=byQuestion.get(json.readTree((String)call.getArgument(3)).path("question").asString());
            ((Runnable)call.getArgument(4)).run();
            var response=json.createObjectNode();
            var calls=response.putArray("toolCalls");
            if (!test.expectedRefusal()) calls.addObject().put("code",test.toolCode()).set("arguments",test.arguments());
            else if ("sec-company".equals(test.id()))
                calls.addObject().put("code","available_stock").set("arguments",
                        json.readTree("{\"sku\":\"A02-SKU01\",\"companyId\":\"999\"}"));
            return new ModelResult(response.toString(),"1",11,3);
        });
        when(requests.businessTool(eq(access),anyString(),any())).thenAnswer(call ->
                queryFixture(call.getArgument(1)));
        var baseline=json.createObjectNode();
        cases.stream().filter(test -> !test.expectedRefusal()).forEach(test ->
                baseline.putObject(test.scenarioId()).put("status","ok").putArray("items")
                        .add(json.readTree(INDEPENDENT_REPORT.get(test.toolCode()))));
        return baseline;
    }

    private void assertIsolation(String run) {
        assertThat(evaluations.runs(actor(tenant,"1002"),BusinessEvaluationSet.ID)).isEmpty();
        assertThatThrownBy(() -> evaluations.detail(actor(tenant,"1002"),run))
                .isInstanceOfSatisfying(AiException.class,error -> assertThat(error.status()).isEqualTo(404));
        assertThatThrownBy(() -> evaluations.detail(actor(Long.toString(Long.parseLong(tenant)+1),"1001"),run))
                .isInstanceOfSatisfying(AiException.class,error -> assertThat(error.status()).isEqualTo(404));
        var reader=new AiActor(tenant,"1","1001",Set.of("ai:use"),Set.of(),Set.of());
        assertThatThrownBy(() -> evaluations.cases(reader,BusinessEvaluationSet.ID))
                .isInstanceOfSatisfying(AiException.class,error -> assertThat(error.status()).isEqualTo(403));
    }

    private JsonNode finished(String run) throws InterruptedException {
        long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
        while (System.nanoTime()<deadline) {
            var report=json.valueToTree(evaluations.detail(actor,run));
            if (!"running".equals(report.path("status").asString())) return report;
            Thread.sleep(50);
        }
        throw new AssertionError("业务查数评测未在测试时限内完成");
    }

    /** 模拟工具结果单独构造，固定报表精度对照来自独立夹具。 */
    private ObjectNode queryFixture(String code) {
        var data=json.createObjectNode().put("status","ok").put("asOf","2026-11-17T00:00:00Z")
                .put("scopeFingerprint",SCOPE);
        var row=data.putArray("items").addObject();
        switch(code) {
            case "receivable_balance" -> row.put("amountOpen","180.01");
            case "payable_balance" -> row.put("amountOpen","249.97");
            case "available_stock" -> row.put("onHandQty","12.000000").put("availableQty","10.000001");
            case "order_progress" -> row.put("orderedQty","12.000000").put("signedQty","2.000000");
            case "purchase_progress" -> row.put("orderedQty","30.000000").put("receivedQty","27.000000");
            case "sales_summary" -> row.put("amount","950.00").put("qty","9.000000");
            case "gross_margin" -> row.put("grossMargin","260.00");
            case "cash_balance" -> row.put("closingBalance","9007199254740993.01");
            case "document_search" -> row.put("documentNo","A02-SO01").put("status","draft");
            default -> throw new AssertionError("评测执行了未知查数工具");
        }
        return data;
    }

    private static AiActor actor(String tenant,String company) {
        var permissions=new HashSet<>(Set.of("ai:use","ai_admin:manage","ai_admin:evaluate","shipment:view"));
        BusinessToolSpec.all().forEach(tool -> permissions.addAll(tool.permissions()));
        return new AiActor(tenant,"1",company,permissions,Set.of("51"),Set.of("61"),true);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM eval_run WHERE tenant_id=?",tenant);
        jdbc.update("DELETE FROM ai_assistant WHERE tenant_id=?",tenant);
    }
}
