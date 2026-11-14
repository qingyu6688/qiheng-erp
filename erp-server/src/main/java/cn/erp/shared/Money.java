package cn.erp.shared;

import java.math.BigDecimal;

public record Money(BigDecimal value) {
    public Money { value = Decimals.exact(value, 2); }
}
