package cn.erp.shared;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReceiptReturnPricingTest {
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test void untouchedReceiptUsesOriginalCumulativeRounding() {
        var row = receipt("0");
        var first = ReceiptReturnPricing.calculate(row, n("3"), json);
        row.put("returned_base_qty", n("3"));
        var last = ReceiptReturnPricing.calculate(row, n("7"), json);
        assertThat(first.amount().add(last.amount())).isEqualByComparingTo("100.00");
        assertThat(first.tax().add(last.tax())).isEqualByComparingTo("11.50");
    }

    @Test void newBasisDoesNotRepriceAlreadyReturnedQuantityAndLastReturnTakesTheRemainder() {
        var row = receipt("2");
        row.put("pricing_return_basis", json.writeValueAsString(new ReceiptReturnPricing.Basis(n("2"), n("8"), n("96.01"), n("11.05"), n("10.62"), LocalDate.of(2026, 11, 23))));
        var first = ReceiptReturnPricing.calculate(row, n("3"), json);
        row.put("returned_base_qty", n("5"));
        var last = ReceiptReturnPricing.calculate(row, n("5"), json);
        assertThat(first.amount().add(last.amount())).isEqualByComparingTo("96.01");
        assertThat(first.tax().add(last.tax())).isEqualByComparingTo("11.05");
        assertThat(first.unitCost()).isEqualByComparingTo("10.62");
        assertThatThrownBy(() -> ReceiptReturnPricing.calculate(row, n("1"), LocalDate.of(2026, 11, 22), json))
                .isInstanceOf(ApiException.class).hasMessageContaining("不能早于当前调价日期");
    }
    private Map<String, Object> receipt(String returned) {
        var row = new HashMap<String, Object>();
        row.put("base_qty", n("10")); row.put("returned_base_qty", n(returned));
        row.put("payable_amount", n("100.00")); row.put("tax_amount", n("11.50")); row.put("unit_cost", n("8.85"));
        return row;
    }
    private static BigDecimal n(String value) { return new BigDecimal(value); }
}
