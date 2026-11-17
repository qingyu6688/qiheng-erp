package cn.erp.openapi;

import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;

import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryModels.Key;
import cn.erp.inventory.InventoryModels.Posting;
import cn.erp.inventory.InventoryModels.Source;
import cn.erp.inventory.InventoryService;
import cn.erp.jobs.OutboxDelivery;
import cn.erp.jobs.OutboxService;
import cn.erp.shared.Ids;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="springdoc.api-docs.enabled=true")
class OpenPlatformIT {
    private static final String ROOT = "/api/v1/open-platform";
    private static final String WAREHOUSES = "/open/v1/warehouses";
    private static final String ORDERS = "/open/v1/sales-orders";
    private static final Set<String> SCOPES = Set.of("warehouse:view","sales_order:create","sales_order:submit","sales_order:view",
            "shipment:view","inventory:view");
    private static final LocalDate DAY = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1);

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired InventoryService inventory;
    @Autowired OutboxService outbox;
    @Autowired OutboxDelivery outboxDelivery;
    @Autowired OpenWebhooks hooks;
    @MockitoSpyBean OpenWebhookTransport transport;

    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final long tenant=Ids.next();
    private final long outside=Ids.next();
    private final long company=Ids.next();
    private final long secondCompany=Ids.next();
    private final long foreignCompany=Ids.next();
    private final long admin=Ids.next();
    private final long outsider=Ids.next();
    private final long warehouse=Ids.next();
    private final long sku=Ids.next();
    private final long unit=Ids.next();
    private final long customer=Ids.next();
    private final long period=Ids.next();
    private String cookie;
    private String csrf;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) {
        properties.add("ERP_OPEN_SETTINGS_KEY",() -> {
            byte[] raw=new byte[32];
            new java.security.SecureRandom().nextBytes(raw);
            return Base64.getEncoder().encodeToString(raw);
        });
        properties.add("ERP_OPEN_WEBHOOK_HOSTS",() -> "receiver.example.com");
    }

    @BeforeEach
    void seed() throws Exception {
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'开放甲企业'),(?,?,'开放乙企业')",tenant,code(tenant),outside,code(outside));
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'C1','甲公司'),(?,?,'C2','乙公司'),(?,?,'C3','外部公司')",
                company,tenant,secondCompany,tenant,foreignCompany,outside);
        cookie=login(tenant,company,admin);
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name,address) VALUES(?,?,?,'W1','甲仓','私有地址')",warehouse,tenant,company);
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'W2','乙仓'),(?,?,?,'W3','外部仓')",
                Ids.next(),tenant,secondCompany,Ids.next(),outside,foreignCompany);
        var action="SELECT id FROM sys_permission WHERE code IN ('open_platform:view','open_platform:manage','warehouse:view','inventory:view',"
                +"'sales_order:create','sales_order:submit','sales_order:view','sales_order:approve','shipment:view','shipment:create','shipment:submit','shipment:post')";
        long role=jdbc.queryForObject("SELECT role_id FROM sys_user_role WHERE tenant_id=? AND user_id=?",Long.class,tenant,admin);
        for(long permission:jdbc.queryForList(action,Long.class)) {
            jdbc.update("INSERT IGNORE INTO sys_role_permission(id,tenant_id,role_id,permission_id) VALUES(?,?,?,?)",Ids.next(),tenant,role,permission);
        }
        long category=Ids.next(),product=Ids.next();
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'BUY','测试客户')",customer,tenant);
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(?,?,?,'customer')",Ids.next(),tenant,customer);
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件')",unit,tenant);
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'CAT','分类','/')",category,tenant);
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'PROD','商品',?)",product,tenant,category,unit);
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name,sale_price) VALUES(?,?,?,'SKU','测试 sku','20')",sku,tenant,product);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,sku,unit);
        var month=YearMonth.from(DAY);
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,?,?,?)",
                period,tenant,company,month.toString().replace("-",""),month.atDay(1),month.atEndOfMonth());
        long seed=Ids.next();
        inventory.receive(new CompanyAccess(tenant,admin,company,true,Set.of(),Set.of()),
                new Posting(new Key(warehouse,sku),new Source("opening_entry",seed,seed),DAY,period,"opening_in"),
                new java.math.BigDecimal("12"),new java.math.BigDecimal("120"));
    }

    @AfterEach
    void cleanup() throws Exception {
        if(cookie!=null) send(internal("POST","/api/v1/auth/logout",null).build());
        for (long owner:List.of(tenant,outside)) {
            jdbc.update("DELETE FROM inbox_event WHERE tenant_id=?",owner);
            jdbc.update("DELETE FROM outbox_event WHERE tenant_id=?",owner);
            for(String table:List.of("open_inventory_event_slot","open_webhook_delivery","open_webhook","open_call_log","open_app_company","open_app",
                    "sys_api_error_log","sys_api_access_log","sys_login_log","notify_message","job_run_log","job_definition",
                    "audit_log","idempotency_record","approval_record","inventory_reservation_change","inventory_reservation",
                    "ledger_balance","journal_line","journal","cost_adjustment","inv_movement_hold","inventory_movement","inventory_cost","inventory_balance",
                    "shipment_line","shipment","sales_order_fee","sales_order_line","sales_order","document_sequence","accounting_period",
                    "unit_conversion","sku","product","product_category","unit","partner_role","business_partner",
                    "org_user_data_scope","sys_user_role","sys_role_permission","sys_role","sys_user","warehouse","company")) {
                jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",owner);
            }
            jdbc.update("DELETE FROM tenant WHERE id=?",owner);
        }
    }

    @Test
    void signaturesBindCompanyAndBodyAndRejectReplayExpiryScopesAndFalseIp() throws Exception {
        var app=createApp(100,List.of("127.0.0.0/8","::1/128"));
        assertThat(send(internal("GET",WAREHOUSES,null).build()).statusCode()).isEqualTo(401);
        String timestamp=Long.toString(Instant.now().getEpochSecond());
        var valid=signed("GET",WAREHOUSES,company,app,nonce(),timestamp,"",null,true);
        var changedCompany=HttpRequest.newBuilder(valid,(name,value) -> !name.equalsIgnoreCase("X-Company-Id"))
                .header("X-Company-Id",id(secondCompany)).build();
        assertThat(send(changedCompany).statusCode()).isEqualTo(401);
        assertThat(data(send(valid),200).path("items").get(0).path("id").asString()).isEqualTo(id(warehouse));
        assertThat(send(valid).statusCode()).isEqualTo(401);
        assertThat(send(signed("GET",WAREHOUSES,company,app,nonce(),
                Long.toString(Instant.now().minusSeconds(301).getEpochSecond()),"",null,true)).statusCode()).isEqualTo(401);
        assertThat(send(signed("GET",WAREHOUSES,company,app,nonce(),timestamp,"",null,false)).statusCode()).isEqualTo(401);
        assertThat(send(signed("GET",WAREHOUSES,foreignCompany,app,nonce(),timestamp,"",null,true)).statusCode()).isEqualTo(403);
        assertThat(send(signed("GET",WAREHOUSES,secondCompany,app,nonce(),timestamp,"",null,true)).statusCode()).isEqualTo(403);
        assertThat(send(signed("GET","/open/v1/payments/1",company,app,nonce(),timestamp,"",null,true)).statusCode()).isEqualTo(404);
        assertThat(send(signed("GET","/open/v1/partners",company,app,nonce(),timestamp,"",null,true)).statusCode()).isEqualTo(403);
        assertThat(send(signed("POST",ORDERS+"/1/approve",company,app,nonce(),timestamp,"approve",Map.of("version",0),true)).statusCode()).isEqualTo(403);
        var blocked=createApp(100,List.of("192.0.2.0/24"));
        var request=signed("GET",WAREHOUSES,company,blocked,nonce(),timestamp,"",null,true);
        var spoof=HttpRequest.newBuilder(request,(name,value) -> true).header("X-Forwarded-For","192.0.2.10").build();
        assertThat(send(spoof).statusCode()).isEqualTo(403);
        assertThat(data(send(internal("GET",ROOT+"/calls",null).build()),200).toString()).contains("/open/v1/warehouses").doesNotContain(app.secret());
    }

    @Test
    void adminScopeAndSecretRotationAndSlidingWindowAreEnforced() throws Exception {
        var app=createApp(1,List.of());
        String timestamp=Long.toString(Instant.now().getEpochSecond());
        data(send(signed("GET",WAREHOUSES,company,app,nonce(),timestamp,"",null,true)),200);
        var limited=send(signed("GET",WAREHOUSES,company,app,nonce(),timestamp,"",null,true));
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
        var rotation=data(send(internal("POST",ROOT+"/apps/"+app.id()+"/rotate-secret",Map.of()).build()),200);
        assertThat(rotation.path("secret").asString()).isNotEqualTo(app.secret());
        assertThat(send(signed("GET",WAREHOUSES,company,app,nonce(),timestamp,"",null,true)).statusCode()).isEqualTo(401);
        assertThat(data(send(internal("GET",ROOT+"/apps",null).build()),200).toString()).doesNotContain(app.secret(),rotation.path("secret").asString());
        var promoted=appInput(100,List.of(),Set.of("sales_order:approve"));
        assertThat(send(internal("POST",ROOT+"/apps",promoted).build()).statusCode()).isEqualTo(400);
        var spec=data(send(internal("GET",ROOT+"/spec",null).build()),200);
        assertThat(spec.path("paths").has(ORDERS)).isTrue();
        assertThat(spec.path("paths").has("/api/v1/sales-orders")).isFalse();
        var internalSpec=send(internal("GET","/v3/api-docs",null).build());
        assertThat(internalSpec.statusCode()).isEqualTo(200);
        var internalPaths=json.readTree(internalSpec.body()).path("paths");
        assertThat(internalPaths.has("/api/v1/sales-orders")).isTrue();
        assertThat(internalPaths.has(ORDERS)).isFalse();
        assertThat(send(internal("GET","/v3/api-docs/open",null).build()).statusCode()).isEqualTo(403);
        var audit=data(send(internal("GET","/api/v1/audit-logs?action=open_app.create",null).build()),200);
        assertThat(audit.path("items").toString()).contains(app.id()).doesNotContain(app.secret());
        // 第二企业的用户即使具备相同管理许可，也不能根据编号转动其他企业的应用密钥。
        String original=cookie;
        String originalCsrf=csrf;
        cookie=login(outside,foreignCompany,outsider);
        try {
            assertThat(send(internal("POST",ROOT+"/apps/"+app.id()+"/rotate-secret",Map.of()).build()).statusCode()).isEqualTo(404);
            assertThat(data(send(internal("GET",ROOT+"/apps",null).build()),200).isEmpty()).isTrue();
            var foreignAudit=data(send(internal("GET","/api/v1/audit-logs?action=open_app.create",null).build()),200);
            assertThat(foreignAudit.path("items").isEmpty()).isTrue();
        } finally {
            send(internal("POST","/api/v1/auth/logout",null).build());
            cookie=original;csrf=originalCsrf;
        }
    }

    @Test
    void externalOrderIsApprovedAndShippedByHumanAndSignedWebhookRetries() throws Exception {
        var app=createApp(100,List.of());
        var hook=data(send(internal("POST",ROOT+"/webhooks",Map.of("appId",app.id(),"url","https://receiver.example.com/erp",
                "events",List.of("sales.order.approved","sales.shipment.posted"),"enabled",true)).build()),201);
        var history=new CopyOnWriteArrayList<Attempt>();
        var postCount=new AtomicInteger();
        doAnswer(call -> {
            byte[] content=call.getArgument(4);
            var body=json.readTree(content);
            String type=body.path("eventType").asString();
            history.add(new Attempt(call.getArgument(1),call.getArgument(2),call.getArgument(3),content));
            return type.equals("sales.shipment.posted") && postCount.getAndIncrement()==0 ? 500 : 204;
        }).when(transport).send(anyString(),anyString(),anyString(),anyString(),any(byte[].class));
        var order=Map.of("customerId",id(customer),"businessDate",DAY.toString(),"priceTaxInclusive",true,
                "lines",List.of(Map.of("skuId",id(sku),"warehouseId",id(warehouse),"unitId",id(unit),"qty","2")));
        String timestamp=Long.toString(Instant.now().getEpochSecond());
        var original=signed("POST",ORDERS,company,app,nonce(),timestamp,"changed-body",order,true);
        var tampered=HttpRequest.newBuilder(original,(name,value) -> true).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertThat(send(tampered).statusCode()).isEqualTo(401);
        var created=data(send(signed("POST",ORDERS,company,app,nonce(),timestamp,"create-order",order,true)),201);
        String orderId=created.path("id").asString();
        var repeated=data(send(signed("POST",ORDERS,company,app,nonce(),timestamp,"create-order",order,true)),201);
        assertThat(repeated.path("id").asString()).isEqualTo(orderId);
        long creator=jdbc.queryForObject("SELECT created_by FROM sales_order WHERE tenant_id=? AND id=?",Long.class,tenant,Long.parseLong(orderId));
        assertThat(jdbc.queryForObject("SELECT user_type FROM sys_user WHERE tenant_id=? AND id=?",String.class,tenant,creator)).isEqualTo("app");
        var submitted=data(send(signed("POST",ORDERS+"/"+orderId+"/submit",company,app,nonce(),timestamp,"submit-order",
                Map.of("version",created.path("version").asLong()),true)),200);
        assertThat(submitted.path("status").asString()).isEqualTo("pending");
        var approved=command("/api/v1/sales-orders/"+orderId+"/approve",submitted.path("version").asLong());
        assertThat(approved.path("status").asString()).isEqualTo("approved");
        var shipment=data(send(internal("POST","/api/v1/shipments",Map.of("customerId",customer,"warehouseId",warehouse,
                "businessDate",DAY.toString(),"deliveryMethod","delivery","lines",List.of(Map.of(
                        "orderLineId",Long.parseLong(created.path("lines").get(0).path("id").asString()),"unitId",unit,"qty","2")))).build()),201);
        String shipmentId=shipment.path("id").asString();
        var ready=command("/api/v1/shipments/"+shipmentId+"/submit",shipment.path("version").asLong());
        assertThat(command("/api/v1/shipments/"+shipmentId+"/post",ready.path("version").asLong()).path("status").asString()).isEqualTo("posted");
        for (var event:outbox.claim(100,tenant)) assertThat(outboxDelivery.deliver(event)).isTrue();
        hooks.processTenant(tenant);
        var pending=jdbc.queryForMap("""
                SELECT id,status,last_status_code,attempts,TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(3),next_attempt_at) delay_s
                FROM open_webhook_delivery WHERE tenant_id=? AND event_type='sales.shipment.posted'
                """,tenant);
        assertThat(pending).containsEntry("status","pending");
        assertThat(((Number)pending.get("last_status_code")).intValue()).isEqualTo(500);
        assertThat(((Number)pending.get("delay_s")).intValue()).isBetween(55,60);
        jdbc.update("UPDATE open_webhook_delivery SET next_attempt_at=UTC_TIMESTAMP(3) WHERE id=?",pending.get("id"));
        hooks.processTenant(tenant);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM open_webhook_delivery WHERE tenant_id=? AND status='delivered'",Long.class,tenant)).isEqualTo(2);
        assertThat(history).hasSize(3);
        String secret=hook.path("secret").asString();
        for (var attempt:history) {
            String expected=Base64.getEncoder().encodeToString(OpenSecrets.sign(secret,
                    attempt.timestamp()+"."+new String(attempt.body(),StandardCharsets.UTF_8)));
            assertThat(attempt.signature()).isEqualTo(expected);
            var message=json.readTree(attempt.body());
            assertThat(message.path("companyId").asString()).isEqualTo(id(company));
            assertThat(message.path("eventId").asString()).isEqualTo(attempt.eventId());
            assertThat(message.path("data").size()).isEqualTo(3);
        }
        assertThat(history.get(1).eventId()).isEqualTo(history.getLast().eventId());
    }

    private App createApp(int limit,List<String> ips) throws Exception {
        var app=data(send(internal("POST",ROOT+"/apps",appInput(limit,ips,SCOPES)).build()),201);
        return new App(app.path("id").asString(),app.path("appKey").asString(),app.path("secret").asString());
    }

    private Map<String,Object> appInput(int limit,List<String> ips,Set<String> scopes) {
        return Map.of("name","回归应用","companyIds",List.of(id(company)),"scopes",scopes,"ipWhitelist",ips,
                "rateLimitPerMinute",limit,"enabled",true);
    }

    private String login(long owner,long grantedCompany,long user) throws Exception {
        String clear=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,'open_admin','开放测试管理员',?)",
                user,owner,passwords.encode(clear));
        long role=Ids.next();
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'OPEN_ADMIN','开放管理员')",role,owner);
        for (long permission:jdbc.queryForList("SELECT id FROM sys_permission WHERE code IN ('open_platform:view','open_platform:manage','audit:view')",Long.class)) {
            jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) VALUES(?,?,?,?)",Ids.next(),owner,role,permission);
        }
        jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",Ids.next(),owner,user,role);
        jdbc.update("INSERT INTO org_user_data_scope(id,tenant_id,user_id,company_id,scope_type) VALUES(?,?,?,?,'company')",
                Ids.next(),owner,user,grantedCompany);
        var response=send(request("/api/v1/auth/login").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(
                json.writeValueAsString(Map.of("tenantCode",code(owner),"username","open_admin","password",clear)))).build());
        data(response,200);
        var sessionCookies=response.headers().allValues("Set-Cookie").stream().flatMap(value -> HttpCookie.parse(value).stream()).toList();
        var session=sessionCookies.stream().filter(c -> c.getName().equals("ERPSESSION")).findFirst().orElseThrow();
        var tokenResponse=send(request("/api/v1/auth/me").header("Cookie","ERPSESSION="+session.getValue()).GET().build());
        data(tokenResponse,200);
        var csrfCookie=tokenResponse.headers().allValues("Set-Cookie").stream().flatMap(value -> HttpCookie.parse(value).stream())
                .filter(c -> c.getName().equals("XSRF-TOKEN")).findFirst()
                .orElseGet(() -> sessionCookies.stream().filter(c -> c.getName().equals("XSRF-TOKEN")).findFirst().orElseThrow());
        csrf=csrfCookie.getValue();
        return "ERPSESSION="+session.getValue()+"; XSRF-TOKEN="+csrf;
    }

    private HttpRequest.Builder internal(String method,String path,Object body) {
        var req=request(path).header("Cookie",cookie).header("X-Company-Id",id(company));
        if (!"GET".equals(method)) req.header("X-XSRF-TOKEN",csrf).header("Idempotency-Key",nonce());
        if (body!=null) req.header("Content-Type","application/json");
        return req.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    }

    private HttpRequest signed(String method,String path,long signedCompany,App app,String nonce,String timestamp,String key,
            Object body,boolean valid) {
        byte[] bytes=body==null?new byte[0]:json.writeValueAsBytes(body);
        String canonical=OpenRequest.canonical(method,path,null,timestamp,nonce,id(signedCompany),key,bytes);
        String signature=Base64.getEncoder().encodeToString(OpenSecrets.sign(valid?app.secret():"无效密钥",canonical));
        var req=request(path).header("X-App-Key",app.key()).header("X-Signature",signature).header("X-Timestamp",timestamp)
                .header("X-Nonce",nonce).header("X-Company-Id",id(signedCompany));
        if (!key.isEmpty()) req.header("Idempotency-Key",key);
        if (body!=null) req.header("Content-Type","application/json");
        return req.method(method,HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
    }

    private JsonNode command(String path,long version) throws Exception {
        return data(send(internal("POST",path,Map.of("version",version)).build()),200);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20));
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request,HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode data(HttpResponse<String> response,int status) {
        assertThat(response.statusCode()).as("%s HTTP %s",response.uri(),response.body()).isEqualTo(status);
        return json.readTree(response.body()).path("data");
    }

    private static String nonce() { return UUID.randomUUID().toString().replace("-",""); }
    private static String id(long value) { return Long.toString(value); }
    private static String code(long value) { return "OA"+value; }
    private record App(String id,String key,String secret) {}
    private record Attempt(String eventId,String timestamp,String signature,byte[] body) {}
}
