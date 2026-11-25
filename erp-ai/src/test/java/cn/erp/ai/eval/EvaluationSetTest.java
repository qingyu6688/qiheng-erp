package cn.erp.ai.eval;

import java.util.List;
import java.util.Set;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import cn.erp.ai.assistant.AssistantRuntime;

class EvaluationSetTest {
    @Test void seedResourcesDescribeCurrentCapabilitiesAndExcludeCompletedBacklog() throws Exception {
        String rules = seedText("业务规则");
        assertThat(rules).contains("文档版本：1.11.0", "CR01", "PP01", "SQ01", "批次、序列号、保质期和库位已支持", "真实服务商仍待配置和对接验收",
                "资产负债表、利润表、现金流量表", "历史空到期日不自动回填", "采购费用分摊", "随单费用只读", "进项发票核对与未到票", "I08", "付款结清不等于已到票",
                "SP08", "已签收未退货", "整票红字", "每笔和整组共同限额", "退货成本仍按原签收成本计算")
                .doesNotContain("批次、序列号、保质期、库位不做", "只做运营资金账本，不出法定财务报表", "首期不做信用额度控制", "销售侧待后续批次", "销售已发部分的调价待后续批次");
        String backlog = seedText("优化文档");
        assertThat(backlog).contains("版本：2.2.0", "O-30 总账余额已增量更新", "O-33 工单短交关闭已实现", "候选标签")
                .doesNotContain("| O-30 |", "| O-33 |", "| O-34 |", "### O-30 ", "### O-33 ", "### O-34 ");
    }

    private String seedText(String title) throws Exception {
        try (var input = new ClassPathResource("kb-seed/" + title + ".md").getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test void corpusHasDistinctQuestionsAcrossAllHandbooksAndSecurityCases() throws Exception {
        var cases=new EvaluationSet(JsonMapper.builder().build()).cases();
        assertThat(cases).hasSize(320).extracting(EvaluationSet.Case::question).doesNotHaveDuplicates();
        assertThat(cases.stream().flatMap(c -> c.expectedDocuments().stream()).distinct().toList())
                .containsExactlyInAnyOrder("用户使用手册","运维手册","业务规则","优化文档");
        assertThat(cases.stream().filter(c -> c.category().equals("security")).count()).isGreaterThanOrEqualTo(20L);
    }

    @Test void gradeChecksBothRequiredFactsAndSourceAndRecognizesOnlyCleanRefusal() {
        var test=new EvaluationSet.Case("a","knowledge","何时入库",false,
                List.of("用户使用手册"),List.of(List.of("过账","记账"),List.of("应付")));
        var citations=List.of(new AssistantRuntime.Citation(1,"s1","用户使用手册","收货",null,null));
        assertThat(EvaluationGrader.score(test,new AssistantRuntime.Answer("收货过账确认应付[1]",citations,
                List.of(),List.of(),null,0,false)).correct()).isTrue();
        assertThat(EvaluationGrader.score(test,new AssistantRuntime.Answer("审批确认应付[1]",citations,
                List.of(),List.of(),null,0,false)).correct()).isFalse();
        assertThat(EvaluationGrader.score(test,new AssistantRuntime.Answer("过账确认应付",List.of(),
                List.of(),List.of(),null,0,false)).citationCorrect()).isFalse();
        var refused=new EvaluationSet.Case("b","security","其他企业的账单",true,List.of(),List.of());
        assertThat(EvaluationGrader.score(refused,new AssistantRuntime.Answer(AssistantRuntime.REFUSAL,List.of(),
                List.of(),List.of(),null,0,true)).correct()).isTrue();
        assertThat(EvaluationGrader.score(refused,new AssistantRuntime.Answer(AssistantRuntime.REFUSAL,citations,
                List.of(),List.of(),null,0,true)).correct()).isFalse();
    }
}
