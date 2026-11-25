package cn.erp.purchasefees;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PurchaseFeeMathTest {
    @Test void splitsAmountAndTaxAndKeepsTheTailOnTheLargestAllocation() {
        var result=allocate("100","13",weight(1,"1"),weight(2,"1"),weight(3,"1"));
        assertThat(result).extracting(PurchaseFeeMath.Portion::amount).containsExactly(n("33.34"),n("33.33"),n("33.33"));
        assertThat(result).extracting(PurchaseFeeMath.Portion::tax).containsExactly(n("4.34"),n("4.33"),n("4.33"));
        assertThat(allocate("0.01","0",weight(2,"1"),weight(1,"9")).getFirst().amount()).isEqualByComparingTo("0.01");
    }

    @Test void manySmallAllocationsNeverBecomeNegativeOrReceiveMoreTaxThanMoney() {
        var weights=IntStream.rangeClosed(1,100).mapToObj(id->weight(id,"1")).toList();
        var result=PurchaseFeeMath.allocate(n("0.50"),n("0.29"),weights);
        assertThat(result).allSatisfy(row->{assertThat(row.amount()).isNotNegative();assertThat(row.tax()).isBetween(BigDecimal.ZERO,row.amount());});
        assertThat(result.stream().map(PurchaseFeeMath.Portion::amount).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("0.50");
        assertThat(result.stream().map(PurchaseFeeMath.Portion::tax).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("0.29");
    }

    @Test void zeroWeightRowsDoNotReceiveCostsAndOrderingDoesNotChangeResults() {
        var first=allocate("10","1.30",weight(1,"0"),weight(2,"5"),weight(3,"10"));
        var reordered=allocate("10","1.30",weight(3,"10"),weight(1,"0"),weight(2,"5"));
        assertThat(first).isEqualTo(reordered);
        assertThat(first.getFirst().amount()).isZero();assertThat(first.getFirst().tax()).isZero();
    }

    @Test void largeQuantityAndWeightProductsKeepMoneyPrecision() {
        var result=allocate("9999999999999999999999.99","100",weight(1,"999999999999999999999999999999999999.123456"),weight(2,"1"));
        assertThat(result.stream().map(PurchaseFeeMath.Portion::amount).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("9999999999999999999999.99");
    }

    @Test void partialReceiptsUseCumulativeRoundingAndLastReceiptCompletesTheFee() {
        var first=recognise("100","13","0","0","1","3");
        var second=recognise("100","13","33.33","4.33","2","3");
        var last=recognise("100","13","66.67","8.67","3","3");
        assertThat(first).isEqualTo(new PurchaseFeeMath.Recognition(n("33.33"),n("4.33")));
        assertThat(second).isEqualTo(new PurchaseFeeMath.Recognition(n("33.34"),n("4.34")));
        assertThat(last).isEqualTo(new PurchaseFeeMath.Recognition(n("33.33"),n("4.33")));
        assertThat(recognise("100","13","100","13","4","3").amount()).isZero();
    }

    @Test void increasedOrderQuantityDoesNotCreateNegativeFeesAndEarlierReversalKeepsTaxBounded() {
        assertThat(recognise("100","13","50","6.50","5","20").amount()).isZero();
        var afterReversal=recognise("0.03","0.01","0.02","0","3","3");
        assertThat(afterReversal).isEqualTo(new PurchaseFeeMath.Recognition(n("0.01"),n("0.01")));
    }

    @Test void rejectsZeroWeightsDuplicatesBadTaxAndBrokenRecognition() {
        assertThatThrownBy(()->allocate("1","0",weight(1,"0"))).hasMessageContaining("权重合计为零");
        assertThatThrownBy(()->allocate("1","0",weight(1,"1"),weight(1,"2"))).hasMessageContaining("来源或权重");
        assertThatThrownBy(()->allocate("1","2",weight(1,"1"))).hasMessageContaining("金额及税额");
        assertThatThrownBy(()->recognise("1","0.80","0.90","0","1","1")).hasMessageContaining("进度不一致");
    }

    private List<PurchaseFeeMath.Portion> allocate(String amount,String tax,PurchaseFeeMath.Weight... weights) {
        return PurchaseFeeMath.allocate(n(amount),n(tax),List.of(weights));
    }
    private PurchaseFeeMath.Recognition recognise(String total,String tax,String previous,String previousTax,String received,String weight) {
        return PurchaseFeeMath.recognise(n(total),n(tax),n(previous),n(previousTax),n(received),n(weight));
    }
    private PurchaseFeeMath.Weight weight(long id,String value) {return new PurchaseFeeMath.Weight(id,n(value));}
    private BigDecimal n(String value) {return new BigDecimal(value);}
}
