package cn.erp.einvoice;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 外部数据先在事务外下载，在同公司核对锁内更新并释放失效关系。 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class InboundInvoiceImport {
    private final JdbcTemplate jdbc;
    private final InboundInvoiceCoordination coordination;
    InboundInvoiceImport(JdbcTemplate jdbc, InboundInvoiceCoordination coordination) { this.jdbc = jdbc; this.coordination = coordination; }

    void save(CompanyAccess actor, List<EInvoiceProvider.Inbound> rows) {
        coordination.lock(actor);
        for (var row : rows.stream().sorted(Comparator.comparing(EInvoiceProvider.Inbound::invoiceNo)).toList()) saveOne(actor, row);
    }

    private void saveOne(CompanyAccess actor, EInvoiceProvider.Inbound row) {
        var existing = jdbc.queryForList("SELECT * FROM einv_inbound WHERE tenant_id=? AND company_id=? AND invoice_no=? FOR UPDATE",
                actor.tenantId(), actor.companyId(), row.invoiceNo());
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO einv_inbound(id,tenant_id,company_id,invoice_no,seller_name,seller_tax_no,invoice_date,amount,tax_amount,status,checked,source,received_date) VALUES(?,?,?,?,?,?,?,?,?,?,?,'einv',?)",
                    Ids.next(), actor.tenantId(), actor.companyId(), row.invoiceNo(), row.sellerName(), row.sellerTaxNo(), row.invoiceDate(), row.amount(), row.taxAmount(), row.status(), row.checked(), LocalDate.now(EInvoiceService.ZONE));
            return;
        }
        var old = existing.getFirst();
        if (!"einv".equals(old.get("source"))) throw new ApiException(409, "INVOICE_SOURCE_CONFLICT", "同号码发票已手工登记，请先复核是否重复，数电同步不会覆盖手工记录");
        String status = !"normal".equals(old.get("status")) && "normal".equals(row.status()) ? old.get("status").toString() : row.status();
        boolean taxChanged = !old.get("seller_tax_no").toString().equalsIgnoreCase(row.sellerTaxNo());
        boolean monetary = ((BigDecimal) old.get("amount")).compareTo(row.amount()) != 0 || ((BigDecimal) old.get("tax_amount")).compareTo(row.taxAmount()) != 0;
        boolean changed = monetary || !Objects.equals(old.get("seller_name"), row.sellerName()) || !Objects.equals(old.get("seller_tax_no"), row.sellerTaxNo())
                || !old.get("invoice_date").toString().equals(row.invoiceDate().toString()) || !Objects.equals(old.get("status"), status) || truth(old.get("checked")) != row.checked();
        if (!changed) return;
        boolean invalidate = taxChanged || monetary || !"normal".equals(status);
        long id = InboundInvoiceSources.number(old, "id");
        String reason = "发票金额、销方或状态已变化，请重新核对";
        int released = invalidate ? coordination.releaseRows(actor, List.of(id), reason) : 0;
        Object notice = invalidate && (released > 0 || old.get("payable_id") != null) ? reason : old.get("match_notice");
        jdbc.update("""
                UPDATE einv_inbound SET seller_name=?,seller_tax_no=?,invoice_date=?,amount=?,tax_amount=?,status=?,checked=?,
                  supplier_id=?,payable_id=?,match_notice=?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=?
                """, row.sellerName(), row.sellerTaxNo(), row.invoiceDate(), row.amount(), row.taxAmount(), status, row.checked(), taxChanged ? null : old.get("supplier_id"),
                invalidate ? null : old.get("payable_id"), notice, actor.tenantId(), actor.companyId(), id);
    }
    private static boolean truth(Object value) { return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() != 0; }
}
