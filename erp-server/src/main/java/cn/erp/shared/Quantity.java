package cn.erp.shared;

import java.math.BigDecimal;

public record Quantity(BigDecimal value) {
    public Quantity { value = Decimals.exact(value, 6); }
}
