package cn.erp.adjustment;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;
import cn.erp.accounting.LedgerService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PriceAdjustmentJournalsTest {
    @Test void manyWarehouseAllocationsProduceBalancedVouchersAndPreserveDimensions() {
        var plan = new PriceAdjustmentJournals();
        plan.add(IntStream.range(0, 1100).mapToObj(i -> new LedgerService.Line("INVENTORY", "D", new BigDecimal("0.01"),
                null, null, (long) i + 1, 1L, 8L, "跨仓成本")).toList(), 5, 8);
        var batches = plan.batches();
        assertThat(batches).hasSize(2);
        for (var batch : batches) {
            assertThat(batch.size()).isBetween(2, 1000);
            assertThat(net(batch)).isZero();
        }
        assertThat(batches.stream().flatMap(List::stream).filter(row -> row.accountCode().equals("INVENTORY"))).hasSize(1100);
        assertThat(batches.stream().flatMap(List::stream).filter(row -> row.accountCode().equals("AP")).map(LedgerService.Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("11.00");
    }

    @Test void positiveAndNegativeLinesShareOneVoucherWithoutNettingTheirFacts() {
        var plan = new PriceAdjustmentJournals();
        plan.add(List.of(new LedgerService.Line("INVENTORY", "D", BigDecimal.TEN, null, null, 1L, 1L, 8L, "在库成本")), 5, 8);
        plan.add(List.of(new LedgerService.Line("COST_VARIANCE", "C", BigDecimal.TEN, null, null, null, null, 9L, "成本差异")), 5, 9);
        assertThat(plan.batches()).singleElement().satisfies(batch -> {
            assertThat(batch).hasSize(4);
            assertThat(net(batch)).isZero();
            assertThat(batch.stream().filter(row -> row.accountCode().equals("AP")).map(LedgerService.Line::sourceLineId)).containsExactly(8L, 9L);
        });
    }

    private BigDecimal net(List<LedgerService.Line> rows) {
        return rows.stream().map(row -> row.direction().equals("D") ? row.amount() : row.amount().negate()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
