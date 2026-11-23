package cn.erp.einvoice;

import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class InboundInvoiceAmountsTest {
    private static final long SUPPLIER = 9;

    @Test void oneInvoiceCanFullyCoverSeveralPayablesWithoutChangingTheirAmounts() {
        var result = calculate("300", "39", "1", List.of(source(1, "100", "13"), source(2, "200", "26")),
                line(1, "100", "13", "100", "13"), line(2, "200", "26", "200", "26"));
        assertThat(result.fullyAllocated()).isTrue();
        assertThat(result.matchStatus()).isEqualTo("matched");
        assertThat(result.absoluteDifference()).isZero();
        assertThat(result.lines()).extracting(InboundInvoiceAmounts.Part::payableStatus).containsOnly("invoiced");
    }

    @Test void anInvoiceCanFinishWhileItsPayableRemainsPartiallyInvoiced() {
        var result = calculate("100", "13", "1", List.of(source(1, "200", "26")), line(1, "100", "13", "100", "13"));
        assertThat(result.matchStatus()).isEqualTo("matched");
        assertThat(result.lines().getFirst().payableStatus()).isEqualTo("partial");
        var next = calculate("100", "13", "1", List.of(source(1, "100", "13")), line(1, "100", "13", "100", "13"));
        assertThat(next.lines().getFirst().payableStatus()).isEqualTo("invoiced");
    }

    @Test void partialAllocationKeepsBothRemainingComponentsAndZeroTaxCanComplete() {
        var result = calculate("200", "26", "1", List.of(source(1, "100", "13")), line(1, "100", "13", "100", "13"));
        assertThat(result.matchStatus()).isEqualTo("partial");
        assertThat(result.remainingUntaxed()).isEqualByComparingTo("100");
        assertThat(result.remainingTax()).isEqualByComparingTo("13");
        assertThat(calculate("1", "0", "0", List.of(source(1, "1", "0")), line(1, "1", "0", "1", "0")).fullyAllocated()).isTrue();
    }

    @Test void aTailDifferenceCompletesThePayableAtItsOwnAmount() {
        var result = calculate("99.99", "13", "0.01", List.of(source(1, "100", "13")), line(1, "99.99", "13", "100", "13"));
        var part = result.lines().getFirst();
        assertThat(result.matchStatus()).isEqualTo("difference");
        assertThat(result.fullyAllocated()).isTrue();
        assertThat(part.untaxedDifference()).isEqualByComparingTo("-0.01");
        assertThat(part.payableUntaxed().add(part.payableTax())).isEqualByComparingTo("113.00");
        assertThat(part.payableStatus()).isEqualTo("invoiced");
    }

    @Test void differencesCannotCancelAcrossRowsOrTaxComponents() {
        assertCode(() -> calculate("200", "0", "1", List.of(source(1, "100", "0"), source(2, "100", "0")),
                line(1, "101", "0", "100", "0"), line(2, "99", "0", "100", "0")), "INVOICE_DIFFERENCE_EXCEEDED");
        assertCode(() -> calculate("100.60", "12.40", "1", List.of(source(1, "100", "13")),
                line(1, "100.60", "12.40", "100", "13")), "INVOICE_DIFFERENCE_EXCEEDED");
    }

    @Test void partialInvoicesUseTheWholeSavedAllocationForTolerance() {
        var sources = List.of(source(1, "100", "0"), source(2, "100", "0"));
        assertThat(calculate("300", "0", "1", sources, line(1, "100.60", "0", "100", "0")).fullyAllocated()).isFalse();
        assertCode(() -> calculate("300", "0", "1", sources, line(1, "100.60", "0", "100", "0"),
                line(2, "100.60", "0", "100", "0")), "INVOICE_DIFFERENCE_EXCEEDED");
    }

    @Test void invoiceAndPayableTaxCannotBorrowUntaxedQuota() {
        assertCode(() -> calculate("100", "13", "1", List.of(source(1, "100", "13")),
                line(1, "99.50", "13.50", "100", "13")), "INVOICE_ALLOCATION_CONFLICT");
        assertCode(() -> calculate("100", "13", "1", List.of(source(1, "100", "13")),
                line(1, "100", "13", "99.50", "13.50")), "INVOICE_ALLOCATION_CONFLICT");
    }

    @Test void duplicateSourcesWrongSuppliersUnknownSourcesAndZeroRowsAreRejected() {
        var source = List.of(source(1, "100", "0"));
        assertCode(() -> calculate("100", "0", "1", source, line(1, "50", "0", "50", "0"),
                line(1, "50", "0", "50", "0")), "VALIDATION_FAILED");
        assertCode(() -> calculate("100", "0", "1", source, line(2, "1", "0", "1", "0")), "NOT_FOUND");
        assertCode(() -> calculate("100", "0", "1", List.of(new InboundInvoiceAmounts.Available(1, 10, n("100"), n("0"))),
                line(1, "1", "0", "1", "0")), "NOT_FOUND");
        assertCode(() -> calculate("100", "0", "1", source, line(1, "0", "0", "1", "0")), "VALIDATION_FAILED");
        assertCode(() -> calculate("100", "0", "1", source, line(1, "1", "0", "0", "0")), "VALIDATION_FAILED");
    }

    @Test void clearingAllocationsReturnsPendingAndDoesNotMutateTheInput() {
        var source = List.of(source(1, "100", "13"));
        var result = calculate("100", "13", "1", source);
        assertThat(result.lines()).isEmpty();
        assertThat(result.matchStatus()).isEqualTo("pending");
        assertThat(result.remainingUntaxed()).isEqualByComparingTo("100");
        assertThat(source.getFirst().untaxedAmount()).isEqualByComparingTo("100");
        assertThatThrownBy(() -> result.lines().add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void decimalPrecisionIsPreservedAndInvalidMoneyNeverReachesRounding() {
        String large = "9007199254740999999999.99";
        var result = calculate(large, "0", "0", List.of(source(1, large, "0")), line(1, large, "0", large, "0"));
        assertThat(result.allocatedUntaxed().toPlainString()).isEqualTo(large);
        for (String bad : new String[]{"-1", "1.001", "1e2", "", "10000000000000000000000", "+1", " 1"})
            assertCode(() -> InboundInvoiceAmounts.parseMoney(bad), "VALIDATION_FAILED");
        assertThat(InboundInvoiceAmounts.parseMoney("0.1").toPlainString()).isEqualTo("0.10");
    }

    @Test void malformedPolicySourcesAndOversizedAllocationsAreRejected() {
        assertCode(() -> calculate("100", "0", "-1", List.of()), "VALIDATION_FAILED");
        assertCode(() -> calculate("100", "0", "1", List.of(source(1, "1.001", "0"))), "VALIDATION_FAILED");
        var lines = java.util.stream.IntStream.rangeClosed(1, 201).mapToObj(id -> line(id, "1", "0", "1", "0")).toList();
        assertCode(() -> InboundInvoiceAmounts.calculate(SUPPLIER, n("201"), n("0"), n("1"), List.of(), lines), "VALIDATION_FAILED");
        assertCode(() -> calculate("9999999999999999999999", "1", "1", List.of()), "VALIDATION_FAILED");
    }

    private static InboundInvoiceAmounts.Result calculate(String untaxed, String tax, String tolerance,
            List<InboundInvoiceAmounts.Available> sources, InboundInvoiceAmounts.Allocation... lines) {
        return InboundInvoiceAmounts.calculate(SUPPLIER, n(untaxed), n(tax), n(tolerance), sources, List.of(lines));
    }
    private static InboundInvoiceAmounts.Available source(long id, String untaxed, String tax) {
        return new InboundInvoiceAmounts.Available(id, SUPPLIER, n(untaxed), n(tax));
    }
    private static InboundInvoiceAmounts.Allocation line(long id, String iu, String it, String pu, String pt) {
        return new InboundInvoiceAmounts.Allocation(id, n(iu), n(it), n(pu), n(pt));
    }
    private static BigDecimal n(String value) { return new BigDecimal(value); }
    private static void assertCode(Runnable work, String code) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
}
