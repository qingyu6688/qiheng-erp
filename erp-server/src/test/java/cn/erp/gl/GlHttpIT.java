package cn.erp.gl;

import java.net.*;
import java.net.http.*;
import java.time.LocalDate;
import java.util.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import cn.erp.shared.Ids;

import static cn.erp.gl.GlTestData.key;
import static cn.erp.gl.GlSupport.number;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GlHttpIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired GlBookService books;
    @Autowired GlVoucherService vouchers;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    GlTestData d;
    long role;
    String password;
    CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();

    @BeforeEach
    void setup() {
        d = new GlTestData(jdbc);
        password = key() + "Aa!";
        jdbc.update(
                "UPDATE sys_user SET password_hash=? WHERE tenant_id=?",
                passwords.encode(password),
                d.tenant);
        role = Ids.next();
        jdbc.update(
                "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'FINANCE','财务验收角色')",
                role,
                d.tenant);
        jdbc.update(
                "INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",
                Ids.next(),
                d.tenant,
                d.maker,
                role);
        for (long id : jdbc.queryForList("SELECT id FROM sys_permission", Long.class))
            jdbc.update(
                    "INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)"
                            + " VALUES(?,?,?,?)",
                    Ids.next(),
                    d.tenant,
                    role,
                    id);
        jdbc.update(
                "INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id)"
                        + " VALUES(?,?,?,'company',?)",
                Ids.next(),
                d.tenant,
                d.maker,
                d.other);
        books.create(d.actor(d.maker), key(), new GlModels.Book("enterprise", d.october, null, 0L));
        var book =
                books.confirm(
                        d.actor(d.reviewer), key(), new GlModels.Confirmation(0L, "本机接口验收口径"));
        books.activate(
                d.actor(d.maker),
                "activate",
                key(),
                new GlModels.Version(number(book.get("version")), null));
    }

    @AfterEach
    void cleanup() {
        cookies.getCookieStore().removeAll();
        if (d != null) d.cleanup();
    }

    HttpResponse<String> request(
            String method, String path, Object body, boolean csrf, long company, String key)
            throws Exception {
        var b =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                        .header("Content-Type", "application/json")
                        .header("X-Company-Id", "" + company);
        if (key != null) b.header("Idempotency-Key", key);
        if (csrf)
            b.header(
                    "X-XSRF-TOKEN",
                    cookies.getCookieStore().getCookies().stream()
                            .filter(c -> c.getName().equals("XSRF-TOKEN"))
                            .map(HttpCookie::getValue)
                            .findFirst()
                            .orElseThrow());
        return client.send(
                b.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(
                                                json.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    void login() throws Exception {
        assertThat(
                        request(
                                        "POST",
                                        "/auth/login",
                                        Map.of(
                                                "tenantCode",
                                                "GL" + d.tenant,
                                                "username",
                                                "U" + d.maker,
                                                "password",
                                                password),
                                        false,
                                        d.company,
                                        null)
                                .statusCode())
                .isEqualTo(200);
    }

    GlModels.Voucher input() {
        return new GlModels.Voucher(
                d.word(),
                LocalDate.of(2026, 10, 15),
                0,
                List.of(
                        new GlModels.Line(
                                "测试费用", d.subject("6602"), "D", "10", "0", null, null, null),
                        new GlModels.Line(
                                "其他应付", d.subject("2241"), "C", "10", "0", null, null, null)),
                null,
                null);
    }

    @Test
    void authenticationCsrfAndLongIdsSurviveHttp() throws Exception {
        assertThat(request("GET", "/gl/book", null, false, d.company, null).statusCode())
                .isEqualTo(401);
        login();
        assertThat(request("GET", "/ar/metadata", null, false, d.company, null).statusCode())
                .isEqualTo(200);
        assertThat(request("GET", "/ar/bad-debts/items", null, false, d.company, null).statusCode())
                .isEqualTo(200);
        assertThat(request("GET", "/gl/sync/errors", null, false, d.company, null).statusCode())
                .isEqualTo(200);
        assertThat(
                        request(
                                        "POST",
                                        "/gl/sync",
                                        Map.of("periodId", d.october),
                                        true,
                                        d.company,
                                        key())
                                .statusCode())
                .isEqualTo(200);

        assertThat(request("POST", "/gl/vouchers", input(), false, d.company, key()).statusCode())
                .isEqualTo(403);
        var response = request("POST", "/gl/vouchers", input(), true, d.company, key());
        assertThat(response.statusCode()).isEqualTo(200);
        var data = json.readTree(response.body()).get("data");
        assertThat(data.get("id").isString()).isTrue();
        assertThat(data.get("lines").get(0).get("subjectId").isString()).isTrue();
        assertThat(
                        request(
                                        "GET",
                                        "/gl/ledgers/balance?periodFrom="
                                                + d.october
                                                + "&periodTo="
                                                + d.october,
                                        null,
                                        false,
                                        d.company,
                                        null)
                                .statusCode())
                .isEqualTo(200);
    }

    @Test
    void anotherCompanyCannotReadVoucherOrUseItsWord() throws Exception {
        long id = number(vouchers.save(d.actor(d.maker), null, key(), input()).get("id"));
        long otherPeriod = Ids.next();
        jdbc.update(
                "INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date)"
                        + " VALUES(?,?,?,'202610','2026-10-01','2026-10-31')",
                otherPeriod,
                d.tenant,
                d.other);
        var other =
                new cn.erp.identity.CompanyAccess(
                        d.tenant, d.maker, d.other, true, Set.of(), d.permissions);
        books.create(other, key(), new GlModels.Book("enterprise", otherPeriod, null, 0L));
        var book = books.confirm(other, key(), new GlModels.Confirmation(0L, "独立公司财务配置"));
        books.activate(
                other, "activate", key(), new GlModels.Version(number(book.get("version")), null));
        login();
        assertThat(request("GET", "/gl/vouchers/" + id, null, false, d.other, null).statusCode())
                .isEqualTo(404);
        assertThat(request("POST", "/gl/vouchers", input(), true, d.other, key()).statusCode())
                .isEqualTo(404);
    }

    @Test
    void revokedCostPermissionBlocksReadAndIdempotentReplay() throws Exception {
        login();
        String key = key();
        assertThat(request("POST", "/gl/vouchers", input(), true, d.company, key).statusCode())
                .isEqualTo(200);
        jdbc.update(
                "DELETE rp FROM sys_role_permission rp JOIN sys_permission p ON"
                        + " p.id=rp.permission_id WHERE rp.tenant_id=? AND rp.role_id=? AND"
                        + " p.code='cost:view'",
                d.tenant,
                role);
        assertThat(request("GET", "/gl/vouchers", null, false, d.company, null).statusCode())
                .isEqualTo(403);
        assertThat(request("POST", "/gl/vouchers", input(), true, d.company, key).statusCode())
                .isEqualTo(403);
        assertThat(
                        request(
                                        "GET",
                                        "/command-results?operation=gl_voucher.save&key=" + key,
                                        null,
                                        false,
                                        d.company,
                                        null)
                                .statusCode())
                .isEqualTo(403);
    }

    @Test
    void revokedFinancialPermissionCannotUseOldResult() throws Exception {
        login();
        String key = key();
        assertThat(request("POST", "/gl/vouchers", input(), true, d.company, key).statusCode())
                .isEqualTo(200);
        jdbc.update(
                "DELETE rp FROM sys_role_permission rp JOIN sys_permission p ON"
                        + " p.id=rp.permission_id WHERE rp.tenant_id=? AND rp.role_id=? AND"
                        + " p.code='gl_voucher:edit'",
                d.tenant,
                role);
        assertThat(request("POST", "/gl/vouchers", input(), true, d.company, key).statusCode())
                .isEqualTo(403);
    }

    @Test
    void financialViewerCanReadRequiredReferenceDataWithoutConfigurationAuthority()
            throws Exception {
        login();
        jdbc.update(
                "DELETE rp FROM sys_role_permission rp JOIN sys_permission p ON"
                        + " p.id=rp.permission_id WHERE rp.tenant_id=? AND rp.role_id=? AND p.code"
                        + " IN('gl_config:view','gl_config:edit')",
                d.tenant,
                role);
        assertThat(request("GET", "/gl/config/subjects", null, false, d.company, null).statusCode())
                .isEqualTo(200);
        assertThat(request("GET", "/gl/config/words", null, false, d.company, null).statusCode())
                .isEqualTo(200);
        assertThat(request("GET", "/gl/choices", null, false, d.company, null).statusCode())
                .isEqualTo(200);
        assertThat(request("GET", "/gl/config/mappings", null, false, d.company, null).statusCode())
                .isEqualTo(403);
        assertThat(
                        request(
                                        "POST",
                                        "/gl/config/digests",
                                        Map.of("content", "越权配置"),
                                        true,
                                        d.company,
                                        key())
                                .statusCode())
                .isEqualTo(403);
    }

    @Test
    void missingReceivableFieldsAndNullBatchRowsReturnValidationErrors() throws Exception {
        login();
        assertThat(
                        request(
                                        "GET",
                                        "/gl/ledgers/detail?periodFrom="
                                                + d.october
                                                + "&periodTo="
                                                + d.october
                                                + "&cursor="
                                                + Long.MAX_VALUE,
                                        null,
                                        false,
                                        d.company,
                                        null)
                                .statusCode())
                .isEqualTo(400);

        for (String path :
                List.of(
                        "/ar/credit-limits",
                        "/ar/credit-increases",
                        "/ar/collection-plans",
                        "/ar/bad-debts")) {
            var response = request("POST", path, Map.of(), true, d.company, key());
            assertThat(response.statusCode()).as(path).isEqualTo(400);
        }
        assertThat(
                        request(
                                        "POST",
                                        "/gl/vouchers/batch/approve",
                                        Map.of("vouchers", Arrays.asList((Object) null)),
                                        true,
                                        d.company,
                                        key())
                                .statusCode())
                .isEqualTo(400);
    }
    @Test void financialAuditSnapshotsRequireCurrentDomainAndCostPermissions() throws Exception {
        login();
        String id=json.readTree(request("POST","/gl/vouchers",input(),true,d.company,key()).body()).get("data").get("id").asString();
        String path="/audit-logs?entityType=gl_voucher&entityId="+id+"&companyId="+d.company;
        var visible=request("GET",path,null,false,d.company,null);
        assertThat(visible.statusCode()).isEqualTo(200);assertThat(json.readTree(visible.body()).get("data").get("items").size()).isEqualTo(1);
        jdbc.update("DELETE rp FROM sys_role_permission rp JOIN sys_permission p ON p.id=rp.permission_id WHERE rp.tenant_id=? AND rp.role_id=? AND p.code='gl_voucher:view'",d.tenant,role);
        assertThat(json.readTree(request("GET",path,null,false,d.company,null).body()).get("data").get("items").size()).isZero();
        jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT ?,?,?,id FROM sys_permission WHERE code='gl_voucher:view'",Ids.next(),d.tenant,role);
        jdbc.update("DELETE rp FROM sys_role_permission rp JOIN sys_permission p ON p.id=rp.permission_id WHERE rp.tenant_id=? AND rp.role_id=? AND p.code='cost:view'",d.tenant,role);
        assertThat(json.readTree(request("GET",path,null,false,d.company,null).body()).get("data").get("items").size()).isZero();
    }

}
