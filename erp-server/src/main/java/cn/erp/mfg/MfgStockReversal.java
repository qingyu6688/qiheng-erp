package cn.erp.mfg;

import cn.erp.identity.CompanyAccess;
import cn.erp.salesfinance.FinanceSupport;
import cn.erp.shared.ActorScope;
import cn.erp.shared.ApiException;
import cn.erp.shared.ManufacturingReversalPort;
import cn.erp.shared.ProductionCostPort;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import static cn.erp.mfg.MfgStockModels.Type;
import static cn.erp.salesfinance.FinanceSupport.*;

@Component
class MfgStockReversal implements ManufacturingReversalPort {
    private final JdbcTemplate jdbc;
    private final MfgOrders orders;
    private final MfgStockQueries queries;
    private final MfgStockPosting posting;
    private final FinanceSupport finance;
    private final ProductionCostPort cost;

    MfgStockReversal(JdbcTemplate jdbc, MfgOrders orders, MfgStockQueries queries, MfgStockPosting posting,
                     FinanceSupport finance, ProductionCostPort cost) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.queries = queries;
        this.posting = posting;
        this.finance = finance;
        this.cost = cost;
    }

    @Override public Map<String, Object> source(ActorScope actor, String type, long id, boolean lock) {
        var scope = company(actor);
        var resource = type(type);
        if (!scope.hasPermission(resource.permission("view"))) throw forbidden();
        var snapshot = queries.header(scope, resource, id, false);
        if (!lock) return snapshot;
        long order=id(snapshot,"work_order_id");
        orders.header(scope,order,true);
        if(orders.shortClose(scope,order,true).filter(close->"approved".equals(close.get("status"))).isPresent())
            throw dependency("工单已短交关闭，不能冲销原生产单据或恢复原工单");
        return queries.header(scope, resource, id, true);
    }

    @Override public void prepare(ActorScope actor, String type, long id, long period, LocalDate businessDate) {
        var scope = company(actor);
        var origin = source(actor, type, id, false);
        state(origin, "posted");
        var sourceDay = ((Date) origin.get("business_date")).toLocalDate();
        if (businessDate.isBefore(sourceDay)) throw invalid("冲销日期不能早于原生产单据日期");
        long sourcePeriod = finance.period(scope, sourceDay);
        cost.requireStockPosting(actor, sourcePeriod);
        if (period != sourcePeriod) cost.requireStockPosting(actor, period);
    }

    @Override public void dependencies(ActorScope actor, String type, long id) {
        if (type(type) != Type.ISSUE) return;
        var scope = company(actor);
        var head = queries.header(scope, Type.ISSUE, id, false);
        if (!"issue".equals(head.get("direction"))) return;
        var returns = jdbc.queryForList("""
                SELECT d.id FROM mfg_issue_line r JOIN mfg_issue_doc d
                  ON d.tenant_id=r.tenant_id AND d.company_id=r.company_id AND d.id=r.doc_id
                JOIN mfg_issue_line o ON o.tenant_id=r.tenant_id AND o.company_id=r.company_id AND o.id=r.original_issue_line_id
                WHERE o.tenant_id=? AND o.company_id=? AND o.doc_id=? AND d.status IN ('draft','pending','approved','posted')
                LIMIT 1 FOR SHARE OF d
                """, Long.class, scope.tenantId(), scope.companyId(), id);
        if (!returns.isEmpty()) throw dependency("存在引用这张领料单的退料单，请先处理退料单");
        var receipts = jdbc.queryForList("""
                SELECT id FROM mfg_receipt_doc WHERE tenant_id=? AND company_id=? AND work_order_id=? AND status='posted'
                LIMIT 1 FOR SHARE
                """, Long.class, scope.tenantId(), scope.companyId(), head.get("work_order_id"));
        if (!receipts.isEmpty()) throw dependency("工单已有过账的完工入库，请先处理完工入库单");
    }

    @Override public void restoreProgress(ActorScope actor, String type, Map<String, Object> source) {
        var scope = company(actor);
        var resource = type(type);
        var lines = queries.lines(scope, resource, id(source, "id"), true);
        long order = id(source, "work_order_id");
        if (resource == Type.ISSUE) {
            boolean returning = "return".equals(source.get("direction"));
            for (var line : lines) posting.updateMaterial(scope, id(line, "material_id"), returning, decimal(line, "material_qty").negate());
        } else {
            var qty = lines.stream().map(line -> decimal(line, "qty")).reduce(BigDecimal.ZERO, BigDecimal::add);
            jdbc.update("UPDATE mfg_work_order SET completed_qty=completed_qty-? WHERE tenant_id=? AND company_id=? AND id=?",
                    qty, scope.tenantId(), scope.companyId(), order);
        }
        posting.refreshOrder(scope, order);
    }

    private Type type(String type) {
        if (Type.ISSUE.head.equals(type)) return Type.ISSUE;
        if (Type.RECEIPT.head.equals(type)) return Type.RECEIPT;
        throw invalid("该来源不支持生产冲销");
    }

    private CompanyAccess company(ActorScope actor) {
        if (actor instanceof CompanyAccess company) return company;
        throw forbidden();
    }

    private ApiException forbidden() { return new ApiException(403, "FORBIDDEN", "没有查看生产单据的权限"); }
    private ApiException dependency(String reason) { return new ApiException(422, "DEPENDENCY_EXISTS", reason); }
}
