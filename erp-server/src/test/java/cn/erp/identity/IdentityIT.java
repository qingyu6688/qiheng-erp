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
        jdbc.update("DELETE FROM sys_user WHERE tenant_id = ?", tenantId);
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
