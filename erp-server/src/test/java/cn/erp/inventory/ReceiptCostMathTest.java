package cn.erp.inventory;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ReceiptCostMathTest {
    @Test void partiallyIssuedCostSplitsIntoStockAndVariance() {
        var rows = ReceiptCostMath.allocate(1, n("10"), n("30.00"), Map.of(1L, n("4")), Map.of(1L, n("100.00")));
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.stock()).isEqualByComparingTo("12.00");
            assertThat(row.issued()).isEqualByComparingTo("18.00");
        });
    }

    @Test void oneCentAcrossTwoWarehousesDoesNotCreateAnExtraCent() {
        var rows = ReceiptCostMath.allocate(1, n("2"), n("0.01"), Map.of(1L, n("1"), 2L, n("1")),
                Map.of(1L, n("10"), 2L, n("10")));
        assertThat(rows.stream().map(ReceiptCostMath.Allocation::stock).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("0.01");
        assertThat(rows.stream().map(ReceiptCostMath.Allocation::issued).reduce(BigDecimal.ZERO, BigDecimal::add)).isZero();
    }

    @Test void negativeAdjustmentCannotMakeCurrentStockCostNegative() {
        var rows = ReceiptCostMath.allocate(1, n("10"), n("-50.00"), Map.of(2L, n("10")), Map.of(2L, n("12.00")));
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.warehouse()).isEqualTo(2);
            assertThat(row.stock()).isEqualByComparingTo("-12.00");
        }).anySatisfy(row -> {
            assertThat(row.warehouse()).isEqualTo(1);
            assertThat(row.issued()).isEqualByComparingTo("-38.00");
        });
    }

    @Test void noCurrentShareMeansAllCostIsIssuedVarianceAndShareCannotExceedTheAdjustment() {
        var rows = ReceiptCostMath.allocate(1, n("2"), n("-3.00"), Map.of(), Map.of());
        assertThat(rows).singleElement().satisfies(row -> assertThat(row.issued()).isEqualByComparingTo("-3.00"));
        var capped = ReceiptCostMath.allocate(1, n("2"), n("3.00"), Map.of(1L, n("4")), Map.of(1L, n("10")));
        assertThat(capped).singleElement().satisfies(row -> {
            assertThat(row.stock()).isEqualByComparingTo("3.00");
            assertThat(row.issued()).isZero();
        });
    }

    private static BigDecimal n(String value) { return new BigDecimal(value); }
}
