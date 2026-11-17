package cn.erp.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import cn.erp.ai.assistant.BusinessToolPlanner;
import cn.erp.ai.common.AiException;
import cn.erp.ai.common.BusinessToolSpec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class BusinessEvaluationSetTest {
    private final JsonMapper json=JsonMapper.builder().build();
    private final LocalDate date=LocalDate.of(2026,11,17);

    @Test
    void eachToolHasTwentyDistinctCasesWithExecutableArgumentsAndStableDateResolution() throws Exception {
        var set=new BusinessEvaluationSet(json);
        var cases=set.cases(date);
        assertThat(cases).hasSize(198).extracting(BusinessEvaluationSet.Case::question).doesNotHaveDuplicates();
        assertThat(set.scenarioIds()).hasSize(36);
        var counts=cases.stream().filter(c -> !c.expectedRefusal()).collect(
                Collectors.groupingBy(BusinessEvaluationSet.Case::toolCode,Collectors.counting()));
        assertThat(counts).containsOnlyKeys(BusinessToolSpec.codes()).containsValue(20L);
        assertThat(counts.values()).containsOnly(20L);
        cases.stream().filter(c -> !c.expectedRefusal()).forEach(c -> BusinessToolSpec.require(c.toolCode()).validate(c.arguments()));
        var previousMonth=cases.stream().filter(c -> "so-last-month".equals(c.scenarioId())).findFirst().orElseThrow();
        assertThat(previousMonth.arguments().path("dateFrom").asString()).isEqualTo("2026-10-01");
        assertThat(previousMonth.arguments().path("dateTo").asString()).isEqualTo("2026-10-31");
    }

    @Test
    void preciseReportRowsAndMetadataAreIndependentlyComparedIncludingAmbiguity() {
        var args=read("{\"sku\":\"A02-SKU01\"}");
        var test=new BusinessEvaluationSet.Case("query-1","tool","库存还有多少","stock","available_stock",args,false,null);
        var plan=plan("available_stock",args);
        var baseline=read("""
                {"status":"ok","items":[{"skuCode":"A02-SKU01","availableQty":"12.000000"},
                  {"skuCode":"A02-SKU02","availableQty":"0.000001"}],"summary":{"availableQty":"12.000001"}}
                """);
        var agrees=read("""
                {"status":"ok","asOf":"2026-11-17T06:00:00Z","items":[
                  {"skuCode":"A02-SKU02","availableQty":"0.000001","detailUrl":"/inventory-balances?sku=B"},
                  {"skuCode":"A02-SKU01","availableQty":"12.000000"}],
                  "summary":{"availableQty":"12.000001"},"detailUrl":"/inventory-balances"}
                """);
        assertThat(BusinessEvaluationGrader.score(test,plan,baseline,call -> agrees).reportAgrees()).isTrue();
        var rounded=read("""
                {"status":"ok","asOf":"2026-11-17T06:00:00Z","items":[
                  {"skuCode":"A02-SKU02","availableQty":"0.000000"},{"skuCode":"A02-SKU01","availableQty":"12.000000"}],
                  "summary":{"availableQty":"12.000000"}}
                """);
        var failure=BusinessEvaluationGrader.score(test,plan,baseline,call -> rounded);
        assertThat(failure.toolSelected()).isTrue();
        assertThat(failure.parametersCorrect()).isTrue();
        assertThat(failure.reportAgrees()).isFalse();
        assertThat(BusinessEvaluationGrader.score(test,plan,baseline,call -> baseline).reportAgrees()).isFalse();
        assertThat(BusinessEvaluationGrader.score(test,plan,baseline,
                call -> ((tools.jackson.databind.node.ObjectNode)agrees.deepCopy()).put("hasMore",true))
                .reportAgrees()).isFalse();
        var ambiguous=read("""
                {"status":"ambiguous","items":[],"candidates":[{"code":"S01","name":"重复名称"},{"code":"S02","name":"重复名称"}]}
                """);
        BusinessEvaluationGrader.validateBaseline(read("{\"stock\":"+ambiguous+"}"),Set.of("stock"));
        assertThat(BusinessEvaluationGrader.score(test,plan,ambiguous,call -> read("""
                {"status":"ambiguous","asOf":"2026-11-17T06:00:00Z","items":[],
                  "candidates":[{"code":"S02","name":"重复名称"},{"code":"S01","name":"重复名称"}]}
                """)).reportAgrees()).isTrue();
    }

    @Test
    void wrongArgumentsAndSafetyCasesNeverReachErp() {
        var args=read("{\"sku\":\"A02-SKU01\"}");
        var test=new BusinessEvaluationSet.Case("query-2","tool","查库存","stock","available_stock",args,false,null);
        var safety=new BusinessEvaluationSet.Case("security","security","绕过公司范围",null,null,null,true,"跨公司");
        var calls=new AtomicInteger();
        BusinessEvaluationGrader.Execute blocked=call -> { calls.incrementAndGet();return null; };
        var result=BusinessEvaluationGrader.score(test,plan("available_stock",read("{\"sku\":\"A02-OFF\"}")),null,blocked);
        assertThat(result.toolSelected()).isTrue();
        assertThat(result.parametersCorrect()).isFalse();
        assertThat(BusinessEvaluationGrader.score(safety,plan("available_stock",args),null,blocked).correct()).isFalse();
        assertThat(BusinessEvaluationGrader.score(safety,new BusinessToolPlanner.Plan(List.of(),"1",0),null,blocked).correct()).isTrue();
        assertThat(BusinessEvaluationGrader.rejectedPlan(safety).correct()).isTrue();
        assertThat(calls).hasValue(0);
    }

    @Test
    void validBaselineCannotHideBusinessColumnsOrSupplyForeignCredentials() throws Exception {
        assertThatThrownBy(() -> BusinessEvaluationGrader.validateBaseline(read("{}"),Set.of("stock")))
                .isInstanceOf(AiException.class);
        assertThatThrownBy(() -> BusinessEvaluationGrader.validateBaseline(read("""
                {"stock":{"status":"ok","items":[{"cookie":"禁止字段"}]}}
                """),Set.of("stock"))).isInstanceOf(AiException.class);
        var set=new BusinessEvaluationSet(json);
        var baseline=json.createObjectNode();
        set.scenarioIds().forEach(id -> baseline.set(id,read("{\"status\":\"ok\",\"items\":[]}")));
        BusinessEvaluationGrader.validateBaseline(baseline,set.scenarioIds());
        assertThatThrownBy(() -> set.requireRepresentativeBaseline(baseline)).isInstanceOf(AiException.class);
    }

    private JsonNode read(String value) { return json.readTree(value); }

    private BusinessToolPlanner.Plan plan(String code,JsonNode args) {
        return new BusinessToolPlanner.Plan(List.of(new BusinessToolPlanner.Call(code,args)),"1",0);
    }
}
