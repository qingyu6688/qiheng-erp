package cn.erp.shared;

import org.springframework.jdbc.core.JdbcTemplate;

/** 核对额度依赖整组采购来源，不能在漏掉受限调价后继续展示较大的可用额度。 */
public final class InboundInvoiceScope {
    private InboundInvoiceScope() {}

    public static void requireFull(ActorScope actor) {
        if (!(actor instanceof WarehouseScope scope) || !scope.allWarehouses() || !OrgOwnership.scope(actor).allDepartments())
            throw new ApiException(403, "FORBIDDEN", "进项核对需要当前公司的完整仓库和部门范围");
    }

    public static String family(String alias) {
        return "CASE WHEN " + alias + ".source_type='goods_receipt_line' THEN " + alias + ".source_line_id"
                + " WHEN " + alias + ".source_type='price_adjustment_line' THEN (SELECT il.receipt_line_id FROM price_adjustment_line il WHERE il.tenant_id="
                + alias + ".tenant_id AND il.company_id=" + alias + ".company_id AND il.adjustment_id=" + alias + ".source_id AND il.id=" + alias + ".source_line_id)"
                + " WHEN " + alias + ".source_type='purchase_return_line' THEN (SELECT il.receipt_line_id FROM purchase_return_line il WHERE il.tenant_id="
                + alias + ".tenant_id AND il.company_id=" + alias + ".company_id AND il.return_id=" + alias + ".source_id AND il.id=" + alias + ".source_line_id) END";
    }

    public static String payableCondition(ActorScope actor, String alias) {
        String own = OrgOwnership.sourceCondition(actor, alias + ".source_type", alias + ".source_id");
        if (own.equals("1=1")) return own;
        String related = OrgOwnership.sourceCondition(actor, "ir.source_type", "ir.source_id");
        return "(" + own + ") AND NOT EXISTS(SELECT 1 FROM payable ir WHERE ir.tenant_id=" + alias + ".tenant_id AND ir.company_id="
                + alias + ".company_id AND ir.supplier_id=" + alias + ".supplier_id AND ir.posting_action='post' AND (" + family("ir") + ")=("
                + family(alias) + ") AND NOT EXISTS(SELECT 1 FROM payable iv WHERE iv.tenant_id=ir.tenant_id AND iv.company_id=ir.company_id AND iv.reverses_payable_id=ir.id)"
                + " AND NOT COALESCE((" + related + "),FALSE))";
    }

    public static String condition(ActorScope actor, String alias) {
        return "NOT EXISTS(SELECT 1 FROM einv_inbound_match im JOIN payable ip ON ip.tenant_id=im.tenant_id AND ip.company_id=im.company_id AND ip.id=im.payable_id"
                + " WHERE im.tenant_id=" + alias + ".tenant_id AND im.company_id=" + alias + ".company_id AND im.invoice_id=" + alias + ".id AND NOT COALESCE(("
                + payableCondition(actor, "ip") + "),FALSE))";
    }

    public static void require(JdbcTemplate jdbc, ActorScope actor, Object id) {
        requireFull(actor);
        if (id == null || jdbc.queryForList("SELECT i.id FROM einv_inbound i WHERE i.tenant_id=? AND i.company_id=? AND i.id=? AND "
                + condition(actor, "i"), Long.class, actor.tenantId(), actor.companyId(), id).isEmpty())
            throw new ApiException(404, "NOT_FOUND", "进项发票不存在或不在当前可见范围");
    }
}
