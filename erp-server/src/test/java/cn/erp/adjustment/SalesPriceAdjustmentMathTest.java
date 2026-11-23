package cn.erp.adjustment;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SalesPriceAdjustmentMathTest {
    @Test void salesSharesVatAndUnitConversionWithoutComputingReturnInventoryCost() {
        var result=PriceAdjustmentMath.sales(n("8"),n("2"),n("90.40"),n("10.40"),n("0.13"),"24.86",null);
        assertThat(result.quantity()).isEqualByComparingTo("4");
        assertThat(result.newAmount()).isEqualByComparingTo("99.44");assertThat(result.amount()).isEqualByComparingTo("9.04");
        assertThat(result.tax()).isEqualByComparingTo("1.04");
    }
    @Test void aLegalSalesPriceDoesNotFailBecauseAnUnusedEightDecimalCostWouldOverflow() {
        var result=PriceAdjustmentMath.sales(n("1"),n("1"),n("999999999999999999.00"),n("0"),n("0"),"999999999999999999.01",null);
        assertThat(result.amount()).isEqualByComparingTo("0.01");
    }
    @Test void fullDiscountAndSignedDeltaUseTheSameR3TailRule() {
        var result=PriceAdjustmentMath.sales(n("3"),n("1"),n("33.33"),n("3.83"),n("0.13"),null,"-33.33");
        assertThat(result.newAmount()).isZero();assertThat(result.newTax()).isZero();assertThat(result.tax()).isEqualByComparingTo("-3.83");
    }
    @Test void repeatingUnitConversionRoundsAHalfCentOnceForBothSalesAndPurchases() {
        var sales=PriceAdjustmentMath.sales(n("1"),n("3"),n("0.30"),n("0"),n("0"),"1.005",null);
        var purchase=PriceAdjustmentMath.calculate(n("1"),n("3"),n("0.30"),n("0"),n("0"),false,"1.005",null);
        assertThat(sales.newAmount()).isEqualByComparingTo("0.34");assertThat(purchase.newAmount()).isEqualByComparingTo("0.34");
        assertThat(sales.amount()).isEqualByComparingTo("0.04");
    }
    private static BigDecimal n(String value){return new BigDecimal(value);}
}
