package cn.erp.notify;

import cn.erp.identity.*;
import cn.erp.shared.*;
import cn.erp.sysadmin.SystemConfiguration;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class RobotSettings {
    private static final String KEY="notify.wecom";
    public record Input(boolean enabled,@Size(max=128,message="机器人密钥过长") String key,@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号无效") Long version) {}
    public record Settings(boolean enabled,String key) {}
    private final JdbcTemplate jdbc;private final SecretProtector secrets;private final BusinessCommands commands;private final AuditService audit;private final ObjectMapper json;private final SystemConfiguration config;private final ApplicationEventPublisher events;
    public RobotSettings(JdbcTemplate jdbc,SecretProtector secrets,BusinessCommands commands,AuditService audit,ObjectMapper json,SystemConfiguration config,ApplicationEventPublisher events) {this.jdbc=jdbc;this.secrets=secrets;this.commands=commands;this.audit=audit;this.json=json;this.config=config;this.events=events;}
    public Map<String,Object> view(CompanyAccess actor) {PlatformAccess.require(jdbc,actor,"notify_robot:manage");var rows=jdbc.queryForList("SELECT version FROM sys_config WHERE tenant_id=? AND config_key=?",actor.tenantId(),KEY);var settings=current(actor.tenantId());return Map.of("enabled",settings.enabled(),"secretConfigured",!settings.key().isEmpty(),"version",rows.isEmpty()?0:rows.getFirst().get("version"));}
    public Settings current(long tenant) {String value=config.get(tenant,KEY,"");return value.isBlank()?new Settings(false,""):json.readValue(value,Settings.class);}
    @Transactional public Map<String,Object> save(CompanyAccess actor,String key,Input input) {
        PlatformAccess.lock(jdbc,actor,"notify_robot:manage");String submitted=Objects.toString(input.key(),"");if(!submitted.isEmpty()&&!submitted.matches("[A-Za-z0-9_-]{16,128}")) throw NoticeService.invalid("机器人密钥格式无效");
        return commands.execute(actor,"robot.save",key,List.of(input.enabled(),input.version(),submitted.isEmpty()?"keep":secrets.fingerprint(actor.tenantId(),KEY,submitted)),200,()->{
            var rows=jdbc.queryForList("SELECT version FROM sys_config WHERE tenant_id=? AND config_key=? FOR UPDATE",actor.tenantId(),KEY);NoticeService.version(input.version(),rows.isEmpty()?0:rows.getFirst().get("version"));String value=submitted.isEmpty()?current(actor.tenantId()).key():submitted;if(input.enabled()&&value.isEmpty()) throw NoticeService.invalid("启用机器人前请填写密钥");
            String cipher=secrets.encrypt(actor.tenantId(),"config:"+KEY,json.writeValueAsString(new Settings(input.enabled(),value)));
            jdbc.update("INSERT INTO sys_config(tenant_id,config_key,config_value,value_type,is_sensitive,description,version,created_by,updated_by) VALUES(?,?,?,'json',1,'企业微信机器人',1,?,?) ON DUPLICATE KEY UPDATE config_value=VALUES(config_value),version=version+1,updated_by=VALUES(updated_by)",actor.tenantId(),KEY,cipher,actor.userId(),actor.userId());events.publishEvent(new UserSignal(actor.tenantId(),0,"config.changed",Map.of("key",KEY)));audit.record(actor,"robot.save","sys_config",Integer.toUnsignedLong(KEY.hashCode()),null,Map.of("enabled",input.enabled(),"secretConfigured",!value.isEmpty()));return Map.<String,Object>of("enabled",input.enabled(),"secretConfigured",!value.isEmpty(),"version",input.version()+1);
        },Map.class);
    }
}
