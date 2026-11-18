package cn.erp.reporting;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class TradeSummaryService {
    private static final Logger LOG=LoggerFactory.getLogger(TradeSummaryService.class);
    private static final Duration CACHE_LIFETIME=Duration.ofMinutes(5);
    private static final ZoneId BUSINESS_ZONE=ZoneId.of("Asia/Shanghai");
    private static final TypeReference<Map<String,Object>> RESULT_TYPE=new TypeReference<>() {};
    private final ReportReadDatabase database;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final String namespace;
    private final Clock clock;

    private record Metrics(boolean sales, boolean purchase, boolean receivable, boolean inventory, boolean inventoryFlow) {
        static Metrics permitted(CompanyAccess actor) {
            boolean inventory = actor.orgScope().allDepartments() && actor.hasPermission("cost:view")
                    && actor.hasPermission(ReportType.INVENTORY_SUMMARY.permission());
            return new Metrics(actor.hasPermission(ReportType.SALES_SUMMARY.permission()),
                    actor.hasPermission(ReportType.PURCHASE_SUMMARY.permission()),
                    actor.allWarehouses() && actor.hasPermission(ReportType.RECEIVABLE_AGING.permission()),
                    inventory, inventory && actor.hasPermission(ReportType.INVENTORY_FLOW.permission()));
        }
    }

    @Autowired
    TradeSummaryService(ReportReadDatabase database,StringRedisTemplate redis,ObjectMapper json,
            @Value("${spring.session.redis.namespace}") String namespace) {
        this(database,redis,json,namespace,Clock.system(BUSINESS_ZONE));
    }

    TradeSummaryService(ReportReadDatabase database,StringRedisTemplate redis,ObjectMapper json,String namespace,Clock clock) {
        this.database=database;this.redis=redis;this.json=json;this.namespace=namespace+":trade-summary:v2:";this.clock=clock;
    }

    public Map<String,Object> summary(CompanyAccess actor,int months) {
        if (months<1 || months>12) throw new ApiException(400,"VALIDATION_FAILED","趋势月份数须在1到12之间");
        var metrics=Metrics.permitted(actor);
        if (!metrics.sales() && !metrics.purchase() && !metrics.receivable() && !metrics.inventory())
            throw new ApiException(403,"FORBIDDEN","没有查看工作台金额的权限");
        LocalDate today=LocalDate.now(clock.withZone(BUSINESS_ZONE));
        String key=namespace+database.replicaEnabled()+":"+scopeKey(actor,months,today,metrics);
        String cached=cached(key);
        if (cached!=null) return json.readValue(cached,RESULT_TYPE);
        var result=database.read(()->calculate(actor,months,today,metrics));
        try { redis.opsForValue().set(key,json.writeValueAsString(result),CACHE_LIFETIME); }
        catch (DataAccessException failure) { LOG.warn("工作台金额缓存写入失败，本次返回数据库统计结果"); }
        return result;
    }

    private String cached(String key) {
        try { return redis.opsForValue().get(key); }
        catch (DataAccessException failure) {
            LOG.warn("工作台金额缓存读取失败，本次重新查询数据库");
            return null;
        }
    }

    private Map<String,Object> calculate(CompanyAccess actor,int months,LocalDate today,Metrics metrics) {
        YearMonth firstMonth=YearMonth.from(today).minusMonths(months-1L);
        LocalDate from=today.minusYears(1).withDayOfMonth(1);
        var result=new LinkedHashMap<String,Object>();
        result.put("asOf",database.asOf());
        result.put("dataSource",database.replicaEnabled()?"replica":"primary");
        var trend=new ArrayList<Map<String,Object>>();
        for (int index=0;index<months;index++) {
            var row=new LinkedHashMap<String,Object>();
            row.put("month",firstMonth.plusMonths(index).toString());
            trend.add(row);
        }
        var comparison=new LinkedHashMap<String,Object>();
        if (metrics.sales()) addFlow(result,comparison,trend,"sales",daily(actor,ReportType.SALES_SUMMARY,from,today),today);
        if (metrics.purchase()) addFlow(result,comparison,trend,"purchase",daily(actor,ReportType.PURCHASE_SUMMARY,from,today),today);
        if (metrics.receivable()) {
            var sampled=new HashMap<LocalDate,String>();
            addBalance(result,comparison,trend,"receivable",today,
                    date->sampled.computeIfAbsent(date,cutoff->receivable(actor,cutoff,cutoff.equals(today))));
        }
        if (metrics.inventory()) {
            String current=aggregate(actor,ReportType.INVENTORY_SUMMARY,"cost_amount",Map.of());
            if (metrics.inventoryFlow()) {
                var amounts=inventoryDaily(actor,from,today);
                addBalance(result,comparison,trend,"inventory",today,
                        date->date.equals(today)?current:sum(amounts,from,date));
            } else {
                result.put("inventory",current);
            }
        }
        result.put("comparison",comparison);
        result.put("trend",trend);
        return result;
    }

    private void addFlow(Map<String,Object> result,Map<String,Object> comparison,List<Map<String,Object>> trend,
                         String key,Map<LocalDate,BigDecimal> amounts,LocalDate today) {
        result.put(key,totals(amounts,today));
        LocalDate lastMonth=today.minusMonths(1),lastYear=today.minusYears(1);
        comparison.put(key,Map.of("previousMonth",sum(amounts,lastMonth.withDayOfMonth(1),lastMonth),
                "previousYear",sum(amounts,lastYear.withDayOfMonth(1),lastYear)));
        for (var point:trend) {
            var month=YearMonth.parse(point.get("month").toString());
            point.put(key,sum(amounts,month.atDay(1),month.atEndOfMonth()));
        }
    }

    private void addBalance(Map<String,Object> result,Map<String,Object> comparison,List<Map<String,Object>> trend,
                            String key,LocalDate today,Function<LocalDate,String> at) {
        String current=at.apply(today);
        result.put(key,current);
        comparison.put(key,Map.of("previousMonth",at.apply(today.minusMonths(1)),"previousYear",at.apply(today.minusYears(1))));
        for (var point:trend) {
            var month=YearMonth.parse(point.get("month").toString());
            point.put(key,month.equals(YearMonth.from(today))?current:at.apply(month.atEndOfMonth()));
        }
    }

    private String receivable(CompanyAccess actor,LocalDate date,boolean current) {
        var cutoff=date.plusDays(1).atStartOfDay(BUSINESS_ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        return aggregate(actor,ReportType.RECEIVABLE_AGING,"open_amount",Map.of("today",date,"agingNow",current,"agingEnd",cutoff));
    }

    private String aggregate(CompanyAccess actor,ReportType type,String amount,Map<String,Object> extra) {
        var params=DashboardReportSources.params(actor,null,null);
        params.putAll(extra);
        String sql="SELECT COALESCE(SUM("+amount+"),0) FROM ("+ReportSql.query(type,true,actor)+") facts WHERE 1=1";
        return database.jdbc().queryForObject(sql+warehouseFilter(actor,type),params,BigDecimal.class).toPlainString();
    }

    private Map<LocalDate,BigDecimal> inventoryDaily(CompanyAccess actor,LocalDate from,LocalDate to) {
        var params=DashboardReportSources.params(actor,LocalDate.of(1000,1,1),to);
        params.put("opening",from);
        String sql="SELECT GREATEST(business_date,DATE(:opening)) day_key,SUM(cost_amount) amount FROM ("
                +ReportSql.query(ReportType.INVENTORY_FLOW,true,actor)+") facts WHERE 1=1"
                +warehouseFilter(actor,ReportType.INVENTORY_FLOW)+" GROUP BY day_key";
        var result=new HashMap<LocalDate,BigDecimal>();
        database.jdbc().query(sql,params,(org.springframework.jdbc.core.RowCallbackHandler) row->
                result.put(row.getDate("day_key").toLocalDate(),row.getBigDecimal("amount")));
        return result;
    }

    private String warehouseFilter(CompanyAccess actor,ReportType type) {
        return !type.companyWide() && !actor.allWarehouses()?" AND warehouse_id IN (:warehouses)":"";
    }

    private Map<LocalDate,BigDecimal> daily(CompanyAccess actor,ReportType type,LocalDate from,LocalDate to) {
        var parameters=new HashMap<String,Object>();
        parameters.put("t",actor.tenantId());parameters.put("c",actor.companyId());
        parameters.put("f",from);parameters.put("d",to);
        String sql="SELECT business_date,SUM(amount) amount FROM ("+ReportSql.query(type,true,actor)+") facts WHERE 1=1";
        if (!actor.allWarehouses()) {
            parameters.put("warehouses",actor.warehouseIds().isEmpty()?List.of(-1L):actor.warehouseIds());
            sql+=" AND warehouse_id IN (:warehouses)";
        }
        sql+=" GROUP BY business_date";
        var result=new HashMap<LocalDate,BigDecimal>();
        database.jdbc().query(sql,parameters,(org.springframework.jdbc.core.RowCallbackHandler) row->
                result.put(row.getDate("business_date").toLocalDate(),row.getBigDecimal("amount")));
        return result;
    }

    private Map<String,String> totals(Map<LocalDate,BigDecimal> amounts,LocalDate today) {
        return Map.of("today",sum(amounts,today,today),"yesterday",sum(amounts,today.minusDays(1),today.minusDays(1)),
                "month",sum(amounts,today.withDayOfMonth(1),today),"year",sum(amounts,today.withDayOfYear(1),today));
    }

    private String sum(Map<LocalDate,BigDecimal> amounts,LocalDate from,LocalDate to) {
        return amounts.entrySet().stream().filter(entry->!entry.getKey().isBefore(from) && !entry.getKey().isAfter(to))
                .map(Map.Entry::getValue).reduce(BigDecimal.ZERO.setScale(2),BigDecimal::add).toPlainString();
    }

    private String scopeKey(CompanyAccess actor,int months,LocalDate today,Metrics metrics) {
        String scope=actor.tenantId()+"|"+actor.companyId()+"|"+actor.userId()+"|"+actor.allWarehouses()+"|"
                +actor.warehouseIds().stream().sorted().toList()+"|"+actor.orgScope().signature()+"|"+months+"|"+today+"|"+metrics;
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(scope.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("无法计算工作台范围摘要",failure); }
    }
}
