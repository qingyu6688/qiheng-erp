package cn.erp.inventory;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import cn.erp.shared.ApiException;

/** 沿实际过账顺序追踪收货数量在移动加权库存中的份额，不回写任何原流水。 */
final class ReceiptCostTrace {
    private static final MathContext PRECISION = MathContext.DECIMAL128;
    private static final BigDecimal EPSILON = new BigDecimal("0.000000000000000000000001");
    record Movement(long id, long warehouse, BigDecimal quantity, String type, long line,
                    long originalMovement, long transferLine, long returnedLine, boolean originalReceipt, boolean weightedReturn) {
        Movement(long id,long warehouse,BigDecimal quantity,String type,long line,long originalMovement,long transferLine,long returnedLine,boolean originalReceipt) {
            this(id,warehouse,quantity,type,line,originalMovement,transferLine,returnedLine,originalReceipt,false);
        }
    }

    private final Set<Long> receipts;
    private final Map<Long, Balance> warehouses = new TreeMap<>();
    private final Map<Long, Balance> departures = new HashMap<>();
    private final Map<Long, Map<Long, BigDecimal>> arrivals = new HashMap<>();
    private final Map<String, Balance> related = new HashMap<>();

    ReceiptCostTrace(Set<Long> receipts) { this.receipts = Set.copyOf(receipts); }

    void opening(long warehouse, BigDecimal quantity) {
        if (quantity.signum() < 0) throw mismatch();
        warehouses.put(warehouse, new Balance(quantity, Map.of()));
    }

    void record(Movement event) {
        if (event.quantity().signum() < 0) outgoing(event);
        else if (event.quantity().signum() > 0) incoming(event);
    }

    private void outgoing(Movement event) {
        BigDecimal quantity = event.quantity().negate();
        // 原价采购退货只移除其来源成本；触发成本不足兜底时才按当前加权份额移出。
        Map<Long, BigDecimal> parts;
        if(event.originalMovement()>0) parts=warehouse(event.warehouse()).removeComposition(quantity,arrivals.getOrDefault(event.originalMovement(),Map.of()));
        else if(event.type().equals("purchase_return_out") && !event.weightedReturn()) parts=warehouse(event.warehouse()).returnReceipt(event.returnedLine(), quantity);
        else parts=warehouse(event.warehouse()).take(quantity);
        departures.put(event.id(), new Balance(quantity, parts));
        String key = switch (event.type()) {
            case "sales_out" -> "sales:" + event.line();
            case "mfg_issue_out" -> "production:" + event.line();
            case "stock_misc_out" -> "misc:" + event.line();
            case "transfer_out" -> "transfer:" + event.line();
            default -> null;
        };
        if (key != null && event.originalMovement() == 0) {
            related.computeIfAbsent(key, ignored -> new Balance(BigDecimal.ZERO, Map.of())).add(quantity, parts);
        }
    }

    private void incoming(Movement event) {
        Balance origin = event.originalMovement() > 0 ? departures.get(event.originalMovement()) : switch (event.type()) {
            case "sales_return_in", "sales_reject_in" -> related.get("sales:" + event.returnedLine());
            case "mfg_return_in" -> related.get("production:" + event.returnedLine());
            case "stock_misc_return_in" -> related.get("misc:" + event.returnedLine());
            case "transfer_in" -> related.get("transfer:" + event.transferLine());
            default -> null;
        };
        Map<Long, BigDecimal> parts = origin == null ? Map.of() : origin.take(event.quantity());
        if (event.originalReceipt() && receipts.contains(event.line())) parts = Map.of(event.line(), event.quantity());
        warehouse(event.warehouse()).add(event.quantity(), parts);
        arrivals.put(event.id(),Map.copyOf(parts));
    }

    Map<Long, Map<Long, BigDecimal>> shares() {
        Map<Long, Map<Long, BigDecimal>> result = new LinkedHashMap<>();
        warehouses.forEach((warehouse, balance) -> balance.parts.forEach((receipt, quantity) ->
                result.computeIfAbsent(receipt, ignored -> new TreeMap<>()).put(warehouse, quantity)));
        return result;
    }

    private Balance warehouse(long id) {
        return warehouses.computeIfAbsent(id, ignored -> new Balance(BigDecimal.ZERO, Map.of()));
    }

    private static ApiException mismatch() {
        return new ApiException(409, "COST_TRACE_MISMATCH", "库存数量追溯不一致，请先检查库存对账");
    }

    private static final class Balance {
        private BigDecimal quantity;
        private final Map<Long, BigDecimal> parts = new LinkedHashMap<>();

        Balance(BigDecimal quantity, Map<Long, BigDecimal> parts) {
            this.quantity = quantity;
            this.parts.putAll(parts);
        }

        void add(BigDecimal added, Map<Long, BigDecimal> composition) {
            quantity = quantity.add(added);
            composition.forEach((receipt, share) -> parts.merge(receipt, share, BigDecimal::add));
        }

        Map<Long, BigDecimal> take(BigDecimal requested) {
            if (requested.signum() <= 0 || requested.compareTo(quantity) > 0) throw mismatch();
            Map<Long, BigDecimal> result = new LinkedHashMap<>();
            for (var entry : parts.entrySet()) {
                BigDecimal removed = requested.compareTo(quantity) == 0 ? entry.getValue()
                        : entry.getValue().multiply(requested).divide(quantity, PRECISION);
                result.put(entry.getKey(), removed);
                entry.setValue(entry.getValue().subtract(removed));
            }
            parts.values().removeIf(value -> value.abs().compareTo(EPSILON) <= 0);
            quantity = quantity.subtract(requested);
            return result;
        }

        Map<Long, BigDecimal> returnReceipt(long receipt, BigDecimal requested) {
            if(requested.signum()<=0 || requested.compareTo(quantity)>0) throw mismatch();
            BigDecimal own=parts.getOrDefault(receipt,BigDecimal.ZERO).min(requested);
            Map<Long,BigDecimal> result=own.signum()>0?new LinkedHashMap<>(removeComposition(own,Map.of(receipt,own))):new LinkedHashMap<>();
            BigDecimal excess=requested.subtract(own);
            // 原来源份额不足时，实际退货会消耗其余库存池，不能留下超过实存数量的来源份额。
            if(excess.signum()>0) take(excess).forEach((source,share)->result.merge(source,share,BigDecimal::add));
            return result;
        }

        Map<Long,BigDecimal> removeComposition(BigDecimal requested,Map<Long,BigDecimal> composition) {
            if(requested.signum()<=0 || requested.compareTo(quantity)>0) throw mismatch();
            BigDecimal unknown=quantity.subtract(parts.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add)).max(BigDecimal.ZERO);
            Map<Long,BigDecimal> removed=new LinkedHashMap<>();
            BigDecimal left=requested;
            for(var entry:composition.entrySet()) {
                long source=entry.getKey();
                BigDecimal prior=parts.getOrDefault(source,BigDecimal.ZERO),taken=prior.min(entry.getValue()).min(left);
                if(taken.signum()>0) {removed.put(source,taken);parts.put(source,prior.subtract(taken));}
                left=left.subtract(taken);
            }
            BigDecimal known=removed.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add);
            BigDecimal originalUnknown=requested.subtract(composition.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add)).max(BigDecimal.ZERO);
            BigDecimal own=known.add(unknown.min(originalUnknown));
            quantity=quantity.subtract(own);
            BigDecimal excess=requested.subtract(own);
            if(excess.signum()>0) take(excess).forEach((source,share)->removed.merge(source,share,BigDecimal::add));
            parts.values().removeIf(value->value.abs().compareTo(EPSILON)<=0);
            return removed;
        }
    }
}
