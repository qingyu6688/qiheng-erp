package cn.erp.stocktransfer;

import cn.erp.ErpApplication;
import cn.erp.shared.Ids;
import java.io.File;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
class StockTransferRestartIT extends StockTransferTestData {
    private static final String TEST_ROOT = System.getenv("ERP_TEST_ROOT");
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    private record Session(HttpClient client,CookieManager cookies,int port) {}
    private record Isolation(String database,String jdbcUrl,String username,String password) {}

    @Test
    @Timeout(180)
    void T12IndependentJvmExitAndRestartPreserveTransitAndAllowReceipt() throws Exception {
        var isolation=isolation();
        String password=UUID.randomUUID()+"Az9!",namespace="erp:session:test:transfer-restart:"+tenant;
        seedUser(password);long transfer=shipped("3");
        assertThat(balance(from).onHand()).isEqualByComparingTo("0");assertThat(balance(to).onHand()).isEqualByComparingTo("0");
        assertOriginalCost(transfer);
        Path evidenceRoot = TEST_ROOT == null || TEST_ROOT.isBlank()
                ? Path.of("target")
                : Files.createDirectories(Path.of(TEST_ROOT, "erp-server", "restart-evidence"));
        Path directory = Files.createTempDirectory(evidenceRoot, "transfer-restart-");
        Process first=null,second=null;
        try {
            int firstPort=freePort();Path firstLog=directory.resolve("first-jvm.log");
            first=start(firstPort,namespace,isolation,directory.resolve("first-jvm.args"),firstLog);
            ready(first,firstPort,firstLog);var firstSession=login(firstPort,password);
            var before=read(firstSession,transfer);assertThat(before.path("status").asText()).isEqualTo("shipped");
            assertThat(before.path("lines").get(0).path("remainingQty").asText()).isEqualTo("3.000000");
            assertThat(before.path("lines").get(0).has("costAmount")).isFalse();logout(firstSession);
            long firstPid=first.pid();stop(first);assertThat(first.isAlive()).isFalse();

            int secondPort=freePort();Path secondLog=directory.resolve("second-jvm.log");
            second=start(secondPort,namespace,isolation,directory.resolve("second-jvm.args"),secondLog);
            assertThat(second.pid()).isNotEqualTo(firstPid);ready(second,secondPort,secondLog);
            var secondSession=login(secondPort,password);var persisted=read(secondSession,transfer);
            assertThat(persisted.path("status").asText()).isEqualTo("shipped");assertOriginalCost(transfer);
            assertThat(persisted.path("events").size()).isEqualTo(1);
            var input=new StockTransferModels.Receive(persisted.path("version").asLong(),null,true,
                List.of(new StockTransferModels.ReceivedLine(persisted.path("lines").get(0).path("id").asLong(),"3")));
            var received=http(secondSession,"POST","/stock-transfers/"+transfer+"/receive",json.writeValueAsString(input),key(),true);
            assertThat(received.statusCode()).as("独立重启进程应完成原调拨接收，日志：%s",secondLog).isEqualTo(200);
            assertThat(json.readTree(received.body()).path("data").path("status").asText()).isEqualTo("received");
            logout(secondSession);stop(second);assertThat(second.isAlive()).isFalse();
            assertThat(balance(from).onHand()).isEqualByComparingTo("0");assertThat(balance(to).onHand()).isEqualByComparingTo("3");
            assertThat(balance(to).cost()).isEqualByComparingTo("1");assertOriginalCost(transfer);
            assertThat(count("stock_transfer_event_line")).isEqualTo(2);zero();
        } finally {
            try {stop(second);} finally {try {stop(first);} finally {clearSessions(namespace);}}
        }
    }

    private void seedUser(String password) {
        long role=Ids.next();jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,'restart','调拨重启验收',?)",user,tenant,passwords.encode(password));
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'RESTART','调拨重启权限')",role,tenant);
        jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",Ids.next(),tenant,user,role);
        for(String permission:List.of("stock_transfer:view","stock_transfer:receive"))jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT ?,?,?,id FROM sys_permission WHERE code=?",Ids.next(),tenant,role,permission);
        jdbc.update("INSERT INTO org_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)",Ids.next(),tenant,user,company);
    }

    private Isolation isolation() throws Exception {
        String database=System.getenv().getOrDefault("TEST_DB_NAME","erp_test"),username=System.getenv("TEST_DB_USER"),password=System.getenv("TEST_DB_PASSWORD");
        if(!database.matches("erp_test(?:_[a-z0-9]+)*")||database.equals(System.getenv("DB_NAME"))||username==null||username.isBlank()||password==null||password.isBlank())throw new IllegalStateException("独立重启验收必须使用项目脚本提供的隔离测试库及凭据");
        try(Connection connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            String url=connection.getMetaData().getURL();
            if(!url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost|\\[::1\\])(?::[0-9]+)?/"+database+"(?:\\?.*)?"))throw new IllegalStateException("独立重启验收仅允许已验证的本机隔离测试库");
            return new Isolation(database,url,username,password);
        }
    }

    private Process start(int port,String namespace,Isolation isolation,Path argumentFile,Path log) throws IOException {
        String classpath=System.getProperty("surefire.test.class.path");if(classpath==null||classpath.isBlank())classpath=System.getProperty("java.class.path");
        if(classpath==null||classpath.isBlank())throw new IllegalStateException("独立重启验收缺少测试类路径");
        classpath=launchClasspath(classpath);
        var arguments=List.of("-Xmx256m","-XX:ActiveProcessorCount=2","-Dfile.encoding=UTF-8","-cp",classpath,"cn.erp.ErpApplication",
            "--spring.profiles.active=test","--spring.main.web-application-type=servlet","--server.address=127.0.0.1","--server.port="+port,
            "--erp.worker.enabled=false","--spring.datasource.hikari.maximum-pool-size=3","--spring.datasource.hikari.minimum-idle=1",
            "--spring.session.redis.namespace="+namespace,"--logging.level.root=WARN");
        Files.write(argumentFile,arguments.stream().map(StockTransferRestartIT::argument).toList(),StandardCharsets.UTF_8);
        String executable=System.getProperty("os.name").startsWith("Windows")?"java.exe":"java";
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",executable).toString(),
            "-Djava.io.tmpdir="+System.getProperty("java.io.tmpdir"),"@"+argumentFile.toAbsolutePath())
            .directory(Path.of("").toAbsolutePath().normalize().toFile());
        var environment=builder.environment();
        for(String name:List.copyOf(environment.keySet()))if(name.startsWith("ERP_DEV_")||name.startsWith("ERP_E2E_")||Set.of("SPRING_APPLICATION_JSON","SPRING_PROFILES_INCLUDE","SPRING_CONFIG_LOCATION","SPRING_CONFIG_ADDITIONAL_LOCATION").contains(name))environment.remove(name);
        environment.put("SPRING_PROFILES_ACTIVE","test");environment.put("APP_ENV","test-restart");environment.put("SERVER_PORT",Integer.toString(port));
        environment.put("ERP_WORKER_ENABLED","false");environment.put("ERP_NODE_ID","1023".equals(System.getenv("ERP_NODE_ID"))?"1022":"1023");
        // 即使基础配置被读取，数据源也只指向父进程已经验证的测试库。
        environment.put("DB_NAME",isolation.database());environment.put("DB_USER",isolation.username());environment.put("DB_PASSWORD",isolation.password());
        environment.put("TEST_DB_NAME",isolation.database());environment.put("TEST_DB_USER",isolation.username());environment.put("TEST_DB_PASSWORD",isolation.password());
        environment.put("SPRING_DATASOURCE_URL",isolation.jdbcUrl());environment.put("SPRING_DATASOURCE_USERNAME",isolation.username());environment.put("SPRING_DATASOURCE_PASSWORD",isolation.password());
        if (TEST_ROOT != null && !TEST_ROOT.isBlank()) {
            String requestLog = log.getFileName().toString().replace(".log", "-http.log");
            environment.put("ERP_HTTP_LOG_FILE", log.resolveSibling(requestLog).toAbsolutePath().toString());
        }
        return builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private static String launchClasspath(String classpath) throws IOException {
        Path working=Path.of("").toAbsolutePath().normalize(),alias=Path.of("F:/projects/erp/erp-server");
        boolean sameAlias=Files.isDirectory(alias)&&Files.isSameFile(working,alias);var entries=new ArrayList<String>();
        for(String raw:classpath.split(Pattern.quote(File.pathSeparator),-1)) {
            String entry=raw.isEmpty()?".":raw;Path actual=Path.of(entry).toAbsolutePath().normalize();
            if(!ascii(entry)&&actual.startsWith(working)) {
                Path relative=working.relativize(actual),replacement=sameAlias?alias.resolve(relative):relative;
                if(replacement.toString().isEmpty())replacement=Path.of(".");
                Path resolved=replacement.isAbsolute()?replacement:working.resolve(replacement);
                if(!Files.isSameFile(actual,resolved))throw new IOException("独立重启验收的类路径入口未指向同一文件");
                entry=replacement.toString();
            }
            // Windows 启动器读取参数文件时使用本机编码，先核验再改写路径，文件内容保持 ASCII。
            if(!ascii(entry))throw new IOException("独立重启验收的类路径仍包含无法安全映射的非ASCII入口");
            entries.add(entry);
        }
        return String.join(File.pathSeparator,entries);
    }
    private static boolean ascii(String value) {return value.chars().allMatch(c->c<128);}
    private static String argument(String value) {return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
    private static int freePort() throws IOException {
        for(int attempt=0;attempt<10;attempt++)try(var socket=new ServerSocket(0,0,InetAddress.getByName("127.0.0.1"))) {
            int port=socket.getLocalPort();if(!Set.of(8080,5173,18080,15173).contains(port))return port;
        }
        throw new IOException("没有可用的独立重启验收端口");
    }
    private void ready(Process process,int port,Path log) throws Exception {
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
        while(System.nanoTime()<deadline) {
            if(!process.isAlive())throw new IOException("独立测试后端提前退出，请检查日志："+log);
            try {
                var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/actuator/health")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString());
                if(response.statusCode()==200&&"UP".equals(json.readTree(response.body()).path("status").asText()))return;
            } catch(IOException error) {if(!process.isAlive())throw new IOException("独立测试后端启动失败，请检查日志："+log,error);}
            Thread.sleep(200);
        }
        throw new IOException("独立测试后端未在60秒内就绪，请检查日志："+log);
    }
    private Session login(int port,String password) throws Exception {
        var cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);var session=new Session(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).cookieHandler(cookies).build(),cookies,port);
        String body=json.writeValueAsString(Map.of("tenantCode","TRANSFER"+tenant,"username","restart","password",password));
        assertThat(http(session,"POST","/auth/login",body,null,false).statusCode()).as("独立进程测试账号应能登录").isEqualTo(200);return session;
    }
    private JsonNode read(Session session,long transfer) throws Exception {
        var response=http(session,"GET","/stock-transfers/"+transfer,null,null,false);assertThat(response.statusCode()).as("独立进程应能读取持久化调拨").isEqualTo(200);return json.readTree(response.body()).path("data");
    }
    private void logout(Session session) throws Exception {assertThat(http(session,"POST","/auth/logout",null,null,true).statusCode()).isEqualTo(200);}
    private HttpResponse<String> http(Session session,String method,String path,String body,String key,boolean csrf) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+session.port()+"/api/v1"+path)).timeout(Duration.ofSeconds(10)).header("X-Company-Id",Long.toString(company));
        if(body!=null)request.header("Content-Type","application/json");if(key!=null)request.header("Idempotency-Key",key);
        if(csrf)request.header("X-XSRF-TOKEN",session.cookies().getCookieStore().getCookies().stream().filter(cookie->cookie.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst().orElseThrow());
        return session.client().send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private static void stop(Process process) throws InterruptedException {
        if(process==null||!process.isAlive())return;
        process.destroy();
        try {if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();if(!process.waitFor(10,TimeUnit.SECONDS))throw new IllegalStateException("独立测试子进程未能结束："+process.pid());}}
        catch(InterruptedException error){process.destroyForcibly();Thread.currentThread().interrupt();throw error;}
    }
    private void assertOriginalCost(long transfer) {
        assertThat(jdbc.queryForObject("SELECT cost_amount FROM stock_transfer_line WHERE tenant_id=? AND company_id=? AND transfer_id=?",java.math.BigDecimal.class,tenant,company,transfer)).isEqualByComparingTo("1");
    }
    private void clearSessions(String namespace) {
        try(var keys=redis.scan(org.springframework.data.redis.core.ScanOptions.scanOptions().match(namespace+":*").count(100).build())) {
            var own=new ArrayList<String>();keys.forEachRemaining(own::add);if(!own.isEmpty())redis.delete(own);
        }
    }
}
