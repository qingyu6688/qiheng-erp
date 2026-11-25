package cn.erp.adjustment;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ActorScope;
import cn.erp.shared.ApiException;
import cn.erp.shared.SalesInvoiceAmounts;
import cn.erp.shared.SalesPriceAdjustmentReversalPort;
import static cn.erp.adjustment.SalesPriceAdjustmentQueries.*;

@Component
class SalesPriceAdjustmentReversal implements SalesPriceAdjustmentReversalPort {
    private final JdbcTemplate jdbc;
    private final SalesPriceAdjustmentQueries queries;
    private final SalesInvoiceAmounts invoices;
    SalesPriceAdjustmentReversal(JdbcTemplate jdbc,SalesPriceAdjustmentQueries queries,SalesInvoiceAmounts invoices){this.jdbc=jdbc;this.queries=queries;this.invoices=invoices;}
    @Override public void prepare(ActorScope scope,long id) {
        var actor=actor(scope);requireFinancial(actor,"reversal:post");queries.head(actor,id,false);
        for(var line:sorted(actor,id))queries.source(actor,number(line,"shipment_line_id"),true);
    }
    @Override public void dependencies(ActorScope scope,long id) {
        var actor=actor(scope);var header=queries.head(actor,id,false);
        var lines=sorted(actor,id);
        for(var line:lines) {
            long sourceId=number(line,"shipment_line_id");var source=queries.source(actor,sourceId,true);
            if(amount(source,"returned_base_qty").compareTo(amount(line,"source_returned_qty"))!=0||amount(source,"pending_return_base_qty").signum()!=0)
                throw dependency("调价后已经退货或有待处理退货，请先处理后续退货");
            if(!jdbc.queryForList("SELECT h.id FROM price_adjustment h JOIN price_adjustment_line l ON l.tenant_id=h.tenant_id AND l.company_id=h.company_id AND l.adjustment_id=h.id"
                    +" WHERE h.tenant_id=? AND h.company_id=? AND l.shipment_line_id=? AND h.id<>? AND h.status NOT IN('voided','reversed','rejected')"
                    +" AND (h.id>? OR h.posted_at>?) LIMIT 1",actor.tenantId(),actor.companyId(),sourceId,id,id,header.get("posted_at")).isEmpty())
                throw dependency("原发货行已有后续调价，请先处理后续调整单");
        }
        invoices.requireRed(actor,lines.stream().filter(line->amount(line,"amount").signum()>0).map(line->number(line,"shipment_line_id")).toList());
    }
    @Override public void restore(ActorScope scope,long id) {
        var actor=actor(scope);
        for(var line:sorted(actor,id))jdbc.update("UPDATE shipment_line SET price_adjusted_amount=price_adjusted_amount-?,price_adjusted_tax=price_adjusted_tax-?,pricing_return_basis=?,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",
                line.get("amount"),line.get("tax_amount"),line.get("prior_return_basis"),actor.userId(),actor.tenantId(),actor.companyId(),line.get("shipment_line_id"));
    }
    private List<Map<String,Object>> sorted(CompanyAccess actor,long id){return queries.lines(actor,id).stream().sorted(java.util.Comparator.comparingLong(row->number(row,"shipment_line_id"))).toList();}
    private static CompanyAccess actor(ActorScope actor){if(actor instanceof CompanyAccess scope)return scope;throw new ApiException(403,"FORBIDDEN","缺少当前公司授权");}
    private static ApiException dependency(String message){return new ApiException(422,"DEPENDENCY_EXISTS",message);}
}
