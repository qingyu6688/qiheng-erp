package cn.erp.inventory;

import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;

final class InventoryNumbers {
    private InventoryNumbers() {}

    static BigDecimal quantity(BigDecimal value) { return exact(value, 6, true); }
    static BigDecimal money(BigDecimal value) { return exact(value, 2, false); }
    static BigDecimal unitCost(BigDecimal value) { return exact(value, 8, false); }

    static BigDecimal exact(BigDecimal value, int scale, boolean positive) {
        if (value == null || value.scale() > scale || value.signum() < 0 || (positive && value.signum() == 0)
                || (value.signum() != 0 && (long)value.precision() - value.scale() > 24 - scale)) {
            throw new ApiException(400, "VALIDATION_FAILED", "库存数量或成本的范围、小数位数有误");
        }
        return value.setScale(scale, RoundingMode.UNNECESSARY);
    }

    static BigDecimal roundedCost(BigDecimal value) {
        return money(value.setScale(2, RoundingMode.HALF_UP));
    }

    static BigDecimal average(BigDecimal quantity, BigDecimal cost) {
        return quantity.signum() == 0 ? BigDecimal.ZERO.setScale(8) : unitCost(cost.divide(quantity, 8, RoundingMode.HALF_UP));
    }
}
