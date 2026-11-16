package cn.erp.identity;

import cn.erp.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 管理用会话摘要不能作为登录凭据；撤销标记优先于删除，防止并发请求回写复活。 */
@Service
public class SessionAuthority {
    private static final Duration LIFETIME=Duration.ofHours(13);
    private final StringRedisTemplate redis;
    private final SessionRepository<? extends Session> sessions;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final BusinessCommands commands;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final String prefix;
    private final Map<String,Long> touched=new java.util.concurrent.ConcurrentHashMap<>();
    public SessionAuthority(StringRedisTemplate redis,SessionRepository<? extends Session> sessions,JdbcTemplate jdbc,ObjectMapper json,BusinessCommands commands,AuditService audit,ApplicationEventPublisher events,@Value("${spring.session.redis.namespace:erp:session:prod}") String namespace) {
        this.redis=redis;this.sessions=sessions;this.jdbc=jdbc;this.json=json;this.commands=commands;this.audit=audit;this.events=events;this.prefix=namespace+":online:";
    }
    public static String publicId(String sid) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sid.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException error) {throw new IllegalStateException("运行环境缺少会话摘要算法",error);}
    }
    public void check(String sid) {if(Boolean.TRUE.equals(redis.hasKey(prefix+"revoked:"+publicId(sid)))) throw new ApiException(401,"SESSION_REVOKED","当前会话已下线，请重新登录");}
    void touch(HttpServletRequest request,SessionAccount actor) {
        var session=request.getSession(false);String id=publicId(session.getId());
        long now=System.currentTimeMillis();if(touched.getOrDefault(id,0L)>now) return;
        if(touched.size()>10000) touched.clear();touched.put(id,now+15000);
        var entry=new Entry(actor.tenantId(),actor.userId(),session.getId(),actor.loginMillis(),System.currentTimeMillis(),safe(request.getRemoteAddr(),64),safe(request.getHeader("User-Agent"),255));
        redis.opsForValue().set(prefix+"entry:"+id,json.writeValueAsString(entry),LIFETIME);
        redis.opsForZSet().add(prefix+actor.tenantId(),id,entry.seenMillis());redis.expire(prefix+actor.tenantId(),LIFETIME);
    }
    public boolean valid(String sid,long tenant,long user,long revision) {
        check(sid);var session=sessions.findById(sid);if(session==null||session.isExpired()) return false;
        Object value=session.getAttribute(SessionAccount.class.getName());
        if(!(value instanceof SessionAccount account)||account.tenantId()!=tenant||account.userId()!=user||account.passwordRevision()!=revision||account.isExpired(java.time.Instant.now())) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM sys_user u JOIN tenant t ON t.id=u.tenant_id WHERE u.tenant_id=? AND u.id=? AND u.status='active' AND t.status='active' AND "+AccessService.PASSWORD_REVISION+"=?)",Boolean.class,tenant,user,revision));
    }
    public SocketIdentity socketIdentity(HttpServletRequest request) {
        var account=SessionAccount.read(request);check(request.getSession(false).getId());
        return new SocketIdentity(account.tenantId(),account.userId(),account.passwordRevision(),request.getSession(false).getId());
    }
    public record SocketIdentity(long tenantId,long userId,long revision,String sessionId) {}
    public Map<String,Object> list(CompanyAccess actor,String before,int limit) {
        PlatformAccess.require(jdbc,actor,"online_user:view");if(limit<1||limit>200||before!=null&&!before.matches("[a-f0-9]{64}")) throw new ApiException(400,"VALIDATION_FAILED","分页条件无效");
        long cutoff=System.currentTimeMillis()-LIFETIME.toMillis();redis.opsForZSet().removeRangeByScore(prefix+actor.tenantId(),0,cutoff);
        var indexed=redis.opsForZSet().rangeByScore(prefix+actor.tenantId(),cutoff,Double.MAX_VALUE);
        var ids=indexed==null?List.<String>of():indexed.stream().filter(id->before==null||id.compareTo(before)<0).sorted(Comparator.reverseOrder()).toList();
        var rows=new ArrayList<Map<String,Object>>();String next=null;
        for(String id:ids) {
            Entry entry=entry(id);if(entry==null||entry.tenantId()!=actor.tenantId()) continue;
            var users=jdbc.queryForList("SELECT username,display_name,"+AccessService.PASSWORD_REVISION+" revision FROM sys_user u WHERE tenant_id=? AND id=? AND status='active'",actor.tenantId(),entry.userId());
            boolean active=false;
            if(!users.isEmpty()) try {active=valid(entry.sessionId(),entry.tenantId(),entry.userId(),((Number)users.getFirst().get("revision")).longValue());} catch(ApiException expired) {active=false;}
            if(!active) {redis.opsForZSet().remove(prefix+actor.tenantId(),id);continue;}
            var row=new LinkedHashMap<String,Object>();row.put("id",id);row.put("userId",Long.toString(entry.userId()));row.put("username",users.getFirst().get("username"));row.put("displayName",users.getFirst().get("display_name"));row.put("ip",entry.ip());row.put("userAgent",entry.agent());row.put("loginAt",java.time.Instant.ofEpochMilli(entry.loginMillis()).toString());row.put("lastSeenAt",java.time.Instant.ofEpochMilli(entry.seenMillis()).toString());rows.add(row);if(rows.size()<=limit) next=id;if(rows.size()>limit) break;
        }
        var result=new LinkedHashMap<String,Object>();result.put("items",rows.stream().limit(limit).toList());result.put("nextCursor",rows.size()>limit?next:null);return result;
    }
    @Transactional
    public Map<String,Object> kick(CompanyAccess actor,String id,String key) {
        PlatformAccess.lock(jdbc,actor,"online_user:kick");if(!id.matches("[a-f0-9]{64}")) throw missing();
        Entry entry=entry(id);if(entry==null||entry.tenantId()!=actor.tenantId()) throw missing();
        return commands.execute(actor,"session.kick",key,Map.of("id",id),200,()->{
            revoke(entry.sessionId(),entry.tenantId(),entry.userId());audit.record(actor,"session.kick","sys_user",entry.userId(),null,Map.of("session",id));return Map.<String,Object>of("id",id,"revoked",true);
        },Map.class);
    }
    void revoke(String sid,long tenant,long user) {
        String id=publicId(sid);touched.remove(id);redis.opsForValue().set(prefix+"revoked:"+id,"1",LIFETIME);sessions.deleteById(sid);redis.opsForZSet().remove(prefix+tenant,id);
        events.publishEvent(new UserSignal(tenant,user,"session.kicked",Map.of("session",id)));
    }
    private Entry entry(String id) {String value=redis.opsForValue().get(prefix+"entry:"+id);return value==null?null:json.readValue(value,Entry.class);}
    static String safe(String value,int length) {if(value==null) return "";return value.replaceAll("[\\p{Cntrl}]","").substring(0,Math.min(value.replaceAll("[\\p{Cntrl}]","").length(),length));}
    private static ApiException missing() {return new ApiException(404,"NOT_FOUND","会话不存在或无权管理");}
    private record Entry(long tenantId,long userId,String sessionId,long loginMillis,long seenMillis,String ip,String agent) {}
}
