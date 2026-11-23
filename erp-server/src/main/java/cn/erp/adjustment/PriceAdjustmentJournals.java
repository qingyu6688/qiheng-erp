package cn.erp.adjustment;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import cn.erp.accounting.LedgerService;

/** 按来源行保留成本维度，拆分后的每张凭证均可独立冲销。 */
final class PriceAdjustmentJournals {
    private static final int MAX_LINES = 1000;
    private final List<List<LedgerService.Line>> batches = new ArrayList<>();
    private List<LedgerService.Line> current = new ArrayList<>();

    void add(List<LedgerService.Line> costs, long partner, long sourceLine) {
        for (int from = 0; from < costs.size(); from += MAX_LINES - 1) {
            var balanced = new ArrayList<>(costs.subList(from, Math.min(from + MAX_LINES - 1, costs.size())));
            BigDecimal net = balanced.stream().map(row -> row.direction().equals("D") ? row.amount() : row.amount().negate())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (net.signum() != 0) balanced.add(new LedgerService.Line("AP", net.signum() > 0 ? "C" : "D", net.abs(),
                    partner, null, null, null, sourceLine, "采购价格调整"));
            if (current.size() + balanced.size() > MAX_LINES) flush();
            current.addAll(balanced);
        }
    }

    List<List<LedgerService.Line>> batches() {
        flush();
        return List.copyOf(batches);
    }

    private void flush() {
        if (current.isEmpty()) return;
        batches.add(List.copyOf(current));
        current = new ArrayList<>();
    }
}
