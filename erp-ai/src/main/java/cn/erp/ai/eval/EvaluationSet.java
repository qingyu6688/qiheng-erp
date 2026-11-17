package cn.erp.ai.eval;

import java.io.IOException;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
class EvaluationSet {
    static final String ID = "erp-kb-m13";
    static final String NAME = "内部知识问答与安全评测";
    private final List<Case> cases;

    EvaluationSet(ObjectMapper json) throws IOException {
        try (var input = new ClassPathResource("eval/m13-kb.json").getInputStream()) {
            cases=List.copyOf(json.readValue(input,new TypeReference<List<Case>>(){}));
        }
        if (cases.size() < 200 || cases.stream().map(Case::id).distinct().count()!=cases.size())
            throw new IllegalStateException("M13 评测集不足200条或编号重复");
        for (Case test : cases) {
            if (!test.expectedRefusal() && (test.expectedDocuments().isEmpty() || test.answerAnchors().isEmpty()))
                throw new IllegalStateException("应回答用例必须指定知识出处和答案要点");
        }
    }

    record Case(String id,String category,String question,boolean expectedRefusal,
                List<String> expectedDocuments,List<List<String>> answerAnchors) {}

    List<Case> cases() { return cases; }
}
