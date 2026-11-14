package cn.erp.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class Decimals {
    private Decimals() {}

    static BigDecimal exact(BigDecimal value, int scale) {
        if (value == null || value.scale() > scale) {
            throw new IllegalArgumentException("数值为空或小数位数超限");
        }
        if (value.signum() != 0 && (long) value.precision() - value.scale() > 24 - scale) {
            throw new IllegalArgumentException("数值超出可存储范围");
        }
        return value.signum() == 0 ? BigDecimal.ZERO.setScale(scale) : value.setScale(scale, RoundingMode.UNNECESSARY);
    }
}
