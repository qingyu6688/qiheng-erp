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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    @Autowired
    TradeSummaryService(ReportReadDatabase database,StringRedisTemplate redis,ObjectMapper json,
            @Value("${spring.session.redis.namespace}") String namespace) {
        this(database,redis,json,namespace,Clock.system(BUSINESS_ZONE));
    }

    TradeSummaryService(ReportReadDatabase database,StringRedisTemplate redis,ObjectMapper json,String namespace,Clock clock) {
        this.database=database;this.redis=redis;this.json=json;this.namespace=namespace+":trade-summary:v1:";this.clock=clock;
    }

    public Map<String,Object> summary(CompanyAccess actor,int months) {
        if (months<1 || months>12) throw new ApiException(400,"VALIDATION_FAILED","趋势月份数须在1到12之间");
        boolean sales=actor.hasPermission(ReportType.SALES_SUMMARY.permission());
        boolean purchase=actor.hasPermission(ReportType.PURCHASE_SUMMARY.permission());
        if (!sales && !purchase) throw new ApiException(403,"FORBIDDEN","没有查看销售或采购金额的权限");
        LocalDate today=LocalDate.now(clock.withZone(BUSINESS_ZONE));
        String key=namespace+database.replicaEnabled()+":"+scopeKey(actor,months,today,sales,purchase);
        String cached=cached(key);
        if (cached!=null) return json.readValue(cached,RESULT_TYPE);
        var result=database.read(()->calculate(actor,months,today,sales,purchase));
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

    private Map<String,Object> calculate(CompanyAccess actor,int months,LocalDate today,boolean sales,boolean purchase) {
        YearMonth firstMonth=YearMonth.from(today).minusMonths(months-1L);
        LocalDate from=firstMonth.atDay(1).isBefore(today.withDayOfYear(1))?firstMonth.atDay(1):today.withDayOfYear(1);
        if (today.minusDays(1).isBefore(from)) from=today.minusDays(1);
        var salesAmounts=sales?daily(actor,ReportType.SALES_SUMMARY,from,today):Map.<LocalDate,BigDecimal>of();
        var purchaseAmounts=purchase?daily(actor,ReportType.PURCHASE_SUMMARY,from,today):Map.<LocalDate,BigDecimal>of();
        var result=new LinkedHashMap<String,Object>();
        result.put("asOf",database.asOf());
        result.put("dataSource",database.replicaEnabled()?"replica":"primary");
        if (sales) result.put("sales",totals(salesAmounts,today));
        if (purchase) result.put("purchase",totals(purchaseAmounts,today));
        var trend=new ArrayList<Map<String,Object>>();
        for (int index=0;index<months;index++) {
            YearMonth month=firstMonth.plusMonths(index);
            var row=new LinkedHashMap<String,Object>();row.put("month",month.toString());
            if (sales) row.put("sales",sum(salesAmounts,month.atDay(1),month.atEndOfMonth()));
            if (purchase) row.put("purchase",sum(purchaseAmounts,month.atDay(1),month.atEndOfMonth()));
            trend.add(row);
        }
        result.put("trend",trend);
        return result;
    }

    private Map<LocalDate,BigDecimal> daily(CompanyAccess actor,ReportType type,LocalDate from,LocalDate to) {
        var parameters=new HashMap<String,Object>();
        parameters.put("t",actor.tenantId());parameters.put("c",actor.companyId());
        parameters.put("f",from);parameters.put("d",to);
        String sql="SELECT business_date,SUM(amount) amount FROM ("+ReportSql.query(type,true)+") facts WHERE 1=1";
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

    private String scopeKey(CompanyAccess actor,int months,LocalDate today,boolean sales,boolean purchase) {
        String scope=actor.tenantId()+"|"+actor.companyId()+"|"+actor.userId()+"|"+actor.allWarehouses()+"|"
                +actor.warehouseIds().stream().sorted().toList()+"|"+months+"|"+today+"|"+sales+"|"+purchase;
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(scope.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("无法计算工作台范围摘要",failure); }
    }
}
