package cn.erp.quotation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.flowable.engine.ProcessEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import cn.erp.ErpApplication;
import cn.erp.identity.AccessService;
import cn.erp.identity.CompanyAccess;
import cn.erp.salesorder.SalesOrderModels;
import cn.erp.salesorder.SalesOrderService;
import cn.erp.scheduler.JobEngine;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import cn.erp.shared.OrgScope;
import cn.erp.workflow.WorkflowModels;
import cn.erp.workflow.WorkflowTasks;
import cn.erp.workflow.WorkflowTestData;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = ErpApplication.class)
@ActiveProfiles("test")
class SalesQuoteIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessService access;
    @Autowired SalesOrderService sales;
    @Autowired WorkflowModels models;
    @Autowired WorkflowTasks tasks;
    @Autowired ProcessEngine engine;
    @Autowired SalesQuoteService service;
    @Autowired SalesQuoteQueries queries;
    @Autowired JobEngine jobs;
    @Autowired cn.erp.workflow.WorkflowAccess workflowAccess;
    @Autowired tools.jackson.databind.ObjectMapper json;
    private WorkflowTestData d;

    @BeforeEach void prepare() { d = new WorkflowTestData(jdbc, access, sales, models); }
    @AfterEach void cleanup() {
        if (d == null) return;
        for (String table : List.of("sales_quote_line", "sales_quote", "crm_business", "crm_business_stage", "crm_business_stage_group", "crm_customer_profile"))
            jdbc.update("DELETE FROM " + table + " WHERE tenant_id=?", d.tenant);
        d.cleanup(engine);
    }

    @Test void quoteHasNoStockOrReceivableEffectAndConvertsOnceToFrozenDraft() {
        String createKey = key(); var input = input("22.60", null, null);
        long id = id(service.create(d.actor(d.maker), createKey, input));
        assertThat(id(service.create(d.actor(d.maker), createKey, input))).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation WHERE tenant_id=?", Long.class, d.tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receivable WHERE tenant_id=?", Long.class, d.tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_order WHERE tenant_id=?", Long.class, d.tenant)).isZero();
        command(id, "submit", d.maker);
        assertThat(queries.get(d.actor(d.maker), id)).containsEntry("status", "issued");
        String convertKey = key(); var command = new SalesQuoteModels.Command(version(id), null, d.day.plusDays(1), null);
        var converted = service.command(d.actor(d.maker), id, "convert", convertKey, command);
        long order = Long.parseLong(converted.get("convertedOrderId").toString());
        assertThat(service.command(d.actor(d.maker), id, "convert", convertKey, command)).containsEntry("convertedOrderId", Long.toString(order));
        var actual = jdbc.queryForMap("SELECT status,source_type,source_id,total_amount FROM sales_order WHERE id=?", order);
        assertThat(actual).containsEntry("status", "draft").containsEntry("source_type", "sales_quote");
        assertThat(((Number) actual.get("source_id")).longValue()).isEqualTo(id);
        assertThat((BigDecimal) actual.get("total_amount")).isEqualByComparingTo(converted.get("totalAmount").toString());
        assertThat(jdbc.queryForObject("SELECT unit_price FROM sales_order_line WHERE order_id=?", BigDecimal.class, order)).isEqualByComparingTo("22.6");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_order WHERE tenant_id=?", Long.class, d.tenant)).isEqualTo(1);
        assertCode(() -> command(id, "convert", d.maker), "STATE_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation WHERE tenant_id=?", Long.class, d.tenant)).isZero();
    }

    @Test void minimumPriceWarningIssuesQuoteButBlockPolicyRejectsIt() {
        jdbc.update("UPDATE sku SET min_sale_price=30 WHERE id=?", d.sku);
        jdbc.update("UPDATE company_policy SET below_min_price_policy='warn' WHERE tenant_id=?", d.tenant);
        long id = create("20", null);
        assertThat(queries.get(d.actor(d.maker), id)).containsEntry("belowMinPrice", true);
        command(id, "submit", d.maker);
        assertThat(queries.get(d.actor(d.maker), id)).containsEntry("status", "issued");
        jdbc.update("UPDATE company_policy SET below_min_price_policy='block' WHERE tenant_id=?", d.tenant);
        assertThat(queries.get(d.actor(d.maker), id).get("allowedActions")).asList().doesNotContain("convert");
        assertCode(() -> create("20", null), "MIN_SALE_PRICE_BLOCKED");
        assertCode(() -> command(id, "convert", d.maker), "MIN_SALE_PRICE_BLOCKED");
    }

    @Test void lowQuoteNeedsPricePermissionAndCurrentFingerprintFromHumanWorkflow() {
        lowPricePolicy();
        long id = create("20", null);
        command(id, "submit", d.maker);
        assertThat(queries.get(d.actor(d.maker), id)).containsEntry("status", "pending");
        assertCode(() -> command(id, "approve", d.manager), "WORKFLOW_IN_PROGRESS");
        assertThat(jdbc.queryForList("SELECT t.assignee_id FROM wf_task_ext t JOIN wf_instance i ON i.id=t.instance_id AND i.tenant_id=t.tenant_id"
                + " WHERE i.tenant_id=? AND i.business_type='sales_quote' AND i.business_id=? AND t.status='pending'", Long.class, d.tenant, id)).doesNotContain(d.maker);
        approve(id);
        assertThat(queries.get(d.actor(d.maker), id)).containsEntry("status", "issued");
        jdbc.update("UPDATE sku SET min_sale_price=31 WHERE id=?", d.sku);
        assertCode(() -> command(id, "convert", d.maker), "PRICE_APPROVAL_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_order WHERE tenant_id=?", Long.class, d.tenant)).isZero();
    }

    @Test void conversionRechecksUnitConversionAndCatalogWithoutChangingSavedQuote() {
        long unit = Ids.next();
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,?)", unit, d.tenant, "双件包");
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,2,0)", Ids.next(), d.tenant, d.sku, unit);
        long id = id(service.create(d.actor(d.maker), key(), input("22.60", null, null, unit)));
        command(id, "submit", d.maker);
        jdbc.update("UPDATE sku SET status='disabled' WHERE id=?", d.sku);
        assertCode(() -> command(id, "convert", d.maker), "MASTER_DATA_DISABLED");
        jdbc.update("UPDATE sku SET status='active' WHERE id=?", d.sku);
        jdbc.update("UPDATE unit_conversion SET rate_to_base=3 WHERE tenant_id=? AND sku_id=? AND unit_id=?", d.tenant, d.sku, unit);
        assertCode(() -> command(id, "convert", d.maker), "QUOTE_TERMS_CHANGED");
        assertThat(queries.get(d.actor(d.maker), id)).containsEntry("status", "issued").containsEntry("totalAmount", "43.20");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_order WHERE tenant_id=?", Long.class, d.tenant)).isZero();
    }

    @Test void conversionRequiresBothQuoteAndOrderPermissionsAndVisibleAllLines() {
        long id = create("22.60", null);
        command(id, "submit", d.maker);
        var actor = d.actor(d.maker); var permissions = new HashSet<>(actor.permissions()); permissions.remove("sales_order:create");
        var noCreate = new CompanyAccess(d.tenant, d.maker, d.company, true, Set.of(), permissions, actor.orgScope());
        assertCode(() -> service.command(noCreate, id, "convert", key(), new SalesQuoteModels.Command(version(id), null, d.day, null)), "FORBIDDEN");
        var noWarehouse = new CompanyAccess(d.tenant, d.maker, d.company, false, Set.of(), actor.permissions(), actor.orgScope());
        assertCode(() -> queries.get(noWarehouse, id), "NOT_FOUND");
        assertThat((List<?>) queries.list(noWarehouse, null, null, 20).get("items")).isEmpty();
        var noDepartment = new CompanyAccess(d.tenant, d.maker, d.company, true, Set.of(), actor.permissions(), OrgScope.restricted(Set.of(Ids.next()), Set.of(), 0));
        assertCode(() -> queries.get(noDepartment, id), "NOT_FOUND");
        var otherCompany = new CompanyAccess(d.tenant, d.maker, Ids.next(), true, Set.of(), actor.permissions());
        assertCode(() -> queries.get(otherCompany, id), "NOT_FOUND");
    }

    @Test void opportunityRequiresMatchingCustomerAndCurrentCrmRelationship() {
        lowPricePolicy();
        long opportunity = opportunity();
        long id = create("20", opportunity);
        command(id, "submit", d.maker);
        assertThat(queries.get(d.actor(d.maker), id)).containsEntry("opportunityName", "设备采购商机");
        var actor = d.actor(d.maker); var permissions = new HashSet<>(actor.permissions()); permissions.remove("crm_business:view");
        var noCrm = new CompanyAccess(d.tenant, d.maker, d.company, true, Set.of(), permissions, actor.orgScope());
        assertCode(() -> queries.get(noCrm, id), "NOT_FOUND");
        assertThat((List<?>) queries.list(noCrm, null, null, 20).get("items")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_instance i WHERE i.tenant_id=? AND i.business_type='sales_quote' AND "
                + workflowAccess.business(noCrm,"i",false),Long.class,d.tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_instance i WHERE i.tenant_id=? AND i.business_type='sales_quote' AND "
                + workflowAccess.business(actor,"i",false),Long.class,d.tenant)).isEqualTo(1);
        assertCode(() -> service.create(noCrm, key(), input("22.60", opportunity, null)), "FORBIDDEN");
        assertCode(() -> create("22.60", Ids.next()), "VALIDATION_FAILED");
    }

    @Test void expiryJobEndsPendingWorkflowBeforeMarkingExpiredAndIsRepeatable() {
        lowPricePolicy();
        long id = create("20", null);
        command(id, "submit", d.maker);
        jdbc.update("UPDATE sales_quote SET valid_to=? WHERE id=?", LocalDate.of(2026, 11, 20), id);
        assertThat(queries.get(d.actor(d.maker), id)).containsEntry("status", "expired");
        assertThat((List<?>) queries.get(d.actor(d.maker), id).get("allowedActions")).isEmpty();
        jobs.ensure(d.tenant);
        assertThat(jobs.runOne(d.tenant, "sales.quote-expiry")).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM sales_quote WHERE id=?", String.class, id)).isEqualTo("expired");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_instance WHERE tenant_id=? AND business_type='sales_quote' AND status='running'", Long.class, d.tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_task_ext WHERE tenant_id=? AND status='pending'", Long.class, d.tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM job_run_log WHERE tenant_id=? AND job_code='sales.quote-expiry'", String.class, d.tenant)).isEqualTo("success");
        service.expire(d.tenant, d.company, id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND action='sales_quote.expire'", Long.class, d.tenant)).isLessThanOrEqualTo(1);
        assertCode(() -> command(id, "convert", d.maker), "STATE_CONFLICT");
    }

    @Test void expiredIssuedQuoteCannotConvertAndEditingRequiresFreshConfirmation() {
        long id = create("22.60", null);
        command(id, "submit", d.maker);
        jdbc.update("UPDATE sales_quote SET valid_to=? WHERE id=?", LocalDate.of(2026, 11, 20), id);
        assertCode(() -> command(id, "convert", d.maker), "QUOTE_EXPIRED");
        service.update(d.actor(d.maker), id, key(), input("25", null, version(id)));
        assertThat(queries.get(d.actor(d.maker), id)).containsEntry("status", "draft");
        assertCode(() -> command(id, "convert", d.maker), "STATE_CONFLICT");
        command(id, "submit", d.maker);
        command(id, "convert", d.maker);
        assertThat(queries.get(d.actor(d.maker), id)).containsEntry("status", "converted");
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="ERP_B08_CAPACITY",matches="true")
    void fiveHundredQuoteLinesConvertAtomicallyWithoutReservationWithinOneGigabyte() throws Exception {
        var lines=java.util.stream.IntStream.range(0,500).mapToObj(index->new SalesOrderModels.Line(null,s(d.sku),s(d.warehouse),s(d.unit),"1","22.60",null,null,null,"0.13")).toList();
        var input=new SalesQuoteModels.Input(new SalesOrderModels.Input(s(d.customer),d.day,null,true,"0",lines,List.of(),"五百行报价容量",null,null),LocalDate.of(2026,11,30),null,null);
        long started=System.nanoTime(),id=id(service.create(d.actor(d.maker),key(),input));
        double createMs=(System.nanoTime()-started)/1e6;
        started=System.nanoTime();command(id,"submit",d.maker);var result=command(id,"convert",d.maker);
        double convertMs=(System.nanoTime()-started)/1e6;
        long order=Long.parseLong(result.get("convertedOrderId").toString());
        assertThat(result).containsEntry("status","converted").containsEntry("totalAmount","11300.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_order_line WHERE order_id=?",Long.class,order)).isEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT total_amount FROM sales_order WHERE id=?",BigDecimal.class,order)).isEqualByComparingTo("11300");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation WHERE tenant_id=?",Long.class,d.tenant)).isZero();
        assertThat(Runtime.getRuntime().maxMemory()).isLessThanOrEqualTo(1_073_741_824L);
        var root=java.nio.file.Path.of(System.getenv("ERP_TEST_ROOT")).toRealPath();
        var evidence=java.nio.file.Path.of(System.getenv("ERP_CAPACITY_EVIDENCE_DIR")).toRealPath();
        if(!evidence.startsWith(root))throw new IllegalStateException("容量证据目录必须位于隔离测试根目录");
        java.nio.file.Files.writeString(evidence.resolve("销售报价容量.json"),json.writeValueAsString(Map.of("quoteLines",500,"orderLines",500,"createMs",createMs,"submitConvertMs",convertMs,
                "maxHeapBytes",Runtime.getRuntime().maxMemory(),"totalAmount","11300.00","reservations",0)),java.nio.file.StandardOpenOption.CREATE_NEW);
    }

    private void lowPricePolicy() {
        jdbc.update("UPDATE sku SET min_sale_price=30 WHERE id=?", d.sku);
        jdbc.update("UPDATE company_policy SET below_min_price_policy='approval' WHERE tenant_id=?", d.tenant);
    }
    private SalesQuoteModels.Input input(String price, Long opportunity, Long version) {
        return input(price, opportunity, version, d.unit);
    }
    private SalesQuoteModels.Input input(String price, Long opportunity, Long version, long unit) {
        return new SalesQuoteModels.Input(new SalesOrderModels.Input(s(d.customer), d.day, null, true, "1",
                List.of(new SalesOrderModels.Line(null, s(d.sku), s(d.warehouse), s(unit), "2", price, null, "1", null, "0.13")),
                List.of(), "报价冻结与转单验证", null, null), LocalDate.of(2026, 11, 30), opportunity, version);
    }
    private long create(String price, Long opportunity) { return id(service.create(d.actor(d.maker), key(), input(price, opportunity, null))); }
    private long version(long id) { return jdbc.queryForObject("SELECT version FROM sales_quote WHERE id=?", Long.class, id); }
    private Map<String,Object> command(long id, String action, long user) {
        return service.command(d.actor(user), id, action, key(), new SalesQuoteModels.Command(version(id), null, d.day.plusDays(1), null));
    }
    private void approve(long id) {
        var task = jdbc.queryForMap("SELECT t.id,t.version FROM wf_task_ext t JOIN wf_instance i ON i.id=t.instance_id AND i.tenant_id=t.tenant_id"
                + " WHERE i.tenant_id=? AND i.business_type='sales_quote' AND i.business_id=? AND t.assignee_id=? AND t.status='pending'", d.tenant, id, d.manager);
        tasks.decide(d.actor(d.manager), ((Number) task.get("id")).longValue(), "approve", key(),
                new WorkflowTasks.Decision(((Number) task.get("version")).longValue(), null, null, null, null, null, null));
    }
    private long opportunity() {
        long group = Ids.next(), stage = Ids.next(), id = Ids.next();
        jdbc.update("INSERT INTO crm_customer_profile(tenant_id,company_id,partner_id,owner_user_id,owner_department_id) VALUES(?,?,?,?,?)",
                d.tenant, d.company, d.customer, d.maker, d.department);
        jdbc.update("INSERT INTO crm_business_stage_group(id,tenant_id,company_id,name) VALUES(?,?,?,?)", group, d.tenant, d.company, "销售阶段");
        jdbc.update("INSERT INTO crm_business_stage(id,tenant_id,company_id,group_id,name,win_rate,sort) VALUES(?,?,?,?,?,0.5,1)", stage, d.tenant, d.company, group, "洽谈");
        jdbc.update("INSERT INTO crm_business(id,tenant_id,company_id,name,partner_id,stage_group_id,stage_id,expected_amount,expected_date,owner_user_id,owner_department_id)"
                + " VALUES(?,?,?,?,?,?,?,100,?,?,?)", id, d.tenant, d.company, "设备采购商机", d.customer, group, stage, d.day, d.maker, d.department);
        return id;
    }
    private static String key() { return UUID.randomUUID().toString(); }
    private static String s(long id) { return Long.toString(id); }
    private static long id(Map<String,Object> result) { return Long.parseLong(result.get("id").toString()); }
    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
}
