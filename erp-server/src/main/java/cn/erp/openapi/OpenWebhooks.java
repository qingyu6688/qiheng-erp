package cn.erp.openapi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import cn.erp.identity.CompanyAccess;
import cn.erp.identity.PlatformAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;

@Service
class OpenWebhooks {
    private static final String TEST_EVENT = "webhook.test";
    private static final List<Integer> RETRY_SECONDS = List.of(60,300,1800,7200,43200);
    private static final int DISABLE_AFTER = 50;
    private static final TypeReference<Set<String>> EVENTS = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final OpenApplications apps;
    private final OpenSecrets secrets;
    private final OpenWebhookTransport transport;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final cn.erp.shared.AuditService audit;

    OpenWebhooks(JdbcTemplate jdbc,ObjectMapper json,OpenApplications apps,OpenSecrets secrets,OpenWebhookTransport transport,
            PlatformTransactionManager manager,ApplicationEventPublisher events,cn.erp.shared.AuditService audit) {
        this.jdbc=jdbc;
        this.json=json;
        this.apps=apps;
        this.secrets=secrets;
        this.transport=transport;
        this.tx=new TransactionTemplate(manager);
        this.events=events;
        this.audit=audit;
    }

    record Input(String appId,String url,Set<String> events,Boolean enabled,Long version) {}
    record View(String id,String appId,String url,Set<String> events,boolean enabled,int consecutiveFailures,long version,String secret) {}
    record Rotation(String id,String secret) {}
    record Delivery(String id,String webhookId,String eventId,String companyId,String eventType,String status,int attempts,
            Integer lastStatusCode,Instant nextAttemptAt,Instant createdAt) {}
    private record Hook(long id,long tenant,long app,String url,Set<String> events,String cipher,boolean enabled,int failures,long version) {}
    private record Job(long id,long tenant,long hook,long event,Long company,String type,String payload,String lease,int attempts) {}

    List<View> list(CompanyAccess admin) {
        PlatformAccess.require(jdbc,admin,OpenPolicy.VIEW);
        return hooks("WHERE tenant_id=? ORDER BY id DESC LIMIT 200",admin.tenantId()).stream()
                .filter(hook -> apps.adminSees(admin,hook.app())).map(hook -> view(hook,null)).toList();
    }

    @Transactional
    View create(CompanyAccess admin,Input input) {
        PlatformAccess.lock(jdbc,admin,OpenPolicy.MANAGE);
        long id=Ids.next();
        var app=apps.inspect(admin,OpenPolicy.id(input.appId()),false);
        String url=validate(input,app);
        String clear=secrets.secret();
        jdbc.update("""
                INSERT INTO open_webhook(id,tenant_id,app_id,url,events,secret_cipher,enabled) VALUES(?,?,?,?,?,?,?)
                """,id,admin.tenantId(),app.id(),url,json.writeValueAsString(input.events()),
                secrets.encrypt(admin.tenantId(),id,"hook",clear),input.enabled());
        var hook=get(admin.tenantId(),id,false);
        audit.record(admin,"open_webhook.create","open_webhook",id,null,snapshot(hook));
        return view(hook,clear);
    }

    @Transactional
    View update(CompanyAccess admin,long id,Input input) {
        PlatformAccess.lock(jdbc,admin,OpenPolicy.MANAGE);
        var prior=inspect(admin,id,true);
        if (input.version()!=null && input.version()!=prior.version()) throw OpenApplications.conflict();
        if (OpenPolicy.id(input.appId())!=prior.app()) throw OpenPolicy.invalid("已有订阅不能更换所属应用");
        var app=apps.get(admin.tenantId(),prior.app(),false);
        String url=validate(input,app);
        jdbc.update("""
                UPDATE open_webhook SET url=?,events=?,enabled=?,version=version+1,consecutive_failures=?
                WHERE tenant_id=? AND id=?
                """,url,json.writeValueAsString(input.events()),input.enabled(),
                input.enabled()&&!prior.enabled()?0:prior.failures(),admin.tenantId(),id);
        var hook=get(admin.tenantId(),id,false);
        audit.record(admin,"open_webhook.update","open_webhook",id,snapshot(prior),snapshot(hook));
        return view(hook,null);
    }

    @Transactional
    Rotation rotate(CompanyAccess admin,long id) {
        PlatformAccess.lock(jdbc,admin,OpenPolicy.MANAGE);
        var hook=inspect(admin,id,true);
        String clear=secrets.secret();
        jdbc.update("UPDATE open_webhook SET secret_cipher=?,version=version+1 WHERE tenant_id=? AND id=?",
                secrets.encrypt(hook.tenant(),hook.id(),"hook",clear),hook.tenant(),id);
        audit.record(admin,"open_webhook.rotate_secret","open_webhook",id,null,Map.of("version",hook.version()+1));
        return new Rotation(Long.toString(id),clear);
    }

    @Transactional
    Map<String,String> test(CompanyAccess admin,long id) {
        PlatformAccess.lock(jdbc,admin,OpenPolicy.MANAGE);
        var hook=inspect(admin,id,true);
        if (!allowed(hook,null,TEST_EVENT)) throw conflict("应用或订阅已停用");
        long event=Ids.next();
        long delivery=enqueue(hook.tenant(),hook.id(),event,null,TEST_EVENT,Map.of("message","开放平台测试推送"));
        audit.record(admin,"open_webhook.test","open_webhook",id,null,Map.of("eventId",Long.toString(event)));
        return Map.of("id",Long.toString(delivery),"status","pending");
    }

    List<Delivery> deliveries(CompanyAccess admin,Long webhookId) {
        PlatformAccess.require(jdbc,admin,OpenPolicy.VIEW);
        if (webhookId!=null) inspect(admin,webhookId,false);
        var ids=webhookId==null ? list(admin).stream().map(hook -> OpenPolicy.id(hook.id())).toList() : List.of(webhookId);
        if (ids.isEmpty()) return List.of();
        var query=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
        return query.query("""
                SELECT id,webhook_id,event_id,company_id,event_type,status,attempts,last_status_code,next_attempt_at,created_at
                FROM open_webhook_delivery WHERE tenant_id=:tenant AND webhook_id IN (:hooks) ORDER BY id DESC LIMIT 200
                """,Map.of("tenant",admin.tenantId(),"hooks",ids),(r,n) -> new Delivery(r.getString(1),r.getString(2),
                r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getInt(7),
                r.getObject(8,Integer.class),r.getTimestamp(9).toInstant(),r.getTimestamp(10).toInstant()));
    }

    @Transactional
    Map<String,String> retry(CompanyAccess admin,long id) {
        PlatformAccess.lock(jdbc,admin,OpenPolicy.MANAGE);
        var job=job(admin.tenantId(),id,true);
        var hook=inspect(admin,job.hook(),false);
        if (!allowed(hook,job.company(),job.type())) throw conflict("应用、订阅或公司权限不可用");
        int changed=jdbc.update("""
                UPDATE open_webhook_delivery SET status='pending',attempts=0,next_attempt_at=UTC_TIMESTAMP(3),lease_owner=NULL,
                   lease_until=NULL,last_status_code=NULL
                WHERE tenant_id=? AND id=? AND status='failed'
                """,admin.tenantId(),id);
        if (changed!=1) throw conflict("只能重推已经失败的记录");
        audit.record(admin,"open_webhook.retry","open_webhook_delivery",id,null,Map.of("status","pending"));
        return Map.of("id",Long.toString(id),"status","pending");
    }

    void fanOut(long tenant,long event,long company,String type,Map<String,Object> data) {
        for (var hook:hooks("""
                WHERE tenant_id=? AND enabled=1 AND JSON_CONTAINS(events,JSON_QUOTE(?))
                AND EXISTS(SELECT 1 FROM open_app_company a WHERE a.tenant_id=open_webhook.tenant_id
                    AND a.app_id=open_webhook.app_id AND a.company_id=?)
                ORDER BY id
                """,tenant,type,company)) {
            if (allowed(hook,company,type)) enqueue(tenant,hook.id(),event,company,type,data);
        }
    }

    long enqueue(long tenant,long hook,long event,Long company,String type,Map<String,Object> data) {
        var body=new java.util.LinkedHashMap<String,Object>();
        body.put("eventId",Long.toString(event));
        body.put("eventType",type);
        if (company!=null) body.put("companyId",Long.toString(company));
        body.put("data",data);
        long id=Ids.next();
        jdbc.update("""
                INSERT INTO open_webhook_delivery(id,tenant_id,webhook_id,event_id,company_id,event_type,payload)
                VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=open_webhook_delivery.id
                """,id,tenant,hook,event,company,type,json.writeValueAsString(body));
        return id;
    }

    void processTenant(long tenant) {
        for (int i=0;i<10;i++) {
            Job job=tx.execute(status -> claim(tenant));
            if (job==null) return;
            deliver(job);
        }
    }

    private Job claim(long tenant) {
        var candidates=jdbc.queryForList("""
                SELECT id FROM open_webhook_delivery WHERE tenant_id=? AND status='pending' AND next_attempt_at<=UTC_TIMESTAMP(3)
                  AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(3))
                ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                """,Long.class,tenant);
        if (candidates.isEmpty()) return null;
        long id=candidates.getFirst();
        jdbc.update("""
                UPDATE open_webhook_delivery SET lease_owner=?,lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 60 SECOND),
                  attempts=attempts+1 WHERE tenant_id=? AND id=?
                """,UUID.randomUUID().toString(),tenant,id);
        return job(tenant,id,false);
    }

    private void deliver(Job job) {
        var hook=get(job.tenant(),job.hook(),false);
        if (!allowed(hook,job.company(),job.type())) {
            tx.executeWithoutResult(status -> settle(job,hook,null,false));
            return;
        }
        String timestamp=Long.toString(Instant.now().getEpochSecond());
        Integer code;
        try {
            String secret=secrets.decrypt(job.tenant(),job.hook(),"hook",hook.cipher());
            String signature=Base64.getEncoder().encodeToString(OpenSecrets.sign(secret,timestamp+"."+job.payload()));
            code=transport.send(hook.url(),Long.toString(job.event()),timestamp,signature,
                    job.payload().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | ApiException failure) { code=null; }
        Integer result=code;
        tx.executeWithoutResult(status -> settle(job,hook,result,true));
    }

    private void settle(Job job,Hook snapshot,Integer code,boolean attempted) {
        var hook=get(job.tenant(),job.hook(),true);
        boolean ok=attempted && code!=null && code>=200 && code<300;
        boolean retry=attempted && !ok && job.attempts()<=RETRY_SECONDS.size() && allowed(hook,job.company(),job.type());
        int delay=retry?RETRY_SECONDS.get(job.attempts()-1):0;
        int changed=jdbc.update("""
                UPDATE open_webhook_delivery SET status=?,last_status_code=?,
                   next_attempt_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL ? SECOND),lease_owner=NULL,lease_until=NULL
                WHERE tenant_id=? AND id=? AND lease_owner=?
                """,ok?"delivered":retry?"pending":"failed",code,delay,job.tenant(),job.id(),job.lease());
        if (changed!=1 || !attempted || hook.version()!=snapshot.version() || TEST_EVENT.equals(job.type())) return;
        changeFailures(hook,job,ok);
    }

    private void changeFailures(Hook hook,Job job,boolean success) {
        int failures=success?0:hook.failures()+1;
        boolean disable=hook.enabled() && failures>=DISABLE_AFTER;
        jdbc.update("""
                UPDATE open_webhook SET consecutive_failures=?,enabled=?,version=version+? WHERE tenant_id=? AND id=?
                """,Math.min(DISABLE_AFTER,failures),hook.enabled()&&!disable,disable?1:0,hook.tenant(),hook.id());
        if (disable) events.publishEvent(new cn.erp.shared.OpenWebhookDisabled(hook.tenant(),job.company(),hook.id(),hook.version()+1));
    }

    private boolean allowed(Hook hook,Long company,String event) {
        if (!hook.enabled()) return false;
        var app=apps.callable(hook.tenant(),hook.app());
        if (app==null || !app.active()) return false;
        if (TEST_EVENT.equals(event)) return true;
        return company!=null && hook.events().contains(event) && app.scopes().contains(OpenPolicy.EVENT_SCOPES.get(event))
                && apps.allowedCompany(app,company);
    }

    private String validate(Input input,OpenApplications.App app) {
        if (input.enabled()==null || input.events()==null || input.events().isEmpty() || input.events().size()>OpenPolicy.EVENT_SCOPES.size()) {
            throw OpenPolicy.invalid("请选择订阅事件和启用状态");
        }
        for (String event:input.events()) {
            String scope=event==null?null:OpenPolicy.EVENT_SCOPES.get(event);
            if (scope==null) throw OpenPolicy.invalid("包含不支持的订阅事件");
            if (!app.scopes().contains(scope)) throw OpenPolicy.denied();
        }
        return transport.validate(input.url()).toString();
    }

    private Hook inspect(CompanyAccess admin,long id,boolean lock) {
        var hook=get(admin.tenantId(),id,lock);
        if (!apps.adminSees(admin,hook.app())) throw OpenPolicy.missing();
        return hook;
    }

    private Hook get(long tenant,long id,boolean lock) {
        return hooks("WHERE tenant_id=? AND id=?"+(lock?" FOR UPDATE":""),tenant,id).stream().findFirst()
                .orElseThrow(OpenPolicy::missing);
    }

    private List<Hook> hooks(String clause,Object... args) {
        return jdbc.query("SELECT * FROM open_webhook "+clause,(r,n) -> new Hook(r.getLong("id"),r.getLong("tenant_id"),
                r.getLong("app_id"),r.getString("url"),Set.copyOf(json.readValue(r.getString("events"),EVENTS)),
                r.getString("secret_cipher"),r.getBoolean("enabled"),r.getInt("consecutive_failures"),r.getLong("version")),args);
    }

    private Job job(long tenant,long id,boolean lock) {
        return jdbc.query("SELECT * FROM open_webhook_delivery WHERE tenant_id=? AND id=?"+(lock?" FOR UPDATE":""),
                (r,n) -> new Job(r.getLong("id"),r.getLong("tenant_id"),r.getLong("webhook_id"),r.getLong("event_id"),
                r.getObject("company_id",Long.class),r.getString("event_type"),r.getString("payload"),r.getString("lease_owner"),
                r.getInt("attempts")),tenant,id).stream().findFirst().orElseThrow(OpenPolicy::missing);
    }

    private static View view(Hook hook,String secret) {
        return new View(Long.toString(hook.id()),Long.toString(hook.app()),hook.url(),hook.events(),
                hook.enabled(),hook.failures(),hook.version(),secret);
    }

    private static Map<String,Object> snapshot(Hook hook) {
        return Map.of("appId",Long.toString(hook.app()),"events",hook.events(),"enabled",hook.enabled(),"version",hook.version());
    }

    private static ApiException conflict(String message) { return new ApiException(409,"STATE_CONFLICT",message); }
}
