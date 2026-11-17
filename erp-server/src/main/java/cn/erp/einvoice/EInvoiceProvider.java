package cn.erp.einvoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 服务商适配器按稳定的业务请求键去重。网关应先持久化请求键，再向服务商提交。 */
public interface EInvoiceProvider {
    boolean available();
    void test(Credentials credentials);
    Result query(Credentials credentials,String requestKey);
    Result issue(Credentials credentials,String requestKey,Issue issue);
    Result redIssue(Credentials credentials,String requestKey,Red red);
    /** 服务商不提供某种版式时返回空，文件尚未就绪时返回临时错误。 */
    byte[] download(Credentials credentials,String invoiceNo,String format);
    List<Inbound> downloadInbound(Credentials credentials,LocalDate from,LocalDate to);

    record Credentials(String taxNo,String token) {
        @Override public String toString() { return "数电发票服务商凭据"; }
    }
    enum Outcome { NOT_FOUND, PENDING, ISSUED, FAILED }
    record Result(Outcome status,String invoiceNo,LocalDate issuedDate) {}
    record Issue(String invoiceKind,String buyerName,String buyerTaxNo,BigDecimal amount,BigDecimal taxAmount,List<Line> lines) {}
    record Line(String goodsName,String taxCategoryCode,String spec,String unitName,BigDecimal quantity,
                BigDecimal untaxedAmount,BigDecimal taxAmount,BigDecimal taxRate,BigDecimal unitPrice) {
        Line(String goodsName,String taxCategoryCode,String spec,String unitName,BigDecimal quantity,
             BigDecimal untaxedAmount,BigDecimal taxAmount,BigDecimal taxRate) {
            this(goodsName,taxCategoryCode,spec,unitName,quantity,untaxedAmount,taxAmount,taxRate,
                    untaxedAmount.divide(quantity,6,java.math.RoundingMode.HALF_UP));
        }
    }
    record Red(String invoiceNo,String reason) {}
    record Inbound(String invoiceNo,String sellerName,String sellerTaxNo,LocalDate invoiceDate,BigDecimal amount,
                   BigDecimal taxAmount,String status,boolean checked) {}
}
