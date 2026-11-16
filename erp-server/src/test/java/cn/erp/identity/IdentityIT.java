package cn.erp.identity;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "springdoc.api-docs.enabled=true")
class IdentityIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired CredentialsService credentials;
    @Autowired ObjectMapper json;

    private final long tenantId = Ids.next();
    private final long userId = Ids.next();
    private final long companyId = Ids.next();
    private final String tenantCode = "T" + tenantId;
    private final String password = UUID.randomUUID().toString();
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();

    @BeforeEach
    void prepareAccount() {
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'测试企业')", tenantId, tenantCode);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'C','测试公司')", companyId, tenantId);
        jdbc.update("""
                INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash,must_change_password)
                VALUES(?,?,'buyer','采购员',?,1)
                """, userId, tenantId, passwords.encode(password));
        jdbc.update("""
                INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id)
                VALUES(?,?,?,'company',?)
                """, Ids.next(), tenantId, userId, companyId);
    }

    @AfterEach
    void removeFixture() {
        jdbc.update("DELETE FROM sys_user_data_scope WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM sys_user_role WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM sys_role WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM sys_user WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM warehouse WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM company WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM tenant WHERE id = ?", tenantId);
    }

    @Test
    void loginChangePasswordAndLogout() throws Exception {
        var form = json.createObjectNode().put("tenantCode", tenantCode).put("username", "buyer").put("password", password);
        var profile = request("POST", "/auth/login", form.toString(), false, 200).get("data");
        assertEquals(Long.toString(userId), profile.get("id").asString());
        assertTrue(profile.get("mustChangePassword").asBoolean());
        assertEquals(Long.toString(companyId), profile.get("companies").get(0).get("id").asString());
        assertEquals("PASSWORD_CHANGE_REQUIRED",
                request("GET", "/warehouses", null, false, 403).get("errorCode").asString());
        var change = json.createObjectNode().put("oldPassword", password).put("newPassword", UUID.randomUUID().toString());
        request("PUT", "/auth/password", change.toString(), true, 200);
        assertFalse(request("GET", "/auth/me", null, false, 200).get("data").get("mustChangePassword").asBoolean());
        request("POST", "/auth/logout", "{}", true, 200);
        request("GET", "/auth/me", null, false, 401);
    }

    @Test
    void sameSessionReflectsRoleAndCompanyRevocationOnNextRequest() throws Exception {
        long roleId = Ids.next(), restrictedCompany = Ids.next(), firstWarehouse = Ids.next(), secondWarehouse = Ids.next();
        jdbc.update("UPDATE sys_user SET must_change_password = 0 WHERE id = ?", userId);
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'WAREHOUSE','仓库查看员')", roleId, tenantId);
        jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)", Ids.next(), tenantId, userId, roleId);
        jdbc.update("""
                INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
                SELECT ?,?,?,id FROM sys_permission WHERE code='warehouse:view'
                """, Ids.next(), tenantId, roleId);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'R','按仓分配公司')", restrictedCompany, tenantId);
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'A','甲仓'),(?,?,?,'B','乙仓')",
                firstWarehouse, tenantId, restrictedCompany, secondWarehouse, tenantId, restrictedCompany);
        jdbc.update("""
                INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id,warehouse_id)
                VALUES(?,?,?,'warehouse',?,?),(?,?,?,'warehouse',?,?)
                """, Ids.next(), tenantId, userId, restrictedCompany, secondWarehouse,
                Ids.next(), tenantId, userId, restrictedCompany, firstWarehouse);

        var form = json.createObjectNode().put("tenantCode", tenantCode).put("username", "buyer").put("password", password);
        var profile = request("POST", "/auth/login", form.toString(), false, 200).get("data");
        assertEquals("warehouse:view", profile.get("permissions").get(0).asString());
        assertTrue(profile.get("companies").get(0).get("allWarehouses").asBoolean());
        var restricted = profile.get("companies").get(1);
        assertEquals(Long.toString(restrictedCompany), restricted.get("id").asString());
        assertFalse(restricted.get("allWarehouses").asBoolean());
        assertEquals(Long.toString(firstWarehouse), restricted.get("warehouseIds").get(0).asString());
        assertEquals(Long.toString(secondWarehouse), restricted.get("warehouseIds").get(1).asString());

        // 重复执行同一查询后撤权，验证驱动缓存继续读取本次请求的授权状态。
        for (int attempt = 0; attempt < 10; attempt++) {
            var repeated = request("GET", "/auth/me", null, false, 200).get("data");
            assertEquals("warehouse:view", repeated.get("permissions").get(0).asString());
            assertEquals(2, repeated.get("companies").size());
        }
        jdbc.update("UPDATE sys_role SET status = 'disabled' WHERE id = ?", roleId);
        jdbc.update("UPDATE company SET status = 'disabled' WHERE id = ?", companyId);
        profile = request("GET", "/auth/me", null, false, 200).get("data");
        assertEquals(0, profile.get("permissions").size());
        assertEquals(1, profile.get("companies").size());
        assertEquals(Long.toString(restrictedCompany), profile.get("companies").get(0).get("id").asString());
        jdbc.update("UPDATE sys_user SET status = 'disabled' WHERE id = ?", userId);
        request("GET", "/auth/me", null, false, 401);
    }

    @Test
    void fiveFailuresLockAccountUntilDeadline() {
        for (int i = 1; i <= 5; i++) {
            var error = assertThrows(ApiException.class,
                    () -> credentials.authenticate(tenantCode, "buyer", "错误密码"));
            assertEquals(i == 5 ? "ACCOUNT_LOCKED" : "AUTH_FAILED", error.getErrorCode());
        }
        assertEquals("ACCOUNT_LOCKED", assertThrows(ApiException.class,
                () -> credentials.authenticate(tenantCode, "buyer", password)).getErrorCode());
        jdbc.update("UPDATE sys_user SET locked_until = UTC_TIMESTAMP(3) - INTERVAL 1 SECOND WHERE id = ?", userId);
        assertEquals(userId, credentials.authenticate(tenantCode, "buyer", password).userId());
    }

    @Test
    void apiSpecificationUsesControllerRoutesAndSession() throws Exception {
        URI address = URI.create("http://127.0.0.1:" + port + "/v3/api-docs");
        var get = HttpRequest.newBuilder(address).GET().build();
        assertEquals(401, client.send(get, HttpResponse.BodyHandlers.discarding()).statusCode());
        jdbc.update("UPDATE sys_user SET must_change_password = 0 WHERE id = ?", userId);
        var form = json.createObjectNode().put("tenantCode", tenantCode).put("username", "buyer").put("password", password);
        request("POST", "/auth/login", form.toString(), false, 200);
        var result = client.send(get, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        JsonNode spec = json.readTree(result.body());
        assertTrue(spec.get("openapi").asString().startsWith("3."));
        assertTrue(spec.get("paths").get("/api/v1/auth/login").has("post"));
        assertTrue(spec.get("paths").get("/api/v1/warehouses/{id}").has("put"));
        assertFalse(spec.get("paths").has("/actuator/health"));
        request("POST", "/auth/logout", "{}", true, 200);
    }

    private JsonNode request(String method, String path, String body, boolean csrf, int status) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (csrf) {
            String xsrf = cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("XSRF-TOKEN"))
                    .map(HttpCookie::getValue).findFirst().orElseThrow();
            req.header("X-XSRF-TOKEN", xsrf);
        }
        var result = client.send(req.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode payload = json.readTree(result.body());
        assertEquals(status, result.statusCode(), () -> path + " " + payload.get("message"));
        return payload;
    }
}
