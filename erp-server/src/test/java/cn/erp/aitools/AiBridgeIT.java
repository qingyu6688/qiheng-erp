package cn.erp.aitools;

import cn.erp.shared.Ids;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AiBridgeIT {
    private static final String CONTEXT = "/api/v1/ai-tools/context";
    private static final String TOOL = "/api/v1/ai-tools/warehouses";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String LOGOUT = "/api/v1/auth/logout";
    private static final String KEY = newSigningKey();

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;

    private final long tenant = Ids.next();
    private final long foreignTenant = Ids.next();
    private final long user = Ids.next();
    private final long company = Ids.next();
    private final long alternateCompany = Ids.next();
    private final long unassignedCompany = Ids.next();
    private final long foreignCompany = Ids.next();
    private final long aiRole = Ids.next();
    private final long warehouseRole = Ids.next();
    private final long disabledRole = Ids.next();
    private final long visibleWarehouse = Ids.next();
    private final long disabledWarehouse = Ids.next();
    private final long activeDepartment = Ids.next();
    private final long disabledDepartment = Ids.next();
    private final long otherDepartment = Ids.next();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private String cookie;
    private String csrf;

    @DynamicPropertySource
    static void configureSignature(DynamicPropertyRegistry properties) {
        properties.add("ERP_AI_SIGNING_KEY", () -> KEY);
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'AI测试企业'),(?,?,'外部测试企业')",
                tenant, tenantCode(), foreignTenant, "T" + foreignTenant);
        addCompany(tenant, company, "C1");
        addCompany(tenant, alternateCompany, "C2");
        addCompany(tenant, unassignedCompany, "C3");
        addCompany(foreignTenant, foreignCompany, "F1");
        String password = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash)
                VALUES(?,?,'ai_reader','知识库阅读者',?)
                """, user, tenant, passwords.encode(password));
        addRole(aiRole, "AI_READER", "active", "ai:use");
        addRole(warehouseRole, "WAREHOUSE_VIEWER", "active", "warehouse:view");
        addRole(disabledRole, "AI_ADMIN_DISABLED", "disabled", "ai_admin:manage");
        addWarehouse(visibleWarehouse, company, "W1", "active");
        addWarehouse(Ids.next(), company, "W2", "active");
        addWarehouse(disabledWarehouse, company, "W3", "disabled");
        addWarehouse(Ids.next(), alternateCompany, "W4", "active");
        addCompanyWarehouseScope();
        addDepartments();
        login(password);
    }

    @AfterEach
    void removeFixture() throws Exception {
        if (cookie != null) {
            send(request(LOGOUT).header("Cookie", cookie).header("X-XSRF-TOKEN", csrf)
                    .POST(HttpRequest.BodyPublishers.noBody()).build());
        }
        jdbc.update("DELETE FROM sys_api_error_log WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM sys_api_access_log WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM sys_login_log WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM audit_log WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM org_user_department WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM org_user_company WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM org_department WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM sys_user_data_scope WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM sys_user_role WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM sys_role WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM sys_user WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM warehouse WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM company WHERE tenant_id IN (?,?)", tenant, foreignTenant);
        jdbc.update("DELETE FROM tenant WHERE id IN (?,?)", tenant, foreignTenant);
    }

    @Test
    void identityFactsUseCurrentRolesAndDepartmentMembershipOfChosenCompany() throws Exception {
        var principal = data(signedGet(CONTEXT, company), 200);
        assertThat(principal.get("tenantId").asString()).isEqualTo(Long.toString(tenant));
        assertThat(principal.get("userId").asString()).isEqualTo(Long.toString(user));
        assertThat(principal.get("companyId").asString()).isEqualTo(Long.toString(company));
        assertThat(strings(principal.get("roleIds")))
                .containsExactlyInAnyOrder(Long.toString(aiRole), Long.toString(warehouseRole));
        assertThat(strings(principal.get("permissions"))).containsExactly("ai:use", "warehouse:view");
        assertThat(strings(principal.get("departmentIds"))).containsExactly(Long.toString(activeDepartment));

        var otherPrincipal = data(signedGet(CONTEXT, alternateCompany), 200);
        assertThat(strings(otherPrincipal.get("departmentIds"))).containsExactly(Long.toString(otherDepartment));
        data(signedGet(CONTEXT, unassignedCompany), 403);
        data(signedGet(CONTEXT, foreignCompany), 403);

        jdbc.update("UPDATE org_department SET status='disabled' WHERE id=?", activeDepartment);
        jdbc.update("UPDATE sys_role SET status='disabled' WHERE id=?", warehouseRole);
        principal = data(signedGet(CONTEXT, company), 200);
        assertThat(strings(principal.get("roleIds"))).containsExactly(Long.toString(aiRole));
        assertThat(strings(principal.get("permissions"))).containsExactly("ai:use");
        assertThat(strings(principal.get("departmentIds"))).isEmpty();

        jdbc.update("UPDATE company SET status='disabled' WHERE id=?", alternateCompany);
        data(signedGet(CONTEXT, alternateCompany), 403);
        jdbc.update("UPDATE sys_role SET status='disabled' WHERE id=?", aiRole);
        data(signedGet(CONTEXT, company), 403);
    }

    @Test
    void signatureBindsSessionMethodPathCompanyAndCannotBeReplayed() throws Exception {
        data(request(CONTEXT).header("Cookie", cookie).header("X-Company-Id", Long.toString(company)).GET().build(), 403);
        data(sign(request(CONTEXT), "GET", CONTEXT, company, cookie, now()).GET().build(), 401);

        HttpRequest once = signedGet(CONTEXT, company);
        data(once, 200);
        data(once, 403);
        data(sign(request(CONTEXT).header("Cookie", cookie), "GET", CONTEXT, company, cookie, now() - 120_000)
                .GET().build(), 403);
        data(sign(request(CONTEXT).header("Cookie", cookie), "GET", CONTEXT, company, cookie, now() + 120_000)
                .GET().build(), 403);
        data(sign(request(CONTEXT).header("Cookie", cookie), "GET", CONTEXT, company, cookie + "; ignored=1", now())
                .GET().build(), 403);
        data(sign(request(TOOL).header("Cookie", cookie), "GET", CONTEXT, company, cookie, now())
                .GET().build(), 403);
        data(sign(request(CONTEXT).header("Cookie", cookie), "GET", CONTEXT, company, cookie, now())
                .header("X-Company-Id", Long.toString(alternateCompany)).GET().build(), 403);
        data(sign(request(CONTEXT).header("Cookie", cookie), "POST", CONTEXT, company, cookie, now())
                .GET().build(), 403);
    }

    @Test
    void postContextRequiresErpCsrfAndToolRejectsParametersAndMutations() throws Exception {
        data(signedPost(CONTEXT, CONTEXT, null, null), 403);
        data(signedPost(CONTEXT, CONTEXT, UUID.randomUUID().toString(), null), 403);
        data(signedPost(CONTEXT, CONTEXT, csrf, null), 200);
        data(signedPost(CONTEXT, CONTEXT, csrf, "{}"), 400);
        data(signedPost(CONTEXT + "?role=1", CONTEXT, csrf, null), 400);
        data(signedPost(TOOL, TOOL, csrf, null), 405);
        data(sign(request(TOOL + "?companyId=1").header("Cookie", cookie), "GET", TOOL, company, cookie, now())
                .GET().build(), 400);
        assertThat(send(request(LOGOUT).header("Cookie", cookie).header("X-XSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.noBody()).build()).statusCode()).isEqualTo(200);
        data(signedGet(CONTEXT, company), 401);
    }

    @Test
    void warehouseToolRetainsBusinessPermissionsAndRecordsOnlyAuditMetadata() throws Exception {
        var original = data(request("/api/v1/warehouses?status=active").header("Cookie", cookie)
                .header("X-Company-Id", Long.toString(company)).GET().build(), 200);
        var tool = data(signedGet(TOOL, company), 200);
        assertThat(tool.get("items").size()).isEqualTo(1);
        assertThat(tool.get("items").get(0)).isEqualTo(json.createObjectNode()
                .put("id", Long.toString(visibleWarehouse)).put("code", "W1").put("name", "测试仓库W1"));
        assertThat(original.get("items").get(0).get("id")).isEqualTo(tool.get("items").get(0).get("id"));
        assertThat(tool.get("hasMore").asBoolean()).isFalse();
        data(signedGet(TOOL, foreignCompany), 403);

        jdbc.update("UPDATE sys_role SET status='disabled' WHERE id=?", warehouseRole);
        data(signedGet(TOOL, company), 403);
        var outcomes = jdbc.queryForList("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(after_data,'$.outcome')) FROM audit_log
                WHERE tenant_id=? AND company_id=? AND operator_id=? AND action='ai.tool.list_warehouses'
                  AND before_data IS NULL AND JSON_LENGTH(after_data)=1
                ORDER BY id
                """, String.class, tenant, company, user);
        assertThat(outcomes).containsExactly("success", "denied");
    }

    @Test
    void queryBindsExactBodyAndChecksCsrfAndCurrentPermissions() throws Exception {
        String path = "/api/v1/ai-tools/query/document_search";
        String scopePath = "/api/v1/ai-tools/scope/document_search";
        String body = "{\"type\":\"sales-orders\"}";
        String forbiddenInput = "{\"type\":\"sales-orders\",\"companyId\":\"1\"}";
        addRole(Ids.next(), "SALES_READER", "active", "sales_order:view");
        var good = signedQuery(path,company,body,body,csrf);
        JsonNode allowed = data(good,200);
        assertThat(allowed.path("items").size()).isZero();
        String fingerprint = allowed.path("scopeFingerprint").asString();
        assertThat(fingerprint).matches("[0-9a-f]{64}");
        assertThat(data(signedGet(scopePath,company),200).path("scopeFingerprint").asString()).isEqualTo(fingerprint);
        data(good,403);
        data(signedQuery(path,company,body,forbiddenInput,csrf),403);
        data(signedQuery(path,company,body,body,null),403);
        data(signedQuery(path,company,forbiddenInput,forbiddenInput,csrf),400);
        data(signedQuery(path,foreignCompany,body,body,csrf),403);
        data(signedQuery(path,unassignedCompany,body,body,csrf),403);
        jdbc.update("UPDATE sys_role SET status='disabled' WHERE tenant_id=? AND code='SALES_READER'",tenant);
        data(signedQuery(path,company,body,body,csrf),403);
        data(signedGet(scopePath,company),403);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND action='ai.tool.document_search'
                  AND before_data IS NULL AND JSON_EXTRACT(after_data,'$.outcome') IS NOT NULL
                  AND JSON_EXTRACT(after_data,'$.query') IS NULL
                """,Long.class,tenant)).isEqualTo(3L);
    }

    private void addCompany(long tenantId, long companyId, String code) {
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,?,?)",
                companyId, tenantId, code, "测试公司" + code);
    }

    private void addRole(long roleId, String code, String status, String permission) {
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name,status) VALUES(?,?,?,'测试角色',?)",
                roleId, tenant, code, status);
        jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",
                Ids.next(), tenant, user, roleId);
        assertThat(jdbc.update("""
                INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
                SELECT ?,?,?,id FROM sys_permission WHERE code=?
                """, Ids.next(), tenant, roleId, permission)).isEqualTo(1);
    }

    private void addWarehouse(long warehouseId, long companyId, String code, String status) {
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name,address,status) VALUES(?,?,?,?,?,'测试地址',?)",
                warehouseId, tenant, companyId, code, "测试仓库" + code, status);
    }

    private void addCompanyWarehouseScope() {
        jdbc.update("""
                INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id,warehouse_id)
                VALUES(?,?,?,'warehouse',?,?),(?,?,?,'warehouse',?,?),(?,?,?,'company',?,NULL)
                """, Ids.next(), tenant, user, company, visibleWarehouse,
                Ids.next(), tenant, user, company, disabledWarehouse, Ids.next(), tenant, user, alternateCompany);
    }

    private void addDepartments() {
        jdbc.update("INSERT INTO org_user_company(tenant_id,company_id,user_id) VALUES(?,?,?),(?,?,?)",
                tenant, company, user, tenant, alternateCompany, user);
        addDepartment(activeDepartment, company, "D1", "active");
        addDepartment(disabledDepartment, company, "D2", "disabled");
        addDepartment(otherDepartment, alternateCompany, "D3", "active");
    }

    private void addDepartment(long dept, long companyId, String code, String status) {
        jdbc.update("""
                INSERT INTO org_department(id,tenant_id,company_id,code,name,path,depth,status)
                VALUES(?,?,?,?,'测试部门',?,1,?)
                """, dept, tenant, companyId, code, "/" + dept + "/", status);
        jdbc.update("INSERT INTO org_user_department(tenant_id,company_id,user_id,department_id) VALUES(?,?,?,?)",
                tenant, companyId, user, dept);
    }

    private String tenantCode() {
        return "T" + tenant;
    }

    private void login(String password) throws Exception {
        var form = json.createObjectNode().put("tenantCode", tenantCode()).put("username", "ai_reader")
                .put("password", password);
        var response = send(request(LOGIN).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString())).build());
        assertThat(response.statusCode()).isEqualTo(200);
        var basePathCookies = response.headers().allValues("Set-Cookie").stream()
                .flatMap(header -> HttpCookie.parse(header).stream())
                .filter(value -> "/".equals(value.getPath())).map(HttpCookie::getName).toList();
        assertThat(basePathCookies).contains("ERPSESSION", "XSRF-TOKEN");
        String session = responseCookie(response, "ERPSESSION");
        csrf = responseCookie(response, "XSRF-TOKEN");
        cookie = "ERPSESSION=" + session + "; XSRF-TOKEN=" + csrf;
    }

    private static String responseCookie(HttpResponse<String> response, String name) {
        String prefix = name + "=";
        return response.headers().allValues("Set-Cookie").stream().filter(header -> header.startsWith(prefix))
                .map(header -> header.split(";", 2)[0].substring(prefix.length()))
                .filter(value -> !value.isEmpty()).reduce((earlier, later) -> later).orElseThrow();
    }

    private HttpRequest signedGet(String path, long companyId) throws Exception {
        return sign(request(path).header("Cookie", cookie), "GET", path, companyId, cookie, now()).GET().build();
    }

    private HttpRequest signedPost(String uri, String path, String csrfToken, String body) throws Exception {
        var builder = sign(request(uri).header("Cookie", cookie), "POST", path, company, cookie, now());
        if (csrfToken != null) {
            builder.header("X-XSRF-TOKEN", csrfToken);
        }
        return builder.POST(body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private static HttpRequest.Builder sign(HttpRequest.Builder builder, String method, String path, long company,
                                            String cookie, long timestamp) throws Exception {
        return sign(builder,method,path,company,cookie,timestamp,null);
    }

    private HttpRequest signedQuery(String uri,long companyId,String signedBody,String sentBody,String csrfToken) throws Exception {
        var builder=sign(request(uri).header("Cookie",cookie).header("Content-Type","application/json"),
                "POST",uri,companyId,cookie,now(),signedBody);
        if(csrfToken!=null) builder.header("X-XSRF-TOKEN",csrfToken);
        return builder.POST(HttpRequest.BodyPublishers.ofString(sentBody)).build();
    }

    private static HttpRequest.Builder sign(HttpRequest.Builder builder, String method, String path, long company,
                                             String cookie, long timestamp, String body) throws Exception {
        String nonce = UUID.randomUUID().toString().replace("-", "");
        var hex = HexFormat.of();
        String digest = hex.formatHex(MessageDigest.getInstance("SHA-256").digest(cookie.getBytes(StandardCharsets.UTF_8)));
        String payload = String.join("\n", Long.toString(timestamp), nonce, method, path, Long.toString(company), digest);
        if(body!=null) payload+="\n"+hex.formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return builder.header("X-Company-Id", Long.toString(company))
                .header("X-Erp-Ai-Timestamp", Long.toString(timestamp)).header("X-Erp-Ai-Nonce", nonce)
                .header("X-Erp-Ai-Signature", hex.formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode data(HttpRequest request, int expectedStatus) throws Exception {
        var response = send(request);
        var body = json.readTree(response.body());
        assertThat(response.statusCode()).as("%s %s", request.uri().getPath(), body.path("errorCode").asString())
                .isEqualTo(expectedStatus);
        return body.get("data");
    }

    private static List<String> strings(JsonNode node) {
        return node.values().stream().map(JsonNode::asString).toList();
    }

    private static String newSigningKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
