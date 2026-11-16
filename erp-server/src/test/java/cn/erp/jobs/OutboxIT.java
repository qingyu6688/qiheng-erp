package cn.erp.jobs;

import cn.erp.ErpApplication;
import cn.erp.shared.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes={ErpApplication.class,OutboxIT.Consumers.class})
@ActiveProfiles("test")
class OutboxIT {
    @TestConfiguration
    static class Consumers {
        @Bean AtomicBoolean failEvent() {return new AtomicBoolean();}
        @Bean EventHandler testHandler(JdbcTemplate jdbc,AtomicBoolean failEvent) {
            return new EventHandler() {
                public String consumer() {return "test_effect";}
                public String eventType() {return "test.effect";}
                public void handle(OutboxService.Event event) {
                    jdbc.update("INSERT INTO audit_log(id,tenant_id,action,object_type,object_id) VALUES(?,?,'test.effect','event',?)",Ids.next(),event.tenantId(),event.id());
                    if(failEvent.get()) {throw new IllegalStateException("测试模拟事务中断");}
                }
            };
        }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxService events;
    @Autowired OutboxDelivery delivery;
    @Autowired BusinessCommands commands;
    @Autowired AtomicBoolean failEvent;
    @Autowired PlatformTransactionManager manager;
    private long tenant;

    @BeforeEach void setup() {tenant=Ids.next();failEvent.set(false);}
    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM inbox_event WHERE tenant_id=?",tenant);
        jdbc.update("DELETE FROM outbox_event WHERE tenant_id=?",tenant);
        jdbc.update("DELETE FROM audit_log WHERE tenant_id=?",tenant);
        jdbc.update("DELETE FROM idempotency_record WHERE tenant_id=?",tenant);
    }

    private long publish(String type) {
        return new TransactionTemplate(manager).execute(status->events.publish(tenant,"test",Ids.next(),0,type,Map.of("value","测试数据")));
    }
    private OutboxService.Event claim(long id) {
        return events.claim(100,tenant).stream().filter(e->e.id()==id).findFirst().orElseThrow();
    }
    private long effects(long id) {return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND object_id=?",Long.class,tenant,id);}

    @Test void T06LeaseRecoveryAndInboxEffectsRemainAtomicAcrossCrashAndDuplicates() {
        long id=publish("test.effect");
        var lost=claim(id);
        jdbc.update("UPDATE outbox_event SET lease_until=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",id);
        var recovered=claim(id);
        assertThat(delivery.deliver(lost)).isFalse();
        failEvent.set(true);
        assertThatThrownBy(()->delivery.deliver(recovered)).isInstanceOf(IllegalStateException.class);
        assertThat(effects(id)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbox_event WHERE tenant_id=?",Long.class,tenant)).isZero();
        failEvent.set(false);
        new TransactionTemplate(manager).executeWithoutResult(status->{
            assertThat(delivery.deliver(recovered)).isTrue();status.setRollbackOnly();
        });
        assertThat(effects(id)).isZero();
        assertThat(delivery.deliver(recovered)).isTrue();
        assertThat(effects(id)).isOne();
        jdbc.update("UPDATE outbox_event SET status='pending',next_attempt_at=UTC_TIMESTAMP(3) WHERE id=?",id);
        assertThat(delivery.deliver(claim(id))).isTrue();
        assertThat(effects(id)).isOne();
    }

    @Test void failedEventsBackOffAndReachDeadLetterThenReplay() {
        long id=publish("test.unregistered");
        for(int i=1;i<=5;i++) {
            var event=claim(id);
            assertThatThrownBy(()->delivery.deliver(event)).isInstanceOf(IllegalStateException.class);
            events.failed(event);
            assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE id=?",String.class,id)).isEqualTo(i<5?"pending":"dead");
            if(i<5) {
                assertThat(events.claim(100,tenant)).noneMatch(item->item.id()==id);
                jdbc.update("UPDATE outbox_event SET next_attempt_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",id);
            }
        }
        events.replay(new Scope(tenant,1,0),id);
        assertThat(claim(id).attempts()).isOne();
    }

    @Test void T18ConcurrentIdempotencyRetriesHaveOneBusinessEffectAcross20Rounds() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            for(int i=0;i<20;i++) {
                String key=UUID.randomUUID().toString();
                Scope scope=new Scope(tenant,1,0);
                var start=new CountDownLatch(1);
                Callable<String> job=()->{
                    start.await();
                    return commands.execute(scope,"test.command",key,Map.of("body","同一内容"),201,()->{
                        jdbc.update("INSERT INTO audit_log(id,tenant_id,action,object_type) VALUES(?,?,'command','test')",Ids.next(),tenant);
                        return "同一结果";
                    },String.class);
                };
                var first=pool.submit(job);var second=pool.submit(job);start.countDown();
                assertThat(first.get(10,TimeUnit.SECONDS)).isEqualTo(second.get(10,TimeUnit.SECONDS));
                assertThatThrownBy(()->commands.execute(scope,"test.command",key,"different",201,()->"bad",String.class))
                    .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getErrorCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
                assertThatThrownBy(()->commands.execute(new Scope(tenant,2,0),"test.command",key,Map.of("body","同一内容"),201,()->"bad",String.class))
                    .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(403));
            }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE tenant_id=?",Long.class,tenant)).isEqualTo(20);
    }

    record Scope(long tenantId,long userId,long companyId) implements ActorScope {}

    @Test void tenantWorkerDoesNotClaimAnotherTenantsPendingEvent() {
        long other=Ids.next();long foreign=new TransactionTemplate(manager).execute(status->events.publish(other,"test",Ids.next(),0,"test.effect",Map.of("value","另一企业")));
        try {
            long own=publish("test.effect");assertThat(events.claim(100,tenant)).extracting(OutboxService.Event::id).containsExactly(own);
            assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE id=?",String.class,foreign)).isEqualTo("pending");
        } finally {jdbc.update("DELETE FROM outbox_event WHERE id=?",foreign);}
    }

    @Test void canonicalRequestHashesIgnoreObjectPropertyOrder() {
        String key=UUID.randomUUID().toString();
        var first=new java.util.LinkedHashMap<String,Object>();
        first.put("quantity","1.000000");first.put("name","测试");
        var reordered=new java.util.LinkedHashMap<String,Object>();
        reordered.put("name","测试");reordered.put("quantity","1.000000");
        var scope=new Scope(tenant,1,0);
        assertThat(commands.execute(scope,"test.canonical",key,first,201,()->"first",String.class)).isEqualTo("first");
        assertThat(commands.execute(scope,"test.canonical",key,reordered,201,()->"unexpected",String.class)).isEqualTo("first");
    }

    @Test void previouslyStoredCommandHashesStillReturnTheCommittedResponse() throws Exception {
        String key=UUID.randomUUID().toString();
        var payload=new java.util.LinkedHashMap<String,Object>();
        payload.put("z",1);payload.put("a","原请求");
        var json=new tools.jackson.databind.ObjectMapper();
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(json.writeValueAsString(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        jdbc.update("""
                INSERT INTO idempotency_record(id,tenant_id,company_id,operation,idempotency_key,request_hash,operator_id,
                status,http_status,response_body,expire_at) VALUES(?,?,0,'legacy',?,?,1,'succeeded',201,?,DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY))
                """,Ids.next(),tenant,key,hash,json.writeValueAsString("旧结果"));
        assertThat(commands.execute(new Scope(tenant,1,0),"legacy",key,payload,201,()->"重复写入",String.class)).isEqualTo("旧结果");
    }
}
