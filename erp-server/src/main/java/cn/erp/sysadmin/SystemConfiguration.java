package cn.erp.sysadmin;

import cn.erp.identity.*;
import cn.erp.shared.*;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static cn.erp.sysadmin.PlatformRows.*;

@Service
public class SystemConfiguration {
    public record Input(@NotNull(message="请填写参数值") @Size(max=16384,message="参数值最多16384字") String value,@NotNull(message="请选择参数类型") @Pattern(regexp="string|number|boolean|json",message="参数类型无效") String valueType,boolean sensitive,@Size(max=200,message="说明最多200字") String description,@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号无效") Long version) {}
    private record Cached(String value,long until) {}
    private final Map<String,Cached> cache=new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SecretProtector secrets;
    private final BusinessCommands commands;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    public SystemConfiguration(JdbcTemplate jdbc,ObjectMapper json,SecretProtector secrets,BusinessCommands commands,AuditService audit,ApplicationEventPublisher events) {this.jdbc=jdbc;this.json=json;this.secrets=secrets;this.commands=commands;this.audit=audit;this.events=events;}
    public List<Map<String,Object>> list(CompanyAccess actor,String keyword) {
        PlatformAccess.require(jdbc,actor,"sys_config:view");if(keyword.length()>100) throw invalid("搜索内容最多100字");
        return jdbc.queryForList("SELECT * FROM sys_config WHERE tenant_id=? AND LOCATE(?,config_key)>0 ORDER BY config_key",actor.tenantId(),keyword).stream().map(this::view).toList();
    }
    public String get(long tenant,String key,String fallback) {
        validateKey(key);String cacheKey=tenant+":"+key;long now=Instant.now().toEpochMilli();var existing=cache.get(cacheKey);
        if(existing!=null&&existing.until()>now) return existing.value()==null?fallback:existing.value();
        var rows=jdbc.queryForList("SELECT * FROM sys_config WHERE tenant_id IN(0,?) AND config_key=? ORDER BY tenant_id DESC LIMIT 1",tenant,key);
        String value=rows.isEmpty()?null:clear(rows.getFirst());
        if(cache.size()>=4096) cache.clear();cache.put(cacheKey,new Cached(value,now+60_000));return value==null?fallback:value;
    }
    @Transactional
    @SuppressWarnings({"unchecked","rawtypes"})
    public Map<String,Object> save(CompanyAccess actor,String key,String idempotencyKey,Input input) {
        validateKey(key);PlatformAccess.lock(jdbc,actor,"sys_config:update");
        if(key.startsWith("auth.")||key.startsWith("session.")||key.startsWith("permission.")||key.startsWith("company.")||key.startsWith("erp.")||key.startsWith("notify.wecom")) throw invalid("该配置由专用管理入口维护，不能通过系统参数覆盖");
        if(input.sensitive()&&input.value().isBlank()) throw invalid("敏感参数不能为空");
        validateValue(input.valueType(),input.value());
        var payload=List.of(key,input.valueType(),input.sensitive(),input.version(),input.sensitive()?secrets.fingerprint(actor.tenantId(),key,input.value()):input.value(),Objects.toString(input.description(),""));
        return commands.execute(actor,"sys_config.save",idempotencyKey,payload,200,()->{
            var rows=jdbc.queryForList("SELECT * FROM sys_config WHERE tenant_id=? AND config_key=? FOR UPDATE",actor.tenantId(),key);
            Map<String,Object> before=rows.isEmpty()?null:view(rows.getFirst());version(input.version(),rows.isEmpty()?0L:rows.getFirst().get("version"));
            if(!rows.isEmpty()&&flag(rows.getFirst().get("is_sensitive"))!=input.sensitive()) throw invalid("已有参数的敏感标记不能修改，请使用新的参数编码");
            String value=input.sensitive()?secrets.encrypt(actor.tenantId(),"config:"+key,input.value()):input.value();
            jdbc.update("INSERT INTO sys_config(tenant_id,config_key,config_value,value_type,is_sensitive,description,version,created_by,updated_by) VALUES(?,?,?,?,?,?,1,?,?) ON DUPLICATE KEY UPDATE config_value=VALUES(config_value),value_type=VALUES(value_type),description=VALUES(description),version=version+1,updated_by=VALUES(updated_by)",actor.tenantId(),key,value,input.valueType(),input.sensitive(),Objects.toString(input.description(),""),actor.userId(),actor.userId());
            cache.remove(actor.tenantId()+":"+key);events.publishEvent(new UserSignal(actor.tenantId(),0,"config.changed",Map.of("key",key)));
            var after=view(jdbc.queryForMap("SELECT * FROM sys_config WHERE tenant_id=? AND config_key=?",actor.tenantId(),key));audit.record(actor,"sys_config.save","sys_config",Integer.toUnsignedLong(key.hashCode()),before,after);return after;
        },(Class)Map.class);
    }
    @EventListener public void localChange(UserSignal signal) {invalidate(signal.tenantId(),signal.type());}
    @EventListener public void remoteChange(RemoteUserSignal signal) {invalidate(signal.signal().tenantId(),signal.signal().type());}
    private void invalidate(long tenant,String type) {if(type.equals("config.changed")) cache.keySet().removeIf(key->key.startsWith(tenant+":"));}
    private String clear(Map<String,Object> row) {return flag(row.get("is_sensitive"))?secrets.decrypt(((Number)row.get("tenant_id")).longValue(),"config:"+row.get("config_key"),row.get("config_value").toString()):row.get("config_value").toString();}
    private Map<String,Object> view(Map<String,Object> row) {var copy=new LinkedHashMap<>(row);boolean sensitive=flag(copy.remove("is_sensitive"));Object value=copy.remove("config_value");var result=ApiRows.row(copy);result.put("id",row.get("config_key"));result.put("sensitive",sensitive);result.put("value",sensitive?"":value);result.put("secretConfigured",sensitive);return result;}
    private void validateValue(String type,String value) {
        try {
            switch(type) {case "boolean"->{if(!Set.of("true","false").contains(value)) throw invalid("布尔参数只能为true或false");}case "number"->new java.math.BigDecimal(value);case "json"->{if(value.isBlank()) throw invalid("结构化参数不能为空");json.readTree(value);}case "string"->{}default->throw invalid("参数类型无效");}
        } catch(NumberFormatException|tools.jackson.core.JacksonException error) {throw invalid("参数值与所选类型不一致");}
    }
    private void validateKey(String key) {if(key==null||!key.matches("[a-z][a-z0-9_.-]{0,63}")) throw invalid("参数编码须为小写字母、数字、点、横线或下划线");}
    private boolean flag(Object value) {return Boolean.TRUE.equals(value)||value instanceof Number n&&n.intValue()==1;}
}
