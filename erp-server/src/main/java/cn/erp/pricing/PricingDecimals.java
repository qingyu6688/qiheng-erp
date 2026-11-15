package cn.erp.pricing;

import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;

final class PricingDecimals {
    static final RoundingMode ROUND=RoundingMode.HALF_UP;
    static final BigDecimal ZERO=BigDecimal.ZERO.setScale(2);

    private PricingDecimals() {}

    static BigDecimal input(String value,int precision,int scale,String label,boolean positive) {
        if(value==null || !value.matches("[0-9]+(?:\\.[0-9]+)?") || value.length()>128) {
            throw invalid(label+"须填写非负十进制数");
        }
        BigDecimal number=new BigDecimal(value);
        if(number.scale()>scale || number.precision()-number.scale()>precision-scale) {
            throw invalid(label+"超出允许精度或范围");
        }
        if(positive && number.signum()==0) {throw invalid(label+"必须大于零");}
        return number.setScale(scale);
    }

    static BigDecimal amount(String value,String label) {return input(value,24,2,label,false);}
    static BigDecimal quantity(String value,String label) {return input(value,24,6,label,true);}
    static BigDecimal ratio(String value,String label) {
        BigDecimal number=input(value,7,6,label,false);
        if(number.compareTo(BigDecimal.ONE)>0) {throw invalid(label+"不能超过1");}
        return number;
    }

    static BigDecimal money(BigDecimal value) {
        BigDecimal rounded=value.setScale(2,ROUND);
        if(rounded.signum()<0 || rounded.precision()-rounded.scale()>22) {throw invalid("计算金额超出允许范围");}
        return rounded;
    }

    static ApiException invalid(String message) {return new ApiException(400,"VALIDATION_FAILED",message);}
}
