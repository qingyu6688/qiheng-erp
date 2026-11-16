package cn.erp.scheduler;

import cn.erp.identity.AccessService;
import cn.erp.shared.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class JobEngineIT {
    @Autowired JdbcTemplate jdbc;@Autowired AccessService access;@Autowired PlatformTransactionManager transactions;
    long tenant,user,company,role;JobEngine first,second;
    @BeforeEach void prepare() {
        tenant=Ids.next();user=Ids.next();company=Ids.next();role=Ids.next();first=new JobEngine(jdbc,access,transactions,false);second=new JobEngine(jdbc,access,transactions,false);
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'调度验收')",tenant,"J"+tenant);jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','调度公司')",company,tenant);jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,'runner','任务管理员','非登录测试摘要')",user,tenant);jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'RUNNER','任务管理员')",role,tenant);jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",Ids.next(),tenant,user,role);jdbc.update("INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)",Ids.next(),tenant,user,company);jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT ?,?,?,id FROM sys_permission WHERE code='job:run'",Ids.next(),tenant,role);
        jdbc.update("INSERT INTO job_definition(tenant_id,code,name,schedule,enabled) VALUES(?,'test.job','并发任务','interval:1000',0)",tenant);
    }
    @AfterEach void cleanup() {first.close();second.close();for(String table:List.of("job_run_log","job_definition","sys_user_data_scope","sys_user_role","sys_role_permission","sys_role","sys_user","company")) jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);jdbc.update("DELETE FROM tenant WHERE id=?",tenant);}
    @Test void Z02_01_twoInstancesClaimManualRequestOnceEvenWhenAutomaticScheduleIsPaused() throws Exception {
        var count=new AtomicInteger();var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        java.util.function.LongConsumer action=selected->{assertThat(selected).isEqualTo(tenant);count.incrementAndGet();started.countDown();try {if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("等待并发验收超时");} catch(InterruptedException error) {Thread.currentThread().interrupt();throw new IllegalStateException("并发验收中断",error);}};
        first.register("test.job",action);second.register("test.job",action);long id=request(user);
        try(var pool=Executors.newFixedThreadPool(2)) {var running=pool.submit(()->first.runOne(tenant,"test.job"));assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();assertThat(pool.submit(()->second.runOne(tenant,"test.job")).get(5,TimeUnit.SECONDS)).isFalse();release.countDown();assertThat(running.get(5,TimeUnit.SECONDS)).isTrue();}
        assertThat(count).hasValue(1);assertThat(jdbc.queryForObject("SELECT status FROM job_run_log WHERE id=?",String.class,id)).isEqualTo("success");assertThat(second.runOne(tenant,"test.job")).isFalse();
    }
    @Test void Z02_02_revokedOperatorIsSkippedAndUnregisteredActionsCannotExecute() {
        var count=new AtomicInteger();first.register("test.job",selected->count.incrementAndGet());long id=request(user);jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id=?",tenant);
        assertThat(first.runOne(tenant,"test.job")).isTrue();assertThat(count).hasValue(0);assertThat(jdbc.queryForObject("SELECT status FROM job_run_log WHERE id=?",String.class,id)).isEqualTo("skipped");assertThat(first.runOne(tenant,"java.lang.Runtime")).isFalse();
    }
    @Test void Z02_03_expiredLeaseCanRecoverButLiveLeaseAndOtherTenantRemainUntouched() {
        var count=new AtomicInteger();first.register("test.job",selected->count.incrementAndGet());long id=request(0);
        jdbc.update("UPDATE job_run_log SET status='running',lease_owner='old',lease_until=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",id);jdbc.update("UPDATE job_definition SET lease_owner='old',lease_until=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE tenant_id=?",tenant);
        assertThat(first.runOne(tenant+1,"test.job")).isFalse();assertThat(first.runOne(tenant,"test.job")).isTrue();assertThat(count).hasValue(1);assertThat(jdbc.queryForObject("SELECT status FROM job_run_log WHERE id=?",String.class,id)).isEqualTo("success");
    }
    @Test void Z02_04_scheduleOnlyAcceptsIntervalsAndCron() {var now=Instant.parse("2026-11-16T00:00:00Z");assertThat(JobEngine.next("interval:1000",now)).isEqualTo(now.plusSeconds(1));assertThat(JobEngine.next("0 0 3 * * *",now)).isAfter(now);for(String value:List.of("interval:0","interval:999","interval:86400001","Runtime.exec","* * *")) assertThatThrownBy(()->JobEngine.next(value,now)).isInstanceOf(ApiException.class);}
    long request(long operator) {long id=Ids.next();jdbc.update("INSERT INTO job_run_log(id,tenant_id,job_code,trigger_kind,operator_id) VALUES(?,?,'test.job',?,?)",id,tenant,operator==0?"schedule":"manual",operator==0?null:operator);return id;}
}
