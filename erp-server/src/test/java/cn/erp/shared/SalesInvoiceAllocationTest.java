package cn.erp.shared;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SalesInvoiceAllocationTest {
    @Test void creditsReduceOldestUntaxedAndTaxSeparatelyAndNewChargesDoNotReorderOldQuotas() {
        var first=SalesInvoiceAllocation.allocate(List.of(charge(1,"100","13","80")),n("20"),n("2.60"));
        var next=SalesInvoiceAllocation.allocate(List.of(charge(1,"100","13","80"),charge(2,"10","1.30","0")),n("20"),n("2.60"));
        assertThat(next.quotas().getFirst()).isEqualTo(first.quotas().getFirst());
        assertThat(next.amount()).isEqualByComparingTo("101.70");
        assertThat(next.tax()).isEqualByComparingTo("11.70");
        assertThat(next.used()).isEqualByComparingTo("80");
    }
    @Test void independentTaxAbsorptionNeverProducesNegativeUntaxedOrTaxAboveGross() {
        var value=SalesInvoiceAllocation.allocate(List.of(charge(1,"0.01","0.01","0"),charge(2,"0.02","0.01","0")),n("0.02"),n("0.01"));
        assertThat(value.amount()).isEqualByComparingTo("0.02");
        assertThat(value.quotas()).allSatisfy(row->{assertThat(row.amount()).isGreaterThanOrEqualTo(row.tax());assertThat(row.tax()).isNotNegative();});
    }
    @Test void fullCreditKeepsZeroQuotaAndHistoricalUseVisible() {
        var value=SalesInvoiceAllocation.allocate(List.of(charge(1,"100","13","113")),n("100"),n("13"));
        assertThat(value.amount()).isZero();assertThat(value.tax()).isZero();assertThat(value.used()).isEqualByComparingTo("113");
    }
    @Test void amountsBeyondDoublePrecisionRetainEveryCent() {
        var value=SalesInvoiceAllocation.allocate(List.of(charge(1,"9007199254740993.12","0.01","0")),n("0.01"),n("0"));
        assertThat(value.amount()).isEqualByComparingTo("9007199254740993.12");
    }
    @Test void creditWithoutAnAvailableChargeAndDuplicateSourcesFailClosed() {
        assertThatThrownBy(()->SalesInvoiceAllocation.allocate(List.of(charge(1,"1","0","0")),n("1.01"),n("0"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->SalesInvoiceAllocation.allocate(List.of(charge(1,"1","0","0"),charge(1,"1","0","0")),n("0"),n("0"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->SalesInvoiceAllocation.allocate(List.of(charge(1,"1","0","2")),n("0"),n("0"))).isInstanceOf(ApiException.class);
    }
    private static SalesInvoiceAllocation.Charge charge(long id,String untaxed,String tax,String used){return new SalesInvoiceAllocation.Charge(id,n(untaxed),n(tax),n(used));}
    private static BigDecimal n(String value){return new BigDecimal(value);}
}
