package cn.erp.shared;

import cn.erp.ErpApplication;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

/** 真实两连接反序行锁，仅使用本用例的公司行，不调整数据库全局参数。 */
@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
class DeadlockIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired BusinessCommands commands;
    long tenant,first,second;
    record Scope(long tenantId,long userId,long companyId) implements ActorScope {}
    record Attempt(String key,int index,RuntimeException error) {}
    @BeforeEach void seed() {
        tenant=Ids.next();first=Ids.next();second=Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'死锁恢复验收')",tenant,"DL"+tenant);
        for(long id:List.of(first,second))jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,?,'死锁验收公司')",id,tenant,id==first?"A":"B");
    }
    @AfterEach void cleanup() {
        for(String table:List.of("idempotency_record","audit_log","company"))jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);
        jdbc.update("DELETE FROM tenant WHERE id=?",tenant);
    }
    @Test void T13DeadlockRollsBackEntireCommandAndOriginalKeyRetryHasOneEffect() throws Exception {
        var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var jobs=new ArrayList<Future<Attempt>>();
            for(int index=0;index<2;index++) {
                int number=index;String key=UUID.randomUUID().toString();
                jobs.add(pool.submit(() -> {
                    try {write(key,number,barrier);return new Attempt(key,number,null);}
                    catch(RuntimeException error) {return new Attempt(key,number,error);}
                }));
            }
            var results=List.of(jobs.getFirst().get(20,TimeUnit.SECONDS),jobs.getLast().get(20,TimeUnit.SECONDS));
            assertThat(results.stream().filter(result->result.error()==null)).hasSize(1);
            var failed=results.stream().filter(result->result.error()!=null).findFirst().orElseThrow();
            assertThat(failed.error()).isInstanceOf(TransientDataAccessException.class);
            assertThat(jdbc.queryForList("SELECT version FROM company WHERE tenant_id=? ORDER BY id",Long.class,tenant)).containsExactly(1L,1L);
            assertThat(count("audit_log")).isEqualTo(1);
            assertThat(count("idempotency_record")).isEqualTo(1);
            var response=new ApiErrors().contention();
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(response.getBody().message()).contains("原请求标识");
            // 死锁回滚没有遗留幂等占位，恢复后只重试失败请求的原键。
            assertThat(write(failed.key(),failed.index(),null)).isEqualTo("已提交"+failed.index());
            assertThat(write(failed.key(),failed.index(),null)).isEqualTo("已提交"+failed.index());
            assertThat(jdbc.queryForList("SELECT version FROM company WHERE tenant_id=? ORDER BY id",Long.class,tenant)).containsExactly(2L,2L);
            assertThat(count("audit_log")).isEqualTo(2);
            assertThat(count("idempotency_record")).isEqualTo(2);
        }
    }
    private String write(String key,int index,CyclicBarrier barrier) {
        return commands.execute(new Scope(tenant,1,first),"test.deadlock",key,Map.of("index",index),201,()->{
            long a=index==0?first:second,b=index==0?second:first;
            jdbc.update("UPDATE company SET version=version+1 WHERE tenant_id=? AND id=?",tenant,a);
            if(barrier!=null) {
                try {barrier.await(10,TimeUnit.SECONDS);}
                catch(InterruptedException error) {Thread.currentThread().interrupt();throw new IllegalStateException("死锁验收线程被中断",error);}
                catch(BrokenBarrierException|TimeoutException error) {throw new IllegalStateException("死锁验收的两条事务未同时取得首行锁",error);}
            }
            jdbc.update("UPDATE company SET version=version+1 WHERE tenant_id=? AND id=?",tenant,b);
            jdbc.update("INSERT INTO audit_log(id,tenant_id,action,object_type) VALUES(?,?,'test.deadlock','test')",Ids.next(),tenant);
            return "已提交"+index;
        },String.class);
    }
    private long count(String table) {return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=?",Long.class,tenant);}
}
