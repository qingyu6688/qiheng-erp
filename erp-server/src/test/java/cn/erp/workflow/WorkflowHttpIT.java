package cn.erp.workflow;

import java.net.*;
import java.net.http.*;
import java.util.*;

import org.flowable.engine.ProcessEngine;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import cn.erp.identity.*;
import cn.erp.salesorder.*;

import static cn.erp.workflow.WorkflowTestData.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WorkflowHttpIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessService access;
    @Autowired SalesOrderService sales;
    @Autowired WorkflowModels models;
    @Autowired ProcessEngine engine;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    WorkflowTestData d;
    final String password = key() + "Aa!";
    final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    final HttpClient client =
            HttpClient.newBuilder()
                    .cookieHandler(cookies)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    @BeforeEach
    void prepare() {
        d = new WorkflowTestData(jdbc, access, sales, models);
        jdbc.update(
                "UPDATE sys_user SET password_hash=? WHERE tenant_id=?",
                passwords.encode(password),
                d.tenant);
    }

    @AfterEach
    void cleanup() {
        cookies.getCookieStore().removeAll();
        if (d != null) d.cleanup(engine);
    }

    HttpResponse<String> request(
            String method, String path, Object body, boolean csrf, long company, String key)
            throws Exception {
        var builder =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                        .header("Content-Type", "application/json")
                        .header("X-Company-Id", Long.toString(company));
        if (key != null) builder.header("Idempotency-Key", key);
        if (csrf)
            builder.header(
                    "X-XSRF-TOKEN",
                    cookies.getCookieStore().getCookies().stream()
                            .filter(c -> c.getName().equals("XSRF-TOKEN"))
                            .map(HttpCookie::getValue)
                            .findFirst()
                            .orElseThrow());
        return client.send(
                builder.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(
                                                json.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    void login(long user) throws Exception {
        cookies.getCookieStore().removeAll();
        assertThat(
                        request(
                                        "POST",
                                        "/auth/login",
                                        Map.of(
                                                "tenantCode",
                                                "WF" + d.tenant,
                                                "username",
                                                "u" + user,
                                                "password",
                                                password),
                                        false,
                                        d.company,
                                        null)
                                .statusCode())
                .isEqualTo(200);
    }

    long flow() {
        d.bind(root(approve("review", List.of(d.manager), "and", null)));
        return d.submit(d.order("1", "100"));
    }

    long task(long instance) {
        return jdbc.queryForObject(
                "SELECT id FROM wf_task_ext WHERE tenant_id=? AND instance_id=? AND"
                    + " status='pending'",
                Long.class,
                d.tenant,
                instance);
    }

    @Test
    void authenticationCsrfAndLegacyCommandCannotBypassFlowAndRetryIsIdempotent() throws Exception {
        long instance = flow(),
                task = task(instance),
                order =
                        jdbc.queryForObject(
                                "SELECT business_id FROM wf_instance WHERE id=?",
                                Long.class,
                                instance);
        var body = Map.of("version", 0, "comment", "真实HTTP复核");
        assertThat(
                        request(
                                        "GET",
                                        "/workflow/instances/" + instance,
                                        null,
                                        false,
                                        d.company,
                                        null)
                                .statusCode())
                .isEqualTo(401);
        login(d.manager);
        assertThat(
                        request(
                                        "POST",
                                        "/workflow/tasks/" + task + "/approve",
                                        body,
                                        false,
                                        d.company,
                                        key())
                                .statusCode())
                .isEqualTo(403);
        assertThat(
                        request(
                                        "POST",
                                        "/sales-orders/" + order + "/approve",
                                        Map.of("version", 1),
                                        true,
                                        d.company,
                                        key())
                                .statusCode())
                .isEqualTo(409);
        String key = key();
        assertThat(
                        request(
                                        "POST",
                                        "/workflow/tasks/" + task + "/approve",
                                        body,
                                        true,
                                        d.company,
                                        key)
                                .statusCode())
                .isEqualTo(200);
        assertThat(
                        request(
                                        "POST",
                                        "/workflow/tasks/" + task + "/approve",
                                        body,
                                        true,
                                        d.company,
                                        key)
                                .statusCode())
                .isEqualTo(200);
        assertThat(d.status(order)).isEqualTo("approved");
    }

    @Test
    void otherAssigneesCompaniesAndRevokedPermissionsAreRejectedOnTheServer() throws Exception {
        long instance = flow(), task = task(instance);
        login(d.financeA);
        assertThat(
                        request(
                                        "POST",
                                        "/workflow/tasks/" + task + "/approve",
                                        Map.of("version", 0),
                                        true,
                                        d.company,
                                        key())
                                .statusCode())
                .isEqualTo(403);
        assertThat(
                        request(
                                        "GET",
                                        "/workflow/instances/" + instance,
                                        null,
                                        false,
                                        d.company + 1,
                                        null)
                                .statusCode())
                .isIn(403, 404);
        login(d.manager);
        jdbc.update(
                "DELETE rp FROM sys_role_permission rp JOIN sys_user_role ur ON"
                    + " ur.tenant_id=rp.tenant_id AND ur.role_id=rp.role_id JOIN sys_permission p"
                    + " ON p.id=rp.permission_id WHERE ur.tenant_id=? AND ur.user_id=? AND p.code"
                    + " IN('sales_order:approve','workflow_model:view')",
                d.tenant,
                d.manager);
        assertThat(request("GET", "/workflow/models", null, false, d.company, null).statusCode())
                .isEqualTo(403);
        assertThat(
                        request(
                                        "POST",
                                        "/workflow/tasks/" + task + "/approve",
                                        Map.of("version", 0),
                                        true,
                                        d.company,
                                        key())
                                .statusCode())
                .isEqualTo(403);
        assertThat(request("GET", "/workflow/tasks/todo", null, false, d.company, null).body())
                .contains("\"items\":[]");
    }

    @Test
    void warehouseRestrictionsHideBothSourceAndWorkflow() throws Exception {
        long instance = flow(), task = task(instance);
        login(d.manager);
        jdbc.update(
                "INSERT INTO warehouse(id,tenant_id,company_id,code,name)"
                    + " VALUES(?,?,?,'OTHER','其他仓')",
                d.warehouse + 1,
                d.tenant,
                d.company);
        jdbc.update(
                "UPDATE sys_user_data_scope SET scope_type='warehouse',warehouse_id=? WHERE"
                    + " tenant_id=? AND user_id=?",
                d.warehouse + 1,
                d.tenant,
                d.manager);
        assertThat(
                        request(
                                        "GET",
                                        "/workflow/instances/" + instance,
                                        null,
                                        false,
                                        d.company,
                                        null)
                                .statusCode())
                .isIn(403, 404);
        assertThat(request("GET", "/workflow/tasks/todo", null, false, d.company, null).body())
                .contains("\"items\":[]");
        assertThat(
                        request(
                                        "POST",
                                        "/workflow/tasks/" + task + "/approve",
                                        Map.of("version", 0),
                                        true,
                                        d.company,
                                        key())
                                .statusCode())
                .isIn(403, 404);
    }

    @Test
    void departmentScopeAlsoRestrictsCurrentAssignedTasksAndCounts() throws Exception {
        long instance = flow(), task = task(instance);
        login(d.manager);
        long role =
                jdbc.queryForObject(
                        "SELECT role_id FROM sys_user_role WHERE tenant_id=? AND user_id=?",
                        Long.class,
                        d.tenant,
                        d.manager);
        jdbc.update(
                "INSERT INTO org_role_data_scope(tenant_id,company_id,role_id,scope_type)"
                    + " VALUES(?,?,?,'self')",
                d.tenant,
                d.company,
                role);
        assertThat(
                        request(
                                        "GET",
                                        "/workflow/instances/" + instance,
                                        null,
                                        false,
                                        d.company,
                                        null)
                                .statusCode())
                .isEqualTo(404);
        assertThat(request("GET", "/workflow/todo-count", null, false, d.company, null).body())
                .contains("\"count\":0");
        assertThat(
                        request(
                                        "POST",
                                        "/workflow/tasks/" + task + "/approve",
                                        Map.of("version", 0),
                                        true,
                                        d.company,
                                        key())
                                .statusCode())
                .isEqualTo(404);
    }
}
