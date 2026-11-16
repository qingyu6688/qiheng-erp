package cn.erp.gl;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class GlFormulaTest {
    @Test
    void arithmeticAndReferences() {
        var formula = GlFormula.parse("QM(1122,D)+R(receivables)*2-(3/2)");
        assertThat(formula.subjectCodes()).containsExactly("1122");
        assertThat(formula.rowReferences()).containsExactly("receivables");
        assertThat(
                        formula.evaluate(
                                (function, args) ->
                                        function.equals("R")
                                                ? new BigDecimal("2.25")
                                                : new BigDecimal("10")))
                .isEqualByComparingTo("13.00");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "T(java.lang.Runtime).getRuntime()",
                "new BigDecimal(1)",
                "QC(1001);DELETE",
                "R(a,b)",
                "QC(1001,Z)",
                "1/0",
                "QC(1001)+",
                "System.exit(0)",
                "1e10",
                "XJ('C01')",
                "CAT(secret)"
            })
    void rejectsUnsafeOrInvalidExpressions(String input) {
        assertThatThrownBy(() -> GlFormula.parse(input).evaluate((f, a) -> BigDecimal.ONE))
                .isInstanceOf(cn.erp.shared.ApiException.class);
    }

    @Test
    void limitsDepth() {
        assertThatThrownBy(() -> GlFormula.parse("(".repeat(30) + "1" + ")".repeat(30)))
                .hasMessageContaining("24");
    }
}
