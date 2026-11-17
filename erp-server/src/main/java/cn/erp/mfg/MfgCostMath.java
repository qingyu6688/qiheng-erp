package cn.erp.mfg;

import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MfgCostMath {
    static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    record Work(long id,BigDecimal plan,BigDecimal finished,BigDecimal lifetimeFinished,BigDecimal material,
                BigDecimal estimate,BigDecimal beginning,BigDecimal beginningMaterial,BigDecimal hours,BigDecimal progress) {}

    record Destination(String type,long warehouse,long sku,long order) {
        static Destination inventory(long warehouse,long sku) {return new Destination("inventory",warehouse,sku,0);}
        static Destination issued(long warehouse,long sku) {return new Destination("issued",warehouse,sku,0);}
        static Destination production(long order) {return new Destination("production",0,0,order);}
    }

    record OrderCost(long workOrderId,BigDecimal finishedQty,BigDecimal materialCost,BigDecimal rolledMaterial,
                     BigDecimal laborCost,BigDecimal overheadCost,BigDecimal wipBegin,BigDecimal wipEnd,
                     BigDecimal wipEndMaterial,BigDecimal finishedCost,BigDecimal estimatedCost,BigDecimal variance,
                     BigDecimal inventoryVariance,BigDecimal issuedVariance,BigDecimal rolledVariance) {}

    record Valuation(long warehouseId,long skuId,BigDecimal amount) {}
    record Plan(List<OrderCost> orders,List<Valuation> inventory,List<Valuation> issued,BigDecimal labor,BigDecimal overhead) {}

    private MfgCostMath() {}

    static Plan calculate(Map<Long,Work> work,Map<Long,Map<Destination,BigDecimal>> exposures,
                          Map<Long,BigDecimal> labor,Map<Long,BigDecimal> overhead,String method) {
        var incoming=new HashMap<Long,BigDecimal>();
        var inventory=new LinkedHashMap<Destination,BigDecimal>();
        var issued=new LinkedHashMap<Destination,BigDecimal>();
        var costs=new ArrayList<OrderCost>();
        for (long id : orderedWorks(work,exposures)) {
            var data=work.get(id);
            var rolled=incoming.getOrDefault(id,ZERO);
            var wages=labor.getOrDefault(id,ZERO);
            var burden=overhead.getOrDefault(id,ZERO);
            var material=data.beginningMaterial().add(data.material()).add(rolled);
            var conversion=data.beginning().subtract(data.beginningMaterial()).add(wages).add(burden);
            var closing=closing(data,material,conversion,method);
            var balance=closing.material().add(closing.conversion());
            var actual=material.add(conversion).subtract(balance);
            var variance=actual.subtract(data.estimate());
            var shares=split(variance,exposures.getOrDefault(id,Map.of()));
            var inv=ZERO;
            var out=ZERO;
            var roll=ZERO;
            for (var share : shares.entrySet()) {
                var target=share.getKey();
                var amount=share.getValue();
                switch (target.type()) {
                    case "inventory" -> {inventory.merge(target,amount,BigDecimal::add);inv=inv.add(amount);}
                    case "issued" -> {issued.merge(target,amount,BigDecimal::add);out=out.add(amount);}
                    case "production" -> {incoming.merge(target.order(),amount,BigDecimal::add);roll=roll.add(amount);}
                    default -> throw new IllegalStateException("未知的生产差异去向");
                }
            }
            costs.add(new OrderCost(id,data.finished(),data.material(),rolled,wages,burden,data.beginning(),balance,
                    closing.material(),actual,data.estimate(),variance,inv,out,roll));
        }
        return new Plan(List.copyOf(costs),valuations(inventory),valuations(issued),sum(labor),sum(overhead));
    }

    private record Closing(BigDecimal material,BigDecimal conversion) {}

    private static Closing closing(Work data,BigDecimal material,BigDecimal conversion,String method) {
        if (material.signum()<0 || conversion.signum()<0 || data.finished().signum()<0)
            throw invalid("工单在制成本或完工数量为负，请核对原始领退料和冲销");
        if (data.finished().signum()==0) return new Closing(material,conversion);
        var unfinished=data.plan().subtract(data.lifetimeFinished()).max(BigDecimal.ZERO);
        if (unfinished.signum()==0) return new Closing(ZERO,ZERO);
        if (method.equals("materials")) return new Closing(weighted(material,unfinished,data.finished()),ZERO);
        if (data.progress()==null) throw invalid("约当产量法需要填写未完工工单的期末完工程度");
        var equivalent=unfinished.multiply(data.progress()).divide(HUNDRED);
        return new Closing(weighted(material,equivalent,data.finished()),weighted(conversion,equivalent,data.finished()));
    }

    private static BigDecimal weighted(BigDecimal total,BigDecimal unfinished,BigDecimal finished) {
        return total.multiply(unfinished).divide(unfinished.add(finished),2,RoundingMode.HALF_UP);
    }

    static <K> Map<K,BigDecimal> split(BigDecimal amount,Map<K,BigDecimal> weights) {
        if (amount.signum()==0) return Map.of();
        var denominator=sum(weights);
        if (denominator.signum()<=0) throw invalid("费用或完工差异没有可分摊的数量或成本基数");
        var result=new LinkedHashMap<K,BigDecimal>();
        var weight=BigDecimal.ZERO;
        var distributed=ZERO;
        for (var item : weights.entrySet()) {
            weight=weight.add(item.getValue());
            var allocated=amount.multiply(weight).divide(denominator,2,RoundingMode.HALF_UP);
            result.put(item.getKey(),allocated.subtract(distributed));
            distributed=allocated;
        }
        return result;
    }

    private static BigDecimal sum(Map<?,BigDecimal> values) {
        return values.values().stream().reduce(ZERO,BigDecimal::add);
    }

    private static List<Long> orderedWorks(Map<Long,Work> work,Map<Long,Map<Destination,BigDecimal>> exposures) {
        var dependency=new LinkedHashMap<Long,Integer>();
        work.keySet().forEach(id->dependency.put(id,0));
        for (var exposure : exposures.values()) {
            for (var destination : exposure.keySet()) {
                if (!destination.type().equals("production")) continue;
                if (!dependency.containsKey(destination.order())) throw invalid("半成品已被其他期间领用，请先处理跨期生产差异");
                dependency.compute(destination.order(),(id,count)->count+1);
            }
        }
        var ready=new ArrayDeque<Long>();
        dependency.forEach((id,count)->{if (count==0) ready.add(id);});
        var ordered=new ArrayList<Long>();
        while (!ready.isEmpty()) {
            long id=ready.removeFirst();
            ordered.add(id);
            for (var destination : exposures.getOrDefault(id,Map.of()).keySet()) {
                if (destination.type().equals("production") && dependency.compute(destination.order(),(key,count)->count-1)==0)
                    ready.add(destination.order());
            }
        }
        if (ordered.size()!=work.size()) throw invalid("工单实际用料存在循环，请纠正半成品领用关系");
        return ordered;
    }

    private static List<Valuation> valuations(Map<Destination,BigDecimal> values) {
        return values.entrySet().stream().filter(row->row.getValue().signum()!=0)
                .map(row->new Valuation(row.getKey().warehouse(),row.getKey().sku(),row.getValue())).toList();
    }

    static ApiException invalid(String reason) {return new ApiException(422,"MFG_COST_INVALID",reason);}
}
