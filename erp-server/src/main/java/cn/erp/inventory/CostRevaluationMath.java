package cn.erp.inventory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.erp.shared.ApiException;

/** 成本重放只计算差额，不修改原始出入库成本。 */
final class CostRevaluationMath {
    record Event(long id, long movement, long original, String type, BigDecimal quantity,
                 BigDecimal cost, BigDecimal previous, BigDecimal residue) {}
    record Difference(long movement, BigDecimal before, BigDecimal after, BigDecimal delta) {}
    record Result(BigDecimal quantity, BigDecimal cost, List<Difference> differences) {}
    private record Effective(BigDecimal quantity, BigDecimal cost) {}

    private CostRevaluationMath() {}

    static Result replay(BigDecimal openingQuantity, BigDecimal openingCost, List<Event> events) {
        BigDecimal quantity = openingQuantity, cost = openingCost;
        var changed = new ArrayList<Difference>();
        var effective = new HashMap<Long, Effective>();
        valid(quantity, cost);
        for (var event : events) {
            if (event.movement() == 0) {
                cost = cost.add(event.cost());
                valid(quantity, cost);
                continue;
            }
            BigDecimal before = event.cost().add(event.previous()).add(event.residue());
            BigDecimal next = calculated(event, quantity, cost, effective, before);
            quantity = quantity.add(event.quantity());
            cost = cost.add(next);
            valid(quantity, cost);
            effective.put(event.movement(), new Effective(event.quantity(), next));
            BigDecimal delta = next.subtract(before);
            if (delta.signum() != 0) changed.add(new Difference(event.movement(), before, next, delta));
        }
        return new Result(quantity, cost, List.copyOf(changed));
    }

    private static BigDecimal calculated(Event event, BigDecimal quantity, BigDecimal cost,
                                         Map<Long, Effective> effective, BigDecimal before) {
        if (event.original() > 0) {
            var original = effective.get(event.original());
            if (original == null) return before;
            // 冲销是完整原流水的反向事实，数量不符时拒绝而不猜测比例。
            if (event.quantity().compareTo(original.quantity().negate()) != 0) throw mismatch();
            return original.cost().negate();
        }
        if (event.quantity().signum() >= 0 || event.type().equals("purchase_return_out")) return before;
        BigDecimal issued = event.quantity().negate();
        if (quantity.compareTo(issued) < 0) throw mismatch();
        if (quantity.compareTo(issued) == 0) return cost.negate();
        return InventoryNumbers.roundedCost(issued.multiply(InventoryNumbers.average(quantity, cost))).negate();
    }

    private static void valid(BigDecimal quantity, BigDecimal cost) {
        if (quantity.signum() < 0 || cost.signum() < 0 || quantity.signum() == 0 && cost.signum() != 0) throw mismatch();
        InventoryNumbers.money(cost);
    }

    private static ApiException mismatch() {
        return new ApiException(409, "COST_REPLAY_MISMATCH", "重放时库存数量或成本不一致，请先处理库存对账异常");
    }
}
