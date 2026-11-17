package cn.erp.ai.eval;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import cn.erp.ai.common.AiException;

@Component
class BusinessEvaluationSet {
    static final String ID = "erp-data-m15";
    static final String NAME = "业务查数与权限评测";
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MIN_QUESTIONS_PER_TOOL = 20;
    private static final Set<String> CODES = Set.of("receivable_balance","payable_balance","available_stock",
            "order_progress","purchase_progress","sales_summary","gross_margin","cash_balance","document_search");
    private final List<Scenario> scenarios;
    private final List<Case> security;

    BusinessEvaluationSet(ObjectMapper json) throws IOException {
        try (var input=new ClassPathResource("eval/m15-business-data.json").getInputStream()) {
            var resource=json.readValue(input,Resource.class);
            scenarios=List.copyOf(resource.scenarios());
            security=resource.security().stream().map(s ->
                    new Case(s.id(),"security",s.question(),null,null,null,true,s.category())).toList();
        }
        var all=cases(LocalDate.now(BUSINESS_ZONE));
        var counts=all.stream().filter(c -> !c.expectedRefusal())
                .collect(Collectors.groupingBy(Case::toolCode,Collectors.counting()));
        if (!counts.keySet().equals(CODES) || counts.values().stream().anyMatch(n -> n<MIN_QUESTIONS_PER_TOOL)
                || security.size()<10 || all.stream().map(Case::id).distinct().count()!=all.size()
                || all.stream().map(Case::question).distinct().count()!=all.size()
                || scenarioIds().size()!=scenarios.size())
            throw new IllegalStateException("M15 查数评测集分类、数量或编号无效");
    }

    private record Resource(List<Scenario> scenarios,List<Security> security) {}
    private record Scenario(String id,String code,ObjectNode arguments,List<String> questions) {}
    private record Security(String id,String category,String question) {}
    record Case(String id,String category,String question,String scenarioId,String toolCode,JsonNode arguments,
                boolean expectedRefusal,String safetyType) {}

    Set<String> scenarioIds() { return scenarios.stream().map(Scenario::id).collect(Collectors.toUnmodifiableSet()); }
    Set<String> toolCodes() { return CODES; }

    void requireRepresentativeBaseline(JsonNode baseline) {
        var nonempty=scenarios.stream().filter(s -> !baseline.path(s.id()).path("items").isEmpty())
                .map(Scenario::code).collect(Collectors.toUnmodifiableSet());
        if (!nonempty.containsAll(CODES))
            throw AiException.invalid("每个查询工具至少需要一组含实际业务明细的独立报表基准");
    }

    List<Case> cases(LocalDate date) {
        var month=date.withDayOfMonth(1);
        var values=Map.of("%TODAY%",date,"%MONTH_START%",month,"%LAST_MONTH_START%",month.minusMonths(1),
                "%LAST_MONTH_END%",month.minusDays(1));
        var cases=new ArrayList<Case>();
        for (var scenario:scenarios) {
            var arguments=scenario.arguments().deepCopy();
            scenario.arguments().properties().forEach(field -> {
                var resolved=values.get(field.getValue().asString());
                if (resolved!=null) arguments.put(field.getKey(),resolved.toString());
            });
            for (int i=0;i<scenario.questions().size();i++)
                cases.add(new Case(scenario.id()+"-"+(i+1),"tool",scenario.questions().get(i),scenario.id(),
                        scenario.code(),arguments,false,null));
        }
        cases.addAll(security);
        return List.copyOf(cases);
    }
}
