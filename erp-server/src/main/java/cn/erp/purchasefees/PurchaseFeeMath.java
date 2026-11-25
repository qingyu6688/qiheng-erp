package cn.erp.purchasefees;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.erp.shared.ApiException;

/** 分摊金额与税额分别守恒，税额尾差不能把单行的未税额减为负数。 */
final class PurchaseFeeMath {
    record Weight(long sourceId, BigDecimal value) {}
    record Portion(long sourceId, BigDecimal amount, BigDecimal tax) {}
    record Recognition(BigDecimal amount, BigDecimal tax) {}
    private PurchaseFeeMath() {}

    static List<Portion> allocate(BigDecimal amount, BigDecimal tax, List<Weight> weights) {
        amount=money(amount);tax=money(tax);
        if(amount.signum()<=0 || tax.signum()<0 || tax.compareTo(amount)>0) throw invalid("费用金额及税额无效");
        var ordered=validate(weights);
        var amounts=split(amount,ordered,null);
        var taxes=split(tax,ordered.stream().map(row->new Weight(row.sourceId(),amounts.get(row.sourceId()))).toList(),amounts);
        return ordered.stream().map(row->new Portion(row.sourceId(),amounts.get(row.sourceId()),taxes.get(row.sourceId()))).toList();
    }

    static Recognition recognise(BigDecimal total,BigDecimal tax,BigDecimal previous,BigDecimal previousTax,
                                 BigDecimal receivedWeight,BigDecimal totalWeight) {
        total=money(total);tax=money(tax);previous=money(previous);previousTax=money(previousTax);
        if(total.signum()<=0 || tax.signum()<0 || tax.compareTo(total)>0 || previous.signum()<0 || previous.compareTo(total)>0
                || previousTax.signum()<0 || previousTax.compareTo(tax)>0 || previousTax.compareTo(previous)>0
                || tax.subtract(previousTax).compareTo(total.subtract(previous))>0) throw invalid("采购费用已确认进度不一致，请先核对");
        if(receivedWeight==null || receivedWeight.signum()<0 || totalWeight==null || totalWeight.signum()<=0) throw invalid("费用分摊权重合计必须大于零");
        BigDecimal next=total.multiply(receivedWeight.min(totalWeight)).divide(totalWeight,2,RoundingMode.HALF_UP).max(previous);
        BigDecimal added=next.subtract(previous);
        // 冲销较早收货后，税额进度可能有一分差异，后续仅在本次货款范围内承接。
        BigDecimal nextTax=tax.multiply(next).divide(total,2,RoundingMode.HALF_UP);
        BigDecimal addedTax=nextTax.subtract(previousTax).max(BigDecimal.ZERO).min(added).min(tax.subtract(previousTax));
        if(next.compareTo(total)==0) addedTax=tax.subtract(previousTax);
        return new Recognition(added,addedTax);
    }

    private static List<Weight> validate(List<Weight> weights) {
        if(weights==null || weights.isEmpty()) throw invalid("请至少选择一条收货来源");
        var ids=new HashSet<Long>();
        for(var row:weights) if(row==null || row.sourceId()<=0 || !ids.add(row.sourceId()) || row.value()==null
                || row.value().signum()<0 || row.value().precision()>60) throw invalid("分摊来源或权重无效");
        if(weights.stream().map(Weight::value).reduce(BigDecimal.ZERO,BigDecimal::add).signum()<=0)
            throw invalid("当前方式的分摊权重合计为零，请更换方式或补齐货款、数量及重量");
        return weights.stream().sorted(Comparator.comparingLong(Weight::sourceId)).toList();
    }

    private static Map<Long,BigDecimal> split(BigDecimal total,List<Weight> weights,Map<Long,BigDecimal> ceilings) {
        var values=new LinkedHashMap<Long,BigDecimal>();
        BigDecimal sum=weights.stream().map(Weight::value).reduce(BigDecimal.ZERO,BigDecimal::add);
        for(var row:weights) values.put(row.sourceId(),total.signum()==0?BigDecimal.ZERO.setScale(2)
                :total.multiply(row.value()).divide(sum,2,RoundingMode.HALF_UP));
        BigDecimal difference=total.subtract(values.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add));
        var priority=new ArrayList<>(weights);
        priority.sort(Comparator.<Weight,BigDecimal>comparing(row->values.get(row.sourceId())).reversed()
                .thenComparing(Weight::value,Comparator.reverseOrder()).thenComparingLong(Weight::sourceId));
        for(var row:priority) {
            if(difference.signum()==0) break;
            BigDecimal current=values.get(row.sourceId());
            BigDecimal change=difference.signum()<0?difference.max(current.negate())
                    :ceilings==null?difference:difference.min(ceilings.get(row.sourceId()).subtract(current));
            values.put(row.sourceId(),current.add(change));difference=difference.subtract(change);
        }
        if(difference.signum()!=0) throw invalid("费用或税额尾差无法分配，请核对来源金额");
        return values;
    }

    private static BigDecimal money(BigDecimal value) {
        if(value==null) throw invalid("请填写费用金额和税额");
        try {
            var result=value.setScale(2,RoundingMode.UNNECESSARY);
            if(result.precision()>24) throw invalid("费用金额超过允许范围");
            return result;
        } catch(ArithmeticException error) {throw invalid("费用金额最多保留两位小数");}
    }
    private static ApiException invalid(String message) {return new ApiException(400,"VALIDATION_FAILED",message);}
}
