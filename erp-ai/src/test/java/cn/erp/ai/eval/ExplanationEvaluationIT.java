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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
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

import cn.erp.ai.assistant.AssistantCatalog;
import cn.erp.ai.common.AiActor;
import cn.erp.ai.common.AiException;
import cn.erp.ai.common.AiRequest;
import cn.erp.ai.gateway.ModelCatalog;
import cn.erp.ai.gateway.ModelGateway;
import cn.erp.ai.gateway.ModelResult;

@SpringBootTest(properties={"erp.ai.kb-worker-enabled=false","erp.ai.search-mode=local"})
@EnabledIfEnvironmentVariable(named="ERP_AI_DB_URL",
        matches="jdbc:mysql://(?:localhost|127[.]0[.]0[.]1):[0-9]+/erp_ai_test(?:_[a-z0-9]+)?(?:[?].*)?")
class ExplanationEvaluationIT {
    private static final String SCOPE="a".repeat(64);
    private static final Set<String> PERMISSIONS=Set.of("ai:use","ai_admin:manage","ai_admin:evaluate",
            "sales_order:view","shipment:view","period:view","period:close");
    private static final Map<Integer,String> BLOCKERS=Map.of(
            101,"CHANGE_PENDING",102,"STATE_CONFLICT",104,"PERIOD_CLOSED",105,"SHIP_QTY_EXCEEDED",
            107,"unposted_documents",108,"pending_money",109,"STATE_CONFLICT",110,"unposted_documents",
            111,"PERIOD_ORDER_INVALID");
    private static final String RELATED="/shipments/49";

    private final String tenant=Long.toString(ThreadLocalRandom.current().nextLong(100_000_000L,900_000_000L));
    private final AiActor actor=new AiActor(tenant,"1","1001",PERMISSIONS,Set.of("51"),Set.of(),true);
    private final AiRequest.Access access=new AiRequest.Access(actor,null);
    @Autowired EvaluationService evaluations;
    @Autowired ExplanationEvaluationSet set;
    @Autowired AssistantCatalog assistants;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ModelGateway model;
    @MockitoBean ModelCatalog models;
    @MockitoBean AiRequest requests;

    @Test
    void scoresRealExplanationServiceWithIsolatedScopeAndControlledModel() throws Exception {
        var baseline=prepare();
        var incompatibleBaseline=baseline.deepCopy();
        incompatibleBaseline.remove("sales-ready");
        assertThatThrownBy(() -> start(incompatibleBaseline))
                .isInstanceOfSatisfying(AiException.class,error -> assertThat(error.status()).isEqualTo(400));
        String run=start(baseline);
        var report=finished(run);
        assertThat(report.path("status").asString()).isEqualTo("completed");
        assertThat(report.path("results").size()).isEqualTo(22);
        assertThat(report.path("correctRate").decimalValue()).isEqualByComparingTo("1");
        assertThat(report.path("citationRate").decimalValue()).isEqualByComparingTo("1");
        assertThat(report.path("securityRate").decimalValue()).isEqualByComparingTo("1");
        assertThat(report.path("passed").asBoolean()).isTrue();
        verify(requests,times(18)).explainDocument(eq(access),any());
        verify(model,times(9)).prioritizeExplanation(eq(actor),anyString(),anyString(),anyString(),any());
        String stored=jdbc.queryForObject("SELECT configuration FROM eval_run WHERE tenant_id=? AND id=?",String.class,tenant,run);
        assertThat(stored).contains("documentBaselineSha256").doesNotContain(RELATED,"CHANGE_PENDING");
        assertThat(evaluations.runs(new AiActor(tenant,"1","1002",PERMISSIONS,Set.of("51"),Set.of(),true),
                ExplanationEvaluationSet.ID)).isEmpty();
        var outsider=new AiActor(Long.toString(Long.parseLong(tenant)+1),"1","1001",PERMISSIONS,Set.of("51"),Set.of(),true);
        assertThatThrownBy(() -> evaluations.detail(outsider,run))
                .isInstanceOfSatisfying(AiException.class,error -> assertThat(error.status()).isEqualTo(404));
        var reader=new AiActor(tenant,"2","1001",Set.of("ai:use"),Set.of(),Set.of(),false);
        assertThatThrownBy(() -> evaluations.cases(reader,ExplanationEvaluationSet.ID))
                .isInstanceOfSatisfying(AiException.class,error -> assertThat(error.status()).isEqualTo(403));
    }

    @Test
    void permissionChangeAbortsBeforeAnyBusinessScoreIsSaved() throws Exception {
        var baseline=prepare();
        var restricted=new AiActor(tenant,"1","1001",PERMISSIONS,Set.of("51"),Set.of(),false);
        when(model.prioritizeExplanation(eq(actor),anyString(),anyString(),anyString(),any())).thenAnswer(call -> {
            when(requests.refresh(access)).thenReturn(restricted);
            return new ModelResult("{\"firstReason\":\"CHANGE_PENDING\"}","1",10,2);
        });
        var report=finished(start(baseline));
        assertThat(report.path("status").asString()).isEqualTo("failed");
        assertThat(report.path("results").size()).isZero();
        assertThat(report.path("passed").asBoolean()).isFalse();
        verify(requests,times(1)).explainDocument(eq(access),any());
    }

    @Test
    void ruleFallbackAndIncorrectSourcesFailTheReleaseGate() throws Exception {
        var baseline=prepare();
        when(model.prioritizeExplanation(eq(actor),anyString(),anyString(),anyString(),any())).thenReturn(
                new ModelResult("{\"firstReason\":\"NOT_IN_ERP\"}","1",10,2));
        ((ObjectNode)baseline.path("period-unposted")).putArray("sourcePaths")
                .add("/accounting-periods?id=107").add("/shipments/50");
        var report=finished(start(baseline));
        assertThat(report.path("status").asString()).isEqualTo("completed");
        assertThat(report.path("correctRate").decimalValue()).isEqualByComparingTo("0.2500");
        assertThat(report.path("citationRate").decimalValue()).isEqualByComparingTo("0.9167");
        assertThat(report.path("securityRate").decimalValue()).isEqualByComparingTo("1");
        assertThat(report.path("results").toString()).contains("来源路径与基准不一致","本次使用了规则解释");
        assertThat(report.path("passed").asBoolean()).isFalse();
    }

    private String start(JsonNode baseline) {
        return evaluations.start(access,ExplanationEvaluationSet.ID,new EvaluationService.StartInput(null,baseline)).get("id");
    }

    private ObjectNode prepare() {
        assistants.save(actor,new AssistantCatalog.Config(null,"internal","单据解释评测助手","",Set.of(),
                Set.of(ExplanationEvaluationSet.TOOL),Set.of(),true));
        when(models.evaluationConfiguration(actor)).thenReturn(Map.of("modelId","1"));
        when(requests.refresh(access)).thenReturn(actor);
        when(requests.documentScope(eq(access),any())).thenReturn(SCOPE);
        when(model.prioritizeExplanation(eq(actor),anyString(),anyString(),anyString(),any())).thenAnswer(call -> {
            ((Runnable)call.getArgument(4)).run();
            var prompt=json.readTree((String)call.getArgument(3));
            assertThat(prompt).isNotNull();
            assertThat(prompt.size()).isEqualTo(2);
            assertThat(prompt.path("reasonCodes").isArray()).isTrue();
            var codes=prompt.path("reasonCodes");
            var reply=json.createObjectNode().put("firstReason",codes.get(codes.size()-1).asString());
            return new ModelResult(reply.toString(),"1",10,2);
        });
        when(requests.explainDocument(eq(access),any())).thenAnswer(call -> fixture(call.getArgument(1)));
        return baseline();
    }

    private ObjectNode baseline() {
        var baseline=json.createObjectNode();
        int id=101;
        for (var test:set.cases()) {
            if (!test.baselineRequired()) continue;
            int documentId=id++;
            var expected=baseline.putObject(test.scenarioId()).put("documentId",Integer.toString(documentId));
            if (test.expectedRefusal()) continue;
            var blockers=test.expectedBlockers();
            expected.put("documentStatus",status(test.documentType(),documentId)).put("checkPassed",blockers.isEmpty());
            expected.putArray("allowedActions");
            var codes=expected.putArray("blockerCodes");
            blockers.forEach(codes::add);
            var sources=expected.putArray("sourcePaths").add(path(test.documentType(),documentId));
            if (test.scenarioId().equals("period-unposted")) sources.add(RELATED);
            if (test.scenarioId().equals("period-multiple")) expected.put("firstReasonCode","pending_money");
        }
        return baseline;
    }

    private ObjectNode fixture(JsonNode arguments) {
        int id=Integer.parseInt(arguments.path("documentId").asString());
        if (id>112) throw id%2==0?AiException.forbidden():AiException.missing();
        String type=arguments.path("documentType").asString();
        String blocker=BLOCKERS.get(id);
        var facts=json.createObjectNode().put("status","ok").put("asOf","2026-11-17T00:00:00Z")
                .put("scopeFingerprint",SCOPE).put("detailUrl",path(type,id));
        facts.putObject("summary").put("documentType",type).put("documentNo","FIXTURE-"+id)
                .put("documentStatus",status(type,id)).put("checkPassed",blocker==null).putArray("allowedActions");
        var items=facts.putArray("items");
        if (blocker!=null) {
            var row=items.addObject().put("code",blocker).put("message","先处理此业务检查项");
            if (id==107) row.putArray("links").addObject().put("label","关联发货单").put("detailUrl",RELATED);
            if (id==110) items.addObject().put("code","pending_money").put("message","处理待确认资金");
        }
        return facts;
    }

    private String path(String type,int id) {
        return switch (type) {
            case "sales_order" -> "/sales-orders/"+id;
            case "shipment" -> "/shipments/"+id;
            default -> "/accounting-periods?id="+id;
        };
    }

    private String status(String type,int id) {
        return switch (type) {
            case "sales_order" -> id==102?"draft":"approved";
            case "shipment" -> "draft";
            default -> id==109?"closed":"open";
        };
    }

    private JsonNode finished(String run) throws InterruptedException {
        long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
        while (System.nanoTime()<deadline) {
            var report=json.valueToTree(evaluations.detail(actor,run));
            if (!"running".equals(report.path("status").asString())) return report;
            Thread.sleep(50);
        }
        throw new AssertionError("单据解释评测未在测试时限内完成");
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM eval_run WHERE tenant_id=?",tenant);
        jdbc.update("DELETE FROM ai_assistant WHERE tenant_id=?",tenant);
    }
}
