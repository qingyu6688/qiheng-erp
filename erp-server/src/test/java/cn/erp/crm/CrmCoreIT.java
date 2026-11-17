package cn.erp.crm;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.importing.ClueCsv;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import cn.erp.shared.OrgScope;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import static cn.erp.crm.CrmCoreModels.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = ErpApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CrmCoreIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired CrmClues clues;
    @Autowired CrmCustomers customers;
    @Autowired CrmFollowUps follows;
    @Autowired CrmPoolSettings pool;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper json;
    private long tenant, company, otherCompany, own, team, manager, stranger, department, outsideDepartment;
    private CompanyAccess admin;
    private String tenantCode;

    @BeforeEach void seed() {
        tenant = Ids.next(); company = Ids.next(); otherCompany = Ids.next();
        own = Ids.next(); team = Ids.next(); manager = Ids.next(); stranger = Ids.next();
        department = Ids.next(); outsideDepartment = Ids.next();
        tenantCode = "CRM" + tenant;
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'客户关系验收')", tenant, tenantCode);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','公司甲'),(?,?,'B','公司乙')",
                company, tenant, otherCompany, tenant);
        for (long user : List.of(own, team, manager, stranger)) {
            jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,?,?,?)",
                    user, tenant, "u" + user, "测试员工", "仅用于领域集成测试");
            assignCompany(user, company);
        }
        assignCompany(own, otherCompany);
        jdbc.update("INSERT INTO org_department(id,tenant_id,company_id,code,name,leader_user_id,path,depth) VALUES(?,?,?,'D1','华东',?, ?,1)",
                department, tenant, company, manager, "/" + department + "/");
        jdbc.update("INSERT INTO org_department(id,tenant_id,company_id,code,name,path,depth) VALUES(?,?,?,'D2','华西',?,1)",
                outsideDepartment, tenant, company, "/" + outsideDepartment + "/");
        for (long user : List.of(own, team, manager, stranger)) {
            jdbc.update("INSERT INTO org_user_company(tenant_id,company_id,user_id) VALUES(?,?,?)", tenant, company, user);
            jdbc.update("INSERT INTO org_user_department(tenant_id,company_id,user_id,department_id,is_primary) VALUES(?,?,?,?,1)",
                    tenant, company, user, user == stranger ? outsideDepartment : department);
        }
        admin = new CompanyAccess(tenant, own, company, true, Set.of(), new HashSet<>(jdbc.queryForList("SELECT code FROM sys_permission", String.class)),
                new OrgScope(List.of(), department));
    }

    @Test void encryptsDeduplicatesAndConvertsOneSharedCustomerWhenRetried() {
        String mobile = "13800005162";
        var clue = clues.create(admin, lead("东来公司", mobile));
        long lead = identifier(clue, "id");
        assertThat(clue).containsEntry("mobile", mobile).doesNotContainKeys("mobileHash", "mobileCipher");
        assertThat(jdbc.queryForObject("SELECT mobile_cipher FROM crm_clue WHERE id=?", String.class, lead)).doesNotContain(mobile);
        assertThat(items(clues.list(admin, null, "东来", null, 20))).singleElement().satisfies(item -> {
            assertThat(item).containsEntry("mobile", "****5162").doesNotContainKeys("mobileCipher", "mobileHash", "email");
        });
        assertCode(() -> clues.create(admin, lead("重复号码", "+1 (380) 000-5162".substring(1))), "CRM_DUPLICATE_CLUE");

        var first = clues.action(admin, lead, "convert", new ClueAction(0L, null, null, "CRM-A1"));
        long customer = identifier(first, "convertedPartnerId");
        assertThat(first).containsEntry("status", "converted");
        assertThat(clues.action(admin, lead, "convert", new ClueAction(0L, null, null, "CRM-A1"))).containsEntry("convertedPartnerId", Long.toString(customer));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_partner WHERE tenant_id=? AND name='东来公司'", Integer.class, tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT role FROM partner_role WHERE tenant_id=? AND partner_id=?", String.class, tenant, customer)).isEqualTo("customer");
        assertThat(customers.get(admin, customer)).containsEntry("partnerName", "东来公司");
        assertThat(jdbc.queryForObject("SELECT CONCAT_WS('',before_data,after_data) FROM audit_log WHERE tenant_id=? AND action='crm_clue.create'", String.class, tenant)).doesNotContain(mobile);
    }

    @Test void explicitMergePreservesSupplierAndReusesTheOriginalPartner() {
        long supplier = Ids.next();
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name,tax_no) VALUES(?,?,'SUP','同名单位','TAX001')", supplier, tenant);
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(?,?,?,'supplier')", Ids.next(), tenant, supplier);
        long clue = identifier(clues.create(admin, lead("同名单位", null)), "id");
        assertCode(() -> clues.action(admin, clue, "convert", new ClueAction(0L, null, null, "NEW")), "CRM_DUPLICATE_PARTNER");
        assertThat(clues.action(admin, clue, "convert", new ClueAction(0L, null, supplier, null)))
                .containsEntry("convertedPartnerId", Long.toString(supplier));
        assertThat(jdbc.queryForList("SELECT role FROM partner_role WHERE tenant_id=? AND partner_id=? AND status='active'",
                String.class, tenant, supplier)).containsExactlyInAnyOrder("customer", "supplier");
    }

    @Test void customerScopeIntersectsCompanyDepartmentRelationshipsAndTeamRights() {
        long customer = convert("权限客户");
        var ordinary = employee(team, new OrgScope(List.of(), department));
        var supervisor = employee(manager, OrgScope.restricted(Set.of(department), Set.of(), department));
        var other = employee(stranger, OrgScope.restricted(Set.of(outsideDepartment), Set.of(), outsideDepartment));
        assertCode(() -> customers.get(ordinary, customer), "NOT_FOUND");
        assertThat(customers.get(supervisor, customer)).containsEntry("canWrite", true);
        customers.saveTeam(admin, customer, new TeamInput(team, "read", null));
        assertThat(customers.get(ordinary, customer)).containsEntry("canWrite", false);
        assertCode(() -> customers.update(ordinary, customer, profile(customer, 0L)), "FORBIDDEN");
        customers.saveTeam(admin, customer, new TeamInput(stranger, "write", null));
        assertCode(() -> customers.get(other, customer), "NOT_FOUND");
        var otherCompanyActor = new CompanyAccess(tenant, own, otherCompany, true, Set.of(), admin.permissions());
        assertCode(() -> customers.get(otherCompanyActor, customer), "NOT_FOUND");
        var foreignActor = new CompanyAccess(Ids.next(), own, company, true, Set.of(), admin.permissions());
        assertCode(() -> customers.get(foreignActor, customer), "NOT_FOUND");
        customers.saveTeam(admin, customer, new TeamInput(team, "write", null));
        assertThat(customers.update(ordinary, customer, profile(customer, 0L))).containsEntry("industry", "retail");
        jdbc.update("UPDATE crm_customer_team SET expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 DAY WHERE tenant_id=? AND company_id=? AND partner_id=? AND user_id=?", tenant, company, customer, team);
        assertCode(() -> customers.get(ordinary, customer), "NOT_FOUND");
        jdbc.update("UPDATE business_partner SET status='disabled' WHERE id=?", customer);
        assertThat(customers.get(admin, customer)).containsEntry("status", "disabled").containsEntry("canWrite", false);
    }

    @Test void poolClaimIsAtomicAndChecksOwnerAndLockLimits() throws Exception {
        long customer = convert("可领取客户");
        customers.action(admin, customer, "release", new OwnerAction(0L, null));
        var a = employee(team, new OrgScope(List.of(), department));
        var b = employee(stranger, new OrgScope(List.of(), outsideDepartment));
        var go = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> claim(go, a, customer));
            var second = executor.submit(() -> claim(go, b, customer));
            go.countDown();
            long one = first.get(10, TimeUnit.SECONDS), two = second.get(10, TimeUnit.SECONDS);
            assertThat(List.of(one, two).stream().filter(value -> value > 0)).hasSize(1);
            var winner = one == team || two == team ? a : b;
            pool.saveLimit(admin, new OwnerLimitInput("user", winner.userId(), 1, 0, true, null));
            assertCode(() -> customers.action(winner, customer, "lock", new OwnerAction(2L, null)), "CRM_LOCK_LIMIT");
            long remaining = convert("第二位客户");
            customers.action(admin, remaining, "release", new OwnerAction(0L, null));
            assertCode(() -> customers.poolAction(winner, remaining, "claim", new OwnerAction(null, null)), "CRM_OWNER_LIMIT");
        }
    }

    @Test void pendingPoolReclamationPreservesLockedCustomersAndClearsOldTeam() {
        long oldest = convert("老客户"), locked = convert("锁定客户");
        customers.saveTeam(admin, oldest, new TeamInput(team, "read", null));
        customers.action(admin, locked, "lock", new OwnerAction(0L, null));
        jdbc.update("UPDATE crm_customer_profile SET created_at=UTC_TIMESTAMP(3)-INTERVAL 40 DAY,owner_since_at=UTC_TIMESTAMP(3)-INTERVAL 40 DAY WHERE tenant_id=? AND company_id=?",
                tenant, company);
        pool.saveRule(admin, new PoolRuleInput(true, 30, 60, 2, List.of(), null));
        assertThat(pool.reclaimBatch(tenant, company, 0)).isZero();
        assertThat(items(customers.list(admin, true, null, null, 20))).singleElement()
                .satisfies(item -> assertThat(item).containsEntry("partnerId", Long.toString(oldest)).containsEntry("poolReason", "automatic"));
        assertThat(customers.get(admin, locked)).containsEntry("ownerUserId", Long.toString(own));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM crm_customer_team WHERE partner_id=?", Integer.class, oldest)).isZero();
    }

    @Test void importRollsBackTheWholeFileAndFollowUpRechecksTheLinkedScope() {
        String header = "name,contactName,mobile,email,taxNo,source,industry\n";
        String body = "来源甲,张三,13800000033,,,referral,retail\n" + "来源乙,张三,13800000033,,,referral,retail\n";
        var file = ClueCsv.read((header + body).getBytes(StandardCharsets.UTF_8));
        assertCode(() -> clues.importRows(admin, file), "CRM_DUPLICATE_CLUE");
        assertThat(items(clues.list(admin, null, null, null, 20))).isEmpty();
        long clue = identifier(clues.create(admin, lead("跟进对象", null)), "id");
        var note = new FollowInput("clue", clue, "call", "约定下周再次联系", Instant.now().plusSeconds(86400), List.of());
        assertThat(follows.create(admin, note)).containsEntry("method", "call");
        assertThat(clues.get(admin, clue)).containsEntry("status", "following");
        assertThat(items(follows.list(admin, "clue", clue, null, 20))).hasSize(1);
        assertCode(() -> follows.list(employee(team, OrgScope.all()), "clue", clue, null, 20), "NOT_FOUND");
    }

    @Test void contactProfilePreservesMaskedDataWhenNonSensitiveFieldsAreEdited() {
        long partner = convert("联系人验收客户");
        var created = customers.saveContact(admin, partner, new ContactInput(null, "联系人甲", "13800005163", "contact@example.invalid",
                null, false, null, null, "经理", true, null, "private_contact", "", null));
        long contact = identifier(created, "id");
        var restricted = employee(own, new OrgScope(List.of(), department));
        assertThat(items(customers.contacts(restricted, partner))).singleElement().satisfies(item -> {
            assertThat(item).containsEntry("mobile", "****5163").doesNotContainKeys("email", "wechat", "wechatCipher");
        });
        customers.saveContact(restricted, partner, new ContactInput(contact, "联系人甲", null, null, null, false, 0L, null,
                "采购负责人", true, null, null, "", 0L));
        assertThat(items(customers.contacts(admin, partner))).singleElement().satisfies(item -> {
            assertThat(item).containsEntry("mobile", "13800005163").containsEntry("email", "contact@example.invalid")
                    .containsEntry("wechat", "private_contact").containsEntry("position", "采购负责人");
        });
    }

    @Test void httpRejectsMissingCsrfWrongCompanyAndRevokedPermission() throws Exception {
        long customer = convert("HTTP验收客户");
        String password = UUID.randomUUID().toString();
        jdbc.update("UPDATE sys_user SET password_hash=? WHERE id=?", passwordEncoder.encode(password), own);
        long role = Ids.next();
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'CRM_READER','CRM验收角色')", role, tenant);
        jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)", Ids.next(), tenant, own, role);
        for (String code : List.of("crm_customer:view", "crm_clue:create")) {
            jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT ?,?,?,id FROM sys_permission WHERE code=?",
                    Ids.next(), tenant, role, code);
        }
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (var client = HttpClient.newBuilder().cookieHandler(cookies).build()) {
            String path = "/crm/customers/" + customer;
            assertThat(http(client, "GET", path, null, company).statusCode()).isEqualTo(401);
            String login = json.writeValueAsString(Map.of("tenantCode", tenantCode, "username", "u" + own, "password", password));
            assertThat(http(client, "POST", "/auth/login", login, company).statusCode()).isEqualTo(200);
            assertThat(http(client, "GET", path, null, company).statusCode()).isEqualTo(200);
            assertThat(http(client, "GET", path, null, otherCompany).statusCode()).isEqualTo(404);
            assertThat(http(client, "POST", "/crm/clues", "{\"name\":\"缺少验证令牌\"}", company).statusCode()).isEqualTo(403);
            jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM sys_permission WHERE code='crm_customer:view')", tenant, role);
            assertThat(http(client, "GET", path, null, company).statusCode()).isEqualTo(403);
        }
    }

    private HttpResponse<String> http(HttpClient client, String method, String path, String body, long companyId) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .header("X-Company-Id", Long.toString(companyId));
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private long claim(CountDownLatch go, CompanyAccess actor, long customer) throws InterruptedException {
        go.await();
        try { customers.poolAction(actor, customer, "claim", new OwnerAction(null, null)); return actor.userId(); }
        catch (ApiException error) {
            if (Set.of("NOT_FOUND", "CRM_POOL_ALREADY_TAKEN").contains(error.getErrorCode())) return 0L;
            throw error;
        }
    }

    private void assignCompany(long user, long companyId) {
        jdbc.update("INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)",
                Ids.next(), tenant, user, companyId);
    }

    private CompanyAccess employee(long user, OrgScope scope) {
        return new CompanyAccess(tenant, user, company, true, Set.of(),
                Set.of("crm_customer:view", "crm_customer:update", "crm_clue:view", "crm_pool:view", "crm_pool:claim"), scope);
    }

    private ClueInput lead(String name, String phone) {
        return new ClueInput(name, "联系人", phone, "fixture@example.invalid", null, "referral", "retail", null, null, null);
    }

    private CustomerInput profile(long partner, Long version) {
        return new CustomerInput(partner, null, "a", "referral", "retail", null, "unsold", null, version);
    }

    private long convert(String name) {
        long clue = identifier(clues.create(admin, lead(name, null)), "id");
        return identifier(clues.action(admin, clue, "convert", new ClueAction(0L, null, null, null)), "convertedPartnerId");
    }

    private static long identifier(Map<String, Object> row, String key) { return Long.parseLong(row.get(key).toString()); }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) { return (List<Map<String, Object>>) page.get("items"); }

    private static void assertCode(Runnable runnable, String key) {
        assertThatThrownBy(runnable::run).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getErrorCode()).isEqualTo(key));
    }
}
