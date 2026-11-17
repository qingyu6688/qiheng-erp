package cn.erp.mfg;

import cn.erp.inventory.InventoryModels.Key;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static cn.erp.mfg.MfgCostMath.Destination;
import static cn.erp.mfg.MfgCostMath.invalid;

/** 追踪本期完工数量在仓库移动加权成本中的份额，库存流水不回写。 */
final class MfgCostFlow {
    private static final BigDecimal EPSILON=new BigDecimal("0.000000000000000001");
    private static final MathContext PRECISION=MathContext.DECIMAL128;

    record Movement(long id,Key key,BigDecimal delta,String type,long line,long originalMovement,
                    long producingOrder,long consumingOrder,long transferLine,long returnedLine) {}

    private record Departure(Balance balance,Destination destination) {}

    private final Map<Key,Balance> warehouses=new TreeMap<>();
    private final Map<Long,Departure> departures=new LinkedHashMap<>();
    private final Map<Long,Departure> issues=new LinkedHashMap<>();
    private final Map<Long,Departure> sales=new LinkedHashMap<>();
    private final Map<Long,Departure> transfers=new LinkedHashMap<>();
    private final Map<Long,Map<Destination,BigDecimal>> exposure=new LinkedHashMap<>();

    void opening(Key key,BigDecimal quantity) {warehouse(key).adjust(quantity);}

    void record(Movement event) {
        if (event.delta().signum()<0) recordOut(event);
        else if (event.delta().signum()>0) recordIn(event);
    }

    private void recordOut(Movement event) {
        var quantity=event.delta().negate();
        var composition=warehouse(event.key()).take(quantity);
        var destination=event.consumingOrder()>0 ? Destination.production(event.consumingOrder()) : issued(event.key());
        var departure=new Departure(new Balance(quantity,composition),destination);
        if (composition.isEmpty()) return;
        if (event.type().equals("transfer_out") && event.originalMovement()==0) {
            transfers.put(event.line(),departure);
            return;
        }
        departures.put(event.id(),departure);
        if (event.consumingOrder()>0) issues.put(event.line(),departure);
        if (event.type().equals("sales_out") && event.originalMovement()==0) sales.put(event.line(),departure);
        record(composition,destination,false);
    }

    private void recordIn(Movement event) {
        var quantity=event.delta();
        var departure=event.originalMovement()>0 ? departures.get(event.originalMovement()) : switch (event.type()) {
            case "mfg_return_in" -> issues.get(event.returnedLine());
            case "sales_return_in", "sales_reject_in" -> sales.get(event.returnedLine());
            case "transfer_in" -> transfers.get(event.transferLine());
            default -> null;
        };
        var balance=warehouse(event.key());
        balance.adjust(quantity);
        if (departure!=null) {
            var composition=departure.balance().take(quantity);
            balance.add(composition);
            if (!event.type().equals("transfer_in")) record(composition,departure.destination(),true);
        }
        if (event.producingOrder()>0) balance.add(Map.of(event.producingOrder(),quantity));
    }

    void transferLoss(long transferLine,BigDecimal quantity) {
        var transfer=transfers.get(transferLine);
        if (transfer!=null) record(transfer.balance().take(quantity),transfer.destination(),false);
    }

    Map<Long,Map<Destination,BigDecimal>> finish() {
        if (transfers.values().stream().anyMatch(transfer->!transfer.balance().layers.isEmpty()))
            throw invalid("本期完工品仍有未处理的调拨在途数量，请先收货或登记调拨差异");
        warehouses.forEach((key,balance)->record(balance.layers,Destination.inventory(key.warehouseId(),key.skuId()),false));
        return exposure;
    }

    private Balance warehouse(Key key) {return warehouses.computeIfAbsent(key,ignored->new Balance(BigDecimal.ZERO,Map.of()));}

    private static Destination issued(Key key) {return Destination.issued(key.warehouseId(),key.skuId());}

    private void record(Map<Long,BigDecimal> composition,Destination destination,boolean reverse) {
        composition.forEach((order,quantity)->{
            var entries=exposure.computeIfAbsent(order,key->new LinkedHashMap<>());
            merge(entries,destination,reverse?quantity.negate():quantity);
        });
    }

    private static <K> void merge(Map<K,BigDecimal> layers,K key,BigDecimal amount) {
        var next=layers.getOrDefault(key,BigDecimal.ZERO).add(amount);
        if (next.abs().compareTo(EPSILON)<=0) layers.remove(key);
        else if (next.signum()<0) throw invalid("生产完工数量归属与库存流水不一致");
        else layers.put(key,next);
    }

    private static final class Balance {
        private BigDecimal quantity;
        private final Map<Long,BigDecimal> layers=new LinkedHashMap<>();

        Balance(BigDecimal quantity,Map<Long,BigDecimal> initial) {this.quantity=quantity;add(initial);}

        void add(Map<Long,BigDecimal> amounts) {amounts.forEach((order,amount)->merge(layers,order,amount));}

        void adjust(BigDecimal delta) {quantity=quantity.add(delta);}

        Map<Long,BigDecimal> take(BigDecimal request) {
            if (request.compareTo(quantity)>0) throw invalid("生产成本追溯出现库存数量不一致，请先检查库存对账");
            var removed=new LinkedHashMap<Long,BigDecimal>();
            for (var entry : layers.entrySet()) {
                var amount=request.compareTo(quantity)==0 ? entry.getValue() : entry.getValue().multiply(request).divide(quantity,PRECISION);
                removed.put(entry.getKey(),amount);
            }
            removed.forEach((order,amount)->merge(layers,order,amount.negate()));
            adjust(request.negate());
            return removed;
        }
    }
}
