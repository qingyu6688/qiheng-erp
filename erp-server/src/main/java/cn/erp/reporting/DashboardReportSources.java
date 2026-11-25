package cn.erp.reporting;

import cn.erp.identity.CompanyAccess;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** 驾驶舱直接在原报表的授权事实集上汇总，避免受分页及导出行数上限影响。 */
@Component
public class DashboardReportSources {
    private final ReportReadDatabase database;

    public DashboardReportSources(ReportReadDatabase database) {this.database=database;}

    public record Sample<T>(String asOf,String dataSource,T value) {}
    public record Point(String label,BigDecimal value) {}
    public record Commerce(BigDecimal amount,BigDecimal untaxed,BigDecimal cost) {
        public BigDecimal gross() {return untaxed.subtract(cost);}
    }

    public Sample<Commerce> commercial(CompanyAccess actor,ReportType type,LocalDate from,LocalDate to) {
        return database.readJdbc(jdbc->{
            String fields=type==ReportType.GROSS_MARGIN?",COALESCE(SUM(cost_amount),0) cost_amount":"";
            var sql="SELECT COALESCE(SUM(amount),0) amount,COALESCE(SUM(untaxed_amount),0) untaxed_amount"+fields+" FROM "+facts(actor,type)+" f";
            var value=jdbc.queryForObject(sql,params(actor,from,to),(r,n)->new Commerce(r.getBigDecimal(1),r.getBigDecimal(2),
                    type==ReportType.GROSS_MARGIN?r.getBigDecimal(3):BigDecimal.ZERO));
            return sampled(value);
        });
    }

    public Sample<List<Point>> commercialPoints(CompanyAccess actor,ReportType type,LocalDate from,LocalDate to,String dimension) {
        return database.readJdbc(jdbc->{
            String amount=type==ReportType.GROSS_MARGIN?"COALESCE(SUM(f.untaxed_amount-f.cost_amount),0)":"COALESCE(SUM(f.amount),0)";
            String key;
            String label;
            String joins;
            switch (dimension) {
                case "month" -> {key="DATE_FORMAT(f.business_date,'%Y-%m')";label=key;joins="";}
                case "customer" -> {key="f.partner_id";label="COALESCE(p.name,'未设置客户')";
                    joins=" LEFT JOIN business_partner p ON p.tenant_id=:t AND p.id=f.partner_id";}
                case "category" -> {key="cat.id";label="COALESCE(cat.name,'费用或未设置品类')";
                    joins=" LEFT JOIN sku s ON s.tenant_id=:t AND s.id=f.sku_id LEFT JOIN product p ON p.tenant_id=s.tenant_id AND p.id=s.product_id"
                            +" LEFT JOIN product_category cat ON cat.tenant_id=p.tenant_id AND cat.id=p.category_id";}
                case "employee" -> {key="f.business_user_id";label="COALESCE(u.display_name,'未分配业务员')";
                    joins=" LEFT JOIN sys_user u ON u.tenant_id=:t AND u.id=f.business_user_id";}
                default -> throw new IllegalArgumentException("驾驶舱汇总维度无效");
            }
            String sort=dimension.equals("month")?" ORDER BY label ASC":" ORDER BY amount DESC,label ASC LIMIT 10";
            if (dimension.equals("category")) sort=" ORDER BY amount DESC,label ASC";
            var sql="SELECT "+label+" label,"+amount+" amount FROM "+facts(actor,type)+" f"+joins+" GROUP BY "+key+","+label+sort;
            return sampled(jdbc.query(sql,params(actor,from,to),(r,n)->new Point(r.getString(1),r.getBigDecimal(2))));
        });
    }

    public Sample<Receivables> receivables(CompanyAccess actor,LocalDate cutoff,List<Integer> bounds) {
        return database.readJdbc(jdbc->{
            var params=params(actor,cutoff.withDayOfMonth(1),cutoff);
            params.put("today",cutoff);params.put("agingNow",cutoff.equals(LocalDate.now(ZoneId.of("Asia/Shanghai"))));
            params.put("agingEnd",cutoff.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
            var balances=jdbc.query("SELECT age_days,SUM(open_amount) amount FROM "+facts(actor,ReportType.RECEIVABLE_AGING)+" f GROUP BY age_days ORDER BY age_days",
                    params,(r,n)->Map.entry(r.getInt(1),r.getBigDecimal(2)));
            var aging=new BigDecimal[bounds.size()+1];java.util.Arrays.fill(aging,BigDecimal.ZERO);
            BigDecimal total=BigDecimal.ZERO;
            for (var row : balances) {
                total=total.add(row.getValue());int index=0;
                while (index<bounds.size() && row.getKey()>bounds.get(index)) index++;
                aging[index]=aging[index].add(row.getValue());
            }
            var types=new java.util.ArrayList<Point>();
            int start=0;
            for (int i=0;i<bounds.size();i++) {
                types.add(new Point(start+"–"+bounds.get(i)+" 天",aging[i]));start=bounds.get(i)+1;
            }
            types.add(new Point(start+" 天及以上",aging[aging.length-1]));
            BigDecimal overdue=jdbc.queryForObject("""
                    SELECT COALESCE(SUM(f.open_amount),0) FROM %s f
                    JOIN partner_open_item o ON o.tenant_id=:t AND o.company_id=:c AND o.id=f.id
                    JOIN receivable r ON r.tenant_id=o.tenant_id AND r.company_id=o.company_id AND r.id=o.source_id
                    WHERE f.direction='D' AND r.due_date IS NOT NULL AND r.due_date<:today
                    """.formatted(facts(actor,ReportType.RECEIVABLE_AGING)),params,BigDecimal.class);
            return sampled(new Receivables(total,overdue,List.copyOf(types)));
        });
    }

    public record Receivables(BigDecimal balance,BigDecimal overdue,List<Point> aging) {}

    public Sample<BigDecimal> inventoryCost(CompanyAccess actor) {
        ReportType.INVENTORY_SUMMARY.authorize(actor,false);
        if (!actor.hasPermission("cost:view")) throw new cn.erp.shared.ApiException(403,"FORBIDDEN","库存金额需要成本权限");
        return database.readJdbc(jdbc->sampled(jdbc.queryForObject("SELECT COALESCE(SUM(cost_amount),0) FROM "+facts(actor,ReportType.INVENTORY_SUMMARY)+" f",
                params(actor,null,null),BigDecimal.class)));
    }

    public Sample<BigDecimal> movementInventoryCost(CompanyAccess actor,LocalDate date) {
        ReportType.INVENTORY_FLOW.authorize(actor,false);
        if (!actor.hasPermission("cost:view") || !actor.orgScope().allDepartments())
            throw new cn.erp.shared.ApiException(403,"FORBIDDEN","库存周转需要完整部门范围与成本权限");
        return database.readJdbc(jdbc->sampled(jdbc.queryForObject("SELECT COALESCE(SUM(closing_cost),0) FROM "+facts(actor,ReportType.INVENTORY_FLOW)+" f",
                params(actor,date,date),BigDecimal.class)));
    }

    public <T> Sample<T> read(java.util.function.Function<NamedParameterJdbcTemplate,T> query) {
        return database.readJdbc(jdbc->sampled(query.apply(jdbc)));
    }

    public <T> T consistent(java.util.function.Supplier<T> query) {return database.read(query);}

    private <T> Sample<T> sampled(T value) {
        return new Sample<>(database.asOf(),database.replicaEnabled()?"replica":"primary",value);
    }

    private static String facts(CompanyAccess actor,ReportType type) {
        type.authorize(actor,false);
        String source="("+ReportSql.query(type,type!=ReportType.INVENTORY_FLOW,actor)+") raw_facts";
        return !type.companyWide() && !actor.allWarehouses()?"(SELECT * FROM "+source+" WHERE warehouse_id IN (:warehouses))": "(SELECT * FROM "+source+")";
    }

    public static Map<String,Object> params(CompanyAccess actor,LocalDate from,LocalDate to) {
        var params=new LinkedHashMap<String,Object>();
        params.put("t",actor.tenantId());params.put("c",actor.companyId());params.put("agingBasis","business");
        if (from!=null) params.put("f",from);
        if (to!=null) params.put("d",to);
        params.put("warehouses",actor.warehouseIds().isEmpty()?Set.of(-1L):actor.warehouseIds());
        return params;
    }
}
