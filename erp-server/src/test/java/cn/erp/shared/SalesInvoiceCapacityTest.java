package cn.erp.shared;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SalesInvoiceCapacityTest {
    @Test void remainingVatUsesTheSameCumulativeRoundingAsInvoiceRegistration() {
        var first = capacity("0.03", "0.01", "0.01", "0.03", "0.01");
        assertThat(first.available()).isEqualByComparingTo("0.02");
        assertThat(first.availableTax()).isEqualByComparingTo("0.01");
        var last = capacity("0.03", "0.01", "0.02", "0.03", "0.02");
        assertThat(last.availableTax()).isEqualByComparingTo("0.00");
    }

    @Test void sharedGroupLimitsTheCandidateAndHistoricalOveruseShowsZero() {
        var limited = capacity("113.00", "13.00", "0.00", "56.50", "0.00");
        assertThat(limited.available()).isEqualByComparingTo("56.50");
        assertThat(limited.availableTax()).isEqualByComparingTo("6.50");
        assertThat(capacity("10", "1", "11", "10", "11").availableTax()).isEqualByComparingTo("0.00");
        assertThat(capacity("0", "0", "0", "0", "0").availableTax()).isEqualByComparingTo("0.00");
    }

    private SalesInvoiceAmounts.Capacity capacity(String amount, String tax, String used, String groupAmount, String groupUsed) {
        return new SalesInvoiceAmounts.Capacity(1, 2L, new BigDecimal(amount), new BigDecimal(tax),
                new BigDecimal(used), new BigDecimal(groupAmount), new BigDecimal(groupUsed));
    }
}
