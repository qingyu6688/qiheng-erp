package cn.erp.openapi;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import cn.erp.identity.AccessService;
import cn.erp.identity.CompanyAccess;
import cn.erp.identity.PlatformAccess;
import cn.erp.shared.AuditService;
import cn.erp.shared.Ids;
import cn.erp.shared.RequestIds;

@Service
class OpenApplications {
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final OpenSecrets secrets;
    private final PasswordEncoder passwords;
    private final AccessService access;
    private final AuditService audit;

    OpenApplications(JdbcTemplate jdbc,ObjectMapper json,OpenSecrets secrets,PasswordEncoder passwords,
            AccessService access,AuditService audit) {
        this.jdbc=jdbc;
        this.json=json;
        this.secrets=secrets;
        this.passwords=passwords;
        this.access=access;
        this.audit=audit;
    }

    record Input(String name,List<String> companyIds,Set<String> scopes,List<String> ipWhitelist,
            Integer rateLimitPerMinute,Boolean enabled,Instant expiresAt,Long version) {}
    record View(String id,String name,String appKey,List<String> companyIds,Set<String> scopes,List<String> ipWhitelist,
            int rateLimitPerMinute,boolean enabled,Instant expiresAt,long version,String secret) {}
    record Rotation(String id,String appKey,String secret) {}
    record App(long id,long tenant,long user,String name,String key,String cipher,Set<String> scopes,List<String> ipWhitelist,
            int limit,boolean enabled,Instant expires,long version) {
        boolean active() { return enabled && (expires == null || expires.isAfter(Instant.now())); }
    }
    record Call(String id,String appId,String companyId,String method,String pathTemplate,int status,long latencyMs,
            String requestId,Instant createdAt) {}

    List<View> list(CompanyAccess admin) {
        PlatformAccess.require(jdbc,admin,OpenPolicy.VIEW);
        return rows("WHERE a.tenant_id=? ORDER BY a.id DESC LIMIT 200",admin.tenantId()).stream()
                .filter(app -> adminSees(admin,app.id())).map(app -> view(app,null)).toList();
    }

    @Transactional
    View create(CompanyAccess admin,Input input) {
        PlatformAccess.lock(jdbc,admin,OpenPolicy.MANAGE);
        List<Long> companies=validate(admin,input);
        long id=Ids.next();
        long user=Ids.next();
        String key=secrets.appKey();
        String clear=secrets.secret();
        // 虚拟操作人不赋予人类角色和数据范围，应用只使用签名链生成的平台主体。
        jdbc.update("""
                INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash,user_type,created_by,updated_by)
                VALUES(?,?,?,?,?,'app',?,?)
                """,user,admin.tenantId(),"open_"+id,input.name().strip().substring(0,Math.min(64,input.name().strip().length())),
                passwords.encode(secrets.secret()),admin.userId(),admin.userId());
        jdbc.update("""
                INSERT INTO open_app(id,tenant_id,user_id,name,app_key,secret_cipher,scopes,ip_whitelist,
                  rate_limit_per_minute,enabled,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """,id,admin.tenantId(),user,input.name().strip(),key,secrets.encrypt(admin.tenantId(),id,"app",clear),
                json.writeValueAsString(input.scopes()),json.writeValueAsString(input.ipWhitelist()),input.rateLimitPerMinute(),
                input.enabled(),timestamp(input.expiresAt()));
        setCompanies(admin.tenantId(),id,companies);
        var app=get(admin.tenantId(),id,false);
        audit.record(admin,"open_app.create","open_app",id,null,snapshot(app,companies));
        return view(app,clear);
    }

    @Transactional
    View update(CompanyAccess admin,long id,Input input) {
        PlatformAccess.lock(jdbc,admin,OpenPolicy.MANAGE);
        var previous=inspect(admin,id,true);
        if (input.version()!=null && input.version()!=previous.version()) throw conflict();
        var before=snapshot(previous,companies(admin.tenantId(),id));
        var companies=validate(admin,input);
        jdbc.update("""
                UPDATE open_app SET name=?,scopes=?,ip_whitelist=?,rate_limit_per_minute=?,enabled=?,expires_at=?,version=version+1
                WHERE tenant_id=? AND id=?
                """,input.name().strip(),json.writeValueAsString(input.scopes()),json.writeValueAsString(input.ipWhitelist()),
                input.rateLimitPerMinute(),input.enabled(),timestamp(input.expiresAt()),admin.tenantId(),id);
        setCompanies(admin.tenantId(),id,companies);
        var app=get(admin.tenantId(),id,false);
        audit.record(admin,"open_app.update","open_app",id,before,snapshot(app,companies));
        return view(app,null);
    }

    @Transactional
    Rotation rotate(CompanyAccess admin,long id) {
        PlatformAccess.lock(jdbc,admin,OpenPolicy.MANAGE);
        var app=inspect(admin,id,true);
        String clear=secrets.secret();
        jdbc.update("UPDATE open_app SET secret_cipher=?,version=version+1 WHERE tenant_id=? AND id=?",
                secrets.encrypt(admin.tenantId(),id,"app",clear),admin.tenantId(),id);
        audit.record(admin,"open_app.rotate_secret","open_app",id,null,Map.of("version",app.version()+1));
        return new Rotation(Long.toString(id),app.key(),clear);
    }

    App inspect(CompanyAccess admin,long id,boolean lock) {
        var app=get(admin.tenantId(),id,lock);
        if (!adminSees(admin,id)) throw OpenPolicy.missing();
        return app;
    }

    boolean adminSees(CompanyAccess admin,long app) {
        List<Long> companies=companies(admin.tenantId(),app);
        return !companies.isEmpty() && companies.stream().allMatch(company -> wholeCompany(admin,company));
    }

    boolean allowedCompany(App app,long company) {
        return !jdbc.queryForList("""
                SELECT c.id FROM open_app_company ac JOIN company c ON c.tenant_id=ac.tenant_id AND c.id=ac.company_id
                WHERE ac.tenant_id=? AND ac.app_id=? AND ac.company_id=? AND c.status='active'
                """,Long.class,app.tenant(),app.id(),company).isEmpty();
    }

    App key(String value) {
        return rows("WHERE a.app_key=? AND t.status='active' AND u.status='active'",value).stream().findFirst().orElse(null);
    }

    App callable(long tenant,long id) {
        return rows("WHERE a.tenant_id=? AND a.id=? AND t.status='active' AND u.status='active'",tenant,id).stream()
                .findFirst().orElse(null);
    }

    App get(long tenant,long id,boolean lock) {
        return rows("WHERE a.tenant_id=? AND a.id=?"+(lock?" FOR UPDATE":""),tenant,id).stream().findFirst()
                .orElseThrow(OpenPolicy::missing);
    }

    private List<App> rows(String clause,Object... args) {
        return jdbc.query("""
                SELECT a.* FROM open_app a JOIN tenant t ON t.id=a.tenant_id
                JOIN sys_user u ON u.tenant_id=a.tenant_id AND u.id=a.user_id AND u.user_type='app'
                """+clause,(r,n) -> new App(r.getLong("id"),r.getLong("tenant_id"),r.getLong("user_id"),r.getString("name"),
                    r.getString("app_key"),r.getString("secret_cipher"),Set.copyOf(json.readValue(r.getString("scopes"),STRINGS)),
                    List.copyOf(json.readValue(r.getString("ip_whitelist"),STRINGS)),r.getInt("rate_limit_per_minute"),
                    r.getBoolean("enabled"),instant(r.getTimestamp("expires_at")),r.getLong("version")),args);
    }

    List<Call> calls(CompanyAccess admin,Long appId) {
        PlatformAccess.require(jdbc,admin,OpenPolicy.VIEW);
        if (appId != null) inspect(admin,appId,false);
        var ids=appId==null ? list(admin).stream().map(v -> OpenPolicy.id(v.id())).toList() : List.of(appId);
        if (ids.isEmpty()) return List.of();
        var query=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
        return query.query("""
                SELECT id,app_id,company_id,method,path_template,status,latency_ms,request_id,created_at FROM open_call_log
                WHERE tenant_id=:tenant AND app_id IN (:apps) ORDER BY id DESC LIMIT 200
                """,Map.of("tenant",admin.tenantId(),"apps",ids),(r,n) -> new Call(r.getString(1),r.getString(2),r.getString(3),
                    r.getString(4),r.getString(5),r.getInt(6),r.getLong(7),r.getString(8),r.getTimestamp(9).toInstant()));
    }

    void log(App app,Long company,String method,String route,int status,long millis) {
        jdbc.update("""
                INSERT INTO open_call_log(id,tenant_id,app_id,company_id,method,path_template,status,latency_ms,request_id)
                VALUES(?,?,?,?,?,?,?,?,?)
                """,Ids.next(),app.tenant(),app.id(),company,method,route,status,millis,RequestIds.current());
    }

    private List<Long> validate(CompanyAccess admin,Input input) {
        if (input.name()==null || input.name().isBlank() || input.name().strip().length()>100
                || input.companyIds()==null || input.companyIds().isEmpty() || input.companyIds().size()>50
                || input.scopes()==null || input.scopes().isEmpty() || input.scopes().stream().anyMatch(java.util.Objects::isNull)
                || input.ipWhitelist()==null || input.ipWhitelist().size()>50 || input.enabled()==null
                || input.rateLimitPerMinute()==null || input.rateLimitPerMinute()<1 || input.rateLimitPerMinute()>3000) {
            throw OpenPolicy.invalid("应用名称、公司、权限、IP 白名单或限流参数无效");
        }
        if (!OpenPolicy.SCOPE_CODES.containsAll(input.scopes())) throw OpenPolicy.invalid("只能配置开放平台公布的业务权限");
        if (!admin.permissions().containsAll(input.scopes())) throw OpenPolicy.denied();
        input.ipWhitelist().forEach(OpenPolicy::cidr);
        var companies=input.companyIds().stream().map(OpenPolicy::id).distinct().toList();
        if (!companies.stream().allMatch(company -> wholeCompany(admin,company))) throw OpenPolicy.denied();
        return companies;
    }

    private boolean wholeCompany(CompanyAccess admin,long company) {
        var actor=access.forUser(admin.tenantId(),admin.userId(),company);
        return actor!=null && actor.allWarehouses() && actor.orgScope().allDepartments();
    }

    private List<Long> companies(long tenant,long app) {
        return jdbc.queryForList("SELECT company_id FROM open_app_company WHERE tenant_id=? AND app_id=? ORDER BY company_id",
                Long.class,tenant,app);
    }

    private void setCompanies(long tenant,long app,List<Long> companies) {
        jdbc.update("DELETE FROM open_app_company WHERE tenant_id=? AND app_id=?",tenant,app);
        jdbc.batchUpdate("INSERT INTO open_app_company(tenant_id,app_id,company_id) VALUES(?,?,?)",
                companies.stream().map(company -> new Object[] {tenant,app,company}).toList());
    }

    private View view(App app,String secret) {
        return new View(Long.toString(app.id()),app.name(),app.key(),companies(app.tenant(),app.id()).stream().map(String::valueOf).toList(),
                app.scopes(),app.ipWhitelist(),app.limit(),app.enabled(),app.expires(),app.version(),secret);
    }

    private Map<String,Object> snapshot(App app,List<Long> companies) {
        return Map.of("name",app.name(),"companyIds",companies.stream().map(String::valueOf).toList(),"scopes",app.scopes(),
                "ipWhitelist",app.ipWhitelist(),"rateLimitPerMinute",app.limit(),"enabled",app.enabled(),"version",app.version());
    }

    private static Instant instant(Timestamp value) { return value==null?null:value.toInstant(); }
    private static Timestamp timestamp(Instant value) { return value==null?null:Timestamp.from(value); }
    static cn.erp.shared.ApiException conflict() {
        return new cn.erp.shared.ApiException(409,"VERSION_CONFLICT","配置已变化，请刷新后重试");
    }
}
