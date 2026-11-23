package cn.erp.purchasefees;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.ReceiptCostAllocator;
import cn.erp.shared.*;
import static cn.erp.purchasefees.PurchaseFeeQueries.*;

@Component
class PurchaseFeeReversal implements PurchaseFeeReversalPort {
    private final JdbcTemplate jdbc;
    private final PurchaseFeeQueries queries;
    private final ReceiptCostAllocator costs;
    PurchaseFeeReversal(JdbcTemplate jdbc,PurchaseFeeQueries queries,ReceiptCostAllocator costs) {this.jdbc=jdbc;this.queries=queries;this.costs=costs;}
    @Override public void prepare(ActorScope scope,long document) {
        FinancialScope.require(scope,"reversal:post");
        var actor=actor(scope);manual(actor,document);
        var allocations=queries.allocations(actor,document);
        for(var line:allocations) queries.source(actor,number(line,"receipt_line_id"),true);
        costs.lock(actor,allocations.stream().map(line->number(line,"sku_id")).collect(java.util.stream.Collectors.toSet()));
    }
    @Override public void dependencies(ActorScope scope,long document) {
        var actor=actor(scope);manual(actor,document);
        for(var allocation:queries.allocations(actor,document)) {
            var source=queries.source(actor,number(allocation,"receipt_line_id"),true);
            if(amount(source,"returned_base_qty").compareTo(amount(allocation,"source_returned_qty"))!=0||amount(source,"pending_return_base_qty").signum()!=0)
                throw dependency("费用分摊后已有退货或待处理退货，请先处理退货单");
        }
        if(!jdbc.queryForList("""
                SELECT m.id FROM inventory_movement m JOIN purchase_fee_cost c
                  ON c.tenant_id=m.tenant_id AND c.company_id=m.company_id AND c.warehouse_id=m.warehouse_id AND c.sku_id=m.sku_id
                WHERE c.tenant_id=? AND c.company_id=? AND c.document_id=? AND c.stock_amount<>0
                  AND (m.posted_at>c.movement_cutoff_at OR(m.posted_at=c.movement_cutoff_at AND m.id>c.movement_cutoff_id))
                  AND m.on_hand_delta<>0 AND m.posting_action='post' AND m.movement_type NOT IN('location_move_in','location_move_out')
                  AND NOT EXISTS(SELECT 1 FROM inventory_movement r WHERE r.tenant_id=m.tenant_id AND r.company_id=m.company_id AND r.reverses_movement_id=m.id)
                LIMIT 1
                """,Long.class,actor.tenantId(),actor.companyId(),document).isEmpty())
            throw dependency("费用成本已有后续库存使用，请先按业务顺序处理后续库存单据");
    }
    private void manual(CompanyAccess actor,long id) {
        if(!"manual".equals(queries.head(actor,id,false).get("source_kind"))) throw invalid("随单费用只能通过原收货单整体冲销");
    }
    private static CompanyAccess actor(ActorScope scope) {
        if(scope instanceof CompanyAccess actor) return actor;
        throw new ApiException(403,"FORBIDDEN","缺少当前公司授权");
    }
    private static ApiException dependency(String message) {return new ApiException(422,"DEPENDENCY_EXISTS",message);}
}
