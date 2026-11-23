package cn.erp.einvoice;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class InboundInvoiceBasisMathTest {
    @Test void creditsReduceTheFamilyInsteadOfChangingOriginalPayableFacts() {
        var charges = List.of(source(1, "100", "13"), source(2, "20", "2.60"));
        var result = net(charges, "60", "7.80");
        assertThat(result).extracting(InboundInvoiceAmounts.Available::untaxedAmount).containsExactly(n("50.00"), n("10.00"));
        assertThat(result).extracting(InboundInvoiceAmounts.Available::taxAmount).containsExactly(n("6.50"), n("1.30"));
        assertThat(charges.getFirst().untaxedAmount()).isEqualByComparingTo("100");
    }

    @Test void eachComponentKeepsItsOwnCentsAndTiesUseThePayableId() {
        var first = net(List.of(source(3, "1", "1"), source(1, "1", "1"), source(2, "1", "1")), "1.00", "2.00");
        assertThat(first).extracting(InboundInvoiceAmounts.Available::untaxedAmount).containsExactly(n("0.67"), n("0.67"), n("0.66"));
        assertThat(first).extracting(InboundInvoiceAmounts.Available::taxAmount).containsExactly(n("0.34"), n("0.33"), n("0.33"));
        assertThat(first).isEqualTo(net(List.of(source(1, "1", "1"), source(2, "1", "1"), source(3, "1", "1")), "1.00", "2.00"));
    }

    @Test void aFullyReturnedFamilyHasNoPositiveQuotaAndZeroTaxDoesNotDivideByZero() {
        var result = net(List.of(source(1, "10", "0")), "10", "0");
        assertThat(result.getFirst().untaxedAmount()).isZero();
        assertThat(result.getFirst().taxAmount()).isZero();
        assertThat(net(List.of(), "0", "0")).isEmpty();
        assertThat(net(List.of(source(1, "0", "0")), "1", "1").getFirst().untaxedAmount()).isZero();
    }

    @Test void largeValuesAndOneCentRemaindersKeepExactTotals() {
        String large = "9999999999999999999999.99";
        var result = net(List.of(source(1, large, "0"), source(2, large, "0")), "0.01", "0");
        var sum = result.stream().map(InboundInvoiceAmounts.Available::untaxedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(n(large).multiply(n("2")).subtract(n("0.01")));
        assertThat(result.getFirst().untaxedAmount()).isEqualByComparingTo(large);
        assertThat(result.getLast().untaxedAmount()).isEqualByComparingTo(n(large).subtract(n("0.01")));
    }

    @Test void duplicateOrCrossSupplierChargesAndInvalidCreditsAreRejected() {
        assertThatThrownBy(() -> net(List.of(source(1, "1", "0"), source(1, "1", "0")), "0", "0")).hasMessageContaining("来源额度无效");
        assertThatThrownBy(() -> net(List.of(new InboundInvoiceAmounts.Available(1, 10, n("1"), n("0"))), "0", "0")).hasMessageContaining("来源额度无效");
        assertThatThrownBy(() -> net(List.of(source(1, "1", "0")), "-1", "0")).hasMessageContaining("金额须为非负");
    }

    private static List<InboundInvoiceAmounts.Available> net(List<InboundInvoiceAmounts.Available> charges, String untaxedCredit, String taxCredit) {
        return InboundInvoiceBasisMath.netAvailable(9, charges, n(untaxedCredit), n(taxCredit));
    }
    private static InboundInvoiceAmounts.Available source(long id, String untaxed, String tax) {
        return new InboundInvoiceAmounts.Available(id, 9, n(untaxed), n(tax));
    }
    private static BigDecimal n(String value) { return new BigDecimal(value); }
}
