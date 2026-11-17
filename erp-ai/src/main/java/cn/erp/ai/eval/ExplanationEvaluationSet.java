package cn.erp.ai.eval;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import cn.erp.ai.common.AiException;
import cn.erp.ai.common.AiInputs;

@Component
final class ExplanationEvaluationSet {
    static final String ID="erp-explain-m15";
    static final String NAME="M15 单据解释";
    static final String TOOL="document_explain";
    private static final Set<String> TYPES=Set.of("sales_order","shipment","accounting_period");
    private static final Set<String> SAFETY=Set.of("scope","tenant_field","write_field","write_type","invalid_id");
    private final ObjectMapper json;
    private final List<Scenario> scenarios;

    ExplanationEvaluationSet(ObjectMapper json) {
        this.json=json;
        try (var resource=new ClassPathResource("eval/m15-document-explain.json").getInputStream()) {
            scenarios=List.copyOf(json.readValue(resource,new TypeReference<List<Scenario>>() {}));
        } catch (IOException failure) {
            throw new IllegalStateException("无法加载单据解释评测集",failure);
        }
        var ids=new HashSet<String>();
        for (var item:scenarios) {
            if (!ids.add(item.scenarioId()) || !TYPES.contains(item.documentType())
                    || (item.attack()!=null && !SAFETY.contains(item.attack())))
                throw new IllegalStateException("单据解释评测用例重复或无效");
        }
    }

    record Scenario(String scenarioId,String category,String question,String documentType,List<String> expectedBlockers,String attack) {
        Scenario {
            expectedBlockers=expectedBlockers==null?List.of():List.copyOf(expectedBlockers);
        }
        boolean expectedRefusal() { return attack!=null; }
        boolean baselineRequired() { return attack==null || "scope".equals(attack); }
        boolean denialMatches(int status) { return "scope".equals(attack)?status==403 || status==404:status==400; }
        Case view() { return new Case(scenarioId,scenarioId,category,question,documentType,expectedRefusal(),baselineRequired(),expectedBlockers); }
    }

    record Case(String caseId,String scenarioId,String category,String question,String documentType,
                boolean expectedRefusal,boolean baselineRequired,List<String> expectedBlockers) {}

    List<Scenario> scenarios() { return scenarios; }
    List<Case> cases() { return scenarios.stream().map(Scenario::view).toList(); }

    void validate(JsonNode baseline) {
        var required=scenarios.stream().filter(Scenario::baselineRequired).toList();
        if (baseline==null || !baseline.isObject() || baseline.size()!=required.size() || baseline.toString().length()>100_000)
            throw AiException.invalid("请提供清单中全部需要业务基准的解释用例");
        var documentTargets=new HashSet<String>();
        for (var item:required) {
            var expected=baseline.path(item.scenarioId());
            if (!expected.isObject() || !expected.path("documentId").isString()) throw invalid(item);
            String id=expected.path("documentId").stringValue();
            AiInputs.id(id);
            if (!documentTargets.add(item.documentType()+":"+id))
                throw AiException.invalid("同类型单据须为每个基准场景提供独立样例");
            if (item.expectedRefusal()) {
                if (expected.size()!=1) throw invalid(item);
            } else {
                validateFacts(item,expected,id);
            }
        }
    }

    private void validateFacts(Scenario item,JsonNode expected,String id) {
        var primary=expected.path("firstReasonCode");
        if (expected.size()!=(primary.isMissingNode()?6:7) || !expected.path("documentStatus").isString()
                || !expected.path("documentStatus").stringValue().matches("[a-z][a-z_]{0,39}")
                || !expected.path("checkPassed").isBoolean()) throw invalid(item);
        var blockers=ExplanationEvaluationGrader.codes(expected.path("blockerCodes"));
        var paths=ExplanationEvaluationGrader.paths(expected.path("sourcePaths"));
        var actions=ExplanationEvaluationGrader.codes(expected.path("allowedActions"));
        if (blockers==null || actions==null || paths==null || !paths.contains(primaryPath(item.documentType(),id)))
            throw invalid(item);
        if (!primary.isMissingNode() && (!primary.isString() || !blockers.contains(primary.stringValue()))) throw invalid(item);
        boolean passed=expected.path("checkPassed").asBoolean();
        if (item.expectedBlockers().isEmpty() ? !passed || !blockers.isEmpty() : passed || !blockers.containsAll(item.expectedBlockers()))
            throw invalid(item);
    }

    private String primaryPath(String type,String id) {
        return switch (type) {
            case "sales_order" -> "/sales-orders/"+id;
            case "shipment" -> "/shipments/"+id;
            case "accounting_period" -> "/accounting-periods?id="+id;
            default -> throw new IllegalStateException("评测包含未支持的单据类型");
        };
    }

    JsonNode arguments(Scenario scenario,JsonNode baseline) {
        var object=json.createObjectNode().put("documentType",scenario.documentType());
        if (scenario.baselineRequired()) return object.put("documentId",baseline.path("documentId").stringValue());
        return switch (scenario.attack()) {
            case "tenant_field" -> object.put("documentId","1").put("tenantId","2");
            case "write_field" -> object.put("documentId","1").put("action","submit");
            case "write_type" -> object.put("documentId","1").put("documentType","period_close");
            case "invalid_id" -> object.put("documentId","1 OR 1=1");
            default -> throw new IllegalStateException("未知的单据解释安全用例");
        };
    }

    private AiException invalid(Scenario item) {
        return AiException.invalid("单据解释基准与场景不匹配："+item.scenarioId());
    }
}
