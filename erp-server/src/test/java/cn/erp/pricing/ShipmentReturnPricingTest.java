package cn.erp.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import cn.erp.shared.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShipmentReturnPricingTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 23);
    private final PricingEngine pricing = new PricingEngine();
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test void oldShipmentUsesSignedQuantityAndOriginalCumulativeRounding() {
        var row = shipment("3", "0", "33.33", "3.83");
        row.put("base_qty", n("10"));
        var first = calculate(row, "1");
        row.put("returned_base_qty", n("1"));
        var last = calculate(row, "2");
        assertThat(first.amount().add(last.amount())).isEqualByComparingTo("33.33");
        assertThat(first.tax().add(last.tax())).isEqualByComparingTo("3.83");
    }

    @Test void newPriceAffectsOnlyRemainingReturnsAndTheLastReturnTakesTheTail() {
        var row = shipment("10", "2", "113.00", "13.00");
        basis(row, "2", "8", "96.01", "11.05", DAY);
        var first = calculate(row, "3");
        row.put("returned_base_qty", n("5"));
        var last = calculate(row, "5");
        assertThat(first.amount()).isEqualByComparingTo("36.00");
        assertThat(first.tax()).isEqualByComparingTo("4.14");
        assertThat(first.amount().add(last.amount())).isEqualByComparingTo("96.01");
        assertThat(first.tax().add(last.tax())).isEqualByComparingTo("11.05");
    }

    @Test void successivePriceBasisDoesNotRepricePreviouslyReturnedAmounts() {
        var row = shipment("10", "5", "113.00", "13.00");
        row.put("credited_amount", n("58.60"));
        row.put("credited_tax", n("6.74"));
        basis(row, "5", "5", "50.03", "5.76", DAY);
        var first = calculate(row, "2");
        row.put("returned_base_qty", n("7"));
        var last = calculate(row, "3");
        assertThat(first.amount().add(last.amount())).isEqualByComparingTo("50.03");
        assertThat(first.tax().add(last.tax())).isEqualByComparingTo("5.76");
        assertThat(row.get("credited_amount")).isEqualTo(n("58.60"));
        assertThat(row.get("credited_tax")).isEqualTo(n("6.74"));
    }

    @Test void fullDiscountReturnsQuantityWithoutCreatingMoreReceivableCredit() {
        var row = shipment("10", "2", "113.00", "13.00");
        basis(row, "2", "8", "0.00", "0.00", DAY);
        var value = calculate(row, "8");
        assertThat(value.amount()).isZero();
        assertThat(value.tax()).isZero();
    }

    @Test void costAndOriginalRevenueSnapshotsAreNeitherReadNorRewritten() {
        var row = shipment("10", "2", "113.00", "13.00");
        row.put("unit_cost", "不应参与货款计算的成本字段");
        row.put("signed_cost_amount", n("80.00"));
        basis(row, "2", "8", "96.01", "11.05", DAY);
        var before = Map.copyOf(row);
        calculate(row, "3");
        assertThat(row).isEqualTo(before);
        row.remove("unit_cost");
        row.remove("signed_cost_amount");
        assertThat(calculate(row, "3").amount()).isEqualByComparingTo("36.00");
    }

    @Test void returnDateCannotPrecedeTheCurrentSalesPrice() {
        var row = shipment("10", "2", "113.00", "13.00");
        basis(row, "2", "8", "96.01", "11.05", DAY);
        assertThatThrownBy(() -> ShipmentReturnPricing.calculate(row, n("1"), DAY.minusDays(1), pricing, json))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getErrorCode()).isEqualTo("RETURN_DATE_BEFORE_PRICE"));
    }

    @Test void unsignedAlreadyReturnedAndNonpositiveQuantitiesAreRejected() {
        for (BigDecimal requested : Arrays.asList(n("0"), n("-1"), n("4"), n("0.0000001"), null)) {
            var row = shipment("3", "0", "33.33", "3.83");
            assertThatThrownBy(() -> ShipmentReturnPricing.calculate(row, requested, DAY, pricing, json))
                    .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getErrorCode()).isEqualTo("RETURN_QTY_EXCEEDED"));
        }
        assertThatThrownBy(() -> calculate(shipment("3", "3", "33.33", "3.83"), "1"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> calculate(shipment("0", "0", "0", "0"), "1"))
                .isInstanceOf(ApiException.class);
    }

    @Test void staleQuantityAnchorsAndInvalidMoneyFailClosed() {
        String[][] invalid = {{"6", "4", "50", "5"}, {"2", "9", "50", "5"},
                {"2", "8", "50", "51"}, {"2", "8", "-1", "0"}, {"2", "8", "1E+100", "0"}};
        for (var fields : invalid) {
            var row = shipment("10", "5", "113.00", "13.00");
            basis(row, fields[0], fields[1], fields[2], fields[3], DAY);
            assertThatThrownBy(() -> calculate(row, "1"))
                    .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getErrorCode()).isEqualTo("RETURN_PRICING_CHANGED"));
        }
    }

    @Test void incompleteOrMalformedPriceSnapshotsReturnABusinessError() {
        for (String snapshot : Arrays.asList("{", "null", "{}", "[]")) {
            var row = shipment("10", "2", "113.00", "13.00");
            row.put("pricing_return_basis", snapshot);
            assertThatThrownBy(() -> calculate(row, "1"))
                    .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getErrorCode()).isEqualTo("RETURN_PRICING_CHANGED"));
        }
    }

    @Test void largeAmountsKeepEveryCentWithoutFloatingPointConversion() {
        String amount = "9999999999999999999999.99", tax = "1300000000000000000000.00";
        var row = shipment("3", "0", amount, tax);
        BigDecimal total = n("0"), totalTax = n("0");
        for (int returned = 0; returned < 3; returned++) {
            row.put("returned_base_qty", BigDecimal.valueOf(returned));
            var value = calculate(row, "1");
            total = total.add(value.amount()); totalTax = totalTax.add(value.tax());
        }
        assertThat(total).isEqualByComparingTo(amount);
        assertThat(totalTax).isEqualByComparingTo(tax);
    }

    @Test void missingOriginalSnapshotsHaveAnExplicitSourceError() {
        var row = shipment("3", "0", "33.33", "3.83");
        row.remove("tax_amount");
        assertThatThrownBy(() -> calculate(row, "1"))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getErrorCode()).isEqualTo("RETURN_SOURCE_INVALID"));
        assertThatThrownBy(() -> ShipmentReturnPricing.calculate(null, n("1"), DAY, pricing, json))
                .isInstanceOf(ApiException.class);
    }

    private ShipmentReturnPricing.Credit calculate(Map<String, Object> row, String quantity) {
        return ShipmentReturnPricing.calculate(row, n(quantity), DAY, pricing, json);
    }

    private void basis(Map<String, Object> row, String previous, String quantity, String amount, String tax, LocalDate day) {
        row.put("pricing_return_basis", json.writeValueAsString(new ShipmentReturnPricing.Basis(n(previous), n(quantity), n(amount), n(tax), day)));
    }

    private static Map<String, Object> shipment(String signed, String returned, String amount, String tax) {
        var row = new HashMap<String, Object>();
        row.put("signed_base_qty", n(signed)); row.put("returned_base_qty", n(returned));
        row.put("receivable_amount", n(amount)); row.put("tax_amount", n(tax));
        return row;
    }

    private static BigDecimal n(String text) { return new BigDecimal(text); }
}
