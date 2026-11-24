package cn.erp.salesfinance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Component;
import cn.erp.pricing.PricingEngine;
import cn.erp.shared.ApiException;

/** 无源退货先冻结取价依据，再按整组精确成本分摊，不用八位显示单价倒算总额。 */
@Component
class SourcelessReturnCosts {
    private static final int QUANTITY_SCALE=6;
    private static final int MONEY_SCALE=2;
    private static final int UNIT_COST_SCALE=8;
    private static final int DECIMAL_PRECISION=24;
    private static final int MAX_REASON_LENGTH=200;
    private static final int MAX_LINES=500;
    record Stock(BigDecimal quantity,BigDecimal amount,BigDecimal lastUnitCost,long version) {}
    record Basis(String source,BigDecimal stockQuantity,BigDecimal stockAmount,BigDecimal unitCost,long version) {}
    private final PricingEngine pricing;

    SourcelessReturnCosts(PricingEngine pricing) { this.pricing=pricing; }

    Basis choose(Stock stock,BigDecimal manual,String reason) {
        if(stock==null || stock.version()<0) throw invalid("库存成本依据不完整，请刷新后重试");
        BigDecimal quantity=number(stock.quantity(),QUANTITY_SCALE,false);
        BigDecimal amount=number(stock.amount(),MONEY_SCALE,false);
        if(quantity.signum()>0 || stock.lastUnitCost()!=null) {
            if(manual!=null || reason!=null && !reason.isBlank()) throw new ApiException(409,"RETURN_COST_SOURCE_CHANGED","当前已有自动成本来源，请清除手填成本后重新核对");
            String source=quantity.signum()>0?"average":"last_in";
            BigDecimal unit=quantity.signum()>0?amount.divide(quantity,UNIT_COST_SCALE,RoundingMode.HALF_UP):stock.lastUnitCost();
            return new Basis(source,quantity,amount,number(unit,UNIT_COST_SCALE,false),stock.version());
        }
        if(manual==null) throw new ApiException(422,"RETURN_COST_REQUIRED","当前仓库没有可用成本，请由有成本权限人员补填依据");
        if(reason==null || reason.isBlank() || reason.length()>MAX_REASON_LENGTH) throw invalid("手填成本须提供200字以内的有效依据");
        return new Basis("manual",quantity,amount,number(manual,UNIT_COST_SCALE,false),stock.version());
    }

    List<BigDecimal> allocate(Basis basis,List<BigDecimal> quantities) {
        if(basis==null || basis.source()==null || !List.of("average","last_in","manual").contains(basis.source())
                || quantities==null || quantities.isEmpty() || quantities.size()>MAX_LINES) throw invalid("退货成本依据或明细不完整，最多500行");
        var weights=quantities.stream().map(quantity->number(quantity,QUANTITY_SCALE,true)).toList();
        BigDecimal totalQuantity=weights.stream().reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal amount;
        if("average".equals(basis.source())) {
            BigDecimal original=number(basis.stockQuantity(),QUANTITY_SCALE,true);
            amount=number(basis.stockAmount(),MONEY_SCALE,false).multiply(totalQuantity).divide(original,MONEY_SCALE,RoundingMode.HALF_UP);
        } else amount=number(basis.unitCost(),UNIT_COST_SCALE,false).multiply(totalQuantity).setScale(MONEY_SCALE,RoundingMode.HALF_UP);
        return pricing.allocateAmount(number(amount,MONEY_SCALE,false),weights);
    }

    private static BigDecimal number(BigDecimal value,int scale,boolean positive) {
        if(value==null || value.scale()>scale || value.signum()<0 || positive && value.signum()==0
                || (long)value.precision()-value.scale()>DECIMAL_PRECISION-scale) throw invalid("退货数量或成本的范围、精度有误");
        return value.setScale(scale,RoundingMode.UNNECESSARY);
    }
    private static ApiException invalid(String message) { return new ApiException(400,"VALIDATION_FAILED",message); }
}
