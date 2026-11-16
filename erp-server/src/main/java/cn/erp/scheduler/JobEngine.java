package cn.erp.scheduler;

import cn.erp.identity.*;
import cn.erp.shared.*;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongConsumer;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 定义租约保护同企业同动作，执行日志租约保护单次请求；动作自身继续保留业务幂等。 */
@Service
public class JobEngine implements ManagedJobs {
    private static final Logger LOG=LoggerFactory.getLogger(JobEngine.class);
    private final Map<String,LongConsumer> actions=new ConcurrentHashMap<>();
    private final Map<Long,Claim> running=new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),Thread.ofPlatform().daemon().name("erp-managed-job-",0).factory());
    private final JdbcTemplate jdbc;private final AccessService access;private final TransactionTemplate tx;private final boolean enabled;private final String node=UUID.randomUUID().toString();
    public JobEngine(JdbcTemplate jdbc,AccessService access,PlatformTransactionManager manager,@Value("${erp.worker.enabled:true}") boolean enabled) {this.jdbc=jdbc;this.access=access;this.tx=new TransactionTemplate(manager);this.enabled=enabled;}
    @Override public void register(String code,LongConsumer action) {if(actions.putIfAbsent(code,action)!=null) throw new IllegalStateException("定时任务动作重复登记："+code);}
    public boolean registered(String code) {return actions.containsKey(code);}
    public void ensure(long tenant) {jdbc.update("INSERT IGNORE INTO job_definition(tenant_id,code,name,schedule,enabled,next_run_at) SELECT ?,code,name,schedule,enabled,UTC_TIMESTAMP(3) FROM job_definition WHERE tenant_id=0",tenant);}
    @Scheduled(fixedDelay=500) public void tick() {
        if(!enabled) return;
        try {
            for(long tenant:jdbc.queryForList("SELECT id FROM tenant WHERE status='active'",Long.class)) ensure(tenant);
            int capacity=Math.min(16,executor.getQueue().remainingCapacity());
            for(var row:jdbc.queryForList("SELECT tenant_id,code FROM job_definition d WHERE d.tenant_id>0 AND (d.lease_until IS NULL OR d.lease_until<UTC_TIMESTAMP(3)) AND ((d.enabled=1 AND (d.next_run_at IS NULL OR d.next_run_at<=UTC_TIMESTAMP(3))) OR EXISTS(SELECT 1 FROM job_run_log l WHERE l.tenant_id=d.tenant_id AND l.job_code=d.code AND (l.status='pending' OR l.status='running' AND l.lease_until<UTC_TIMESTAMP(3)))) ORDER BY COALESCE(d.next_run_at,'1970-01-01'),d.tenant_id,d.code LIMIT ?",capacity)) {
                String code=row.get("code").toString();if(!registered(code)) continue;Claim claim=claim(((Number)row.get("tenant_id")).longValue(),code);if(claim!=null) executor.execute(()->execute(claim));
            }
        } catch(RuntimeException failure) {LOG.warn("定时任务调度暂不可用，异常类型 {}",failure.getClass().getSimpleName());}
    }
    /** 供一次性调度与并发验收使用，仍须取得数据库租约。 */
    public boolean runOne(long tenant,String code) {Claim claim=claim(tenant,code);if(claim==null) return false;execute(claim);return true;}
    private Claim claim(long tenant,String code) {
        if(!registered(code)) return null;
        return tx.execute(status->{
            var definitions=jdbc.queryForList("SELECT * FROM job_definition WHERE tenant_id=? AND code=? AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(3)) FOR UPDATE SKIP LOCKED",tenant,code);if(definitions.isEmpty()) return null;var definition=definitions.getFirst();
            var requests=jdbc.queryForList("SELECT * FROM job_run_log WHERE tenant_id=? AND job_code=? AND (status='pending' OR status='running' AND lease_until<UTC_TIMESTAMP(3)) ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED",tenant,code);
            long id,operator=0;String kind;
            if(!requests.isEmpty()) {var request=requests.getFirst();id=((Number)request.get("id")).longValue();operator=request.get("operator_id")==null?0:((Number)request.get("operator_id")).longValue();kind=request.get("trigger_kind").toString();}
            else {
                if(!flag(definition.get("enabled"))||instant(definition.get("next_run_at")).isAfter(Instant.now())) return null;
                id=Ids.next();kind="schedule";jdbc.update("INSERT INTO job_run_log(id,tenant_id,job_code,trigger_kind) VALUES(?,?,?,'schedule')",id,tenant,code);
            }
            String owner=UUID.randomUUID().toString();jdbc.update("UPDATE job_definition SET lease_owner=?,lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 2 MINUTE),next_run_at=? WHERE tenant_id=? AND code=?",owner,java.sql.Timestamp.from(next(definition.get("schedule").toString(),Instant.now())),tenant,code);
            jdbc.update("UPDATE job_run_log SET status='running',node_id=?,lease_owner=?,lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 2 MINUTE),started_at=UTC_TIMESTAMP(3),finished_at=NULL,message=NULL WHERE id=?",node,owner,id);
            return new Claim(id,tenant,code,operator,kind,owner);
        });
    }
    private void execute(Claim claim) {
        running.put(claim.id(),claim);String result="success",message="执行完成";
        try {
            if(claim.kind().equals("manual")) {var actor=access.forUser(claim.tenant(),claim.operator(),0);if(actor==null) throw new ApiException(403,"FORBIDDEN","发起账号已失效");PlatformAccess.require(jdbc,actor,"job:run");}
            actions.get(claim.code()).accept(claim.tenant());
        } catch(ApiException failure) {result=failure.getStatus()==403?"skipped":"failed";message=result.equals("skipped")?"发起账号或授权已变化，本次执行已跳过":"动作未完成，请按执行编号检查运行日志";}
        catch(RuntimeException failure) {result="failed";message="动作未完成，请按执行编号检查运行日志";LOG.warn("任务执行未完成，编号 {}，异常类型 {}",claim.id(),failure.getClass().getSimpleName());}
        finally {running.remove(claim.id());}
        String outcome=result,summary=message;
        tx.executeWithoutResult(status->{
            jdbc.update("UPDATE job_run_log SET status=?,message=?,finished_at=UTC_TIMESTAMP(3),lease_owner=NULL,lease_until=NULL WHERE id=? AND lease_owner=?",outcome,summary,claim.id(),claim.owner());
            jdbc.update("UPDATE job_definition SET last_run_at=UTC_TIMESTAMP(3),last_result=?,lease_owner=NULL,lease_until=NULL WHERE tenant_id=? AND code=? AND lease_owner=?",outcome,claim.tenant(),claim.code(),claim.owner());
        });
    }
    @Scheduled(fixedDelay=10000) public void heartbeat() {
        for(var claim:running.values()) try {tx.executeWithoutResult(status->{
            int renewed=jdbc.update("UPDATE job_definition SET lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 2 MINUTE) WHERE tenant_id=? AND code=? AND lease_owner=? AND lease_until>=UTC_TIMESTAMP(3)",claim.tenant(),claim.code(),claim.owner());
            if(renewed==1) jdbc.update("UPDATE job_run_log SET lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 2 MINUTE) WHERE id=? AND lease_owner=?",claim.id(),claim.owner());
        });} catch(RuntimeException failure) {LOG.warn("任务租约续期未完成，编号 {}",claim.id());}
    }
    public static Instant next(String schedule,Instant after) {
        if(schedule==null) throw invalid();
        if(schedule.startsWith("interval:")) {try {long delay=Long.parseLong(schedule.substring(9));if(delay>=1000&&delay<=86_400_000) return after.plusMillis(delay);} catch(NumberFormatException failure) {throw invalid();}throw invalid();}
        try {var expression=CronExpression.parse(schedule);var next=expression.next(after.atZone(ZoneId.of("Asia/Shanghai")));if(next==null) throw invalid();if(Duration.between(after,next.toInstant()).toMillis()<1000) throw invalid();return next.toInstant();} catch(IllegalArgumentException failure) {throw invalid();}
    }
    private static ApiException invalid() {return new ApiException(400,"VALIDATION_FAILED","计划须为有效的六段Cron表达式，或interval:毫秒数（1000至86400000）");}
    private static boolean flag(Object value) {return Boolean.TRUE.equals(value)||value instanceof Number n&&n.intValue()==1;}
    private static Instant instant(Object value) {if(value instanceof java.sql.Timestamp time) return time.toInstant();if(value instanceof LocalDateTime time) return time.toInstant(ZoneOffset.UTC);return Instant.EPOCH;}
    private record Claim(long id,long tenant,String code,long operator,String kind,String owner) {}
    @PreDestroy void close() {executor.shutdownNow();}
}
