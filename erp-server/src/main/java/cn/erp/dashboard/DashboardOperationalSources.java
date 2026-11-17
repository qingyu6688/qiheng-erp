package cn.erp.dashboard;

import cn.erp.identity.CompanyAccess;
import cn.erp.reporting.DashboardReportSources;
import cn.erp.reporting.DashboardReportSources.Sample;
import cn.erp.shared.ManufacturingScope;
import cn.erp.shared.OrgOwnership;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class DashboardOperationalSources {
    private final DashboardReportSources reports;
    private final JdbcTemplate primary;

    DashboardOperationalSources(DashboardReportSources reports,JdbcTemplate primary) {this.reports=reports;this.primary=primary;}

    record Totals(BigDecimal numerator,BigDecimal denominator) {}
    record Manufacturing(BigDecimal activeOrders,BigDecimal completed,BigDecimal planned,BigDecimal materialRate) {}

    Sample<BigDecimal> customerReceipts(CompanyAccess actor,LocalDate from,LocalDate to) {
        var sql="""
                WITH receipts AS (
                    SELECT r.id,r.tenant_id,r.company_id,r.business_date,r.amount FROM receipt r
                    WHERE r.tenant_id=:t AND r.company_id=:c AND r.receipt_type='customer'
                      AND r.status IN ('confirmed','reversed') %s
                ), flows AS (
                    SELECT business_date,amount FROM receipts
                    UNION ALL SELECT rev.business_date,-r.amount FROM receipts r JOIN document_reversal rev
                      ON rev.tenant_id=r.tenant_id AND rev.company_id=r.company_id AND rev.source_id=r.id
                      AND rev.source_type='receipt' AND rev.status='posted'
                ) SELECT COALESCE(SUM(amount),0) FROM flows WHERE business_date BETWEEN :f AND :d
                """.formatted(OrgOwnership.filter(actor,"r"));
        return reports.read(jdbc->jdbc.queryForObject(sql,params(actor,from,to),BigDecimal.class));
    }

    Sample<BigDecimal> cash(CompanyAccess actor,LocalDate cutoff) {
        var sql="""
                SELECT COALESCE(SUM(IF(l.direction='D',l.amount,-l.amount)),0) FROM journal_line l
                JOIN journal j ON j.tenant_id=l.tenant_id AND j.company_id=l.company_id AND j.id=l.journal_id
                WHERE l.tenant_id=:t AND l.company_id=:c AND l.account_code='FUNDS' AND j.business_date<=:d
                """;
        return reports.read(jdbc->jdbc.queryForObject(sql,params(actor,null,cutoff),BigDecimal.class));
    }

    Sample<BigDecimal> slowStock(CompanyAccess actor,LocalDate cutoff) {
        var sql="""
                SELECT COALESCE(SUM(c.total_cost),0) FROM inventory_cost c
                WHERE c.tenant_id=:t AND c.company_id=:c AND c.qty>0 %s
                  AND EXISTS(SELECT 1 FROM inventory_movement inbound
                     WHERE inbound.tenant_id=c.tenant_id AND inbound.company_id=c.company_id AND inbound.warehouse_id=c.warehouse_id
                       AND inbound.sku_id=c.sku_id AND inbound.on_hand_delta>0 AND inbound.business_date<=:f
                       AND inbound.movement_type NOT IN ('location_move_out','location_move_in'))
                  AND NOT EXISTS(SELECT 1 FROM inventory_movement outbound
                     WHERE outbound.tenant_id=c.tenant_id AND outbound.company_id=c.company_id AND outbound.warehouse_id=c.warehouse_id
                       AND outbound.sku_id=c.sku_id AND outbound.on_hand_delta<0 AND outbound.business_date>:f AND outbound.business_date<=:d
                       AND outbound.movement_type NOT IN ('location_move_out','location_move_in'))
                """.formatted(warehouses(actor,"c"));
        return reports.read(jdbc->jdbc.queryForObject(sql,params(actor,cutoff.minusDays(90),cutoff),BigDecimal.class));
    }

    Sample<BigDecimal> expiring(CompanyAccess actor,LocalDate today) {
        var sql="""
                SELECT COUNT(*) FROM inv_batch b JOIN sku s ON s.tenant_id=b.tenant_id AND s.id=b.sku_id
                WHERE b.tenant_id=:t AND b.company_id=:c AND b.expiry_date BETWEEN :d AND DATE_ADD(:d, INTERVAL s.near_expiry_days DAY)
                  AND EXISTS(SELECT 1 FROM inventory_balance l WHERE l.tenant_id=b.tenant_id AND l.company_id=b.company_id
                     AND l.sku_id=b.sku_id AND l.lot_key=CAST(b.id AS CHAR) AND l.on_hand_qty>0 %s)
                """.formatted(warehouses(actor,"l"));
        return reports.read(jdbc->jdbc.queryForObject(sql,params(actor,null,today),BigDecimal.class));
    }

    Sample<Totals> onTimeArrivals(CompanyAccess actor,LocalDate from,LocalDate to) {
        var sql="""
                SELECT COALESCE(SUM(g.business_date<=o.delivery_date),0),COUNT(*)
                FROM goods_receipt_line l JOIN goods_receipt g ON g.tenant_id=l.tenant_id AND g.company_id=l.company_id AND g.id=l.receipt_id
                JOIN purchase_order_line p ON p.tenant_id=l.tenant_id AND p.company_id=l.company_id AND p.id=l.order_line_id
                JOIN purchase_order o ON o.tenant_id=p.tenant_id AND o.company_id=p.company_id AND o.id=p.order_id
                WHERE l.tenant_id=:t AND l.company_id=:c AND g.status='posted' AND o.delivery_date IS NOT NULL
                  AND g.business_date BETWEEN :f AND :d %s %s %s
                """.formatted(warehouses(actor,"g"),OrgOwnership.filter(actor,"g"),OrgOwnership.filter(actor,"o"));
        return reports.read(jdbc->jdbc.queryForObject(sql,params(actor,from,to),(rs,n)->new Totals(rs.getBigDecimal(1),rs.getBigDecimal(2))));
    }

    Sample<Totals> qualification(CompanyAccess actor,LocalDate from,LocalDate to) {
        var sql="""
                SELECT COALESCE(SUM(r.qualified_qty),0),COALESCE(SUM(q.received_qty),0)
                FROM qc_inspection q JOIN qc_result_round r ON r.tenant_id=q.tenant_id AND r.company_id=q.company_id
                  AND r.inspection_id=q.id AND r.round_no=1
                WHERE q.tenant_id=:t AND q.company_id=:c AND q.business_date BETWEEN :f AND :d %s %s
                """.formatted(warehouses(actor,"q"),OrgOwnership.filter(actor,"q"));
        return reports.read(jdbc->jdbc.queryForObject(sql,params(actor,from,to),(rs,n)->new Totals(rs.getBigDecimal(1),rs.getBigDecimal(2))));
    }

    Sample<Manufacturing> production(CompanyAccess actor,LocalDate from,LocalDate to) {
        var sql="""
                WITH orders AS (
                  SELECT o.tenant_id,o.company_id,o.id,o.status,o.plan_start_date,o.plan_finish_date
                  FROM mfg_work_order o WHERE o.tenant_id=:t AND o.company_id=:c AND %s
                ), amounts AS (
                  SELECT COALESCE(SUM(status IN ('released','in_progress')),0) active,
                      COALESCE(SUM(plan_start_date<=:d AND plan_finish_date>=:f AND status IN ('completed','closed')),0) completed,
                      COALESCE(SUM(plan_start_date<=:d AND plan_finish_date>=:f AND status IN ('released','in_progress','completed','closed')),0) planned
                  FROM orders
                ), materials AS (
                  SELECT ROUND(AVG((d.issued_qty-d.returned_qty-d.required_qty)*100 / NULLIF(d.required_qty,0)),2) material_rate
                  FROM orders o JOIN mfg_work_order_material d ON d.tenant_id=o.tenant_id AND d.company_id=o.company_id AND d.work_order_id=o.id
                  WHERE o.plan_start_date<=:d AND o.plan_finish_date>=:f AND o.status IN ('released','in_progress','completed','closed')
                ) SELECT amounts.*,materials.* FROM amounts CROSS JOIN materials
                """.formatted(ManufacturingScope.orderCondition(actor,"o"));
        return reports.read(jdbc->jdbc.queryForObject(sql,params(actor,from,to),(rs,n)->new Manufacturing(rs.getBigDecimal(1),
                rs.getBigDecimal(2),rs.getBigDecimal(3),rs.getBigDecimal(4))));
    }

    Long accountPeriod(CompanyAccess actor,YearMonth period) {
        return primary.queryForList("SELECT id FROM accounting_period WHERE tenant_id=? AND company_id=? AND period=?",Long.class,
                actor.tenantId(),actor.companyId(),period.toString().replace("-","")).stream().findFirst().orElse(null);
    }

    String rolePreset(CompanyAccess actor) {
        var roles=primary.queryForList("""
                SELECT DISTINCT r.code FROM sys_user_role m JOIN sys_role r ON r.tenant_id=m.tenant_id AND r.id=m.role_id
                WHERE m.tenant_id=? AND m.user_id=? AND r.status='active'
                """,String.class,actor.tenantId(),actor.userId());
        if (roles.contains("SYSTEM_ADMIN") || roles.contains("BOSS")) return "owner";
        if (roles.stream().anyMatch(code->code.contains("FINANCE") || code.contains("ACCOUNT"))) return "finance";
        if (roles.stream().anyMatch(code->code.contains("SALES"))) return "sales";
        if (roles.stream().anyMatch(code->code.contains("WAREHOUSE"))) return "warehouse";
        return "owner";
    }

    private static String warehouses(CompanyAccess actor,String alias) {return actor.allWarehouses()?"":" AND "+alias+".warehouse_id IN (:warehouses)";}
    private static Map<String,Object> params(CompanyAccess actor,LocalDate from,LocalDate to) {return DashboardReportSources.params(actor,from,to);}
}
