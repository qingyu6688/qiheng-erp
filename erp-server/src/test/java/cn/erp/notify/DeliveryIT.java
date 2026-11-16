package cn.erp.notify;

import cn.erp.identity.*;
import cn.erp.jobs.OutboxService;
import cn.erp.shared.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.*;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties="ERP_LOGIN_SETTINGS_KEY=9MPE+YdNGv8bC9K/9OWeYl5K0tqY7MSqwxW8gWsPmpU=")
@ActiveProfiles("test")
@Transactional
@Sql(scripts="/constraints/fixtures.sql",config=@SqlConfig(encoding="UTF-8"))
class DeliveryIT {
    @Autowired JdbcTemplate jdbc;@Autowired TemplateService templates;@Autowired DeliveryService delivery;@MockitoBean NotificationChannels channels;@MockitoBean RobotClient robot;
    CompanyAccess actor;
    @BeforeEach void prepare() {
        jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(89001,70101,'sender','消息收件人','非登录测试摘要')");jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(89011,70101,'DELIVERY','测试消息管理员')");jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(89012,70101,89001,89011)");jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT 89000+id,70101,89011,id FROM sys_permission");jdbc.update("INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(89013,70101,89001,'company',70201)");
        actor=new CompanyAccess(70101,89001,70201,true,Set.of(),Set.copyOf(jdbc.queryForList("SELECT code FROM sys_permission",String.class)));when(channels.destination(70101,89001,"email")).thenReturn("verified@example.test");
    }
    @Test void Y06_02_templateVariablesAreLiteralWhitelistAndPresetTemplatesRemainReadOnly() {
        var saved=templates.save(actor,null,key(),new TemplateService.Input("custom.test","email","{title}","正文 {title}",List.of("title"),null,"active",0L));assertThat(saved).containsEntry("code","custom.test");
        var rendered=templates.render(70101,"custom.test","email",Map.of("title","$1\\<script>字面值</script>"));assertThat(rendered.title()).isEqualTo("$1\\<script>字面值</script>");
        assertThatThrownBy(()->templates.save(actor,1L,key(),new TemplateService.Input("business.todo","site","标题","内容",List.of(),null,"active",0L))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->templates.save(actor,null,key(),new TemplateService.Input("bad.expr","email","${system.exec()}","内容",List.of(),null,"active",0L))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->templates.save(actor,null,key(),new TemplateService.Input("business.todo","email","{unknown}","内容",List.of("unknown"),null,"active",0L))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->templates.save(actor,null,key(),new TemplateService.Input("custom.sms","sms","标题","内容",List.of(),"未审核模板","active",0L))).isInstanceOf(ApiException.class);
    }
    @Test void Y06_03_subscriptionAndVerifiedBindingAreRequiredAndDeliveryIsDeduplicated() {
        template();long message=message();delivery.handle(event(message));assertThat(logCount()).isZero();
        subscribe();delivery.handle(event(message));delivery.handle(event(message));assertThat(logCount()).isEqualTo(1);
        var queued=jdbc.queryForMap("SELECT * FROM notify_send_log WHERE tenant_id=70101");assertThat(queued.get("payload_ciphertext").toString()).doesNotContain("verified@example.test","提醒标题");assertThat(queued.get("receiver")).isEqualTo("v***@example.test");
        assertThat(delivery.processNext(70102)).isFalse();assertThat(delivery.processNext(70101)).isTrue();verify(channels).send(eq(70101L),eq("email"),eq("verified@example.test"),eq("提醒标题"),anyString(),isNull(),anyMap());assertThat(jdbc.queryForObject("SELECT status FROM notify_send_log WHERE tenant_id=70101",String.class)).isEqualTo("sent");assertThat(jdbc.queryForObject("SELECT payload_ciphertext FROM notify_send_log WHERE tenant_id=70101",String.class)).isNull();
        assertThat(delivery.processNext(70101)).isFalse();
    }
    @Test void Y06_04_unsubscribeAndChangedBindingSkipQueuedMessage() {
        template();subscribe();long message=message();delivery.handle(event(message));when(channels.destination(70101,89001,"email")).thenReturn("changed@example.test");delivery.processNext(70101);assertThat(jdbc.queryForObject("SELECT status FROM notify_send_log WHERE tenant_id=70101",String.class)).isEqualTo("skipped");verify(channels,never()).send(anyLong(),anyString(),anyString(),anyString(),anyString(),nullable(String.class),anyMap());
        long next=message();delivery.handle(event(next));jdbc.update("UPDATE notify_subscription SET enabled=0 WHERE tenant_id=70101");delivery.processNext(70101);assertThat(jdbc.queryForList("SELECT status FROM notify_send_log WHERE tenant_id=70101",String.class)).containsOnly("skipped");
    }
    @Test void Y06_05_transientFailuresBackOffAtOneFiveFifteenMinutesAndKeepErrorsRedacted() {
        template();subscribe();delivery.handle(event(message()));doThrow(new ApiException(503,"CHANNEL_FAILED","禁止记录的邮箱、口令或第三方响应")).when(channels).send(anyLong(),anyString(),anyString(),anyString(),anyString(),nullable(String.class),anyMap());
        for(int attempt=1;attempt<=4;attempt++) {delivery.processNext(70101);var log=jdbc.queryForMap("SELECT *,TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(3),next_attempt_at) wait_seconds FROM notify_send_log WHERE tenant_id=70101");assertThat(log.get("error").toString()).doesNotContain("禁止记录","verified@example.test");assertThat(((Number)log.get("attempts")).intValue()).isEqualTo(attempt);assertThat(log.get("status")).isEqualTo(attempt==4?"failed":"pending");if(attempt<4) assertThat(((Number)log.get("wait_seconds")).intValue()).isBetween((attempt==1?60:attempt==2?300:900)-2,attempt==1?60:attempt==2?300:900);jdbc.update("UPDATE notify_send_log SET next_attempt_at=UTC_TIMESTAMP(3) WHERE tenant_id=70101");}
        assertThat(jdbc.queryForObject("SELECT payload_ciphertext FROM notify_send_log WHERE tenant_id=70101",String.class)).isNull();
    }
    void template() {templates.save(actor,null,key(),new TemplateService.Input("business.todo","email","{title}","请登录柒衡ERP处理。",List.of("title"),null,"active",0L));}
    void subscribe() {jdbc.update("INSERT INTO notify_subscription(tenant_id,user_id,category,channel,enabled) VALUES(70101,89001,'system','email',1)");}
    long message() {long id=Ids.next();jdbc.update("INSERT INTO notify_message(id,tenant_id,company_id,user_id,template_code,title,content,category,event_key) VALUES(?,70101,70201,89001,'business.todo','提醒标题','安全提醒','system',?)",id,key());return id;}
    OutboxService.Event event(long message) {return new OutboxService.Event(Ids.next(),70101,"notify_message",message,0,"notify.created","{}","owner",1);}
    int logCount() {return jdbc.queryForObject("SELECT COUNT(*) FROM notify_send_log WHERE tenant_id=70101",Integer.class);}
    String key() {return UUID.randomUUID().toString();}
}
