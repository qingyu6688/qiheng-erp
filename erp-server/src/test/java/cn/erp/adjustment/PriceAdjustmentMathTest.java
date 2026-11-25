package cn.erp.adjustment;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PriceAdjustmentMathTest {
    @Test void adjustsOnlyRemainingTransactionQuantityAndSeparatesTaxByNewTotal() {
        var value = PriceAdjustmentMath.calculate(n("8"), n("1"), n("80.00"), n("9.20"), n("0.13"), false, "12.00", null);
        assertThat(value.amount()).isEqualByComparingTo("16.00");
        assertThat(value.newTax()).isEqualByComparingTo("11.04");
        assertThat(value.tax()).isEqualByComparingTo("1.84");
        assertThat(value.untaxed()).isEqualByComparingTo("14.16");
        assertThat(value.returnUnitCost()).isEqualByComparingTo("10.62");
    }

    @Test void convertsTransactionUnitsBeforeRoundingAndSupportsTaxInclusiveCost() {
        var value = PriceAdjustmentMath.calculate(n("6"), n("3"), n("60.00"), n("0.00"), n("0"), true, "45.00", null);
        assertThat(value.quantity()).isEqualByComparingTo("2");
        assertThat(value.amount()).isEqualByComparingTo("30.00");
        assertThat(value.returnUnitCost()).isEqualByComparingTo("15.00");
    }

    @Test void rebateMayReducePriceToZeroButNotBelowZeroAndRequiresExactlyOneInput() {
        var value = PriceAdjustmentMath.calculate(n("1"), n("1"), n("1.13"), n("0.13"), n("0.13"), false, null, "-1.13");
        assertThat(value.amount()).isEqualByComparingTo("-1.13");
        assertThat(value.tax()).isEqualByComparingTo("-0.13");
        assertThat(value.returnUnitCost()).isZero();
        for (String delta : new String[]{"-1.14", "0", "1.001", "1e3"}) {
            assertThatThrownBy(() -> PriceAdjustmentMath.calculate(n("1"), n("1"), n("1.13"), n("0.13"), n("0.13"), false, null, delta))
                    .isInstanceOf(cn.erp.shared.ApiException.class);
        }
        assertThatThrownBy(() -> PriceAdjustmentMath.calculate(n("1"), n("1"), n("1.13"), n("0.13"), n("0.13"), false, "2", "1"))
                .isInstanceOf(cn.erp.shared.ApiException.class);
    }
    private static BigDecimal n(String value) { return new BigDecimal(value); }
}
