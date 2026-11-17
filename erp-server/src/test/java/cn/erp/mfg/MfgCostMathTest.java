package cn.erp.mfg;

import cn.erp.inventory.InventoryModels.Key;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static cn.erp.mfg.MfgCostMath.*;
import static org.assertj.core.api.Assertions.assertThat;

class MfgCostMathTest {
    @Test void rollsFinishedComponentVarianceThroughTheNextOrderAndRetainsWip() {
        var semi=new Key(10,100);
        var item=new Key(10,101);
        var flow=new MfgCostFlow();
        flow.record(move(1,semi,"2","mfg_receipt_in",1,0));
        flow.record(move(2,semi,"-1","mfg_issue_out",0,2));
        flow.record(move(3,item,"1","mfg_receipt_in",2,0));
        flow.record(move(4,item,"-0.5","sales_out",0,0));
        var work=new LinkedHashMap<Long,Work>();
        work.put(2L,new Work(2,n("2"),n("1"),n("1"),n("30"),n("15"),ZERO,ZERO,ZERO,n("50")));
        work.put(1L,new Work(1,n("2"),n("2"),n("2"),n("20"),n("20"),ZERO,ZERO,ZERO,null));

        var plan=calculate(work,flow.finish(),Map.of(1L,n("6"),2L,n("3")),Map.of(1L,n("4"),2L,n("1")),"materials");
        assertThat(plan.orders()).extracting(OrderCost::workOrderId).containsExactly(1L,2L);
        var semiCost=plan.orders().getFirst();
        assertThat(semiCost.finishedCost()).isEqualByComparingTo("30.00");
        assertThat(semiCost.rolledVariance()).isEqualByComparingTo("5.00");
        var productCost=plan.orders().getLast();
        assertThat(productCost.wipEnd()).isEqualByComparingTo("17.50");
        assertThat(productCost.finishedCost()).isEqualByComparingTo("21.50");
        assertThat(plan.issued()).containsExactly(new Valuation(10,101,n("3.25")));
        assertThat(plan.inventory()).containsExactly(new Valuation(10,100,n("5.00")),new Valuation(10,101,n("3.25")));
    }

    @Test void equivalentProductionAndSignedCentsRemainDeterministic() {
        var works=Map.of(1L,new Work(1,n("10"),n("6"),n("6"),n("64"),n("70"),ZERO,ZERO,ZERO,n("50")));
        var plan=calculate(works,Map.of(1L,Map.of(Destination.inventory(10,100),n("6"))),Map.of(1L,n("16")),Map.of(),"equivalent");
        assertThat(plan.orders().getFirst().wipEnd()).isEqualByComparingTo("20.00");
        assertThat(plan.inventory()).containsExactly(new Valuation(10,100,n("-10.00")));
        var weights=new LinkedHashMap<String,BigDecimal>();
        List.of("一","二","三").forEach(key->weights.put(key,BigDecimal.ONE));
        assertThat(split(n("0.02"),weights).values()).containsExactly(n("0.01"),n("0.00"),n("0.01"));
    }

    @Test void retainsProportionAcrossTransferAndSaleReturn() {
        var source=new Key(10,100);
        var target=new Key(20,100);
        var flow=new MfgCostFlow();
        flow.opening(source,n("5"));
        flow.record(move(1,source,"5","mfg_receipt_in",1,0));
        flow.record(new MfgCostFlow.Movement(2,source,n("-4"),"transfer_out",20,0,0,0,0,0));
        flow.record(new MfgCostFlow.Movement(3,target,n("4"),"transfer_in",30,0,0,0,20,0));
        flow.record(new MfgCostFlow.Movement(4,target,n("-2"),"sales_out",40,0,0,0,0,0));
        flow.record(new MfgCostFlow.Movement(5,target,n("1"),"sales_return_in",50,0,0,0,0,40));
        var result=flow.finish().get(1L);
        assertThat(result.get(Destination.inventory(10,100))).isEqualByComparingTo("3");
        assertThat(result.get(Destination.inventory(20,100))).isEqualByComparingTo("1.5");
        assertThat(result.get(Destination.issued(20,100))).isEqualByComparingTo("0.5");
    }

    private static MfgCostFlow.Movement move(long id,Key key,String qty,String type,long producing,long consuming) {
        return new MfgCostFlow.Movement(id,key,n(qty),type,id,0,producing,consuming,0,0);
    }

    private static BigDecimal n(String quantity) {return new BigDecimal(quantity);}
}
