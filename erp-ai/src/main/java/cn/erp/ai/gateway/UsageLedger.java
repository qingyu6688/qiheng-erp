package cn.erp.ai.gateway;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.erp.ai.common.AiActor;
import cn.erp.ai.common.AiException;

@Service
public class UsageLedger {
    private static final long DEFAULT_DAILY = 100_000;
    private static final BigDecimal DEFAULT_MONTHLY = BigDecimal.valueOf(100);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;

    UsageLedger(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Quota(long dailyTokenLimit, BigDecimal monthlyCostLimit) {}
    record Reservation(String tenant, String user, String scene, long model, LocalDate day,
                       long tokens, BigDecimal cost, BigDecimal priceIn, BigDecimal priceOut) {}

    public Quota quota(AiActor actor) {
        actor.requireAdminView();
        return limits(actor.tenantId());
    }

    public Quota saveQuota(AiActor actor, Quota input) {
        actor.require("ai_admin:manage");
        if (input.dailyTokenLimit() < 0 || input.dailyTokenLimit() > 1_000_000_000L)
            throw AiException.invalid("每日令牌配额须介于0和1000000000之间");
        BigDecimal cost = ModelCatalog.amount(input.monthlyCostLimit(),"每月金额上限",14);
        jdbc.update("""
            INSERT INTO ai_quota(tenant_id,daily_token_limit,monthly_cost_limit) VALUES(?,?,?) AS staged
            ON DUPLICATE KEY UPDATE daily_token_limit=staged.daily_token_limit,monthly_cost_limit=staged.monthly_cost_limit
            """,actor.tenantId(),input.dailyTokenLimit(),cost);
        return input;
    }

    private Quota limits(String tenant) {
        return jdbc.query("SELECT daily_token_limit,monthly_cost_limit FROM ai_quota WHERE tenant_id=?",
                (r,n) -> new Quota(r.getLong(1),r.getBigDecimal(2)),tenant).stream()
                .findFirst().orElse(new Quota(DEFAULT_DAILY,DEFAULT_MONTHLY));
    }

    @Transactional
    public Reservation reserve(AiActor actor, String scene, ModelCatalog.Target target, long input, int output) {
        String tenant = actor.tenantId();
        LocalDate day = LocalDate.now(BUSINESS_ZONE);
        LocalDate month = day.withDayOfMonth(1);
        initialize(tenant,"D",day);
        initialize(tenant,"M",month);
        long dailyTotal = jdbc.queryForObject("""
            SELECT tokens FROM ai_quota_window WHERE tenant_id=? AND window_type='D' AND window_start=? FOR UPDATE
            """,Long.class,tenant,day);
        BigDecimal monthlyTotal = jdbc.queryForObject("""
            SELECT cost FROM ai_quota_window WHERE tenant_id=? AND window_type='M' AND window_start=? FOR UPDATE
            """,BigDecimal.class,tenant,month);
        var quota = limits(tenant);
        long count = input + output;
        BigDecimal amount = cost(input,output,target.priceIn(),target.priceOut());
        if (dailyTotal + count > quota.dailyTokenLimit() || monthlyTotal.add(amount).compareTo(quota.monthlyCostLimit()) > 0)
            throw new AiException(429,"AI_QUOTA_EXCEEDED","当前企业的模型额度已用完，请联系管理员");
        update(tenant,day,count,amount);
        return new Reservation(tenant,actor.userId(),scene,target.id(),day,count,amount,target.priceIn(),target.priceOut());
    }

    @Transactional
    public void finish(Reservation r, long input, long output, long latency, String status, String errorCode) {
        BigDecimal cost = cost(input,output,r.priceIn(),r.priceOut());
        update(r.tenant(),r.day(),input + output - r.tokens(),cost.subtract(r.cost()));
        jdbc.update("""
            INSERT INTO ai_usage_daily(tenant_id,usage_date,model_id,scene,input_tokens,output_tokens,cost,requests)
            VALUES(?,?,?,?,?,?,?,1) AS staged
            ON DUPLICATE KEY UPDATE input_tokens=ai_usage_daily.input_tokens+staged.input_tokens,
              output_tokens=ai_usage_daily.output_tokens+staged.output_tokens,
              cost=ai_usage_daily.cost+staged.cost,requests=ai_usage_daily.requests+1
            """,r.tenant(),r.day(),r.model(),r.scene(),input,output,cost);
        jdbc.update("""
            INSERT INTO ai_request_log(tenant_id,user_id,scene,model_id,latency_ms,input_tokens,output_tokens,status,error_code)
            VALUES(?,?,?,?,?,?,?,?,?)
            """,r.tenant(),r.user(),r.scene(),r.model(),latency,input,output,status,errorCode);
    }

    public List<Map<String,Object>> usage(AiActor actor) {
        actor.requireAdminView();
        return jdbc.query("""
            SELECT usage_date,scene,model_id,input_tokens,output_tokens,cost,requests
            FROM ai_usage_daily WHERE tenant_id=? ORDER BY usage_date DESC,scene,model_id LIMIT 300
            """,(r,n) -> Map.of("date",r.getString(1),"scene",r.getString(2),"modelId",r.getString(3),
                "inputTokens",r.getLong(4),"outputTokens",r.getLong(5),"cost",r.getBigDecimal(6).toPlainString(),
                "requests",r.getLong(7)),actor.tenantId());
    }

    public List<Map<String,Object>> logs(AiActor actor) {
        actor.requireAdminView();
        return jdbc.query("""
            SELECT id,scene,model_id,input_tokens,output_tokens,status,error_code,latency_ms,created_at
            FROM ai_request_log WHERE tenant_id=? ORDER BY id DESC LIMIT 300
            """,(r,n) -> Map.of("id",r.getString(1),"scene",r.getString(2),"modelId",r.getString(3),
                "inputTokens",r.getLong(4),"outputTokens",r.getLong(5),"status",r.getString(6),
                "errorCode",java.util.Objects.toString(r.getString(7),""),"latencyMs",r.getLong(8),
                "createdAt",r.getTimestamp(9).toInstant().toString()),actor.tenantId());
    }

    private void initialize(String tenant,String type,LocalDate date) {
        jdbc.update("""
            INSERT INTO ai_quota_window(tenant_id,window_type,window_start) VALUES(?,?,?)
            ON DUPLICATE KEY UPDATE window_start=ai_quota_window.window_start
            """,tenant,type,date);
    }

    private void update(String tenant, LocalDate day, long delta, BigDecimal amount) {
        jdbc.update("""
            UPDATE ai_quota_window SET tokens=tokens+?
            WHERE tenant_id=? AND window_type='D' AND window_start=?
            """,delta,tenant,day);
        jdbc.update("""
            UPDATE ai_quota_window SET cost=cost+?
            WHERE tenant_id=? AND window_type='M' AND window_start=?
            """,amount,tenant,day.withDayOfMonth(1));
    }

    private static BigDecimal cost(long input,long output,BigDecimal priceIn,BigDecimal priceOut) {
        return priceIn.multiply(BigDecimal.valueOf(input)).add(priceOut.multiply(BigDecimal.valueOf(output)))
                .movePointLeft(6).setScale(6,RoundingMode.CEILING);
    }
}
