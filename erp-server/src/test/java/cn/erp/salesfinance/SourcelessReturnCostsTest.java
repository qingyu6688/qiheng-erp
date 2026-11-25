package cn.erp.salesfinance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import cn.erp.pricing.PricingEngine;
import cn.erp.shared.ApiException;
import cn.erp.salesfinance.SourcelessReturnCosts.Stock;
import static org.assertj.core.api.Assertions.*;

class SourcelessReturnCostsTest {
    private final PricingEngine pricing=new PricingEngine();
    private final SourcelessReturnCosts costs=new SourcelessReturnCosts(pricing);

    @Test void averageUsesExactRatioBeforeRoundingTheGroupAmount() {
        var basis=costs.choose(stock("3","0.01","9"),null,null);
        assertThat(basis.source()).isEqualTo("average");
        assertThat(basis.unitCost()).isEqualByComparingTo("0.00333333");
        // 八位单价乘1.5会舍成零；原成本比例计算应为一分。
        assertThat(costs.allocate(basis,List.of(decimal("1.5")))).containsExactly(decimal("0.01"));
        assertThat(costs.allocate(basis,Collections.nCopies(3,BigDecimal.ONE)))
                .containsExactly(decimal("0.01"),decimal("0.00"),decimal("0.00"));
    }

    @Test void zeroAverageAndZeroLastCostAreUsableSources() {
        var average=costs.choose(stock("4","0","7"),null,"");
        assertThat(average.source()).isEqualTo("average");
        assertThat(costs.allocate(average,List.of(BigDecimal.ONE))).containsExactly(decimal("0.00"));
        var recent=costs.choose(stock("0","0","0"),null,null);
        assertThat(recent.source()).isEqualTo("last_in");
        assertThat(recent.unitCost()).isEqualByComparingTo("0");
    }

    @Test void emptyStockUsesLastCostAndPreservesItsPrecision() {
        var recent=costs.choose(stock("0","0","2.12345678"),null,null);
        assertThat(recent.source()).isEqualTo("last_in");
        assertThat(recent.version()).isEqualTo(7);
        assertThat(costs.allocate(recent,List.of(decimal("3")))).containsExactly(decimal("6.37"));
    }

    @Test void manualCostRequiresMissingAutomaticSourceAndAnExplanation() {
        var empty=stock("0","0",null);
        assertThatThrownBy(()->costs.choose(empty,null,null)).isInstanceOfSatisfying(ApiException.class,
                error->assertThat(error.getStatus()).isEqualTo(422));
        assertThatThrownBy(()->costs.choose(empty,BigDecimal.ZERO," ")).isInstanceOf(ApiException.class);
        var manual=costs.choose(empty,BigDecimal.ZERO,"历史单据确认零成本");
        assertThat(manual.source()).isEqualTo("manual");
        assertThat(costs.allocate(manual,List.of(BigDecimal.ONE))).containsExactly(decimal("0.00"));
        assertThatThrownBy(()->costs.choose(stock("1","1",null),decimal("9"),"手填依据"))
                .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getStatus()).isEqualTo(409));
        assertThatThrownBy(()->costs.choose(stock("0","0","0"),decimal("9"),"手填依据")).isInstanceOf(ApiException.class);
    }

    @Test void rejectsInvalidCostQuantityPrecisionAndOverflow() {
        var empty=stock("0","0",null);
        for(String value:List.of("-1","1.000000001","10000000000000000"))
            assertThatThrownBy(()->costs.choose(empty,decimal(value),"历史依据")).isInstanceOf(ApiException.class);
        var basis=costs.choose(stock("1","1",null),null,null);
        for(String value:List.of("0","-1","1.0000001"))
            assertThatThrownBy(()->costs.allocate(basis,List.of(decimal(value)))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->costs.allocate(basis,List.of())).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->costs.allocate(new SourcelessReturnCosts.Basis(null,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,0),List.of(BigDecimal.ONE)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(()->costs.allocate(basis,Collections.nCopies(501,BigDecimal.ONE))).isInstanceOf(ApiException.class);
        var maximum=costs.choose(stock("0","0","9999999999999999.99999999"),null,null);
        assertThatThrownBy(()->costs.allocate(maximum,List.of(decimal("10000000")))).isInstanceOf(ApiException.class);
    }

    @Test void lowGroupCostAcrossManyLinesNeverProducesNegativeOrConcentratedAmounts() {
        var basis=costs.choose(stock("100","0.51",null),null,null);
        var shares=costs.allocate(basis,Collections.nCopies(100,BigDecimal.ONE));
        assertThat(shares.subList(0,51)).allSatisfy(value->assertThat(value).isEqualByComparingTo("0.01"));
        assertThat(shares.subList(51,100)).allSatisfy(value->assertThat(value).isEqualByComparingTo("0.00"));
        assertThat(sum(shares)).isEqualByComparingTo("0.51");
    }

    @Test void weightedCostsStayWithinAdjacentCentsAndKeepTheGroupTotal() {
        for(int sample=1;sample<=500;sample++) {
            var stock=stock("100",BigDecimal.valueOf((sample*7919L)%200001,2).toPlainString(),null);
            int count=sample%17+1,offset=sample%7;
            var quantities=IntStream.range(0,count).mapToObj(index->BigDecimal.valueOf(offset+index+1,2)).toList();
            var basis=costs.choose(stock,null,null);
            var shares=costs.allocate(basis,quantities);
            var total=stock.amount().multiply(sum(quantities)).divide(stock.quantity(),2,RoundingMode.HALF_UP);
            assertThat(sum(shares)).isEqualByComparingTo(total);
            for(int index=0;index<count;index++) {
                var exact=total.multiply(quantities.get(index)).divide(sum(quantities),16,RoundingMode.HALF_UP);
                assertThat(shares.get(index)).isBetween(exact.setScale(2,RoundingMode.FLOOR),exact.setScale(2,RoundingMode.CEILING));
            }
        }
    }

    @Test void commonAllocationAcceptsLargeMonetaryWeightsAndMixedScales() {
        var shares=pricing.allocateAmount(decimal("0.03"),List.of(decimal("1000000000000000000000.00"),decimal("2000000000000000000000")));
        assertThat(shares).containsExactly(decimal("0.01"),decimal("0.02"));
        assertThat(pricing.allocateAmount(decimal("0.01"),List.of(decimal("0.1"),decimal("0.200000"))))
                .containsExactly(decimal("0.00"),decimal("0.01"));
    }

    private static Stock stock(String quantity,String amount,String recent) {
        return new Stock(decimal(quantity),decimal(amount),recent==null?null:decimal(recent),7);
    }
    private static BigDecimal decimal(String value) { return new BigDecimal(value); }
    private static BigDecimal sum(List<BigDecimal> values) { return values.stream().reduce(BigDecimal.ZERO,BigDecimal::add); }
}
