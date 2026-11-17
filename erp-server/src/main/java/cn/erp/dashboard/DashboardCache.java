package cn.erp.dashboard;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class DashboardCache {
    private static final Logger LOG=LoggerFactory.getLogger(DashboardCache.class);
    private static final Duration TTL=Duration.ofMinutes(10);
    private static final DefaultRedisScript<Long> RATE=new DefaultRedisScript<>("""
            local attempts=redis.call('INCR',KEYS[1]);
            if attempts==1 then redis.call('EXPIRE',KEYS[1],60) end;
            return attempts;
            """,Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final String namespace;

    DashboardCache(StringRedisTemplate redis,ObjectMapper json,@Value("${spring.session.redis.namespace}") String namespace) {
        this.redis=redis;this.json=json;this.namespace=namespace+":dashboard:v1:";
    }

    void refresh(CompanyAccess actor) {
        try {
            // 公司切换不得绕过同一登录人的限流。
            Long count=redis.execute(RATE,List.of(namespace+"refresh:"+actor.tenantId()+":"+actor.userId()));
            if (count==null) throw new ApiException(503,"DASHBOARD_RATE_UNAVAILABLE","刷新暂不可用，请稍后再试");
            if (count>2) throw new ApiException(429,"DASHBOARD_RATE_LIMIT","每分钟可手动刷新两次，请稍后再试");
        } catch (DataAccessException error) {
            LOG.warn("驾驶舱手动刷新限流服务不可用");
            throw new ApiException(503,"DASHBOARD_RATE_UNAVAILABLE","刷新暂不可用，请稍后再试");
        }
    }

    DashboardMetrics.MetricData result(CompanyAccess actor,DashboardMetric metric,DashboardMetrics.Period period,boolean refresh,
                                       Supplier<DashboardMetrics.MetricData> query) {
        // CRM 客户移交可在用户权限集合不变时收回可见对象，受限 CRM 始终按主库当前范围读取。
        if (metric==DashboardMetric.CRM && (!actor.orgScope().allDepartments() || !actor.hasPermission("crm_pool:view")
                || !actor.hasPermission("crm_customer:view_all") && !actor.hasPermission("crm_customer:manage_all"))) return query.get();
        String key=namespace+metric.code()+":"+period.month()+":"+period.today()+":"+scope(actor);
        if (!refresh) {
            try {
                var value=redis.opsForValue().get(key);
                if (value!=null) return json.readValue(value,DashboardMetrics.MetricData.class);
            } catch (DataAccessException|tools.jackson.core.JacksonException error) {
                LOG.warn("驾驶舱指标缓存读取失败，本次重新计算");
            }
        }
        var result=query.get();
        try {redis.opsForValue().set(key,json.writeValueAsString(result),TTL);}
        catch (DataAccessException error) {LOG.warn("驾驶舱指标缓存写入失败，本次直接返回计算结果");}
        return result;
    }

    static String scope(CompanyAccess actor) {
        String base=actor.tenantId()+"|"+actor.companyId()+"|"+actor.userId()+"|"+actor.allWarehouses()+"|"
                +actor.warehouseIds().stream().sorted().toList()+"|"+actor.orgScope().signature()+"|"+actor.permissions().stream().sorted().toList();
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(base.getBytes(StandardCharsets.UTF_8)));}
        catch (NoSuchAlgorithmException error) {throw new IllegalStateException("缺少驾驶舱权限摘要算法",error);}
    }
}
