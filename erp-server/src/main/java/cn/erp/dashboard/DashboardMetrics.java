package cn.erp.dashboard;

import cn.erp.crm.CrmDashboardMetrics;
import cn.erp.gl.GlQueryService;
import cn.erp.identity.CompanyAccess;
import cn.erp.reporting.DashboardReportSources;
import cn.erp.reporting.DashboardReportSources.Point;
import cn.erp.reporting.DashboardReportSources.Sample;
import cn.erp.reporting.ReportType;
import cn.erp.shared.AgingPolicyPort;
import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
class DashboardMetrics {
    private static final String MONEY="元";
    private static final String PERCENT="%";
    private final DashboardReportSources reports;
    private final DashboardOperationalSources operations;
    private final AgingPolicyPort aging;
    private final GlQueryService gl;
    private final CrmDashboardMetrics crm;

    DashboardMetrics(DashboardReportSources reports,DashboardOperationalSources operations,AgingPolicyPort aging,
                     GlQueryService gl,CrmDashboardMetrics crm) {
        this.reports=reports;this.operations=operations;this.aging=aging;this.gl=gl;this.crm=crm;
    }

    record Period(YearMonth month,LocalDate today) {
        LocalDate from() {return month.atDay(1);}
        LocalDate end() {return month.atEndOfMonth().isAfter(today)?today:month.atEndOfMonth();}
        LocalDate previousEnd(YearMonth other) {
            return end().equals(month.atEndOfMonth())?other.atEndOfMonth():other.atDay(Math.min(other.lengthOfMonth(),end().getDayOfMonth()));
        }
    }

    record Value(String label,String value,String unit) {}
    record Plot(String name,List<Value> points) {}
    record MetricData(String code,String asOf,String dataSource,List<Value> values,List<Plot> plots,String note) {}

    MetricData load(CompanyAccess actor,DashboardMetric metric,Period period) {
        if (metric==DashboardMetric.FINANCE) return finance(actor,period);
        if (metric==DashboardMetric.CRM) return crm(actor,period);
        return reports.consistent(()->loadReport(actor,metric,period));
    }

    private MetricData loadReport(CompanyAccess actor,DashboardMetric metric,Period period) {
        return switch (metric) {
            case SALES -> sales(actor,period);
            case GROSS_MARGIN -> margin(actor,period);
            case RECEIVABLES -> receivables(actor,period);
            case INVENTORY -> inventory(actor,period);
            case PURCHASE -> purchase(actor,period);
            case PRODUCTION -> production(actor,period);
            default -> throw new IllegalStateException("指标不属于报表数据源");
        };
    }

    private MetricData sales(CompanyAccess actor,Period p) {
        var type=ReportType.SALES_SUMMARY;
        var current=reports.commercial(actor,type,p.from(),p.end());
        var year=reports.commercial(actor,type,p.from().withDayOfYear(1),p.end());
        var previous=p.month().minusMonths(1);
        var lastYear=p.month().minusYears(1);
        var compared=reports.commercial(actor,type,previous.atDay(1),p.previousEnd(previous)).value().amount();
        var comparedYear=reports.commercial(actor,type,lastYear.atDay(1),p.previousEnd(lastYear)).value().amount();
        var month=reports.commercialPoints(actor,type,p.month().minusMonths(11).atDay(1),p.end(),"month").value();
        var sourceMonth=month.stream().collect(java.util.stream.Collectors.toMap(Point::label,Point::value));
        var series=java.util.stream.IntStream.rangeClosed(-11,0).mapToObj(p.month()::plusMonths).map(value->
                new Point(value.toString(),sourceMonth.getOrDefault(value.toString(),BigDecimal.ZERO))).toList();
        var amount=current.value().amount();
        var categories=reports.commercialPoints(actor,type,p.from(),p.end(),"category").value();
        var share=new Plot("销售品类占比",categories.stream().map(row->value(row.label(),percent(row.value(),amount),PERCENT)).toList());
        return from(DashboardMetric.SALES,current,List.of(value("本月销售额",amount,MONEY),value("本年累计",year.value().amount(),MONEY),
                value("同比",percent(amount.subtract(comparedYear),comparedYear.abs()),PERCENT),
                value("环比",percent(amount.subtract(compared),compared.abs()),PERCENT)),List.of(plot("销售月度趋势",series,MONEY),
                plot("客户 Top 10",reports.commercialPoints(actor,type,p.from(),p.end(),"customer").value(),MONEY),share),
                "按销售汇总的确认金额，未结束月份按同日比较。");
    }

    private MetricData margin(CompanyAccess actor,Period p) {
        var type=ReportType.GROSS_MARGIN;
        var current=reports.commercial(actor,type,p.from(),p.end());
        return from(DashboardMetric.GROSS_MARGIN,current,List.of(value("毛利额",current.value().gross(),MONEY),
                value("毛利率",percent(current.value().gross(),current.value().untaxed()),PERCENT)),List.of(
                plot("品类毛利",reports.commercialPoints(actor,type,p.from(),p.end(),"category").value(),MONEY),
                plot("客户毛利 Top 10",reports.commercialPoints(actor,type,p.from(),p.end(),"customer").value(),MONEY),
                plot("业务员毛利 Top 10",reports.commercialPoints(actor,type,p.from(),p.end(),"employee").value(),MONEY)),"按毛利报表的未税收入减销售成本计算。");
    }

    private MetricData receivables(CompanyAccess actor,Period p) {
        var source=reports.receivables(actor,p.end(),aging.bounds(actor));
        var items=new ArrayList<Value>();
        if (actor.hasPermission("receipt:view")) {
            var received=operations.customerReceipts(actor,p.from(),p.end()).value();
            items.add(value("本月净回款",received,MONEY));
            if (actor.hasPermission(ReportType.SALES_SUMMARY.permission())) {
                var sales=reports.commercial(actor,ReportType.SALES_SUMMARY,p.from(),p.end()).value().amount();
                items.add(value("净回款 / 销售额",percent(received,sales),PERCENT));
            }
        }
        items.add(value("应收余额",source.value().balance(),MONEY));
        items.add(value("逾期应收",source.value().overdue(),MONEY));
        return from(DashboardMetric.RECEIVABLES,source,items,List.of(plot("应收账龄",source.value().aging(),MONEY)),"应收按所选月截止日计算，净回款包含期间冲销。");
    }

    private MetricData inventory(CompanyAccess actor,Period p) {
        var items=new ArrayList<Value>();
        if (actor.hasPermission("cost:view")) {
            items.add(value("当前库存金额",reports.inventoryCost(actor).value(),MONEY));
            if (actor.orgScope().allDepartments()) items.add(value("90 天无出库库存金额",operations.slowStock(actor,p.today()).value(),MONEY));
            if (actor.hasPermission(ReportType.INVENTORY_FLOW.permission()) && actor.hasPermission(ReportType.GROSS_MARGIN.permission())
                    && actor.orgScope().allDepartments()) {
                var opening=reports.movementInventoryCost(actor,p.from().minusDays(1)).value();
                var closing=reports.movementInventoryCost(actor,p.end()).value();
                var cost=reports.commercial(actor,ReportType.GROSS_MARGIN,p.from(),p.end()).value().cost();
                var days=BigDecimal.valueOf(ChronoUnit.DAYS.between(p.from(),p.end())+1);
                items.add(value("本月库存周转天数",ratio(opening.add(closing).divide(BigDecimal.TWO).multiply(days),cost),"天"));
            }
        }
        if (actor.hasPermission("batch:view")) items.add(value("在库临期批次",operations.expiring(actor,p.today()).value(),"批"));
        return from(DashboardMetric.INVENTORY,reports.read(jdbc->null),items,List.of(),"库存金额、呆滞和临期为当前仓库快照。");
    }

    private MetricData purchase(CompanyAccess actor,Period p) {
        var source=reports.commercial(actor,ReportType.PURCHASE_SUMMARY,p.from(),p.end());
        var items=new ArrayList<Value>();items.add(value("本月采购额",source.value().amount(),MONEY));
        if (actor.hasPermission("purchase_order:view")) {
            var onTime=operations.onTimeArrivals(actor,p.from(),p.end()).value();
            items.add(value("到货及时率",percent(onTime.numerator(),onTime.denominator()),PERCENT));
        }
        if (actor.hasPermission("qc_inspection:view")) {
            var qc=operations.qualification(actor,p.from(),p.end()).value();
            items.add(value("来料首检合格率",percent(qc.numerator(),qc.denominator()),PERCENT));
        }
        return from(DashboardMetric.PURCHASE,source,items,List.of(),"采购按收货确认，及时率以有约定日期的已过账收货明细数为分母。");
    }

    private MetricData finance(CompanyAccess actor,Period p) {
        var items=new ArrayList<Value>();
        var cash=actor.hasPermission(ReportType.CASH_JOURNAL.permission())?operations.cash(actor,p.end()):null;
        if (cash!=null) items.add(value("资金余额",cash.value(),MONEY));
        var period=operations.accountPeriod(actor,p.month());
        if (period==null) return from(DashboardMetric.FINANCE,cash==null?primary():cash,items,List.of(),"当前月份没有会计期间。");
        try {
            for (var row : rows(gl.indicators(actor,period),"items")) {
                String code=row.get("code").toString();
                if (code.equals("debt_ratio") || code.equals("current_ratio")) items.add(new Value(row.get("name").toString(),
                        row.get("value")==null?null:row.get("value").toString(),row.get("unit").toString()));
            }
        } catch (ApiException error) {
            if (!List.of("GL_BOOK_REQUIRED","GL_BOOK_INACTIVE","GL_CONFIG_UNCONFIRMED","VALIDATION_FAILED").contains(error.getErrorCode())) throw error;
            return from(DashboardMetric.FINANCE,cash==null?primary():cash,items,List.of(),error.getMessage());
        }
        var stamp=cash!=null && cash.dataSource().equals("replica")?new Sample<>(cash.asOf(),"mixed",null):primary();
        return from(DashboardMetric.FINANCE,stamp,items,List.of(),"财务比率沿用总账指标口径，资金余额按资金日记账截至所选月份。");
    }

    private MetricData production(CompanyAccess actor,Period p) {
        var source=operations.production(actor,p.from(),p.end());
        var row=source.value();
        return from(DashboardMetric.PRODUCTION,source,List.of(value("当前在制工单",row.activeOrders(),"张"),
                value("计划重叠工单完工率",percent(row.completed(),row.planned()),PERCENT),
                value("平均净用料差异率",row.materialRate(),PERCENT)),List.of(),
                "在制为当前状态。完工按张数统计，用料差异先按物料行计算百分比再取平均。");
    }

    private MetricData crm(CompanyAccess actor,Period p) {
        var data=crm.metrics(actor,p.from(),p.end());
        var items=new ArrayList<Value>();
        items.add(value("商机金额",data.opportunityAmount(),MONEY));
        items.add(value("商机赢单率",percent(data.won(),data.won().add(data.lost())),PERCENT));
        if (actor.hasPermission("crm_target:view")) items.add(value("合同目标完成率",percent(data.contractAmount(),data.contractTarget()),PERCENT));
        var stamp=data.dataSource().equals("replica")?reports.read(jdbc->null):primary();
        return from(DashboardMetric.CRM,stamp,items,List.of(),"沿用商机漏斗和业绩统计口径，赢单率以已赢或已输的商机为分母。");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> rows(Map<String,Object> x,String name) {return (List<Map<String,Object>>)x.get(name);}
    private static Sample<Void> primary() {return new Sample<>(Instant.now().toString(),"primary",null);}
    private static MetricData from(DashboardMetric metric,Sample<?> source,List<Value> values,List<Plot> plots,String note) {
        return new MetricData(metric.code(),source.asOf(),source.dataSource(),List.copyOf(values),plots,note);
    }
    private static Plot plot(String name,List<Point> data,String unit) {return new Plot(name,data.stream().map(p->value(p.label(),p.value(),unit)).toList());}
    private static Value value(String label,BigDecimal value,String unit) {return new Value(label,value==null?null:value.toPlainString(),unit);}
    private static BigDecimal percent(BigDecimal numerator,BigDecimal denominator) {return ratio(numerator.multiply(BigDecimal.valueOf(100)),denominator);}
    private static BigDecimal ratio(BigDecimal numerator,BigDecimal denominator) {
        return denominator.signum()==0?null:numerator.divide(denominator,2,RoundingMode.HALF_UP);
    }
}
