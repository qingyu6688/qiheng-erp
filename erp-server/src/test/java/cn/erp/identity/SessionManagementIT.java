package cn.erp.identity;

import cn.erp.shared.*;
import cn.erp.sysadmin.ApiLogWriter;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.*;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SessionManagementIT {
    @LocalServerPort int port;@Autowired JdbcTemplate jdbc;@Autowired PasswordEncoder encoder;@Autowired ObjectMapper json;@Autowired SessionRepository<? extends Session> repository;@Autowired SessionAuthority authority;@Autowired ApiLogWriter logs;
    long tenant,user,company,role;String code,password;CookieManager cookies;HttpClient client;
    @BeforeEach void prepare() {
        tenant=Ids.next();user=Ids.next();company=Ids.next();role=Ids.next();code="S"+tenant;password=UUID.randomUUID().toString();cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);client=HttpClient.newBuilder().cookieHandler(cookies).build();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'会话验收企业')",tenant,code);jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','会话验收公司')",company,tenant);
        jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,'admin','会话管理员',?)",user,tenant,encoder.encode(password));jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'ADMIN','测试管理员')",role,tenant);jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",Ids.next(),tenant,user,role);jdbc.update("INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)",Ids.next(),tenant,user,company);
        for(long permission:jdbc.queryForList("SELECT id FROM sys_permission",Long.class)) jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) VALUES(?,?,?,?)",Ids.next(),tenant,role,permission);
    }
    @AfterEach void cleanup() {
        logs.flush();for(String table:List.of("notify_message","sys_notice_read","sys_notice_target","sys_notice","outbox_event","audit_log","idempotency_record","sys_login_log","sys_api_error_log","sys_api_access_log","sys_user_data_scope","sys_user_role","sys_role_permission","sys_role","sys_user","company")) jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);jdbc.update("DELETE FROM tenant WHERE id=?",tenant);
    }
    @Test void Y05_02_concurrentInboxAndBellReadsDoNotDeadlockOrDuplicateMessages() throws Exception {
        request("POST","/auth/login",Map.of("tenantCode",code,"username","admin","password",password),false,200);
        long notice=Ids.next();jdbc.update("INSERT INTO sys_notice(id,tenant_id,title,content,status,publish_at,created_by) VALUES(?,?,'并发提醒','测试正文','published',UTC_TIMESTAMP(3),?)",notice,tenant,user);jdbc.update("INSERT INTO sys_notice_target(tenant_id,notice_id,target_type) VALUES(?,?,'all')",tenant,notice);
        try(var executor=Executors.newFixedThreadPool(8)) {
            var ready=new CountDownLatch(8);var start=new CountDownLatch(1);var calls=new ArrayList<Future<?>>();
            for(int index=0;index<8;index++) {int selected=index;calls.add(executor.submit(()->{ready.countDown();if(!start.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("并发请求未同时准备完成");for(int round=0;round<3;round++) request("GET",selected%2==0?"/notify/messages":"/notify/unread-count",null,false,200);return null;}));}
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();for(var call:calls) call.get(30,TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_message WHERE tenant_id=? AND user_id=? AND notice_id=?",Long.class,tenant,user,notice)).isEqualTo(1);
        assertThat(request("GET","/notify/unread-count",null,false,200).path("data").path("total").asLong()).isEqualTo(1);
    }
    @Test void Y07_01_loginAuditOpaqueSessionsKickAndInflightWritebackCannotRestoreAccess() throws Exception {
        request("POST","/auth/login",Map.of("tenantCode",code,"username","admin","password","错误密码"),false,401);
        request("POST","/auth/login",Map.of("tenantCode",code,"username","admin","password",password),false,200);
        var list=request("GET","/sys/online-users",null,false,200).path("data").path("items");assertThat(list.size()).isEqualTo(1);String publicId=list.get(0).path("id").asString();assertThat(publicId).matches("[a-f0-9]{64}");assertThat(list.toString()).doesNotContain("sessionId","passwordRevision",password);
        String rawCookie=cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("ERPSESSION")).findFirst().orElseThrow().getValue().replace("\"","");String sid=new String(Base64.getDecoder().decode(rawCookie),StandardCharsets.UTF_8);assertThat(SessionAuthority.publicId(sid)).isEqualTo(publicId);
        Runnable writeBack=writeBack(repository,sid);
        var wrongTenant=new CompanyAccess(tenant+1,user,0,true,Set.of(),Set.of("online_user:kick"));assertThatThrownBy(()->authority.kick(wrongTenant,publicId,"cross")).isInstanceOf(ApiException.class);
        request("POST","/sys/online-users/"+publicId+"/kick",Map.of(),true,200);assertThatThrownBy(writeBack::run).isInstanceOf(IllegalStateException.class);assertThat(repository.findById(sid)).isNull();assertThatThrownBy(()->authority.check(sid)).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getStatus()).isEqualTo(401));
        request("GET","/auth/me",null,false,401);
        var replay=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/me")).header("Cookie","ERPSESSION="+rawCookie).GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(replay.statusCode()).isEqualTo(401);assertThat(json.readTree(replay.body()).path("errorCode").asString()).isEqualTo("SESSION_REVOKED");
        assertThat(jdbc.queryForList("SELECT result FROM sys_login_log WHERE tenant_id=? ORDER BY id",String.class,tenant)).containsExactly("failed","success");
        assertThat(jdbc.queryForList("SELECT * FROM sys_login_log WHERE tenant_id=?",tenant).toString()).doesNotContain(password,sid,rawCookie,"错误密码");
    }
    @Test void Z03_01_logsUseTemplateAndHandleVersionWithoutSavingInputs() throws Exception {
        request("POST","/auth/login",Map.of("tenantCode",code,"username","admin","password",password),false,200);
        request("GET","/sys/notices/999999?token=禁止入日志",null,false,404);logs.flush();
        var row=jdbc.queryForList("SELECT * FROM sys_api_access_log WHERE tenant_id=? AND route_pattern='/api/v1/sys/notices/{id}'",tenant);assertThat(row).hasSize(1);assertThat(row.toString()).doesNotContain("999999","禁止入日志","token=");
        long id=Ids.next();jdbc.update("INSERT INTO sys_api_error_log(id,tenant_id,company_id,user_id,error_class,error_summary,stack_frames,request_id) VALUES(?,?,?,?,?,'服务端处理失败',JSON_ARRAY('安全定位'),?)",id,tenant,company,user,"测试异常",UUID.randomUUID().toString());
        request("PUT","/sys/api-error-logs/"+id+"/handled",Map.of("version",0,"handled",true),true,200);request("PUT","/sys/api-error-logs/"+id+"/handled",Map.of("version",0,"handled",false),true,409);
        jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM sys_permission WHERE code='api_error:view')",tenant,role);request("GET","/sys/api-error-logs",null,false,403);
    }
    private <S extends Session> Runnable writeBack(SessionRepository<S> sessions,String sid) {S snapshot=sessions.findById(sid);assertThat(snapshot).isNotNull();return ()->sessions.save(snapshot);}
    private JsonNode request(String method,String path,Object input,boolean csrf,int status) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).header("Content-Type","application/json").header("X-Company-Id",Long.toString(company)).header("Idempotency-Key",UUID.randomUUID().toString()).method(method,input==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input)));
        if(csrf) builder.header("X-XSRF-TOKEN",cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("XSRF-TOKEN")).findFirst().orElseThrow().getValue());
        var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).as(path+" "+response.body()).isEqualTo(status);return json.readTree(response.body());
    }
}
