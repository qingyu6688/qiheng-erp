package cn.erp.adjustment;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

import cn.erp.shared.ApiException;

final class PriceAdjustmentMath {
    private static final MathContext PRECISION = MathContext.DECIMAL128;
    record Result(BigDecimal quantity, BigDecimal oldUnitPrice, BigDecimal newUnitPrice,
                  BigDecimal amount, BigDecimal tax, BigDecimal untaxed, BigDecimal newAmount,
                  BigDecimal newTax, BigDecimal returnUnitCost) {}
    record SalesResult(BigDecimal quantity, BigDecimal oldUnitPrice, BigDecimal newUnitPrice,
                       BigDecimal amount, BigDecimal tax, BigDecimal untaxed, BigDecimal newAmount, BigDecimal newTax) {}
    private PriceAdjustmentMath() {}

    static Result calculate(BigDecimal remaining, BigDecimal unitRate, BigDecimal oldAmount, BigDecimal oldTax,
                            BigDecimal rate, boolean inclusive, String newPrice, String delta) {
        return calculate(remaining, unitRate, oldAmount, oldTax, rate, inclusive, newPrice, delta, true);
    }

    static SalesResult sales(BigDecimal remaining, BigDecimal unitRate, BigDecimal oldAmount, BigDecimal oldTax,
                             BigDecimal rate, String newPrice, String delta) {
        var value = calculate(remaining, unitRate, oldAmount, oldTax, rate, false, newPrice, delta, false);
        return new SalesResult(value.quantity(), value.oldUnitPrice(), value.newUnitPrice(), value.amount(), value.tax(),
                value.untaxed(), value.newAmount(), value.newTax());
    }

    private static Result calculate(BigDecimal remaining, BigDecimal unitRate, BigDecimal oldAmount, BigDecimal oldTax,
                                    BigDecimal rate, boolean inclusive, String newPrice, String delta, boolean returnCostRequired) {
        if ((newPrice == null) == (delta == null)) throw invalid("新含税单价和调整金额请选择一项填写");
        if (remaining.signum() <= 0 || unitRate.signum() <= 0) throw invalid("原来源已经没有可调价数量");
        BigDecimal quantity = remaining.divide(unitRate, PRECISION);
        BigDecimal next = newPrice == null ? oldAmount.add(decimal(delta, 2, true, "调整金额"))
                : decimal(newPrice, 6, false, "新含税单价").multiply(remaining).divide(unitRate, 2, RoundingMode.HALF_UP);
        if (next.signum() < 0 || next.precision() > 24) throw invalid("调整后剩余含税金额不能为负或超过金额范围");
        BigDecimal difference = next.subtract(oldAmount).setScale(2, RoundingMode.UNNECESSARY);
        if (difference.signum() == 0) throw invalid("本行价格没有变化，请删除该行或修改金额");
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) >= 0) throw invalid("原单税率无效，请先核对");
        BigDecimal nextTax = next.subtract(next.divide(BigDecimal.ONE.add(rate), 2, RoundingMode.HALF_UP));
        BigDecimal tax = nextTax.subtract(oldTax);
        BigDecimal untaxed = difference.subtract(tax);
        if (tax.signum() != 0 && tax.signum() != difference.signum() || untaxed.signum() != 0 && untaxed.signum() != difference.signum())
            throw invalid("原剩余税额与当前税率不一致，请先核对原单税额");
        BigDecimal returnCost = inclusive ? next : next.subtract(nextTax);
        // 先乘后除，只在货款或单价的最终精度舍入，避免循环换算量把半分向下误舍。
        BigDecimal oldUnit = oldAmount.multiply(unitRate).divide(remaining, 6, RoundingMode.HALF_UP);
        BigDecimal newUnit = newPrice == null ? next.multiply(unitRate).divide(remaining, 6, RoundingMode.HALF_UP) : decimal(newPrice, 6, false, "新含税单价");
        BigDecimal returnUnit = returnCostRequired ? returnCost.divide(remaining, 8, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        if(oldUnit.precision()>24 || newUnit.precision()>24 || returnUnit.precision()>24)
            throw invalid("调整后的单位价格或退货单位成本超过可用范围，请核对单位和金额");
        return new Result(quantity.setScale(18, RoundingMode.HALF_UP), oldUnit, newUnit,
                difference, tax, untaxed, next, nextTax, returnUnit);
    }

    static BigDecimal decimal(String text, int scale, boolean signed, String label) {
        String pattern = (signed ? "-?" : "") + "[0-9]+(?:\\.[0-9]{1," + scale + "})?";
        if (text == null || text.length() > 32 || !text.matches(pattern)) throw invalid(label + "格式或小数位数有误");
        BigDecimal value = new BigDecimal(text).setScale(scale, RoundingMode.UNNECESSARY);
        if (value.precision() > 24) throw invalid(label + "超过可用范围");
        return value;
    }

    private static ApiException invalid(String message) { return new ApiException(400, "VALIDATION_FAILED", message); }
}
