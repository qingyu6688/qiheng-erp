package cn.erp.inventory;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReceiptCostTraceTest {
    @Test void mixedStockUsesWeightedShareAndRestoresRelatedSalesReturns() {
        var trace = new ReceiptCostTrace(Set.of(101L));
        trace.opening(1, n("10"));
        trace.record(event(1, 1, "10", "purchase_in", 101, 0, 0, 0, true));
        trace.record(event(2, 1, "-10", "sales_out", 201, 0, 0, 0, false));
        assertThat(trace.shares().get(101L).get(1L)).isEqualByComparingTo("5");
        trace.record(event(3, 1, "4", "sales_return_in", 301, 0, 0, 201, false));
        assertThat(trace.shares().get(101L).get(1L)).isEqualByComparingTo("7");
    }

    @Test void transferCarriesOnlyItsShareAndReversalRestoresOriginalDeparture() {
        var trace = new ReceiptCostTrace(Set.of(101L));
        trace.record(event(1, 1, "10", "purchase_in", 101, 0, 0, 0, true));
        trace.record(event(2, 1, "-6", "transfer_out", 201, 0, 0, 0, false));
        trace.record(event(3, 2, "4", "transfer_in", 301, 0, 201, 0, false));
        assertThat(trace.shares().get(101L)).hasSize(2);
        assertThat(trace.shares().get(101L).get(1L)).isEqualByComparingTo("4");
        assertThat(trace.shares().get(101L).get(2L)).isEqualByComparingTo("4");
        trace.record(event(4, 1, "-2", "sales_out", 401, 0, 0, 0, false));
        trace.record(event(5, 1, "2", "sales_out", 401, 4, 0, 0, false));
        assertThat(trace.shares().get(101L).get(1L)).isEqualByComparingTo("4");
    }

    @Test void tracksSeveralReceiptsAndDoesNotInventStockForIssuedOrInTransitShares() {
        var trace = new ReceiptCostTrace(Set.of(101L, 102L));
        trace.record(event(1, 1, "3", "purchase_in", 101, 0, 0, 0, true));
        trace.record(event(2, 1, "7", "purchase_in", 102, 0, 0, 0, true));
        trace.record(event(3, 1, "-5", "mfg_issue_out", 201, 0, 0, 0, false));
        trace.record(event(4, 1, "2", "mfg_return_in", 301, 0, 0, 201, false));
        assertThat(trace.shares().get(101L).get(1L)).isEqualByComparingTo("2.1");
        assertThat(trace.shares().get(102L).get(1L)).isEqualByComparingTo("4.9");
        trace.record(event(5, 1, "-7", "transfer_out", 401, 0, 0, 0, false));
        assertThat(trace.shares()).isEmpty();
    }

    @Test void rejectsBrokenQuantityHistory() {
        var trace = new ReceiptCostTrace(Set.of(101L));
        assertThatThrownBy(() -> trace.record(event(1, 1, "-1", "sales_out", 101, 0, 0, 0, false)))
                .isInstanceOf(cn.erp.shared.ApiException.class).hasMessageContaining("追溯不一致");
    }

    @Test void originalCostPurchaseReturnsRemoveTheirOwnSourceAndKeepOtherReceiptInfluence() {
        var trace = new ReceiptCostTrace(Set.of(101L,999L));
        trace.record(event(0,1,"10","purchase_in",999,0,0,0,true));
        trace.record(event(1,1,"10","purchase_in",101,0,0,0,true));
        trace.record(event(2,1,"-10","sales_out",201,0,0,0,false));
        trace.record(event(3,1,"-2","purchase_return_out",301,0,0,101,false));
        assertThat(trace.shares().get(101L).get(1L)).isEqualByComparingTo("3");
        trace.record(event(4,1,"-2","purchase_return_out",302,0,0,999,false));
        assertThat(trace.shares().get(101L).get(1L)).isEqualByComparingTo("3");
        trace.record(new ReceiptCostTrace.Movement(5,1,n("-2"),"purchase_return_out",303,0,0,999,false,true));
        assertThat(trace.shares().get(101L).get(1L)).isEqualByComparingTo("2");
    }

    @Test void reversingAnOlderIndependentReceiptDoesNotConsumeTheNewReceiptShare() {
        var trace = new ReceiptCostTrace(Set.of(101L));
        trace.opening(1,n("10"));
        trace.record(event(1,1,"10","purchase_in",101,0,0,0,true));
        trace.record(event(2,1,"-10","purchase_in",999,999,0,0,false));
        assertThat(trace.shares().get(101L).get(1L)).isEqualByComparingTo("10");
    }

    @Test void returnBeyondItsOwnStockConsumesTheOtherPoolWithoutDependingOnSelectedLines() {
        for(var tracked:java.util.List.of(Set.of(101L,102L),Set.of(101L,102L,103L))) {
            var trace=new ReceiptCostTrace(tracked);
            for(long receipt:java.util.List.of(101L,102L,103L)) trace.record(event(receipt,1,"10","purchase_in",receipt,0,0,0,true));
            trace.record(event(201,1,"-15","sales_out",201,0,0,0,false));
            trace.record(event(301,1,"-8","purchase_return_out",301,0,0,101,false));
            assertThat(trace.shares().get(102L).get(1L)).isEqualByComparingTo("3.5");
            assertThat(trace.shares().containsKey(101L)).isFalse();
        }
    }

    @Test void reversingAReceiptWithOnlyPartOfItsShareLeftConsumesTheOtherStockPool() {
        var trace=new ReceiptCostTrace(Set.of(101L,102L));
        trace.record(event(1,1,"10","purchase_in",101,0,0,0,true));
        trace.record(event(2,1,"10","purchase_in",102,0,0,0,true));
        trace.record(event(3,1,"-8","sales_out",201,0,0,0,false));
        trace.record(event(4,1,"-10","purchase_in",1,1,0,0,false));
        assertThat(trace.shares().get(102L).get(1L)).isEqualByComparingTo("2");
        assertThat(trace.shares().containsKey(101L)).isFalse();
    }

    private static ReceiptCostTrace.Movement event(long id, long warehouse, String qty, String type, long line,
                                                   long original, long transfer, long returned, boolean receipt) {
        return new ReceiptCostTrace.Movement(id, warehouse, n(qty), type, line, original, transfer, returned, receipt);
    }
    private static BigDecimal n(String value) { return new BigDecimal(value); }
}
