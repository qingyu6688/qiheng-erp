package cn.erp.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.time.LocalDate;
import tools.jackson.databind.ObjectMapper;

/** 调价只替换剩余退货的计价基准，已退货金额和原收货快照保持不变。 */
public final class ReceiptReturnPricing {
    private ReceiptReturnPricing() {}
    public record Basis(BigDecimal returnedBefore, BigDecimal quantity, BigDecimal amount,
                        BigDecimal tax, BigDecimal unitCost, LocalDate priceDate) {}
    public record Price(BigDecimal amount, BigDecimal tax, BigDecimal unitCost) {}

    public static Price calculate(Map<String, Object> receipt, BigDecimal request, ObjectMapper json) {
        return calculate(receipt, request, null, json);
    }

    public static Price calculate(Map<String, Object> receipt, BigDecimal request, LocalDate day, ObjectMapper json) {
        BigDecimal previous = decimal(receipt, "returned_base_qty");
        Object snapshot = receipt.get("pricing_return_basis");
        if (snapshot == null) {
            BigDecimal total = decimal(receipt, "base_qty");
            return new Price(part(decimal(receipt, "payable_amount"), total, previous, request),
                    part(decimal(receipt, "tax_amount"), total, previous, request), decimal(receipt, "unit_cost"));
        }
        Basis basis = json.readValue(snapshot.toString(), Basis.class);
        if(day!=null && basis.priceDate()!=null && day.isBefore(basis.priceDate()))
            throw new ApiException(422, "RETURN_DATE_BEFORE_PRICE", "退货日期不能早于当前调价日期");
        BigDecimal relative = previous.subtract(basis.returnedBefore());
        if (relative.signum() < 0 || relative.add(request).compareTo(basis.quantity()) > 0)
            throw new ApiException(409, "RETURN_PRICING_CHANGED", "退货计价基准与已退数量不一致，请先核对调价与退货记录");
        return new Price(part(basis.amount(), basis.quantity(), relative, request),
                part(basis.tax(), basis.quantity(), relative, request), basis.unitCost());
    }

    private static BigDecimal decimal(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof BigDecimal number ? number : new BigDecimal(value.toString());
    }

    private static BigDecimal part(BigDecimal amount, BigDecimal total, BigDecimal previous, BigDecimal request) {
        return amount.multiply(previous.add(request)).divide(total, 2, RoundingMode.HALF_UP)
                .subtract(amount.multiply(previous).divide(total, 2, RoundingMode.HALF_UP));
    }
}
