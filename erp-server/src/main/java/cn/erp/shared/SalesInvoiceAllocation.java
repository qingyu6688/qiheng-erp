package cn.erp.shared;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 未税额、税额按原正向应收顺序吸收信用额，补价不会重排先前的额度。 */
public final class SalesInvoiceAllocation {
    public record Charge(long id, BigDecimal untaxed, BigDecimal tax, BigDecimal used) {}
    public record Quota(long id, BigDecimal amount, BigDecimal tax, BigDecimal used) {}
    public record Result(BigDecimal amount, BigDecimal tax, BigDecimal used, List<Quota> quotas) {}
    private SalesInvoiceAllocation() {}

    public static Result allocate(List<Charge> charges, BigDecimal creditUntaxed, BigDecimal creditTax) {
        if (charges == null || creditUntaxed == null || creditTax == null || creditUntaxed.signum() < 0 || creditTax.signum() < 0)
            throw changed();
        var ids = new java.util.HashSet<Long>();
        var quotas = new ArrayList<Quota>();
        BigDecimal remainingUntaxed = creditUntaxed, remainingTax = creditTax;
        BigDecimal gross = BigDecimal.ZERO, tax = BigDecimal.ZERO, used = BigDecimal.ZERO;
        for (var charge : charges) {
            if (charge == null || charge.id() <= 0 || !ids.add(charge.id()) || charge.untaxed() == null || charge.tax() == null
                    || charge.used() == null || charge.untaxed().signum() < 0 || charge.tax().signum() < 0 || charge.used().signum() < 0
                    || charge.used().compareTo(charge.untaxed().add(charge.tax())) > 0) throw changed();
            BigDecimal untaxed = charge.untaxed().subtract(remainingUntaxed.min(charge.untaxed()));
            BigDecimal vat = charge.tax().subtract(remainingTax.min(charge.tax()));
            remainingUntaxed = remainingUntaxed.subtract(charge.untaxed()).max(BigDecimal.ZERO);
            remainingTax = remainingTax.subtract(charge.tax()).max(BigDecimal.ZERO);
            quotas.add(new Quota(charge.id(), untaxed.add(vat), vat, charge.used()));
            gross = gross.add(untaxed).add(vat); tax = tax.add(vat); used = used.add(charge.used());
        }
        if (remainingUntaxed.signum() > 0 || remainingTax.signum() > 0) throw changed();
        return new Result(gross, tax, used, List.copyOf(quotas));
    }

    private static ApiException changed() { return new ApiException(409, "INVOICE_CAPACITY_CHANGED", "销售货款、税额或开票占用不一致，请先核对原单及红字记录"); }
}
