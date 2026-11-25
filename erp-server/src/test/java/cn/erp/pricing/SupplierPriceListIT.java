package cn.erp.pricing;

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
import cn.erp.purchasing.PurchaseOrderModels;
import cn.erp.purchasing.PurchaseOrderQueries;
import cn.erp.purchasing.PurchaseOrderService;
import cn.erp.salesorder.SalesOrderService;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import cn.erp.workflow.WorkflowModels;
import cn.erp.workflow.WorkflowTasks;
import cn.erp.workflow.WorkflowTestData;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = ErpApplication.class)
@ActiveProfiles("test")
class SupplierPriceListIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessService access;
    @Autowired SalesOrderService sales;
    @Autowired WorkflowModels models;
    @Autowired WorkflowTasks tasks;
    @Autowired ProcessEngine engine;
    @Autowired PriceListService lists;
    @Autowired PricingService pricing;
    @Autowired PurchaseOrderService orders;
    @Autowired PurchaseOrderQueries queries;
    @Autowired tools.jackson.databind.ObjectMapper json;
    private WorkflowTestData d;

    @BeforeEach void prepare() {
        d = new WorkflowTestData(jdbc, access, sales, models);
        jdbc.update("UPDATE sku SET purchase_price=8,sale_price=25 WHERE id=?", d.sku);
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role,term_type,term_value) VALUES(?,?,?,'supplier','days',30)",
                Ids.next(), d.tenant, d.customer);
    }
    @AfterEach void cleanup() { if (d != null) d.cleanup(engine); }

    @Test void supplierTiersAndTaxBasisAreAppliedOnlyWithinValidDates() {
        var saved = save(null, null, "active");
        var small = preview("2", null, false, d.day, d.unit);
        assertThat(small.lines().getFirst().unitPrice()).isEqualTo("10.00000000");
        assertThat(small.priceSources().getFirst()).extracting(PricingModels.PriceSource::source).isEqualTo("supplier_price_list");
        assertThat(small.priceSources().getFirst().priceListId()).isEqualTo(saved.header().id());
        assertThat(preview("12", null, false, d.day, d.unit).lines().getFirst().unitPrice()).isEqualTo("9.00000000");
        assertThat(preview("12", null, true, d.day, d.unit).lines().getFirst().unitPrice()).isEqualTo("10.17000000");
        assertThat(preview("2", null, false, d.day.minusDays(1), d.unit).priceSources().getFirst().referenceSource()).isEqualTo("reference");
        assertThat(preview("2", "12", true, d.day, d.unit).warnings()).anySatisfy(warning -> assertThat(warning.type()).isEqualTo("ABOVE_SUPPLIER_PRICE"));
    }

    @Test void purchaseAndSalesListsHaveSeparateTargetsAndCostPermissions() {
        var saved = save(null, null, "active");
        assertThat(lists.list(d.actor(d.maker), null, 20, true).items()).hasSize(1);
        assertThat(lists.list(d.actor(d.maker), null, 20, false).items()).isEmpty();
        assertCode(() -> lists.get(d.actor(d.maker), Long.parseLong(saved.header().id()), false), "NOT_FOUND");
        var bad = new PriceListService.Input("销售价误用供应商", true, d.day, d.day.plusDays(5), 1, "active", null,
                List.of(new PriceListService.Item(s(d.sku), s(d.unit), "1", "10")), List.of(new PriceListService.Target("supplier", s(d.customer))));
        assertCode(() -> lists.save(d.actor(d.maker), null, bad, false), "VALIDATION_FAILED");
        assertCode(() -> lists.get(withoutCost(d.maker), Long.parseLong(saved.header().id()), true), "FORBIDDEN");
        assertCode(() -> lists.save(withoutCost(d.maker), null, bad, true), "FORBIDDEN");
        var salesPreview = pricing.preview(d.actor(d.maker), new PricingModels.PreviewRequest("sales_order", s(d.customer), null, true, "0",
                List.of(new PricingModels.PreviewLine(s(d.sku), s(d.unit), s(d.warehouse), "2", null, null, null, null, "0.13")), List.of(), d.day));
        assertThat(salesPreview.lines().getFirst().unitPrice()).isNotEqualTo("11.30000000");
    }

    @Test void historyUsesApprovedSameSupplierAndConvertsOrderUnits() {
        long box = Ids.next();
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,?)", box, d.tenant, "十二件箱");
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,12,0)", Ids.next(), d.tenant, d.sku, box);
        long old = createOrder("120", box, d.day, null);
        approveLegacy(old);
        createOrder("1", d.unit, d.day.plusDays(1), null);
        var result = preview("2", null, true, d.day.plusDays(1), d.unit);
        assertThat(result.lines().getFirst().unitPrice()).isEqualTo("10.00000000");
        assertThat(result.priceSources().getFirst().referenceSource()).isEqualTo("recent_purchase");
        assertThat(result.priceSources().getFirst().historyLineId()).isEqualTo(jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM purchase_order_line WHERE order_id=?", String.class, old));
        assertThat(preview("2", null, true, d.day.minusDays(1), d.unit).priceSources().getFirst().referenceSource()).isEqualTo("reference");
        save(null, null, "active");
        assertThat(preview("2", null, true, d.day, d.unit).priceSources().getFirst().referenceSource()).isEqualTo("supplier_price_list");
    }

    @Test void warningPolicyFreezesSourcesWithoutForcingPriceReviewAndRedactsSuggestions() {
        save(null, null, "active");
        long order = createOrder("12", d.unit, d.day, null);
        var detail = json.valueToTree(queries.get(d.actor(d.maker), order));
        assertThat(detail.at("/lines/0/priceSourceSnapshot/abovePriceList").asBoolean()).isTrue();
        assertThat(detail.at("/lines/0/priceSourceSnapshot/priceListId").asString()).isNotBlank();
        assertThat(json.valueToTree(queries.get(withoutCost(d.maker), order)).at("/lines/0/priceSourceSnapshot").isMissingNode()).isTrue();
        approveLegacy(order);
        assertThat(jdbc.queryForObject("SELECT status FROM purchase_order WHERE id=?", String.class, order)).isEqualTo("approved");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_instance WHERE tenant_id=?", Long.class, d.tenant)).isZero();
        assertThat(previewAs(withoutCost(d.maker), "2", "12").priceSources().getFirst().suggestedUnitPrice()).isNull();
        assertCode(() -> previewAs(withoutCost(d.maker), "2", null), "FORBIDDEN");
    }

    @Test void approvalPolicyUsesActualTwoPersonWorkflowAndExcludesCostlessReviewers() {
        save(null, null, "active");
        jdbc.update("UPDATE company_policy SET purchase_over_price_policy='approval' WHERE tenant_id=?", d.tenant);
        jdbc.update("DELETE rp FROM sys_role_permission rp JOIN sys_permission p ON p.id=rp.permission_id JOIN sys_user_role ur ON ur.role_id=rp.role_id"
                + " AND ur.tenant_id=rp.tenant_id WHERE ur.tenant_id=? AND ur.user_id=? AND p.code='cost:view'", d.tenant, d.financeA);
        long order = createOrder("12", d.unit, d.day, null);
        command(order, "submit", d.maker);
        assertThat(jdbc.queryForList("SELECT t.assignee_id FROM wf_task_ext t JOIN wf_instance i ON i.id=t.instance_id AND i.tenant_id=t.tenant_id"
                + " WHERE i.tenant_id=? AND i.business_type='purchase_order' AND i.business_id=? AND t.status='pending'", Long.class, d.tenant, order))
                .contains(d.manager).doesNotContain(d.maker, d.financeA);
        assertCode(() -> command(order, "approve", d.manager), "WORKFLOW_IN_PROGRESS");
        var task = jdbc.queryForMap("SELECT t.id,t.version FROM wf_task_ext t JOIN wf_instance i ON i.id=t.instance_id AND i.tenant_id=t.tenant_id"
                + " WHERE i.tenant_id=? AND i.business_id=? AND i.business_type='purchase_order' AND t.assignee_id=? AND t.status='pending'", d.tenant, order, d.manager);
        tasks.decide(d.actor(d.manager), ((Number) task.get("id")).longValue(), "approve", key(),
                new WorkflowTasks.Decision(((Number) task.get("version")).longValue(), null, null, null, null, null, null));
        assertThat(jdbc.queryForObject("SELECT status FROM purchase_order WHERE id=?", String.class, order)).isEqualTo("approved");
    }

    @Test void changedContractBlocksStaleDraftButNewHistoryDoesNotInvalidateManualPrice() {
        var saved = save(null, null, "active");
        long order = createOrder("12", d.unit, d.day, null);
        save(Long.parseLong(saved.header().id()), saved.header().version(), "disabled");
        assertCode(() -> command(order, "submit", d.maker), "PURCHASE_PRICE_SOURCE_CHANGED");
        assertThat(jdbc.queryForObject("SELECT status FROM purchase_order WHERE id=?", String.class, order)).isEqualTo("draft");
        long manual = createOrder("9", d.unit, d.day, null);
        long newer = createOrder("15", d.unit, d.day, null);
        approveLegacy(newer);
        approveLegacy(manual);
        assertThat(jdbc.queryForObject("SELECT unit_price FROM purchase_order_line WHERE order_id=?", BigDecimal.class, manual)).isEqualByComparingTo("9");
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="ERP_B07_CAPACITY",matches="true")
    void fiveHundredPurchaseLinesResolveFiveHundredTiersAndFreezeEverySourceWithinOneGigabyte() throws Exception {
        var tiers=java.util.stream.IntStream.rangeClosed(1,500).mapToObj(index->new PriceListService.Item(s(d.sku),s(d.unit),Integer.toString(index),
                new BigDecimal("10.00").subtract(BigDecimal.valueOf(index).movePointLeft(3)).toPlainString())).toList();
        lists.save(d.actor(d.maker),null,new PriceListService.Input("五百阶梯容量",true,d.day,d.day.plusDays(5),10,"active",null,tiers,
                List.of(new PriceListService.Target("supplier",s(d.customer)))),true);
        var pricingLines=java.util.stream.IntStream.range(0,500).mapToObj(index->new PricingModels.PreviewLine(s(d.sku),s(d.unit),s(d.warehouse),"500","10",null,null,null,"0.13")).toList();
        long started=System.nanoTime();
        var preview=pricing.preview(d.actor(d.maker),new PricingModels.PreviewRequest("purchase_order",null,s(d.customer),true,"0",pricingLines,List.of(),d.day));
        double previewMs=(System.nanoTime()-started)/1e6;
        assertThat(preview.priceSources()).hasSize(500).allSatisfy(source->{
            assertThat(new BigDecimal(source.suggestedUnitPrice())).isEqualByComparingTo("9.5");assertThat(source.abovePriceList()).isTrue();
        });
        var lines=java.util.stream.IntStream.range(0,500).mapToObj(index->new PurchaseOrderModels.Line(null,null,s(d.sku),s(d.warehouse),s(d.unit),"500","10",null,null,null,"0.13")).toList();
        started=System.nanoTime();
        long id=id(orders.create(d.actor(d.maker),key(),new PurchaseOrderModels.Input(s(d.customer),d.day,null,true,"0",lines,List.of(),"五百行取价容量",null,null)));
        approveLegacy(id);
        double postingMs=(System.nanoTime()-started)/1e6;
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order_line WHERE order_id=? AND price_source_snapshot IS NOT NULL",Long.class,id)).isEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT total_amount FROM purchase_order WHERE id=?",BigDecimal.class,id)).isEqualByComparingTo("2500000");
        assertThat(Runtime.getRuntime().maxMemory()).isLessThanOrEqualTo(1_073_741_824L);
        var root=java.nio.file.Path.of(System.getenv("ERP_TEST_ROOT")).toRealPath();
        var evidence=java.nio.file.Path.of(System.getenv("ERP_CAPACITY_EVIDENCE_DIR")).toRealPath();
        if(!evidence.startsWith(root))throw new IllegalStateException("容量证据目录必须位于隔离测试根目录");
        java.nio.file.Files.writeString(evidence.resolve("采购价格表容量.json"),json.writeValueAsString(Map.of("tiers",500,"lines",500,"previewMs",previewMs,
                "createSubmitApproveMs",postingMs,"maxHeapBytes",Runtime.getRuntime().maxMemory(),"sourceSnapshots",500,"totalAmount","2500000.00")),java.nio.file.StandardOpenOption.CREATE_NEW);
    }

    private PriceListService.View save(Long id, Integer version, String status) {
        return lists.save(d.actor(d.maker), id, new PriceListService.Input("供应商阶梯协议", true, d.day, d.day.plusDays(5), 10, status, version,
                List.of(new PriceListService.Item(s(d.sku), s(d.unit), "1", "11.30"), new PriceListService.Item(s(d.sku), s(d.unit), "10", "10.17")),
                List.of(new PriceListService.Target("supplier", s(d.customer)))), true);
    }
    private PricingModels.Preview preview(String qty, String price, boolean inclusive, LocalDate day, long unit) {
        return pricing.preview(d.actor(d.maker), request(qty, price, inclusive, day, unit));
    }
    private PricingModels.Preview previewAs(CompanyAccess actor, String qty, String price) {
        return pricing.preview(actor, request(qty, price, true, d.day, d.unit));
    }
    private PricingModels.PreviewRequest request(String qty, String price, boolean inclusive, LocalDate day, long unit) {
        return new PricingModels.PreviewRequest("purchase_order", null, s(d.customer), inclusive, "0",
                List.of(new PricingModels.PreviewLine(s(d.sku), s(unit), s(d.warehouse), qty, price, null, null, null, "0.13")), List.of(), day);
    }
    private long createOrder(String price, long unit, LocalDate day, Long version) {
        return id(orders.create(d.actor(d.maker), key(), new PurchaseOrderModels.Input(s(d.customer), day, null, true, "0",
                List.of(new PurchaseOrderModels.Line(null, null, s(d.sku), s(d.warehouse), s(unit), "2", price, null, null, null, "0.13")),
                List.of(), "采购取价验证", version, null)));
    }
    private void approveLegacy(long id) { command(id, "submit", d.maker); command(id, "approve", d.manager); }
    private void command(long id, String action, long user) {
        orders.command(d.actor(user), id, action, key(), new PurchaseOrderModels.Command(jdbc.queryForObject("SELECT version FROM purchase_order WHERE id=?", Long.class, id), null, null));
    }
    private CompanyAccess withoutCost(long user) {
        var actor = d.actor(user); var permissions = new HashSet<>(actor.permissions()); permissions.remove("cost:view");
        return new CompanyAccess(d.tenant, user, d.company, true, Set.of(), permissions, actor.orgScope());
    }
    private static String s(long id) { return Long.toString(id); }
    private static String key() { return UUID.randomUUID().toString(); }
    private static long id(Map<String,Object> result) { return Long.parseLong(result.get("id").toString()); }
    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
}
