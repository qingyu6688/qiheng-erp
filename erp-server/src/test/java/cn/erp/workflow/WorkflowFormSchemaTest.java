package cn.erp.workflow;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import cn.erp.shared.ApiException;
import static org.assertj.core.api.Assertions.*;

class WorkflowFormSchemaTest {
    @Test void validatesSafeFormVocabularyBeforeRuntime() {
        var money = new WorkflowFormSchema.Field("money","金额","number",true,null);
        var day = new WorkflowFormSchema.Field("date","日期","date",false,null);
        assertThat(WorkflowFormSchema.values(WorkflowFormSchema.describe(List.of(money,day)),Map.of("money","10.20"),Set.of("money"),true))
                .containsEntry("money","10.20").containsEntry("date", null);
        assertThatThrownBy(() -> WorkflowFormSchema.describe(List.of(new WorkflowFormSchema.Field("render","组件","html",false,null))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> WorkflowFormSchema.describe(List.of(new WorkflowFormSchema.Field("constructor","组件","text",false,null))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> WorkflowFormSchema.values(List.of(day),Map.of("date","2026-02-29"),Set.of("date"),false))
                .isInstanceOf(ApiException.class);
    }

    @Test void protectedFieldsCannotBeSmuggledAlongsideWritableFields() {
        var one = new WorkflowFormSchema.Field("one","可写","text",true,null);
        var two = new WorkflowFormSchema.Field("two","受控","text",true,null);
        assertThatThrownBy(() -> WorkflowFormSchema.values(List.of(one,two),Map.of("one","通过","two","越权"),Set.of("one"),false))
                .isInstanceOf(ApiException.class);
    }
}
