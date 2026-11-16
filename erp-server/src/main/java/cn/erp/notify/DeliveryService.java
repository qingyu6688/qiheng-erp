package cn.erp.notify;

import cn.erp.identity.*;
import cn.erp.jobs.*;
import cn.erp.shared.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class DeliveryService implements EventHandler {
    private static final Logger LOG=LoggerFactory.getLogger(DeliveryService.class);
    private final JdbcTemplate jdbc;private final NamedParameterJdbcTemplate named;private final AccessService access;private final NotificationAccess visibility;private final TemplateService templates;private final NotificationChannels channels;private final SecretProtector secrets;private final RobotSettings robots;private final RobotClient robot;private final ObjectMapper json;private final TransactionTemplate tx;
    public record Payload(String destination,String title,String content,String smsTemplateId,Map<String,String> parameters) {}
    public DeliveryService(JdbcTemplate jdbc,NamedParameterJdbcTemplate named,AccessService access,NotificationAccess visibility,TemplateService templates,NotificationChannels channels,SecretProtector secrets,RobotSettings robots,RobotClient robot,ObjectMapper json,PlatformTransactionManager manager) {this.jdbc=jdbc;this.named=named;this.access=access;this.visibility=visibility;this.templates=templates;this.channels=channels;this.secrets=secrets;this.robots=robots;this.robot=robot;this.json=json;tx=new TransactionTemplate(manager);}
    @Override public String eventType() {return "notify.created";}
    @Override public String consumer() {return "notify.delivery";}
    @Override public void handle(OutboxService.Event event) {
        var rows=jdbc.queryForList("SELECT * FROM notify_message WHERE tenant_id=? AND id=?",event.tenantId(),event.aggregateId());if(rows.isEmpty()) return;var message=rows.getFirst();
        var actor=recipient(message);if(actor==null||!visible(actor,event.aggregateId())) return;
        var parameters=Map.of("title",message.get("title").toString(),"message",message.get("content").toString());
        for(String channel:List.of("email","sms")) {
            if(!subscribed(actor,message.get("category").toString(),channel)) continue;
            String destination=channels.destination(actor.tenantId(),actor.userId(),channel);var rendered=templates.render(actor.tenantId(),message.get("template_code").toString(),channel,parameters);
            if(destination==null||rendered==null) {queue(message,channel,"未绑定或模板未启用",null,"skipped");continue;}
            queue(message,channel,NotificationChannels.mask(destination),new Payload(destination,rendered.title(),rendered.content(),rendered.smsTemplateId(),rendered.parameters()),secrets.configured()?"pending":"failed");
        }
        if(message.get("category").equals("todo")&&robots.current(actor.tenantId()).enabled()) {
            Long first=jdbc.queryForObject("SELECT MIN(id) FROM notify_message WHERE tenant_id=? AND company_id=? AND event_key=?",Long.class,actor.tenantId(),actor.companyId(),message.get("event_key"));
            if(first!=null&&first==event.aggregateId()) queue(message,"wecom","企业微信工作群",null,"pending");
        }
    }
    private void queue(Map<String,Object> message,String channel,String receiver,Payload payload,String status) {
        long tenant=((Number)message.get("tenant_id")).longValue();String encrypted=payload!=null&&secrets.configured()?secrets.encrypt(tenant,"delivery",json.writeValueAsString(payload)):null;
        jdbc.update("INSERT IGNORE INTO notify_send_log(id,tenant_id,company_id,user_id,message_id,channel,template_code,receiver,payload_ciphertext,status,error) VALUES(?,?,?,?,?,?,?,?,?,?,?)",Ids.next(),tenant,message.get("company_id"),message.get("user_id"),message.get("id"),channel,message.get("template_code"),receiver,encrypted,status,status.equals("failed")?"平台加密密钥未配置":null);
    }
    public boolean processNext(long tenant) {
        var log=tx.execute(status->{
            var rows=jdbc.queryForList("SELECT * FROM notify_send_log WHERE tenant_id=? AND ((status='pending' AND next_attempt_at<=UTC_TIMESTAMP(3)) OR (status='sending' AND lease_until<UTC_TIMESTAMP(3))) ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",tenant);if(rows.isEmpty()) return null;
            var row=rows.getFirst();String owner=UUID.randomUUID().toString();jdbc.update("UPDATE notify_send_log SET status='sending',attempts=attempts+1,lease_owner=?,lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 2 MINUTE) WHERE id=?",owner,row.get("id"));row.put("lease_owner",owner);row.put("attempts",((Number)row.get("attempts")).intValue()+1);return row;
        });if(log==null) return false;
        if(((Number)log.get("attempts")).intValue()>4) {finish(log,"failed","发送租约连续失效，结果待核对");return true;}
        try {
            var messageRows=jdbc.queryForList("SELECT * FROM notify_message WHERE tenant_id=? AND id=?",tenant,log.get("message_id"));
            if(messageRows.isEmpty()) {finish(log,"skipped",null);return true;}
            var message=messageRows.getFirst();var actor=recipient(message);String channel=log.get("channel").toString();
            if(actor==null||!visible(actor,((Number)message.get("id")).longValue())) {finish(log,"skipped",null);return true;}
            if(channel.equals("wecom")) {var config=robots.current(tenant);if(!config.enabled()) {finish(log,"skipped",null);return true;}robot.send(config.key());}
            else {
                if(!subscribed(actor,message.get("category").toString(),channel)||templates.render(tenant,message.get("template_code").toString(),channel,Map.of("title",message.get("title").toString(),"message",message.get("content").toString()))==null) {finish(log,"skipped",null);return true;}
                Payload payload=json.readValue(secrets.decrypt(tenant,"delivery",log.get("payload_ciphertext").toString()),Payload.class);
                if(!Objects.equals(payload.destination(),channels.destination(tenant,actor.userId(),channel))) {finish(log,"skipped",null);return true;}
                channels.send(tenant,channel,payload.destination(),payload.title(),payload.content(),payload.smsTemplateId(),payload.parameters());
            }
            finish(log,"sent",null);
        } catch(RuntimeException failure) {
            int attempts=((Number)log.get("attempts")).intValue();int delay=switch(attempts) {case 1->60;case 2->300;default->900;};
            jdbc.update("UPDATE notify_send_log SET status=?,error='渠道发送未完成，请核对渠道配置及收件人绑定',next_attempt_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL ? SECOND),lease_owner=NULL,lease_until=NULL,finished_at=IF(?,UTC_TIMESTAMP(3),NULL),payload_ciphertext=IF(?,NULL,payload_ciphertext) WHERE id=? AND lease_owner=?",attempts>=4?"failed":"pending",delay,attempts>=4,attempts>=4,log.get("id"),log.get("lease_owner"));
            LOG.warn("消息发送未完成，日志号 {}，异常类型 {}",log.get("id"),failure.getClass().getSimpleName());
        }return true;
    }
    private void finish(Map<String,Object> log,String status,String error) {jdbc.update("UPDATE notify_send_log SET status=?,error=?,payload_ciphertext=NULL,finished_at=UTC_TIMESTAMP(3),lease_owner=NULL,lease_until=NULL WHERE id=? AND lease_owner=?",status,error,log.get("id"),log.get("lease_owner"));}
    private boolean visible(CompanyAccess actor,long id) {var filter=visibility.visible(actor);var params=new HashMap<>(filter.params());params.put("id",id);return named.queryForObject("SELECT COUNT(*) FROM notify_message m WHERE "+filter.where()+" AND m.id=:id",params,Long.class)>0;}
    private CompanyAccess recipient(Map<String,Object> row) {return access.forUser(((Number)row.get("tenant_id")).longValue(),((Number)row.get("user_id")).longValue(),((Number)row.get("company_id")).longValue());}
    private boolean subscribed(CompanyAccess actor,String category,String channel) {return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM notify_subscription WHERE tenant_id=? AND user_id=? AND category=? AND channel=? AND enabled=1)",Boolean.class,actor.tenantId(),actor.userId(),category,channel));}
}
