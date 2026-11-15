package cn.erp.accounting;

import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;

final class AccountingAmounts {
    private AccountingAmounts() {}

    static BigDecimal money(BigDecimal value) {
        if (value==null || value.scale()>2 || value.signum()<=0 || (long)value.precision()-value.scale()>22) {
            throw invalid("金额必须大于零，最多22位整数和2位小数");
        }
        return value.setScale(2,RoundingMode.UNNECESSARY);
    }

    static ApiException invalid(String message) {return new ApiException(400,"VALIDATION_FAILED",message);}
    static ApiException missing() {return new ApiException(404,"NOT_FOUND","记录不存在或不在当前公司范围");}
    static ApiException conflict(String message) {return new ApiException(409,"STATE_CONFLICT",message);}
}
