package cn.erp.identity;

import cn.erp.shared.Ids;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.verify;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "ERP_LOGIN_SETTINGS_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "ERP_LOGIN_SMTP_ALLOWED_HOSTS=smtp.example.test"
})
class AlternativeLoginIT {
    private static final String LEGACY_MOBILE = "+8613800138000";
    private static final String PHONE = "+8613900139000";
    private static final String EMAIL = "buyer@example.test";
    private static final String ACCOUNT = "/account/login-methods";
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired LoginSettingsService settingsService;
    @MockitoBean LoginCodeDelivery delivery;

    private final long tenant = Ids.next();
    private final long admin = Ids.next();
    private final long buyer = Ids.next();
    private final long role = Ids.next();
    private final long roleManager = Ids.next();
    private final String tenantCode = "AUTH" + tenant;
    private final String password = UUID.randomUUID().toString();
    private final Map<String,CompletableFuture<String>> issued = new ConcurrentHashMap<>();

    @BeforeEach
    void setup() {
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'登录演练企业')", tenant, tenantCode);
        String hash = passwords.encode(password);
        jdbc.update("""
                INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash,mobile)
                VALUES(?,?,'boss','管理员',?,NULL),(?,?,'buyer','员工',?,?)
                """, admin, tenant, hash, buyer, tenant, hash, LEGACY_MOBILE);
        jdbc.update("""
                INSERT INTO sys_role(id,tenant_id,code,name)
                VALUES(?,?,'SYSTEM_ADMIN','系统管理员'),(?,?,'ROLE_MANAGER','角色管理员')
                """, role, tenant, roleManager, tenant);
        jdbc.update("""
                INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?),(?,?,?,?)
                """, Ids.next(), tenant, admin, role, Ids.next(), tenant, buyer, roleManager);
        grant(role, "tenant:login_settings");
        grant(role, "audit:view");
        grant(roleManager, "role:assign_permission");
        doAnswer(call -> {
            issued.computeIfAbsent(call.getArgument(2), key -> new CompletableFuture<>()).complete(call.getArgument(3));
            return null;
        }).when(delivery).send(anyString(), any(LoginSettingsService.Provider.class), anyString(), anyString());
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM audit_log WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM user_login_identity WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM tenant_login_provider WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM sys_user_role WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM sys_role WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM sys_user WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM tenant WHERE id=?", tenant);
    }

    @Test
    void legacyMigrationKeepsAConcurrentAdministratorUpdate() throws Exception {
        enable(login("boss"));
        var cipher = new LoginSettingsCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        String legacy = LoginSecretFixtures.legacySecret(new byte[32], tenant, "sms", "旧版短信密钥");
        jdbc.update("UPDATE tenant_login_provider SET secret_ciphertext=? WHERE tenant_id=? AND method='sms'",
                legacy, tenant);
        long version = jdbc.queryForObject(
                "SELECT version FROM tenant_login_provider WHERE tenant_id=? AND method='sms'", Long.class, tenant);
        String changed = cipher.encrypt(tenant, "sms", "管理员并发更新的密钥");
        var racingJdbc = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public int update(String sql, Object... arguments) {
                jdbc.update("""
                        UPDATE tenant_login_provider SET secret_ciphertext=?,version=version+1
                        WHERE tenant_id=? AND method='sms'
                        """, changed, tenant);
                return super.update(sql, arguments);
            }
        };
        var racingSettings = new LoginSettingsService(racingJdbc, json, cipher, null, "smtp.example.test");

        racingSettings.provider(tenant, "sms");

        assertEquals(changed, jdbc.queryForObject(
                "SELECT secret_ciphertext FROM tenant_login_provider WHERE tenant_id=? AND method='sms'", String.class, tenant));
        assertEquals(version + 1, settingsService.provider(tenant, "sms").version());
        assertEquals("管理员并发更新的密钥", settingsService.provider(tenant, "sms").secret());
    }

    @Test
    void oldProviderIsReencryptedOnUseWithoutChangingConfigurationVersion() throws Exception {
        enable(login("boss"));
        String legacy = LoginSecretFixtures.legacySecret(new byte[32], tenant, "sms", "旧版短信密钥");
        jdbc.update("UPDATE tenant_login_provider SET secret_ciphertext=? WHERE tenant_id=? AND method='sms'",
                legacy, tenant);
        long version = jdbc.queryForObject(
                "SELECT version FROM tenant_login_provider WHERE tenant_id=? AND method='sms'", Long.class, tenant);

        var provider = settingsService.provider(tenant, "sms");

        assertEquals("旧版短信密钥", provider.secret());
        assertEquals(version, provider.version());
        var migrated = jdbc.queryForMap(
                "SELECT secret_ciphertext,version FROM tenant_login_provider WHERE tenant_id=? AND method='sms'", tenant);
        assertTrue(migrated.get("secret_ciphertext").toString().startsWith("2."));
        assertEquals(version, ((Number) migrated.get("version")).longValue());
        assertEquals("旧版短信密钥", settingsService.provider(tenant, "sms").secret());
    }

    @Test
    void methodsDefaultOffAndConfigurationIsPrivilegedAndWriteOnly() throws Exception {
        var anonymous = new Browser();
        assertFalse(anonymous.call("GET", "/auth/methods?tenantCode=" + tenantCode, null, false, 200)
                .path("data").path("sms").asBoolean());
        anonymous.call("GET", "/login-settings", null, false, 401);
        var ordinary = login("buyer");
        assertEquals("role:assign_permission", ordinary.call("GET", "/auth/me", null, false, 200)
                .path("data").path("permissions").get(0).asString());
        ordinary.call("GET", "/login-settings", null, false, 403);
        ordinary.call("PUT", "/login-settings", Map.of("sms", sms(true)), true, 403);
        ordinary.call("PUT", "/roles/" + roleManager + "/permissions",
                Map.of("version", 0, "permissionCodes", java.util.List.of("tenant:login_settings")), true, 403);
        var privileged = login("boss");
        var settings = enable(privileged);
        assertTrue(settings.path("data").path("email").path("secretConfigured").asBoolean());
        assertFalse(settings.path("data").path("email").has("secret"));
        assertTrue(anonymous.call("GET", "/auth/methods?tenantCode=" + tenantCode, null, false, 200)
                .path("data").path("email").asBoolean());
        assertFalse(anonymous.call("GET", "/auth/methods?tenantCode=missing", null, false, 200)
                .path("data").path("sms").asBoolean());
        assertNotEquals("test-sms-private", jdbc.queryForObject(
                "SELECT secret_ciphertext FROM tenant_login_provider WHERE tenant_id=? AND method='sms'", String.class, tenant));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND CAST(after_data AS CHAR) LIKE '%test-sms-private%'
                """, Integer.class, tenant));
        String recorded = privileged.call("GET", "/audit-logs?action=login_settings.update", null, false, 200)
                .path("data").path("items").get(0).path("createdAt").asString();
        assertTrue(recorded.endsWith("Z"));
        assertDoesNotThrow(() -> Instant.parse(recorded));
    }

    private void grant(long roleId, String permission) {
        jdbc.update("""
                INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
                SELECT ?,?,?,id FROM sys_permission WHERE code=?
                """, Ids.next(), tenant, roleId, permission);
    }

    @Test
    void bindingRequiresExistingPasswordAndASeparateOneTimeCode() throws Exception {
        enable(login("boss"));
        var user = login("buyer");
        new Browser().call("POST", "/auth/code/send", address("sms", LEGACY_MOBILE), false, 200);
        verify(delivery, after(200).never()).send(anyString(), any(), eq(LEGACY_MOBILE), anyString());
        var request = Map.of("method", "sms", "destination", "13900139000", "password", password);
        user.call("POST", ACCOUNT + "/code/send", request, false, 403);
        user.call("POST", ACCOUNT + "/code/send",
                Map.of("method", "sms", "destination", PHONE, "password", "错误密码"), true, 401);
        user.call("POST", ACCOUNT + "/code/send", request, true, 200);
        String code = issuedCode(PHONE);
        new Browser().call("POST", "/auth/code/login", withCode("sms", PHONE, code), false, 401);
        var proof = Map.of("method", "sms", "destination", PHONE, "code", code);
        login("buyer").call("PUT", ACCOUNT + "/code", proof, true, 401);
        user.call("PUT", ACCOUNT + "/code", proof, true, 200);
        user.call("PUT", ACCOUNT + "/code", proof, true, 401);
        assertEquals("+86139****9000", user.call("GET", ACCOUNT, null, false, 200).path("data").path("sms").asString());
        user.call("DELETE", ACCOUNT + "/sms", Map.of("password", password), true, 200);
        assertTrue(user.call("GET", ACCOUNT, null, false, 200).path("data").path("sms").isNull());
    }

    @Test
    void smsAndEmailOpenOrdinarySessionsAndCannotBeReplayed() throws Exception {
        enable(login("boss"));
        bind("sms", PHONE);
        bind("email", EMAIL);
        for (var entry : Map.of("sms", PHONE, "email", EMAIL).entrySet()) {
            String method = entry.getKey(), destination = entry.getValue();
            var anonymous = new Browser();
            anonymous.call("POST", "/auth/code/send", address(method, destination), false, 200);
            anonymous.call("POST", "/auth/code/send", address(method, destination), false, 429);
            String code = issuedCode(destination);
            var data = anonymous.call("POST", "/auth/code/login", withCode(method, destination, code), false, 200).path("data");
            assertEquals(Long.toString(buyer), data.path("id").asString());
            assertEquals("buyer", anonymous.call("GET", "/auth/me", null, false, 200).path("data").path("username").asString());
            new Browser().call("POST", "/auth/code/login", withCode(method, destination, code), false, 401);
            anonymous.call("POST", "/auth/logout", Map.of(), true, 200);
        }
    }

    @Test
    void previouslyIssuedCodeCannotBypassLockOrFailingAttemptLimit() throws Exception {
        enable(login("boss"));
        bind("sms", PHONE);
        bind("email", EMAIL);
        var browser = new Browser();
        browser.call("POST", "/auth/code/send", address("sms", PHONE), false, 200);
        String phoneCode = issuedCode(PHONE);
        jdbc.update("UPDATE sys_user SET locked_until=UTC_TIMESTAMP(3)+INTERVAL 2 MINUTE WHERE id=?", buyer);
        browser.call("POST", "/auth/code/login", withCode("sms", PHONE, phoneCode), false, 401);
        jdbc.update("UPDATE sys_user SET locked_until=NULL WHERE id=?", buyer);
        browser.call("POST", "/auth/code/send", address("email", EMAIL), false, 200);
        String real = issuedCode(EMAIL);
        String wrong = "000000".equals(real) ? "999999" : "000000";
        for (int attempt = 0; attempt < 5; attempt++) {
            browser.call("POST", "/auth/code/login", withCode("email", EMAIL, wrong), false, 401);
        }
        browser.call("POST", "/auth/code/login", withCode("email", EMAIL, real), false, 401);
    }

    private void bind(String method, String subject) {
        jdbc.update("INSERT INTO user_login_identity(id,tenant_id,user_id,method,subject) VALUES(?,?,?,?,?)",
                Ids.next(), tenant, buyer, method, subject);
    }

    private String issuedCode(String destination) throws Exception {
        return issued.computeIfAbsent(destination, key -> new CompletableFuture<>()).get(5, TimeUnit.SECONDS);
    }

    private Browser login(String username) throws Exception {
        var browser = new Browser();
        browser.call("POST", "/auth/login", Map.of("tenantCode", tenantCode, "username", username, "password", password), false, 200);
        return browser;
    }

    private JsonNode enable(Browser browser) throws Exception {
        return browser.call("PUT", "/login-settings", Map.of("sms", sms(true), "email", Map.of(
                "enabled", true, "host", "smtp.example.test", "port", 587, "security", "starttls",
                "username", "no-reply@example.test", "from", "no-reply@example.test", "secret", "test-email-private")), true, 200);
    }

    private Map<String,Object> sms(boolean enabled) {
        return Map.of("enabled", enabled, "accessKeyId", "test_sms_id", "signName", "短信测试",
                "templateCode", "SMS_12345", "secret", "test-sms-private");
    }

    private Map<String,String> address(String method, String destination) {
        return Map.of("tenantCode",tenantCode,"method",method,"destination",destination);
    }

    private Map<String,String> withCode(String method, String destination, String code) {
        return Map.of("tenantCode",tenantCode,"method",method,"destination",destination,"code",code);
    }

    private class Browser {
        private final CookieManager cookies = new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();

        JsonNode call(String method, String path, Object data, boolean csrf, int status) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path))
                    .header("Content-Type","application/json").method(method, data == null
                            ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(data)));
            if (csrf) {
                String token = cookies.getCookieStore().getCookies().stream().filter(cookie -> "XSRF-TOKEN".equals(cookie.getName()))
                        .map(HttpCookie::getValue).findFirst().orElseThrow();
                builder.header("X-XSRF-TOKEN", token);
            }
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            var payload = json.readTree(response.body());
            assertEquals(status, response.statusCode(), () -> path + " " + payload.path("message").asString());
            return payload;
        }
    }
}
