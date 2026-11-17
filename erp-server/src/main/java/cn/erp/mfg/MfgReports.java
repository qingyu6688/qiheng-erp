package cn.erp.mfg;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
class MfgReports {
    private static final String ORDER_WITH_SKU="""
            FROM mfg_work_order o JOIN sku s ON s.tenant_id=o.tenant_id AND s.id=o.sku_id
            """;
    private final JdbcTemplate jdbc;

    MfgReports(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    Map<String,Object> page(CompanyAccess actor,String name,Long warehouse,Long order,Long period,Long cursor,int limit) {
        MfgCostService.paging(cursor,limit);
        if (!actor.hasPermission("mfg_report:view") || name.equals("cost-detail") && !actor.hasPermission("cost:view"))
            throw new ApiException(403,"FORBIDDEN","当前账户没有查看该生产报表的权限");
        if (warehouse!=null && !actor.allowsWarehouse(warehouse)) throw MfgCostFacts.missing();
        var report=report(name);
        var sql=new StringBuilder(report.select()).append(ORDER_WITH_SKU).append(report.join())
                .append(" WHERE o.tenant_id=? AND o.company_id=?");
        var args=new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId()));
        MfgOrders.appendScope(actor,"o",sql,args);
        if (warehouse!=null) {
            sql.append(" AND (o.issue_warehouse_id=? OR o.receive_warehouse_id=? OR EXISTS(SELECT 1 FROM mfg_work_order_material f"
                    +" WHERE f.tenant_id=o.tenant_id AND f.company_id=o.company_id AND f.work_order_id=o.id AND f.issue_warehouse_id=?))");
            args.addAll(List.of(warehouse,warehouse,warehouse));
        }
        if (order!=null) {sql.append(" AND o.id=?");args.add(order);}
        if (period!=null && name.equals("cost-detail")) {sql.append(" AND d.period_id=?");args.add(period);}
        if (cursor!=null) {sql.append(" AND ").append(report.rowId()).append("<?");args.add(cursor);}
        sql.append(" ORDER BY ").append(report.rowId()).append(" DESC LIMIT ?");args.add(limit+1);
        return MfgCostService.page(jdbc.queryForList(sql.toString(),args.toArray()),limit,"id");
    }

    private record Report(String select,String join,String rowId) {}

    private static Report report(String name) {
        return switch (name) {
            case "progress" -> new Report("""
                    SELECT o.id,o.id work_order_id,o.document_no,o.sku_id,s.code sku_code,s.name sku_name,
                       o.status,o.plan_qty,o.completed_qty,o.issue_warehouse_id,o.receive_warehouse_id,o.plan_start_date,o.plan_finish_date
                    ""","","o.id");
            case "material-variance" -> new Report("""
                    SELECT d.id,o.id work_order_id,o.document_no,d.component_sku_id,d.unit_id,part.code component_sku_code,
                       part.name component_sku_name,d.required_qty,d.issued_qty,d.returned_qty,
                       d.issued_qty-d.returned_qty-d.required_qty variance_qty,d.issue_warehouse_id
                    ""","""
                    JOIN mfg_work_order_material d ON d.tenant_id=o.tenant_id AND d.company_id=o.company_id AND d.work_order_id=o.id
                    JOIN sku part ON part.tenant_id=d.tenant_id AND part.id=d.component_sku_id
                    ""","d.id");
            case "cost-detail" -> new Report("""
                    SELECT d.*,o.document_no,o.sku_id,s.code sku_code,p.period,c.status
                    ""","""
                    JOIN mfg_cost_item d ON d.tenant_id=o.tenant_id AND d.company_id=o.company_id AND d.work_order_id=o.id
                    JOIN mfg_cost_period c ON c.tenant_id=d.tenant_id AND c.company_id=d.company_id AND c.period_id=d.period_id
                    JOIN accounting_period p ON p.tenant_id=d.tenant_id AND p.company_id=d.company_id AND p.id=d.period_id
                    ""","d.id");
            default -> throw MfgCostFacts.missing();
        };
    }
}
