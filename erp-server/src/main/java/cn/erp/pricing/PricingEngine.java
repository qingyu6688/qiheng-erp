package cn.erp.pricing;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;
import static cn.erp.pricing.PricingDecimals.*;

/** R3 的精确计算入口，输入是已选定的价格与税率快照。 */
@Component
public class PricingEngine {
    public PricingModels.Preview calculate(PricingModels.Calculation input) {
        if(input==null || input.lines()==null || input.lines().isEmpty() || input.lines().size()>500) {
            throw invalid("商品明细须在1到500行之间");
        }
        if(input.fees()!=null && input.fees().size()>50) {throw invalid("费用最多50行");}
        List<PricedLine> priced=new ArrayList<>();
        List<PricingModels.Warning> warnings=new ArrayList<>();
        for(int i=0;i<input.lines().size();i++) {priced.add(price(input.lines().get(i),i+1,warnings));}
        BigDecimal discount=amount(input.orderDiscountAmount()==null?"0":input.orderDiscountAmount(),"整单优惠");
        List<BigDecimal> allocations=allocate(priced.stream().map(PricedLine::net).toList(),discount);
        List<PricingModels.LineResult> lines=new ArrayList<>();
        for(int i=0;i<priced.size();i++) {
            lines.add(result(priced.get(i),i+1,allocations.get(i),input.priceTaxInclusive()));
        }
        List<PricingModels.FeeResult> fees=fees(input.fees(),input.priceTaxInclusive());
        BigDecimal goods=sum(priced.stream().map(PricedLine::amount).toList());
        BigDecimal lineDiscount=sum(priced.stream().map(PricedLine::discount).toList());
        BigDecimal feeAmount=sum(fees.stream().filter(PricingModels.FeeResult::included).map(f->new BigDecimal(f.amount())).toList());
        BigDecimal total=sum(lines.stream().map(l->new BigDecimal(l.totalAmount())).toList())
                .add(sum(fees.stream().filter(PricingModels.FeeResult::included).map(f->new BigDecimal(f.totalAmount())).toList()));
        BigDecimal tax=sum(lines.stream().map(l->new BigDecimal(l.taxAmount())).toList())
                .add(sum(fees.stream().filter(PricingModels.FeeResult::included).map(f->new BigDecimal(f.taxAmount())).toList()));
        BigDecimal untaxed=money(total.subtract(tax));
        return new PricingModels.Preview(2,input.priceTaxInclusive(),"CNY",money(goods).toPlainString(),
                money(lineDiscount).toPlainString(),discount.toPlainString(),money(feeAmount).toPlainString(),
                money(total).toPlainString(),untaxed.toPlainString(),money(tax).toPlainString(),
                List.copyOf(lines),List.copyOf(fees),List.copyOf(warnings));
    }

    private PricedLine price(PricingModels.Line input,int no,List<PricingModels.Warning> warnings) {
        if(input==null) {throw invalid("商品行不能为空");}
        BigDecimal qty=quantity(input.qty(),"数量");
        BigDecimal rate=input(input.unitRate(),18,6,"单位换算率",true);
        String base=convertQuantity(qty.toPlainString(),rate.toPlainString());
        if(input.unitPrice()!=null && input.amountInput()!=null) {throw invalid("单价与反算金额只能填写一项");}
        BigDecimal unitPrice;
        if(input.amountInput()!=null) {
            unitPrice=amount(input.amountInput(),"反算金额").divide(qty,8,ROUND);
            // 反算结果也必须满足单价字段的整数位上限。
            unitPrice=input(unitPrice.toPlainString(),24,8,"反算单价",false);
        } else {unitPrice=input(input.unitPrice(),24,8,"单价",false);}
        BigDecimal lineAmount=money(qty.multiply(unitPrice));
        if(input.amountInput()!=null && lineAmount.compareTo(amount(input.amountInput(),"反算金额"))!=0) {
            warnings.add(new PricingModels.Warning(no,"AMOUNT_RECALCULATED",
                    "按反算单价"+unitPrice.toPlainString()+"计算，行金额为"+lineAmount.toPlainString()+"，请确认"));
        }
        if(input.lineDiscountAmount()!=null && input.lineDiscountRatio()!=null) {throw invalid("行优惠金额与比例只能填写一项");}
        BigDecimal discount=input.lineDiscountRatio()!=null
                ?money(lineAmount.multiply(ratio(input.lineDiscountRatio(),"行优惠比例")))
                :amount(input.lineDiscountAmount()==null?"0":input.lineDiscountAmount(),"行优惠");
        if(discount.compareTo(lineAmount)>0) {throw invalid("行优惠不能超过行金额");}
        BigDecimal taxRate=taxRate(input.taxRate());
        return new PricedLine(input,qty,rate,base,unitPrice,lineAmount,discount,lineAmount.subtract(discount),taxRate);
    }

    private static List<BigDecimal> allocate(List<BigDecimal> bases,BigDecimal discount) {
        BigInteger total=sum(bases).unscaledValue();
        if(discount.compareTo(new BigDecimal(total,2))>0) {throw invalid("整单优惠不能超过商品净额");}
        if(total.signum()==0) {return bases.stream().map(v->ZERO).toList();}
        List<Share> shares=new ArrayList<>();
        BigInteger used=BigInteger.ZERO;
        for(int i=0;i<bases.size();i++) {
            BigInteger[] divided=bases.get(i).unscaledValue().multiply(discount.unscaledValue()).divideAndRemainder(total);
            shares.add(new Share(i,divided[0],divided[1]));
            used=used.add(divided[0]);
        }
        int remainder=discount.unscaledValue().subtract(used).intValueExact();
        List<Share> ordered=shares.stream().sorted(Comparator.comparing(Share::remainder).reversed().thenComparingInt(Share::index)).toList();
        BigInteger[] cents=shares.stream().map(Share::cents).toArray(BigInteger[]::new);
        for(int i=0;i<remainder;i++) {int index=ordered.get(i).index();cents[index]=cents[index].add(BigInteger.ONE);}
        return java.util.Arrays.stream(cents).map(v->new BigDecimal(v,2)).toList();
    }

    private static PricingModels.LineResult result(PricedLine value,int no,BigDecimal allocation,boolean inclusive) {
        BigDecimal net=value.net().subtract(allocation);
        Tax tax=tax(net,value.taxRate(),inclusive);
        var line=value.source();
        return new PricingModels.LineResult(no,inclusive,line.skuId(),line.unitId(),line.warehouseId(),value.qty().toPlainString(),
                value.rate().toPlainString(),value.baseQty(),value.price().toPlainString(),value.amount().toPlainString(),
                value.discount().toPlainString(),value.net().toPlainString(),allocation.toPlainString(),tax.total().toPlainString(),
                value.taxRate().toPlainString(),tax.untaxed().toPlainString(),tax.tax().toPlainString(),tax.total().toPlainString());
    }

    private static List<PricingModels.FeeResult> fees(List<PricingModels.Fee> values,boolean inclusive) {
        List<PricingModels.FeeResult> result=new ArrayList<>();
        if(values==null) {return result;}
        for(int i=0;i<values.size();i++) {
            var fee=values.get(i);
            if(fee==null || fee.feeType()==null || !List.of("freight","other").contains(fee.feeType())
                    || fee.bearer()==null || !List.of("buyer","seller").contains(fee.bearer())) {throw invalid("费用类型或承担方有误");}
            BigDecimal amount=amount(fee.amount(),"费用金额");
            if(amount.signum()==0) {throw invalid("费用金额必须大于零");}
            BigDecimal rate=taxRate(fee.taxRate());
            Tax tax=tax(amount,rate,inclusive);
            result.add(new PricingModels.FeeResult(i+1,inclusive,fee.feeType(),fee.bearer(),amount.toPlainString(),rate.toPlainString(),
                    tax.untaxed().toPlainString(),tax.tax().toPlainString(),tax.total().toPlainString(),fee.bearer().equals("buyer")));
        }
        return result;
    }

    private static BigDecimal taxRate(String value) {
        BigDecimal rate=ratio(value,"税率");
        if(rate.compareTo(BigDecimal.ONE)>=0) {throw invalid("税率必须小于1");}
        return rate;
    }

    private static Tax tax(BigDecimal net,BigDecimal rate,boolean inclusive) {
        if(inclusive) {
            BigDecimal untaxed=money(net.divide(BigDecimal.ONE.add(rate),2,ROUND));
            return new Tax(untaxed,money(net.subtract(untaxed)),money(net));
        }
        BigDecimal tax=money(net.multiply(rate));
        return new Tax(money(net),tax,money(net.add(tax)));
    }

    /** 换算后的基础数量必须能精确保存到六位小数。 */
    public String convertQuantity(String quantity,String unitRate) {
        BigDecimal qty=quantity(quantity,"数量");
        BigDecimal rate=input(unitRate,18,6,"单位换算率",true);
        try {
            BigDecimal base=qty.multiply(rate).setScale(6,RoundingMode.UNNECESSARY);
            if(base.precision()-base.scale()>18) {throw invalid("基础数量超出允许范围");}
            return base.toPlainString();
        } catch(ArithmeticException ex) {throw invalid("单位换算结果超过六位小数，请调整数量");}
    }

    /** R3-08，累计金额与累计税额各自计算，关闭数量不会进入本次确认。 */
    public PricingModels.Recognition recognize(String totalAmount,String totalTax,String originalQty,
                                              String previousQty,String cumulativeQty) {
        BigDecimal total=amount(totalAmount,"原含税金额");
        BigDecimal tax=amount(totalTax,"原税额");
        if(tax.compareTo(total)>0) {throw invalid("原税额不能超过原含税金额");}
        BigDecimal original=quantity(originalQty,"原数量");
        BigDecimal previous=input(previousQty,24,6,"此前累计数量",false);
        BigDecimal cumulative=input(cumulativeQty,24,6,"本次累计数量",false);
        if(previous.compareTo(cumulative)>0 || cumulative.compareTo(original)>0) {throw invalid("累计数量超出原单可确认数量");}
        BigDecimal amount=total.multiply(cumulative).divide(original,2,ROUND)
                .subtract(total.multiply(previous).divide(original,2,ROUND));
        BigDecimal taxAmount=tax.multiply(cumulative).divide(original,2,ROUND)
                .subtract(tax.multiply(previous).divide(original,2,ROUND));
        // 金额与税额分别累计进位时，单批未税额可能为负一分，累计未税额仍与原单一致。
        return new PricingModels.Recognition(money(amount).toPlainString(),money(taxAmount).toPlainString(),
                amount.subtract(taxAmount).setScale(2).toPlainString());
    }

    private static BigDecimal sum(List<BigDecimal> values) {return values.stream().reduce(ZERO,BigDecimal::add);}
    private record PricedLine(PricingModels.Line source,BigDecimal qty,BigDecimal rate,String baseQty,
                              BigDecimal price,BigDecimal amount,BigDecimal discount,BigDecimal net,BigDecimal taxRate) {}
    private record Share(int index,BigInteger cents,BigInteger remainder) {}
    private record Tax(BigDecimal untaxed,BigDecimal tax,BigDecimal total) {}
}
