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
import cn.erp.shared.Ids;
import tools.jackson.databind.JsonNode;

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
    void crmLeadToCashCycleStaysInTheSameCompany() throws Exception {
        try {
            long otherCompany = addSecondCompany();
            String rootPath = "/crm";
            assertThat(request("GET", rootPath + "/contracts", null, false, d.company, null).statusCode()).isEqualTo(401);
            login(d.maker);
            var clue = crm("POST", rootPath + "/clues", Map.of("name", "合同闭环客户"));
            var converted = crm("POST", rootPath + "/clues/" + clue.path("id").asString() + "/convert",
                    Map.of("version", clue.path("version").asLong(), "code", "CRM-CYCLE"));
            assertThat(converted.path("status").asString()).isEqualTo("converted");
            String customer = converted.path("convertedPartnerId").asString();
            assertThat(crm("GET", rootPath + "/customers/" + customer, null).path("partnerName").asString())
                    .isEqualTo("合同闭环客户");
            var stages = crm("POST", rootPath + "/stage-groups", Map.of("name", "商机分组", "stages", List.of(
                    Map.of("name", "沟通", "winRate", "0.3"), Map.of("name", "签约", "winRate", "0.9"))));
            var product = Map.of("skuId", "" + d.sku, "unitId", "" + d.unit, "qty", "4", "unitPrice", "100");
            var opportunity = crm("POST", rootPath + "/businesses", Map.of("name", "商机验收", "partnerId", customer,
                    "stageGroupId", stages.path("id").asString(), "stageId", stages.path("stages").get(0).path("id").asString(),
                    "expectedDate", d.day.toString(), "lines", List.of(product)));
            String opportunityId = opportunity.path("id").asString();
            crm("POST", rootPath + "/businesses/" + opportunityId + "/advance", Map.of("version", 0,
                    "stageId", stages.path("stages").get(1).path("id").asString()));
            assertThat(crm("POST", rootPath + "/businesses/" + opportunityId + "/win", Map.of("version", 1)).path("result").asString())
                    .isEqualTo("won");
            var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
            var funnel = crm("GET", "/crm/statistics/funnel?dateFrom=" + today.minusDays(4) + "&dateTo=" + today, null);
            assertThat(funnel.path("items").get(0).path("advancedCount").asInt()).isEqualTo(1);
            var contract = crm("POST", rootPath + "/contracts", Map.of("name", "合同验收", "partnerId", customer,
                    "businessId", opportunityId, "signDate", d.day.toString(), "startDate", d.day.toString(),
                    "endDate", d.day.plusDays(20).toString(), "priceTaxInclusive", true, "lines", List.of(product)));
            String contractId = contract.path("id").asString();
            String contractPath = rootPath + "/contracts/" + contractId;
            assertThat(request("GET", contractPath, null, false, d.company + 1, null).statusCode()).isEqualTo(403);
            assertThat(request("GET", contractPath, null, false, otherCompany, null).statusCode()).isEqualTo(404);
            assertThat(request("POST", contractPath + "/submit", Map.of("version", 0), true, d.company, key()).statusCode()).isEqualTo(409);
            d.bind(root(approve("review", List.of(d.manager), "and", null)), "crm_contract");
            var submitted = crm("POST", contractPath + "/submit", Map.of("version", 0));
            assertThat(submitted.path("status").asString()).isEqualTo("pending");
            assertThat(request("POST", contractPath + "/approve", Map.of("version", 1), true, d.company, key()).statusCode()).isEqualTo(409);
            login(d.manager);
            long instance = Long.parseLong(submitted.path("wfInstanceId").asString());
            crm("POST", "/workflow/tasks/" + task(instance) + "/approve", Map.of("version", 0, "comment", "同意"));
            assertThat(crm("GET", contractPath, null).path("status").asString()).isEqualTo("approved");

            login(d.maker);
            var allocation = Map.of("businessDate", d.day.toString(), "lines", List.of(Map.of(
                    "contractLineId", contract.path("lines").get(0).path("id").asString(), "warehouseId", "" + d.warehouse, "qty", "2")));
            String orderPath = contractPath + "/create-sales-order";
            String firstKey = key();
            var order = crm("POST", orderPath, allocation, firstKey);
            assertThat(order.path("sourceType").asString()).isEqualTo("crm_contract");
            assertThat(order.path("sourceId").asString()).isEqualTo(contractId);
            assertThat(order.path("status").asString()).isEqualTo("draft");
            assertThat(crm("POST", orderPath, allocation, firstKey).path("id").asString()).isEqualTo(order.path("id").asString());
            var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> allocateStatus(orderPath, allocation));
            var second = java.util.concurrent.CompletableFuture.supplyAsync(() -> allocateStatus(orderPath, allocation));
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 409);
            assertThat(crm("GET", contractPath, null).path("salesOrderIds").size()).isEqualTo(2);
            verifyReadOnlyContractTeam(customer, contractPath, orderPath, allocation);
            fulfillCrmOrder(customer, order);
            verifyCrmCollection(customer, contractId, order.path("id").asString());
        } finally {
            for (String table : List.of("crm_receivable", "crm_receivable_plan", "crm_contract_order_line", "crm_contract_order",
                    "crm_contract_line", "crm_contract", "crm_business_line", "crm_business_stage_event", "crm_business", "crm_business_stage",
                    "crm_stage_department", "crm_business_stage_group", "crm_customer_team", "crm_customer_profile",
                    "crm_clue", "crm_tenant_guard"))
                jdbc.update("DELETE FROM " + table + " WHERE tenant_id=?", d.tenant);
        }
    }

    private long addSecondCompany() {
        long id = Ids.next();
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'B','另一家公司')", id, d.tenant);
        jdbc.update("INSERT INTO org_user_company(tenant_id,company_id,user_id) VALUES(?,?,?)", d.tenant, id, d.maker);
        jdbc.update("INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)",
                Ids.next(), d.tenant, d.maker, id);
        return id;
    }

    private void verifyReadOnlyContractTeam(String customer, String contract, String order, Object allocation) throws Exception {
        jdbc.update("""
                DELETE rp FROM sys_role_permission rp JOIN sys_user_role ur ON ur.tenant_id=rp.tenant_id AND ur.role_id=rp.role_id
                JOIN sys_permission p ON p.id=rp.permission_id WHERE rp.tenant_id=? AND ur.user_id=?
                  AND p.code IN ('crm_customer:manage_all','crm_customer:view_all')
                """, d.tenant, d.financeA);
        login(d.financeA);
        assertThat(request("GET", contract, null, false, d.company, null).statusCode()).isEqualTo(404);
        jdbc.update("INSERT INTO crm_customer_team(tenant_id,company_id,partner_id,user_id,permission) VALUES(?,?,?,?,'read')",
                d.tenant, d.company, customer, d.financeA);
        assertThat(crm("GET", contract, null).path("allowedActions").size()).isZero();
        assertThat(request("POST", order, allocation, true, d.company, key()).statusCode()).isEqualTo(403);
    }

    private int allocateStatus(String path, Object payload) {
        try {
            return request("POST", path, payload, true, d.company, key()).statusCode();
        } catch (Exception failure) {
            throw new IllegalStateException("合同并发分单测试请求失败", failure);
        }
    }

    private void fulfillCrmOrder(String customer, JsonNode order) throws Exception {
        String orderPath = "/sales-orders/" + order.path("id").asString();
        login(d.maker);
        var submitted = crm("POST", orderPath + "/submit", commandVersion(order));
        assertThat(submitted.path("status").asString()).isEqualTo("pending");
        login(d.manager);
        var approved = crm("POST", orderPath + "/approve", commandVersion(submitted));
        assertThat(approved.path("status").asString()).isEqualTo("approved");

        login(d.maker);
        var orderLines = crm("GET", orderPath, null).path("lines");
        assertThat(orderLines.size()).isEqualTo(1);
        var shipment = created("/shipments", Map.of("customerId", customer, "warehouseId", "" + d.warehouse,
                "businessDate", d.day.toString(), "deliveryMethod", "delivery", "lines", List.of(Map.of(
                        "orderLineId", orderLines.get(0).path("id").asString(), "unitId", "" + d.unit, "qty", "2"))));
        String shipmentPath = "/shipments/" + shipment.path("id").asString();
        var ready = crm("POST", shipmentPath + "/submit", commandVersion(shipment));
        var posted = crm("POST", shipmentPath + "/post", commandVersion(ready));
        assertThat(posted.path("status").asString()).isEqualTo("posted");
        var signed = crm("POST", shipmentPath + "/sign", Map.of("version", posted.path("version").asLong(),
                "signDate", d.day.toString(), "lines", List.of(Map.of("lineId", posted.path("lines").get(0).path("id").asString(),
                        "signedQty", "2"))));
        assertThat(signed.path("status").asString()).isEqualTo("signed");
        assertThat(signed.path("receivableAmount").asString()).isEqualTo("200.00");
        assertThat(crm("GET", orderPath, null).path("fulfillmentStatus").asString()).isEqualTo("full");
    }

    private void verifyCrmCollection(String customer, String contractId, String orderId) throws Exception {
        long bank = Ids.next();
        jdbc.update("INSERT INTO cash_account(id,tenant_id,company_id,code,name,account_type) VALUES(?,?,?,'CRM','回款银行','bank')",
                bank, d.tenant, d.company);
        jdbc.update("INSERT INTO cash_account_balance(id,tenant_id,company_id,cash_account_id) VALUES(?,?,?,?)",
                Ids.next(), d.tenant, d.company, bank);
        String amount = "200.00";
        var plan = crm("POST", "/crm/receivable-plans", Map.of("contractId", contractId, "periodNo", 1,
                "planDate", d.day.plusDays(2).toString(), "planAmount", amount, "remindDays", 3));
        var receipt = created("/receipts", Map.of("receiptType", "customer", "partnerId", customer,
                "cashAccountId", "" + bank, "amount", amount, "businessDate", d.day.toString(), "relatedOrderId", orderId));
        String receiptId = receipt.path("id").asString();
        crm("POST", "/crm/receivables", Map.of("planId", plan.path("id").asString(), "receiptId", receiptId));
        String statsPath = "/crm/statistics/performance?dateFrom=" + d.day + "&dateTo=" + d.day;
        assertThat(crm("GET", statsPath, null).path("items").get(0).path("receivableAmount").asString()).isIn("0", "0.00");
        String openItemsUri = "/open-items?partnerId=" + customer;
        assertThat(crm("GET", openItemsUri, null).path("items").size()).isEqualTo(1);
        crm("POST", "/receipts/" + receiptId + "/submit", Map.of("version", 1));
        login(d.manager);
        crm("POST", "/receipts/" + receiptId + "/confirm", Map.of("version", 2));
        var openItems = crm("GET", openItemsUri, null).path("items");
        assertThat(openItems.size()).isEqualTo(2);
        crm("POST", "/settlement-allocations", Map.of("partnerId", customer, "allocations", List.of(Map.of(
                "debitItemId", openItemId(openItems, "D"), "creditItemId", openItemId(openItems, "C"), "amount", amount))));
        assertThat(crm("GET", openItemsUri, null).path("items").size()).isZero();
        assertThat(crm("GET", "/sales-orders/" + orderId, null).path("settlementStatus").asString()).isEqualTo("full");
        assertThat(crm("GET", statsPath, null).path("items").get(0).path("receivableAmount").asString()).isEqualTo(amount);
        var completed = crm("GET", "/crm/receivable-plans?contractId=" + contractId, null).path("items").get(0);
        assertThat(completed.path("status").asString()).isEqualTo("finished");
        assertThat(completed.path("receivedAmount").asString()).isEqualTo(amount);
        assertThat(crm("GET", "/crm/receivable-plans?partnerId=" + customer, null).path("items").size()).isEqualTo(1);
        assertThat(crm("GET", "/crm/receivables?partnerId=" + customer, null).path("items").size()).isEqualTo(1);
        assertThat(crm("GET", "/crm/receivables?partnerId=" + d.warehouse, null).path("items").size()).isZero();
    }

    private static String openItemId(JsonNode items, String direction) {
        for (JsonNode item : items)
            if (item.path("direction").asString().equals(direction)) return item.path("id").asString();
        throw new AssertionError("缺少核销所需的 " + direction + " 向未清项");
    }

    private static Map<String, Long> commandVersion(JsonNode row) {
        return Map.of("version", row.path("version").asLong());
    }

    private JsonNode created(String path, Object body) throws Exception {
        var response = request("POST", path, body, true, d.company, key());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json.readTree(response.body()).path("data");
    }

    private JsonNode crm(String method, String path, Object body) throws Exception {
        return crm(method, path, body, key());
    }

    private JsonNode crm(String method, String path, Object body, String key) throws Exception {
        var response = request(method, path, body, body != null, d.company, key);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("data");
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
