package cn.erp.realtime;

import cn.erp.ErpApplication;
import cn.erp.identity.*;
import cn.erp.shared.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RealtimeIT {
    @LocalServerPort int port;@Autowired JdbcTemplate jdbc;@Autowired PasswordEncoder encoder;@Autowired ApplicationEventPublisher events;@Autowired ObjectMapper json;@Autowired SessionAuthority authority;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    long tenant,user,company,role,foreignTenant,foreignUser,foreignCompany;String password;ConfigurableApplicationContext second;int secondPort;
    final List<WebSocket> sockets=new ArrayList<>();
    @BeforeEach void prepare() {
        tenant=Ids.next();user=Ids.next();company=Ids.next();role=Ids.next();foreignTenant=Ids.next();foreignUser=Ids.next();foreignCompany=Ids.next();password=UUID.randomUUID().toString();
        account(tenant,user,company);account(foreignTenant,foreignUser,foreignCompany);
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'RT_ADMIN','实时验收管理')",role,tenant);jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",Ids.next(),tenant,user,role);jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT ?,?,?,id FROM sys_permission WHERE code='online_user:kick'",Ids.next(),tenant,role);
        second=new SpringApplicationBuilder(ErpApplication.class).profiles("test").run("--server.port=0","--management.server.port=0","--erp.worker.enabled=false","--spring.main.banner-mode=off");secondPort=((WebServerApplicationContext)second).getWebServer().getPort();
    }
    void account(long selectedTenant,long selectedUser,long selectedCompany) {
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'实时验收企业')",selectedTenant,"RT"+selectedTenant);jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','实时验收公司')",selectedCompany,selectedTenant);jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,'reader','实时用户',?)",selectedUser,selectedTenant,encoder.encode(password));jdbc.update("INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)",Ids.next(),selectedTenant,selectedUser,selectedCompany);
    }
    @AfterEach void cleanup() {
        for(var socket:sockets) socket.abort();if(second!=null) second.close();
        for(long selected:List.of(tenant,foreignTenant)) {for(String table:List.of("outbox_event","audit_log","idempotency_record","sys_login_log","sys_api_access_log","sys_api_error_log","sys_user_data_scope","sys_user_role","sys_role_permission","sys_role","sys_user","company")) jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",selected);jdbc.update("DELETE FROM tenant WHERE id=?",selected);}
    }
    @Test void Z04_01_twoApiInstancesBroadcastThroughRedisAndKickClosesBothWithoutCrossTenantDelivery() throws Exception {
        var login=login(tenant);var foreign=login(foreignTenant);var a=new Listener();var b=new Listener();var other=new Listener();
        connect(port,login,a,"http://127.0.0.1:"+port);connect(secondPort,login,b,"http://127.0.0.1:"+secondPort);connect(secondPort,foreign,other,"http://127.0.0.1:"+secondPort);
        assertThat(a.take()).contains("connected");assertThat(b.take()).contains("connected");assertThat(other.take()).contains("connected");
        events.publishEvent(new UserSignal(tenant,user,"message.changed",Map.of("messageId","123")));
        assertThat(a.take()).contains("message.changed","123");assertThat(b.take()).contains("message.changed","123");assertThat(other.messages.poll(500,TimeUnit.MILLISECONDS)).isNull();
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status->events.publishEvent(new BusinessChanged(tenant,company,user,"sales_order.submit",Ids.next())));
        assertThat(a.take()).contains("business.changed").doesNotContain("sales_order","documentId");assertThat(b.take()).contains("business.changed");assertThat(other.messages.poll(500,TimeUnit.MILLISECONDS)).isNull();
        String sid=new String(Base64.getDecoder().decode(login.session().replace("\"","")),java.nio.charset.StandardCharsets.UTF_8);
        authority.kick(new CompanyAccess(tenant,user,0,true,Set.of(),Set.of("online_user:kick")),SessionAuthority.publicId(sid),UUID.randomUUID().toString());
        assertThat(a.closed.get(5,TimeUnit.SECONDS)).isEqualTo(1008);assertThat(b.closed.get(5,TimeUnit.SECONDS)).isEqualTo(1008);
        var replay=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+secondPort+"/api/v1/auth/me")).header("Cookie","ERPSESSION="+login.session()).GET().build(),HttpResponse.BodyHandlers.discarding());assertThat(replay.statusCode()).isEqualTo(401);
    }
    @Test void Z04_02_maliciousOriginAnonymousHandshakeAndClientBusinessCommandsAreRejected() throws Exception {
        var login=login(tenant);
        rejected(login,"https://malicious.example",403);
        rejected(new Login(""),"http://127.0.0.1:"+secondPort,401);
        var listener=new Listener();var socket=connect(secondPort,login,listener,"http://127.0.0.1:"+secondPort);assertThat(listener.take()).contains("connected");socket.sendText("{\"type\":\"approve\",\"id\":\"1\"}",true).get(5,TimeUnit.SECONDS);assertThat(listener.closed.get(5,TimeUnit.SECONDS)).isEqualTo(1008);
    }
    private Login login(long selectedTenant) throws Exception {
        var cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);var client=HttpClient.newBuilder().cookieHandler(cookies).build();
        var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/login")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("tenantCode","RT"+selectedTenant,"username","reader","password",password)))).build(),HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).isEqualTo(200);
        return new Login(cookies.getCookieStore().getCookies().stream().filter(cookie->cookie.getName().equals("ERPSESSION")).findFirst().orElseThrow().getValue());
    }
    private WebSocket connect(int target,Login login,Listener listener,String origin) throws Exception {var socket=HttpClient.newHttpClient().newWebSocketBuilder().header("Cookie","ERPSESSION="+login.session()).header("Origin",origin).connectTimeout(java.time.Duration.ofSeconds(5)).buildAsync(URI.create("ws://127.0.0.1:"+target+"/ws/v1/events"),listener).get(8,TimeUnit.SECONDS);sockets.add(socket);return socket;}
    private record Login(String session) {}
    private void rejected(Login login,String origin,int expected) {
        assertThatThrownBy(()->connect(secondPort,login,new Listener(),origin)).isInstanceOfSatisfying(ExecutionException.class,error->{
            assertThat(error.getCause()).isInstanceOfSatisfying(WebSocketHandshakeException.class,handshake->assertThat(handshake.getResponse().statusCode()).isEqualTo(expected));
        });
    }
    private static class Listener implements WebSocket.Listener {
        final BlockingQueue<String> messages=new LinkedBlockingQueue<>();final CompletableFuture<Integer> closed=new CompletableFuture<>();final StringBuilder fragments=new StringBuilder();
        @Override public void onOpen(WebSocket socket) {socket.request(1);}
        @Override public CompletionStage<?> onText(WebSocket socket,CharSequence value,boolean last) {fragments.append(value);if(last) {String message=fragments.toString();fragments.setLength(0);if(message.contains("\"ping\"")) socket.sendText("{\"type\":\"pong\"}",true);else messages.offer(message);}socket.request(1);return null;}
        @Override public CompletionStage<?> onClose(WebSocket socket,int status,String reason) {closed.complete(status);return null;}
        @Override public void onError(WebSocket socket,Throwable failure) {closed.completeExceptionally(failure);}
        String take() throws InterruptedException {String message=messages.poll(8,TimeUnit.SECONDS);assertThat(message).as("实时事件应在8秒内送达").isNotNull();return message;}
    }
}
