package cn.erp.einvoice;

import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 分别校验票面分配额与应付覆盖额，尾差不改写任何应付事实。 */
final class InboundInvoiceAmounts {
    static final int MAX_LINES = 200;
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private InboundInvoiceAmounts() {}

    record Available(long payableId, long supplierId, BigDecimal untaxedAmount, BigDecimal taxAmount) {}
    record Allocation(long payableId, BigDecimal invoiceUntaxed, BigDecimal invoiceTax,
                      BigDecimal payableUntaxed, BigDecimal payableTax) {}
    record Part(long payableId, BigDecimal invoiceUntaxed, BigDecimal invoiceTax,
                BigDecimal payableUntaxed, BigDecimal payableTax,
                BigDecimal untaxedDifference, BigDecimal taxDifference, String payableStatus) {}
    record Result(List<Part> lines, BigDecimal allocatedUntaxed, BigDecimal allocatedTax,
                  BigDecimal remainingUntaxed, BigDecimal remainingTax,
                  BigDecimal untaxedDifference, BigDecimal taxDifference, BigDecimal absoluteDifference,
                  boolean fullyAllocated, String matchStatus) {}

    static Result calculate(long supplierId, BigDecimal invoiceUntaxed, BigDecimal invoiceTax,
                            BigDecimal tolerance, List<Available> available, List<Allocation> allocations) {
        requireMoney(invoiceUntaxed); requireMoney(invoiceTax); requireMoney(tolerance);
        requireMoney(invoiceUntaxed.add(invoiceTax));
        if (invoiceUntaxed.add(invoiceTax).signum() == 0) throw invalid("发票供应商或金额无效");
        if (allocations == null || allocations.size() > MAX_LINES) throw invalid("一张发票最多核对200笔应付");
        if (supplierId <= 0 && !allocations.isEmpty()) throw invalid("发票供应商或金额无效");
        var sources = sources(available);
        var used = new java.util.HashSet<Long>();
        var parts = new java.util.ArrayList<Part>();
        BigDecimal untaxed = ZERO, tax = ZERO, untaxedDifference = ZERO, taxDifference = ZERO, absolute = ZERO;
        for (var line : allocations) {
            if (line == null || !used.add(line.payableId())) throw invalid("应付来源不能为空或重复");
            var source = sources.get(line.payableId());
            if (source == null || source.supplierId() != supplierId) throw missing();
            var part = part(source, line);
            parts.add(part);
            untaxed = untaxed.add(part.invoiceUntaxed()); tax = tax.add(part.invoiceTax());
            untaxedDifference = untaxedDifference.add(part.untaxedDifference());
            taxDifference = taxDifference.add(part.taxDifference());
            absolute = absolute.add(part.untaxedDifference().abs()).add(part.taxDifference().abs());
        }
        if (untaxed.compareTo(invoiceUntaxed) > 0 || tax.compareTo(invoiceTax) > 0)
            throw conflict("分配未税额或税额超过本张发票，请调整分配");
        if (absolute.compareTo(tolerance) > 0)
            throw new ApiException(422, "INVOICE_DIFFERENCE_EXCEEDED", "核对差异合计" + absolute.toPlainString()
                    + "元，超过公司容差" + tolerance.toPlainString() + "元，请办理采购调价或退回供应商");
        var remainingUntaxed = invoiceUntaxed.subtract(untaxed).setScale(2);
        var remainingTax = invoiceTax.subtract(tax).setScale(2);
        boolean complete = remainingUntaxed.signum() == 0 && remainingTax.signum() == 0;
        String status = parts.isEmpty() ? "pending" : absolute.signum() != 0 ? "difference" : complete ? "matched" : "partial";
        return new Result(List.copyOf(parts), untaxed.setScale(2), tax.setScale(2), remainingUntaxed, remainingTax,
                untaxedDifference.setScale(2), taxDifference.setScale(2), absolute.setScale(2), complete, status);
    }

    private static Map<Long, Available> sources(List<Available> available) {
        if (available == null) throw invalid("应付额度无效，请刷新后重试");
        var result = new HashMap<Long, Available>();
        for (var source : available) {
            if (source == null || source.payableId() <= 0 || source.supplierId() <= 0)
                throw invalid("应付额度无效，请刷新后重试");
            requireMoney(source.untaxedAmount()); requireMoney(source.taxAmount());
            requireMoney(source.untaxedAmount().add(source.taxAmount()));
            if (result.put(source.payableId(), source) != null) throw invalid("应付额度重复，请刷新后重试");
        }
        return result;
    }

    private static Part part(Available source, Allocation line) {
        requireMoney(line.invoiceUntaxed()); requireMoney(line.invoiceTax());
        requireMoney(line.payableUntaxed()); requireMoney(line.payableTax());
        if (line.invoiceUntaxed().add(line.invoiceTax()).signum() == 0
                || line.payableUntaxed().add(line.payableTax()).signum() == 0)
            throw invalid("每行票面分配额和应付覆盖额都必须大于零");
        if (line.payableUntaxed().compareTo(source.untaxedAmount()) > 0
                || line.payableTax().compareTo(source.taxAmount()) > 0)
            throw conflict("所选应付的未税或税额额度不足，请刷新后重试");
        String status = line.payableUntaxed().compareTo(source.untaxedAmount()) == 0
                && line.payableTax().compareTo(source.taxAmount()) == 0 ? "invoiced" : "partial";
        return new Part(line.payableId(), line.invoiceUntaxed().setScale(2), line.invoiceTax().setScale(2),
                line.payableUntaxed().setScale(2), line.payableTax().setScale(2),
                line.invoiceUntaxed().subtract(line.payableUntaxed()).setScale(2),
                line.invoiceTax().subtract(line.payableTax()).setScale(2), status);
    }

    static BigDecimal parseMoney(String value) {
        if (value == null || !value.matches("[0-9]{1,22}(?:\\.[0-9]{1,2})?")) throw invalid("金额须为非负数字，最多两位小数");
        return new BigDecimal(value).setScale(2);
    }

    static void requireMoney(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.scale() > 2 || (long) value.precision() - value.scale() > 22)
            throw invalid("金额须为非负数字，最多两位小数且不超过22位整数");
    }

    private static ApiException invalid(String message) { return new ApiException(400, "VALIDATION_FAILED", message); }
    private static ApiException conflict(String message) { return new ApiException(409, "INVOICE_ALLOCATION_CONFLICT", message); }
    private static ApiException missing() { return new ApiException(404, "NOT_FOUND", "应付不存在、供应商不同或无权核对"); }
}
