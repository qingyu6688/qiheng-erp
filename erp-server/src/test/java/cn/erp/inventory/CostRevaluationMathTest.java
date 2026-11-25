package cn.erp.inventory;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.erp.shared.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CostRevaluationMathTest {
    @Test @DisplayName("两次入库后的出库按加权成本计算差额，原金额留在差异记录")
    void weightedIssuePreservesOriginalAndAddsOnlyDifference() {
        var result=replay("0","0.00",event(1,"10","100.00"),event(2,"5","100.00"),event(3,"-6","-60.00"));
        assertThat(result.quantity()).isEqualByComparingTo("9");
        assertThat(result.cost()).isEqualByComparingTo("120.00");
        assertThat(result.differences()).singleElement().satisfies(line->{
            assertThat(line.movement()).isEqualTo(3);
            assertThat(line.before()).isEqualByComparingTo("-60.00");
            assertThat(line.after()).isEqualByComparingTo("-80.00");
            assertThat(line.delta()).isEqualByComparingTo("-20.00");
        });
    }

    @Test @DisplayName("同一范围再次重放承接既有差额，不重复调整")
    void previousRevaluationIsIncludedInEffectiveCost() {
        var issued=new CostRevaluationMath.Event(3,3,0,"sales_out",n("-6"),n("-60.00"),n("-20.00"),n("0.00"));
        var result=replay("0","0.00",event(1,"10","100.00"),event(2,"5","100.00"),issued);
        assertThat(result.differences()).isEmpty();
        assertThat(result.cost()).isEqualByComparingTo("120.00");
    }

    @Test @DisplayName("出尽采用剩余全部成本，原清零尾差合并后不多调一分钱")
    void finalIssueAbsorbsRoundingResidue() {
        var last=new CostRevaluationMath.Event(2,2,0,"sales_out",n("-2"),n("-0.66"),n("0.00"),n("-0.01"));
        var result=replay("3","1.00",event(1,"-1","-0.33"),last);
        assertThat(result.quantity()).isZero();
        assertThat(result.cost()).isZero();
        assertThat(result.differences()).isEmpty();
    }

    @Test @DisplayName("采购退货沿用原价，不能改成当前平均成本")
    void originalPricePurchaseReturnRemainsFixed() {
        var returned=new CostRevaluationMath.Event(1,1,0,"purchase_return_out",n("-3"),n("-45.00"),n("0.00"),n("0.00"));
        var result=replay("10","100.00",returned);
        assertThat(result.cost()).isEqualByComparingTo("55.00");
        assertThat(result.differences()).isEmpty();
    }

    @Test @DisplayName("原价退货触发库存成本封底时，保留原差异处理结果")
    void originalReturnVarianceKeepsInventoryFromGoingNegative() {
        var returned=new CostRevaluationMath.Event(1,1,0,"purchase_return_out",n("-3"),n("-90.00"),n("0.00"),n("87.00"));
        var result=replay("10","10.00",returned);
        assertThat(result.cost()).isEqualByComparingTo("7.00");
        assertThat(result.differences()).isEmpty();
    }

    @Test @DisplayName("原出库及其冲销同时重估时，两笔差额互相抵消")
    void reversalRestoresRevaluedOriginalCost() {
        var inverse=new CostRevaluationMath.Event(2,2,1,"sales_out",n("4"),n("40.00"),n("0.00"),n("0.00"));
        var result=replay("10","200.00",event(1,"-4","-40.00"),inverse);
        assertThat(result.quantity()).isEqualByComparingTo("10");
        assertThat(result.cost()).isEqualByComparingTo("200.00");
        assertThat(result.differences()).hasSize(2);
        assertThat(result.differences().stream().map(CostRevaluationMath.Difference::delta).reduce(BigDecimal.ZERO,BigDecimal::add)).isZero();
    }

    @Test @DisplayName("原流水在起算范围之前时，冲销保留已有有效成本")
    void reversalOutsideWindowUsesFrozenEffectiveSnapshot() {
        var inverse=new CostRevaluationMath.Event(2,2,99,"sales_out",n("4"),n("40.00"),n("40.00"),n("0.00"));
        var result=replay("6","120.00",inverse);
        assertThat(result.cost()).isEqualByComparingTo("200.00");
        assertThat(result.differences()).isEmpty();
    }

    @Test @DisplayName("费用在实际发生位置加入成本池，只影响其后的出库")
    void supplementalCostAffectsOnlySubsequentIssues() {
        var fee=new CostRevaluationMath.Event(1,0,0,"adjustment",n("0"),n("20.00"),n("0.00"),n("0.00"));
        var result=replay("10","100.00",fee,event(2,"-5","-50.00"));
        assertThat(result.cost()).isEqualByComparingTo("60.00");
        assertThat(result.differences()).singleElement().satisfies(line->assertThat(line.delta()).isEqualByComparingTo("-10.00"));
    }

    @Test @DisplayName("后来补入的事实不会倒排到已经发生的出库之前")
    void laterReceiptDoesNotRewriteEarlierIssue() {
        var result=replay("0","0.00",event(1,"10","100.00"),event(2,"-5","-50.00"),event(3,"10","200.00"));
        assertThat(result.cost()).isEqualByComparingTo("250.00");
        assertThat(result.differences()).isEmpty();
    }

    @Test @DisplayName("固定来源入库仍按原冻结成本进入成本池")
    void returnedGoodsKeepFrozenInboundCost() {
        var returned=new CostRevaluationMath.Event(2,2,0,"sales_return_in",n("2"),n("50.00"),n("0.00"),n("0.00"));
        var result=replay("5","100.00",returned,event(3,"-1","-20.00"));
        assertThat(result.cost()).isEqualByComparingTo("128.57");
        assertThat(result.differences()).singleElement().satisfies(line->assertThat(line.delta()).isEqualByComparingTo("-1.43"));
    }

    @Test @DisplayName("重放发现负库存、负成本或不完整冲销时拒绝执行")
    void inconsistentFactsAreRejected() {
        assertThatThrownBy(()->replay("1","10.00",event(1,"-2","-20.00"))).isInstanceOf(ApiException.class).hasMessageContaining("库存数量或成本不一致");
        var negative=new CostRevaluationMath.Event(1,0,0,"adjustment",n("0"),n("-20.00"),n("0.00"),n("0.00"));
        assertThatThrownBy(()->replay("1","10.00",negative)).isInstanceOf(ApiException.class);
        var partial=new CostRevaluationMath.Event(2,2,1,"sales_out",n("2"),n("20.00"),n("0.00"),n("0.00"));
        assertThatThrownBy(()->replay("10","100.00",event(1,"-3","-30.00"),partial)).isInstanceOf(ApiException.class);
    }

    private static CostRevaluationMath.Result replay(String quantity,String cost,CostRevaluationMath.Event... events) {
        return CostRevaluationMath.replay(n(quantity),n(cost),List.of(events));
    }
    private static CostRevaluationMath.Event event(long id,String quantity,String cost) {
        return new CostRevaluationMath.Event(id,id,0,n(quantity).signum()>0?"purchase_in":"sales_out",n(quantity),n(cost),n("0.00"),n("0.00"));
    }
    private static BigDecimal n(String value) {return new BigDecimal(value);}
}
