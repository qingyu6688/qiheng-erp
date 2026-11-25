package cn.erp.inventory;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import cn.erp.shared.ApiException;

/** 先确定可归库存总额，再分到仓库，避免逐行舍入把一分钱分成两分钱。 */
final class ReceiptCostMath {
    private static final MathContext PRECISION = MathContext.DECIMAL128;
    record Allocation(long warehouse, BigDecimal quantity, BigDecimal stock, BigDecimal issued) {}
    private record Remainder(long warehouse, BigDecimal value) {}

    private ReceiptCostMath() {}

    static List<Allocation> allocate(long originalWarehouse, BigDecimal remaining, BigDecimal delta,
                                     Map<Long, BigDecimal> shares, Map<Long, BigDecimal> currentCosts) {
        if (remaining == null || remaining.signum() <= 0 || delta == null || delta.scale() > 2)
            throw new ApiException(400, "VALIDATION_FAILED", "成本分配数量或金额无效");
        var positive = new TreeMap<Long, BigDecimal>();
        shares.forEach((warehouse, quantity) -> { if (quantity.signum() > 0) positive.put(warehouse, quantity); });
        BigDecimal total = positive.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal stock = delta.multiply(total.min(remaining)).divide(remaining, 2, RoundingMode.HALF_UP);
        Map<Long, BigDecimal> portions = split(stock, positive);
        BigDecimal assigned = BigDecimal.ZERO;
        List<Allocation> result = new ArrayList<>();
        for (var entry : positive.entrySet()) {
            BigDecimal amount = portions.get(entry.getKey());
            BigDecimal available = currentCosts.get(entry.getKey());
            if (available == null || available.signum() < 0)
                throw new ApiException(409, "COST_TRACE_MISMATCH", "成本追溯仓库缺少有效成本余额");
            if (amount.signum() < 0) amount = amount.max(available.negate());
            assigned = assigned.add(amount);
            result.add(new Allocation(entry.getKey(), entry.getValue().setScale(12, RoundingMode.HALF_UP), amount, BigDecimal.ZERO.setScale(2)));
        }
        BigDecimal issued = delta.subtract(assigned).setScale(2, RoundingMode.UNNECESSARY);
        attachIssued(result, originalWarehouse, issued);
        return List.copyOf(result);
    }

    private static void attachIssued(List<Allocation> rows, long warehouse, BigDecimal issued) {
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            if (row.warehouse() == warehouse) {
                rows.set(i, new Allocation(warehouse, row.quantity(), row.stock(), issued));
                return;
            }
        }
        rows.add(new Allocation(warehouse, BigDecimal.ZERO.setScale(12), BigDecimal.ZERO.setScale(2), issued));
    }

    private static Map<Long, BigDecimal> split(BigDecimal amount, Map<Long, BigDecimal> weights) {
        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        if (weights.isEmpty()) return result;
        BigDecimal sum = weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cents = amount.abs().movePointRight(2);
        BigDecimal used = BigDecimal.ZERO;
        List<Remainder> remainders = new ArrayList<>();
        for (var entry : weights.entrySet()) {
            BigDecimal exact = cents.multiply(entry.getValue()).divide(sum, PRECISION);
            BigDecimal floor = exact.setScale(0, RoundingMode.DOWN);
            result.put(entry.getKey(), floor);
            used = used.add(floor);
            remainders.add(new Remainder(entry.getKey(), exact.subtract(floor)));
        }
        remainders.sort(Comparator.comparing(Remainder::value).reversed().thenComparingLong(Remainder::warehouse));
        int left = cents.subtract(used).intValueExact();
        for (int i = 0; i < left; i++) result.merge(remainders.get(i).warehouse(), BigDecimal.ONE, BigDecimal::add);
        result.replaceAll((warehouse, value) -> value.movePointLeft(2).multiply(BigDecimal.valueOf(amount.signum())).setScale(2));
        return result;
    }
}
