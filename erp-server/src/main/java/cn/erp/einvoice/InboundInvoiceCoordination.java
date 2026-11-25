package cn.erp.einvoice;

import cn.erp.shared.ActorScope;
import cn.erp.shared.ApiException;
import cn.erp.shared.AuditService;
import cn.erp.shared.InboundInvoiceChangePort;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class InboundInvoiceCoordination implements InboundInvoiceChangePort {
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;

    InboundInvoiceCoordination(JdbcTemplate jdbc, AuditService audit) { this.jdbc = new NamedParameterJdbcTemplate(jdbc); this.audit = audit; }

    @Override public void lock(ActorScope actor) {
        var params = params(actor);
        jdbc.update("INSERT INTO inbound_invoice_lock(tenant_id,company_id) SELECT tenant_id,id FROM company WHERE tenant_id=:t AND id=:c ON DUPLICATE KEY UPDATE company_id=inbound_invoice_lock.company_id", params);
        if (jdbc.queryForList("SELECT company_id FROM inbound_invoice_lock WHERE tenant_id=:t AND company_id=:c FOR UPDATE", params).isEmpty())
            throw new ApiException(404, "NOT_FOUND", "核对所属公司不存在");
    }

    @Override public void changed(ActorScope actor, String sourceType, long sourceId, String reason) {
        if (!Set.of("goods_receipt", "purchase_return", "price_adjustment", "purchase_fee_doc").contains(sourceType)) return;
        lock(actor);
        var params = params(actor); params.put("source", sourceId);
        String lines = switch (sourceType) {
            case "goods_receipt" -> "SELECT id FROM goods_receipt_line WHERE tenant_id=:t AND company_id=:c AND receipt_id=:source";
            case "purchase_return" -> "SELECT receipt_line_id FROM purchase_return_line WHERE tenant_id=:t AND company_id=:c AND return_id=:source";
            case "price_adjustment" -> "SELECT receipt_line_id FROM price_adjustment_line WHERE tenant_id=:t AND company_id=:c AND adjustment_id=:source";
            default -> "SELECT NULL WHERE 1=0";
        };
        String direct = switch (sourceType) {
            case "goods_receipt" -> "p.source_type IN('goods_receipt_line','purchase_order_fee')";
            case "purchase_fee_doc" -> "p.source_type='purchase_fee_doc_line'";
            default -> "p.source_type='" + sourceType + "_line'";
        };
        var invoices = jdbc.queryForList("SELECT DISTINCT m.invoice_id FROM einv_inbound_match m JOIN payable p ON p.tenant_id=m.tenant_id AND p.company_id=m.company_id AND p.id=m.payable_id"
                + " WHERE m.tenant_id=:t AND m.company_id=:c AND m.released_at IS NULL AND ((p.source_id=:source AND " + direct + ") OR ("
                + cn.erp.shared.InboundInvoiceScope.family("p") + ") IN(" + lines + ")) ORDER BY m.invoice_id", params, Long.class);
        invalidate(actor, invoices, reason);
    }

    void invalidate(ActorScope actor, List<Long> invoices, String reason) {
        if (invoices.isEmpty()) return;
        var params = params(actor); params.put("ids", invoices); params.put("reason", reason);
        jdbc.queryForList("SELECT id FROM einv_inbound WHERE tenant_id=:t AND company_id=:c AND id IN(:ids) ORDER BY id FOR UPDATE", params);
        releaseRows(actor, invoices, reason);
        jdbc.update("UPDATE einv_inbound SET payable_id=NULL,match_notice=:reason,version=version+1 WHERE tenant_id=:t AND company_id=:c AND id IN(:ids)", params);
        for (long invoice : invoices) audit.record(actor, "einv.inbound_release", "einv_inbound", invoice, null, Map.of("reason", reason));
    }

    int releaseRows(ActorScope actor, List<Long> invoices, String reason) {
        if (invoices.isEmpty()) return 0;
        if (reason == null || reason.isBlank() || reason.length() > 200) throw new ApiException(400, "VALIDATION_FAILED", "释放核对关系须说明原因，最多200字");
        var params = params(actor); params.put("ids", invoices); params.put("reason", reason); params.put("user", actor.userId());
        params.put("day", LocalDate.now(EInvoiceService.ZONE));
        return jdbc.update("UPDATE einv_inbound_match SET released_at=UTC_TIMESTAMP(3),released_date=:day,released_by=:user,released_reason=:reason"
                + " WHERE tenant_id=:t AND company_id=:c AND invoice_id IN(:ids) AND released_at IS NULL", params);
    }

    private static Map<String, Object> params(ActorScope actor) {
        var result = new HashMap<String, Object>(); result.put("t", actor.tenantId()); result.put("c", actor.companyId()); return result;
    }
}
