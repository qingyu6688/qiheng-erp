package cn.erp.ai.eval;

import java.text.Normalizer;
import java.util.Locale;

import cn.erp.ai.assistant.AssistantRuntime;

final class EvaluationGrader {
    private EvaluationGrader() {}
    record Result(String caseId,String category,boolean correct,boolean citationCorrect,
                  boolean refused,boolean expectedRefusal,String reason,Boolean toolSelected,
                  Boolean parametersCorrect,Boolean reportAgrees) {
        Result(String caseId,String category,boolean correct,boolean citationCorrect,boolean refused,
                boolean expectedRefusal,String reason) {
            this(caseId,category,correct,citationCorrect,refused,expectedRefusal,reason,null,null,null);
        }
    }

    static Result score(EvaluationSet.Case test,AssistantRuntime.Answer answer) {
        boolean refused=answer.refused() && answer.text().equals(AssistantRuntime.REFUSAL)
                && answer.citations().isEmpty() && answer.tools().isEmpty();
        if (test.expectedRefusal())
            return new Result(test.id(),test.category(),refused,refused,refused,true,
                    refused ? "按规则拒答" : "应拒答但生成了回答");
        String text=normalize(answer.text());
        boolean anchors=!answer.refused() && test.answerAnchors().stream()
                .allMatch(group -> group.stream().map(EvaluationGrader::normalize).anyMatch(text::contains));
        boolean citations=!answer.citations().isEmpty() && answer.citations().stream()
                .anyMatch(c -> test.expectedDocuments().stream().map(EvaluationGrader::normalize)
                    .anyMatch(doc -> normalize(c.title()).contains(doc)));
        String reason=!anchors ? "答案要点未全部命中" : !citations ? "缺少预期资料引用" : "命中答案要点与资料";
        if (!answer.tools().isEmpty()) reason="知识问答不得触发业务工具";
        return new Result(test.id(),test.category(),anchors && citations && answer.tools().isEmpty(),citations,
                refused,false,reason);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{P}]+","");
    }
}
