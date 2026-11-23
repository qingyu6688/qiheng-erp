package cn.erp.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import cn.erp.shared.ApiException;

/** 销售调价只影响剩余退货货款；退回成本仍由原签收成本快照计算。 */
public final class ShipmentReturnPricing {
    private static final int MONEY_SCALE = 2;
    private static final int QUANTITY_SCALE = 6;
    private static final int MAX_PRECISION = 24;

    private ShipmentReturnPricing() {}

    public record Basis(BigDecimal returnedBefore, BigDecimal quantity, BigDecimal amount,
                        BigDecimal tax, LocalDate priceDate) {}
    public record Credit(BigDecimal amount, BigDecimal tax) {}

    public static Credit calculate(Map<String, Object> shipment, BigDecimal request, LocalDate day,
                                   PricingEngine pricing, ObjectMapper json) {
        BigDecimal signed = decimal(shipment, "signed_base_qty");
        BigDecimal previous = decimal(shipment, "returned_base_qty");
        if (request == null || !validNumber(request, QUANTITY_SCALE) || request.signum() <= 0 || signed.signum() <= 0 || previous.signum() < 0
                || previous.add(request).compareTo(signed) > 0)
            throw new ApiException(422, "RETURN_QTY_EXCEEDED", "退货数量必须大于零且不能超过已签收未退数量");
        Object snapshot = shipment.get("pricing_return_basis");
        if (snapshot == null)
            return recognize(decimal(shipment, "receivable_amount"), decimal(shipment, "tax_amount"), signed, previous, request, pricing);
        Basis basis = readBasis(snapshot, json);
        if (!valid(basis, signed)) throw changed();
        if (day != null && day.isBefore(basis.priceDate()))
            throw new ApiException(422, "RETURN_DATE_BEFORE_PRICE", "退货日期不能早于当前销售调价日期");
        BigDecimal relative = previous.subtract(basis.returnedBefore());
        if (relative.signum() < 0 || relative.add(request).compareTo(basis.quantity()) > 0) throw changed();
        return recognize(basis.amount(), basis.tax(), basis.quantity(), relative, request, pricing);
    }

    private static Credit recognize(BigDecimal amount, BigDecimal tax, BigDecimal quantity, BigDecimal previous,
                                    BigDecimal request, PricingEngine pricing) {
        var result = pricing.recognize(text(amount), text(tax), text(quantity), text(previous), text(previous.add(request)));
        return new Credit(new BigDecimal(result.totalAmount()), new BigDecimal(result.taxAmount()));
    }

    private static Basis readBasis(Object snapshot, ObjectMapper json) {
        try {
            return json.readValue(snapshot.toString(), Basis.class);
        } catch (JacksonException invalid) {
            throw changed();
        }
    }

    private static boolean valid(Basis basis, BigDecimal signed) {
        return basis != null && basis.priceDate() != null
                && validNumber(basis.returnedBefore(), QUANTITY_SCALE) && basis.returnedBefore().signum() >= 0
                && validNumber(basis.quantity(), QUANTITY_SCALE) && basis.quantity().signum() > 0
                && signed.subtract(basis.returnedBefore()).compareTo(basis.quantity()) == 0
                && validNumber(basis.amount(), MONEY_SCALE) && basis.amount().signum() >= 0
                && validNumber(basis.tax(), MONEY_SCALE) && basis.tax().signum() >= 0
                && basis.tax().compareTo(basis.amount()) <= 0;
    }

    private static boolean validNumber(BigDecimal value, int scale) {
        return value != null && (long) value.precision() - value.scale() <= MAX_PRECISION - scale
                && value.stripTrailingZeros().scale() <= scale;
    }

    private static String text(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }

    private static BigDecimal decimal(Map<String, Object> source, String field) {
        if (source == null) throw new ApiException(422, "RETURN_SOURCE_INVALID", "原签收的数量、货款或税额快照缺失，请先核对");
        Object value = source.get(field);
        if (value == null) throw new ApiException(422, "RETURN_SOURCE_INVALID", "原签收的数量、货款或税额快照缺失，请先核对");
        try {
            return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
        } catch (NumberFormatException invalid) {
            throw new ApiException(422, "RETURN_SOURCE_INVALID", "原签收的数量、货款或税额快照无效，请先核对");
        }
    }

    private static ApiException changed() {
        return new ApiException(409, "RETURN_PRICING_CHANGED", "销售退货计价基准与当前来源不一致，请先核对调价及退货记录");
    }
}
