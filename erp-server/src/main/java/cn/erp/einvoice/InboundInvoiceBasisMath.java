package cn.erp.einvoice;

import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/** 将同一收货行扣减退货、折让后的净额度分给原应付和正调价应付。 */
final class InboundInvoiceBasisMath {
    private InboundInvoiceBasisMath() {}

    static List<InboundInvoiceAmounts.Available> netAvailable(long supplierId,
            List<InboundInvoiceAmounts.Available> charges, BigDecimal creditedUntaxed, BigDecimal creditedTax) {
        InboundInvoiceAmounts.requireMoney(creditedUntaxed); InboundInvoiceAmounts.requireMoney(creditedTax);
        if (supplierId <= 0 || charges == null) throw invalid();
        var ids = new HashSet<Long>();
        for (var charge : charges) {
            if (charge == null || charge.payableId() <= 0 || charge.supplierId() != supplierId || !ids.add(charge.payableId()))
                throw invalid();
            InboundInvoiceAmounts.requireMoney(charge.untaxedAmount()); InboundInvoiceAmounts.requireMoney(charge.taxAmount());
            InboundInvoiceAmounts.requireMoney(charge.untaxedAmount().add(charge.taxAmount()));
        }
        var untaxed = spread(charges, creditedUntaxed, false);
        var tax = spread(charges, creditedTax, true);
        return charges.stream().sorted(Comparator.comparingLong(InboundInvoiceAmounts.Available::payableId))
                .map(charge -> new InboundInvoiceAmounts.Available(charge.payableId(), supplierId,
                        untaxed.get(charge.payableId()), tax.get(charge.payableId()))).toList();
    }

    private record Share(long id, BigInteger cents, BigInteger remainder) {}

    private static Map<Long, BigDecimal> spread(List<InboundInvoiceAmounts.Available> charges, BigDecimal credit, boolean tax) {
        BigInteger total = charges.stream().map(charge -> cents(tax ? charge.taxAmount() : charge.untaxedAmount()))
                .reduce(BigInteger.ZERO, BigInteger::add);
        BigInteger net = total.subtract(cents(credit)).max(BigInteger.ZERO);
        var shares = new ArrayList<Share>();
        BigInteger allocated = BigInteger.ZERO;
        for (var charge : charges) {
            var weight = cents(tax ? charge.taxAmount() : charge.untaxedAmount());
            BigInteger[] part = total.signum() == 0 ? new BigInteger[]{BigInteger.ZERO, BigInteger.ZERO}
                    : net.multiply(weight).divideAndRemainder(total);
            shares.add(new Share(charge.payableId(), part[0], part[1]));
            allocated = allocated.add(part[0]);
        }
        int tail = net.subtract(allocated).intValueExact();
        shares.sort(Comparator.comparing(Share::remainder).reversed().thenComparingLong(Share::id));
        var result = new HashMap<Long, BigDecimal>();
        for (int index = 0; index < shares.size(); index++) {
            var part = shares.get(index);
            result.put(part.id(), new BigDecimal(part.cents().add(index < tail ? BigInteger.ONE : BigInteger.ZERO), 2));
        }
        return result;
    }

    private static BigInteger cents(BigDecimal value) { return value.movePointRight(2).toBigIntegerExact(); }
    private static ApiException invalid() { return new ApiException(400, "VALIDATION_FAILED", "采购来源额度无效，请刷新后重试"); }
}
