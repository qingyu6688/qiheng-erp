package cn.erp.workflow;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.flowable.engine.ProcessEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import cn.erp.ErpApplication;
import cn.erp.accounting.LedgerService;
import cn.erp.adjustment.PriceAdjustmentModels;
import cn.erp.adjustment.PriceAdjustmentQueries;
import cn.erp.adjustment.PriceAdjustmentService;
import cn.erp.identity.AccessService;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryModels;
import cn.erp.inventory.InventoryService;
import cn.erp.purchasing.PurchaseOrderModels;
import cn.erp.purchasing.PurchaseOrderService;
import cn.erp.receiving.ReceivingModels;
import cn.erp.receiving.ReceivingService;
import cn.erp.reconciliation.ReconciliationChecks;
import cn.erp.reversal.ReversalModels;
import cn.erp.reversal.ReversalService;
import cn.erp.reporting.ReportService;
import cn.erp.reporting.ReportType;
import cn.erp.reporting.ReportQuery;
import cn.erp.salesorder.SalesOrderService;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import static cn.erp.workflow.WorkflowTestData.key;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = ErpApplication.class)
@ActiveProfiles("test")
class PriceAdjustmentIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessService access;
    @Autowired SalesOrderService sales;
    @Autowired WorkflowModels models;
    @Autowired WorkflowTasks tasks;
    @Autowired ProcessEngine engine;
    @Autowired PurchaseOrderService orders;
    @Autowired ReceivingService receiving;
    @Autowired PriceAdjustmentService prices;
    @Autowired PriceAdjustmentQueries queries;
    @Autowired InventoryService inventory;
    @Autowired LedgerService ledger;
    @Autowired ReversalService reversals;
    @Autowired ReconciliationChecks checks;
    @Autowired ReportService reports;
    @Autowired tools.jackson.databind.ObjectMapper json;
    @Autowired cn.erp.inventory.InventoryReversalService inventoryReversals;
    @Autowired cn.erp.salesfinance.SalesFinanceQueries finance;
    @Autowired cn.erp.stocktransfer.StockTransferService transfers;
    @Autowired WorkflowQueries flows;
    private WorkflowTestData d;
    private long period;

    @BeforeEach void prepare() {
        d = new WorkflowTestData(jdbc, access, sales, models);
        jdbc.update("UPDATE inventory_balance SET on_hand_qty=0 WHERE tenant_id=?", d.tenant);
        jdbc.update("UPDATE inventory_cost SET qty=0,total_cost=0 WHERE tenant_id=?", d.tenant);
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role,term_type,term_value) VALUES(?,?,?,'supplier','days',30)", Ids.next(), d.tenant, d.customer);
        period = jdbc.queryForObject("SELECT id FROM accounting_period WHERE tenant_id=?", Long.class, d.tenant);
    }
    @AfterEach void cleanup() { if (d != null) d.cleanup(engine); }

    @Test void partiallyIssuedReceiptCreatesPayableAndSeparatesInventoryFromCostVarianceThenReverses() {
        long source = receive("10", "10", "0");
        issue("6");
        long price = adjustment(source, "12", null);
        var metadata=json.valueToTree(queries.metadata(d.actor(d.maker)));
        assertThat(metadata.path("partners").get(0).path("id").asString()).isEqualTo(Long.toString(d.customer));
        var preview=json.valueToTree(prices.preview(d.actor(d.maker),price));
        assertThat(preview.at("/items/0/allocations/0/stockAmount").asString()).isEqualTo("8.00");
        assertThat(preview.at("/items/0/allocations/0/warehouseName").asString()).isNotBlank();
        var posted = approveAndPost(price);
        assertThat(posted).containsEntry("amount", "20.00").containsEntry("stockCost", "8.00").containsEntry("issuedCost", "12.00");
        assertThat(inventory.balance(d.actor(d.maker), new InventoryModels.Key(d.warehouse, d.sku)).cost()).isEqualByComparingTo("48");
        assertThat(jdbc.queryForObject("SELECT due_date FROM payable WHERE source_type='price_adjustment_line' AND source_id=?", java.sql.Date.class, price))
                .isEqualTo(java.sql.Date.valueOf(d.day.plusDays(30)));
        var query = new ReportQuery(d.day,d.day.plusDays(2),null,null,null,null,true);
        var purchase = reports.snapshot(d.actor(d.maker),ReportType.PURCHASE_SUMMARY,query);
        assertThat(purchase.summary()).containsEntry("amount","120.00");
        assertThat(purchase.items()).anySatisfy(row->assertThat(row).containsEntry("sourceId",Long.toString(price))
                .containsEntry("sourceUrl","/purchase-price-adjustments/"+price).containsEntry("amount","20.00"));
        var flow = reports.snapshot(d.actor(d.maker),ReportType.INVENTORY_FLOW,new ReportQuery(d.day,d.day.plusDays(2),null,null,null,null,false));
        assertThat(flow.summary()).containsEntry("closingCost","48.00");
        zeroDifferences();
        long reversal = id(reversals.create(d.actor(d.maker), key(), new ReversalModels.Input("price_adjustment", price, d.day.plusDays(2), "撤销误调价格")));
        reversals.command(d.actor(d.manager), reversal, "approve", key(), new ReversalModels.Version(0L, null));
        reversals.command(d.actor(d.maker), reversal, "post", key(), new ReversalModels.Version(1L, null));
        assertThat(queries.get(d.actor(d.maker), price)).containsEntry("status", "reversed");
        assertThat(inventory.balance(d.actor(d.maker), new InventoryModels.Key(d.warehouse, d.sku)).cost()).isEqualByComparingTo("40");
        assertThat(value("SELECT price_adjusted_amount FROM goods_receipt_line WHERE id=?", source)).isZero();
        assertThat(reports.snapshot(d.actor(d.maker),ReportType.PURCHASE_SUMMARY,query).summary()).containsEntry("amount","100.00");
        zeroDifferences();
    }

    @Test void adjustmentAfterPartialReturnDoesNotRepriceReturnedQuantity() {
        long source = receive("10", "10", "0");
        returned(source, "2");
        long price = adjustment(source, "12", null);
        assertThat(approveAndPost(price)).containsEntry("amount", "16.00").containsEntry("stockCost", "16.00");
        long returned = returned(source, "8");
        assertThat(value("SELECT debit_amount FROM purchase_return WHERE id=?", returned)).isEqualByComparingTo("96");
        assertThat(value("SELECT debited_amount FROM goods_receipt_line WHERE id=?", source)).isEqualByComparingTo("116");
        assertThat(inventory.balance(d.actor(d.maker), new InventoryModels.Key(d.warehouse, d.sku)).cost()).isZero();
        zeroDifferences();
    }

    @Test void mixedReceiptsAndOriginalCostReturnKeepTheCorrectRemainingSourceShare() {
        receive("10","10","0");
        long source=receive("10","10","0");
        issue("10");
        returned(source,"2");
        var posted=approveAndPost(adjustment(source,"12",null));
        assertThat(posted).containsEntry("amount","16.00").containsEntry("stockCost","6.00").containsEntry("issuedCost","10.00");
        assertThat(inventory.balance(d.actor(d.maker),new InventoryModels.Key(d.warehouse,d.sku)).cost()).isEqualByComparingTo("86.00");
        zeroDifferences();
    }

    @Test void largeReturnOfAnotherReceiptDoesNotOverstateRemainingStockOrDependOnBatchSelection() {
        long returnedSource=receive("10","10","0"),source=receive("10","10","0"),third=receive("10","10","0");
        issue("15");returned(returnedSource,"8");
        long single=adjustment(source,"12",null);
        long batch=id(prices.create(d.actor(d.maker),key(),new PriceAdjustmentModels.Input(null,d.customer,d.warehouse,d.day.plusDays(2),"多行选择一致性",
                List.of(new PriceAdjustmentModels.LineInput(source,"12",null),new PriceAdjustmentModels.LineInput(third,"12",null)))));
        assertThat(json.valueToTree(prices.preview(d.actor(d.maker),single)).at("/items/0/allocations/0/stockAmount").asString()).isEqualTo("7.00");
        assertThat(json.valueToTree(prices.preview(d.actor(d.maker),batch)).at("/items/0/allocations/0/stockAmount").asString()).isEqualTo("7.00");
        command(batch,"void",d.maker);
        assertThat(approveAndPost(single)).containsEntry("stockCost","7.00").containsEntry("issuedCost","13.00");zeroDifferences();
    }

    @Test void successiveAdjustmentsAndReturnsPreserveOriginalReceiptAndCumulativeAmounts() {
        long source = receive("10", "10", "0");
        long first = adjustment(source, "12", null);
        approveAndPost(first);
        returned(source, "2");
        long second = adjustment(source, "15", null);
        assertThat(approveAndPost(second)).containsEntry("amount", "24.00");
        assertThat(value("SELECT payable_amount FROM goods_receipt_line WHERE id=?", source)).isEqualByComparingTo("100");
        assertThat(value("SELECT unit_cost FROM goods_receipt_line WHERE id=?", source)).isEqualByComparingTo("10");
        returned(source, "3"); returned(source, "5");
        assertThat(value("SELECT debited_amount FROM goods_receipt_line WHERE id=?", source)).isEqualByComparingTo("144");
        zeroDifferences();
    }

    @Test void invoiceTaxAndSmallTaxpayerCostUseTheOriginalReceiptPolicy() {
        jdbc.update("UPDATE company_policy SET taxpayer_type='small' WHERE tenant_id=?", d.tenant);
        long source = receive("10", "11.30", "0.13");
        jdbc.update("UPDATE company_policy SET taxpayer_type='general' WHERE tenant_id=?", d.tenant);
        long price = adjustment(source, "12.43", null);
        var posted = approveAndPost(price);
        assertThat(posted).containsEntry("amount", "11.30").containsEntry("taxAmount", "1.30").containsEntry("stockCost", "11.30");
        assertThat(value("SELECT SUM(amount) FROM journal_line WHERE tenant_id=? AND account_code='INPUT_VAT'", d.tenant)).isNull();
        zeroDifferences();
    }

    @Test void requiresDifferentReviewerAndRejectsStaleSourcesBeforePosting() {
        long source = receive("10", "10", "0");
        long price = adjustment(source, "11", null);
        command(price, "submit", d.maker);
        assertThatThrownBy(() -> command(price, "approve", d.maker)).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getErrorCode()).isEqualTo("TWO_PERSON_REQUIRED"));
        command(price, "approve", d.manager);
        returned(source, "1");
        assertThatThrownBy(() -> command(price, "post", d.maker)).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getErrorCode()).isEqualTo("PRICE_SOURCE_CHANGED"));
        command(price, "void", d.maker);
        zeroDifferences();
    }

    @Test void actualWorkflowApprovalCallsTheBusinessCommandAndPreventsDirectBypass() {
        long source = receive("10", "10", "0");
        d.bind(WorkflowTestData.root(WorkflowTestData.approve("review", List.of(d.manager), "or", null)), "price_adjustment");
        long price = adjustment(source, "11", null);
        command(price, "submit", d.maker);
        assertThatThrownBy(() -> command(price, "approve", d.manager)).isInstanceOf(ApiException.class);
        var filter=new WorkflowQueries.Filter("price_adjustment",null,null,null,null);
        assertThat(flows.list(d.actor(d.manager),"all",null,30,filter).get("items")).asList().hasSize(1);
        var full=d.actor(d.manager);
        var outside=new CompanyAccess(full.tenantId(),full.userId(),full.companyId(),false,Set.of(Ids.next()),full.permissions());
        assertThat(flows.list(outside,"all",null,30,filter).get("items")).asList().isEmpty();
        var permissions=new java.util.HashSet<>(full.permissions());permissions.remove("cost:view");
        var noCost=new CompanyAccess(full.tenantId(),full.userId(),full.companyId(),true,Set.of(),permissions);
        assertThat(flows.list(noCost,"all",null,30,filter).get("items")).asList().isEmpty();
        var task = jdbc.queryForMap("SELECT t.id,t.version FROM wf_task_ext t JOIN wf_instance i ON i.id=t.instance_id AND i.tenant_id=t.tenant_id WHERE i.tenant_id=? AND i.business_type='price_adjustment' AND i.business_id=? AND t.status='pending'", d.tenant, price);
        tasks.decide(d.actor(d.manager), ((Number) task.get("id")).longValue(), "approve", key(),
                new WorkflowTasks.Decision(((Number) task.get("version")).longValue(), null, null, null, null, null, null));
        assertThat(queries.get(d.actor(d.maker), price)).containsEntry("status", "approved");
        command(price, "post", d.maker);
        zeroDifferences();
    }

    @Test void oldIdempotentResultCannotBypassRevokedCostPermission() {
        long source = receive("10", "10", "0");
        var input = input(source, "11", null);
        String key = key();
        var created = prices.create(d.actor(d.maker), key, input);
        assertThat(prices.create(d.actor(d.maker), key, input).get("id")).isEqualTo(created.get("id"));
        var permissions = new java.util.HashSet<>(d.actor(d.maker).permissions()); permissions.remove("cost:view");
        var restricted = new CompanyAccess(d.tenant, d.maker, d.company, true, Set.of(), permissions);
        assertThatThrownBy(() -> prices.create(restricted, key, input)).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(403));
        assertThatThrownBy(() -> queries.get(restricted, id(created))).isInstanceOf(ApiException.class);
    }

    @Test void starterSelectedReviewerIsValidatedAndPassedToTheExistingWorkflow() {
        long source=receive("2","10","0");
        var reviewer=new SimpleModel.Node("select","approver","自选复核人",new SimpleModel.Assignee("starter_select",null,null,null,null,null,null,"admin"),"or",null,null,null,false);
        d.bind(WorkflowTestData.root(reviewer),"price_adjustment");
        long price=adjustment(source,"12",null);
        prices.command(d.actor(d.maker),price,"submit",key(),new PriceAdjustmentModels.Command(0L,null,Map.of("select",List.of(d.manager))));
        assertThat(jdbc.queryForList("SELECT assignee_id FROM wf_task_ext WHERE tenant_id=? AND status='pending'",Long.class,d.tenant)).containsExactly(d.manager);
        assertThat(queries.get(d.actor(d.maker),price).get("allowedActions")).isEqualTo(List.of());
    }

    @Test void closedPeriodDoesNotLeaveCostOrPayableFacts() {
        long price=adjustment(receive("10","10","0"),"12",null);
        command(price,"submit",d.maker);command(price,"approve",d.manager);
        jdbc.update("UPDATE accounting_period SET status='closed',closed_at=UTC_TIMESTAMP(3) WHERE id=?",period);
        assertCode(()->command(price,"post",d.maker),"PERIOD_CLOSED");
        assertThat(queries.get(d.actor(d.maker),price)).containsEntry("status","approved");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM price_adjustment_cost WHERE adjustment_id=?",Long.class,price)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payable WHERE source_type='price_adjustment_line' AND source_id=?",Long.class,price)).isZero();
        jdbc.update("UPDATE accounting_period SET status='open',closed_at=NULL WHERE id=?",period);
        command(price,"post",d.maker);zeroDifferences();
    }

    @Test void concurrentAdjustmentsOfTheSameSourceCannotBothUseAnOldPrice() throws Exception {
        long source=receive("10","10","0");
        long first=adjustment(source,"12",null),second=adjustment(source,"13",null);
        for(long price:List.of(first,second)) {command(price,"submit",d.maker);command(price,"approve",d.manager);}
        var ready=new java.util.concurrent.CountDownLatch(2);var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var results=List.of(first,second).stream().map(price->pool.submit(()->{
                ready.countDown();if(!start.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("并发测试启动超时");
                try {command(price,"post",d.maker);return "posted";}
                catch(ApiException error) {return error.getErrorCode();}
            })).toList();
            assertThat(ready.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(List.of(results.get(0).get(20,java.util.concurrent.TimeUnit.SECONDS),results.get(1).get(20,java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("posted","PRICE_SOURCE_CHANGED");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payable WHERE tenant_id=? AND source_type='price_adjustment_line'",Long.class,d.tenant)).isEqualTo(1);
        for(long price:List.of(first,second)) if(queries.get(d.actor(d.maker),price).get("status").equals("approved")) command(price,"void",d.maker);
        zeroDifferences();
    }

    @Test void laterInventoryUseBlocksReversalUntilThatUseIsReversed() {
        long price=adjustment(receive("10","10","0"),"12",null);approveAndPost(price);
        long reversal=reversal(price);long issue=issue("2");
        assertCode(()->reversals.command(d.actor(d.maker),reversal,"post",key(),new ReversalModels.Version(1L,null)),"DEPENDENCY_EXISTS");
        inventoryReversals.reverse(d.actor(d.maker),"shipment",issue,Ids.next(),period,d.day.plusDays(2));
        long journal=jdbc.queryForObject("SELECT id FROM journal WHERE source_type='shipment_post' AND source_id=?",Long.class,issue);
        ledger.reverse(d.actor(d.maker),journal,"test_reverse",Ids.next(),0,period,d.day.plusDays(2));
        reversals.command(d.actor(d.maker),reversal,"post",key(),new ReversalModels.Version(1L,null));
        assertThat(queries.get(d.actor(d.maker),price)).containsEntry("status","reversed");zeroDifferences();
    }

    @Test void laterReturnsAndLaterAdjustmentsMustBeHandledBeforeReversal() {
        long source=receive("10","10","0");long first=adjustment(source,"12",null);approveAndPost(first);
        long reversal=reversal(first);long second=adjustment(source,"13",null);
        assertCode(()->reversals.command(d.actor(d.maker),reversal,"post",key(),new ReversalModels.Version(1L,null)),"DEPENDENCY_EXISTS");
        command(second,"void",d.maker);returned(source,"1");
        assertCode(()->reversals.command(d.actor(d.maker),reversal,"post",key(),new ReversalModels.Version(1L,null)),"DEPENDENCY_EXISTS");
    }

    @Test void departmentWarehouseCompanyAndTenantScopesApplyToDetailsSourcesAndFinancialActions() {
        long source=receive("10","10","0");long price=adjustment(source,"12",null);
        var actor=d.actor(d.manager);
        for(var restricted:List.of(
                new CompanyAccess(Ids.next(),actor.userId(),actor.companyId(),true,Set.of(),actor.permissions()),
                new CompanyAccess(actor.tenantId(),actor.userId(),Ids.next(),true,Set.of(),actor.permissions()),
                new CompanyAccess(actor.tenantId(),actor.userId(),actor.companyId(),false,Set.of(Ids.next()),actor.permissions()),
                new CompanyAccess(actor.tenantId(),actor.userId(),actor.companyId(),true,Set.of(),actor.permissions(),cn.erp.shared.OrgScope.restricted(Set.of(Ids.next()),Set.of(),Ids.next())))) {
            assertCode(()->queries.get(restricted,price),"NOT_FOUND");
            assertThat(queries.list(restricted,null,null,null,30).get("items")).asList().isEmpty();
            assertThatThrownBy(()->prices.create(restricted,key(),input(source,"13",null))).isInstanceOfSatisfying(ApiException.class,
                    error->assertThat(error.getStatus()).isIn(400,403,404,422));
        }
        command(price,"submit",d.maker);
        var warehouseOnly=new CompanyAccess(actor.tenantId(),actor.userId(),actor.companyId(),false,Set.of(d.warehouse),actor.permissions());
        assertCode(()->prices.command(warehouseOnly,price,"approve",key(),new PriceAdjustmentModels.Command(1L,null)),"FORBIDDEN");
        command(price,"reject",d.manager,"原合同价格需要核对");
        var audit=jdbc.queryForObject("SELECT after_data FROM audit_log WHERE object_id=? AND action='price_adjustment.reject'",String.class,price);
        assertThat(json.readTree(audit).path("operationReason").asString()).isEqualTo("原合同价格需要核对");
    }

    @Test void transferredCostCannotLeakThroughPayablesOpenItemsReportsOrOldResponses() {
        long source=receive("10","10","0"),target=Ids.next();
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'SECOND','调入仓')",target,d.tenant,d.company);
        var transfer=new cn.erp.stocktransfer.StockTransferModels.Input(d.warehouse,target,d.day,null,
                List.of(new cn.erp.stocktransfer.StockTransferModels.Line(d.sku,d.unit,"4")),null);
        long doc=id(transfers.create(d.actor(d.maker),key(),transfer));
        transfers.command(d.actor(d.maker),doc,"submit",key(),new cn.erp.stocktransfer.StockTransferModels.Version(0L,d.day));
        transfers.command(d.actor(d.maker),doc,"ship",key(),new cn.erp.stocktransfer.StockTransferModels.Version(1L,d.day));
        long line=jdbc.queryForObject("SELECT id FROM stock_transfer_line WHERE transfer_id=?",Long.class,doc);
        transfers.receive(d.actor(d.maker),doc,key(),new cn.erp.stocktransfer.StockTransferModels.Receive(2L,d.day,true,
                List.of(new cn.erp.stocktransfer.StockTransferModels.ReceivedLine(line,"4"))));
        String createKey=key();var input=input(source,"12",null);long price=id(prices.create(d.actor(d.maker),createKey,input));approveAndPost(price);
        var full=d.actor(d.maker);var restricted=new CompanyAccess(full.tenantId(),full.userId(),full.companyId(),false,Set.of(d.warehouse),full.permissions());
        assertCode(()->queries.get(restricted,price),"NOT_FOUND");
        assertCode(()->prices.create(restricted,createKey,input),"NOT_FOUND");
        assertThat(queries.list(restricted,null,null,null,30).get("items")).asList().isEmpty();
        assertThat(json.valueToTree(finance.payables(restricted,null,false,null,100)).path("items").findValuesAsString("sourceNo")).noneMatch(no->no.startsWith("PA"));
        assertThat(json.valueToTree(finance.openItems(restricted,null,"ap",false,null,100)).path("items").findValuesAsString("sourceNo")).noneMatch(no->no.startsWith("PA"));
        assertThat(reports.snapshot(restricted,ReportType.PURCHASE_SUMMARY,new ReportQuery(d.day,d.day.plusDays(2),null,null,null,null,true)).items())
                .noneSatisfy(row->assertThat(row).containsEntry("sourceId",Long.toString(price)));
        long reversal=reversal(price);
        reversals.command(full,reversal,"post",key(),new ReversalModels.Version(1L,null));
        var permissions=new java.util.HashSet<>(full.permissions());permissions.remove("cost:view");
        var noCost=new CompanyAccess(full.tenantId(),full.userId(),full.companyId(),true,Set.of(),permissions);
        var statement=reports.snapshot(noCost,ReportType.PARTNER_STATEMENT,new ReportQuery(d.day,d.day.plusDays(2),null,null,null,null,false));
        assertThat(statement.summary()).containsEntry("debitAmount","0.00").containsEntry("creditAmount","100.00");
        zeroDifferences();
    }

    private long reversal(long price) {
        long reversal=id(reversals.create(d.actor(d.maker),key(),new ReversalModels.Input("price_adjustment",price,d.day.plusDays(2),"调价冲销验证")));
        reversals.command(d.actor(d.manager),reversal,"approve",key(),new ReversalModels.Version(0L,null));return reversal;
    }

    @Test void combinedLineAmountsAreValidatedBeforeDatabaseOverflow() {
        long first=receive("1000000","1","0"),second=receive("1000000","1","0");
        var lines=List.of(new PriceAdjustmentModels.LineInput(first,null,"5000000000000000000000.00"),
                new PriceAdjustmentModels.LineInput(second,null,"5000000000000000000000.00"));
        assertCode(()->prices.create(d.actor(d.maker),key(),new PriceAdjustmentModels.Input(null,d.customer,d.warehouse,d.day,"金额范围验证",lines)),"VALIDATION_FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM price_adjustment WHERE tenant_id=?",Long.class,d.tenant)).isZero();
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="ERP_B02_CAPACITY",matches="true")
    void hundredSourceLinesReplayTwentyThousandHistoryMovementsWithoutLosingCost() throws Exception {
        var sources=new java.util.ArrayList<Long>();
        for(int index=0;index<100;index++) sources.add(receive("10","10","0"));
        var rows=new java.util.ArrayList<Object[]>();
        for(int index=0;index<10000;index++) {
            long movement=Ids.next(),reverse=Ids.next(),source=Ids.next();
            rows.add(new Object[]{movement,d.tenant,d.company,d.warehouse,d.sku,"sales_out","post",source,source,-1,-10,null,d.day,period,d.maker});
            rows.add(new Object[]{reverse,d.tenant,d.company,d.warehouse,d.sku,"sales_out","reverse",source,source,1,10,movement,d.day,period,d.maker});
        }
        // 历史样本以已冲销出库成对追加，净数量与净成本为零，不修改真实收货快照。
        jdbc.batchUpdate("INSERT INTO inventory_movement(id,tenant_id,company_id,warehouse_id,sku_id,movement_type,posting_action,source_type,source_id,source_line_id,on_hand_delta,cost_delta,reverses_movement_id,business_date,period_id,posted_at,created_by)"
                +" VALUES(?,?,?,?,?,?,?,'capacity_history',?,?,?,?,?,?,?,UTC_TIMESTAMP(3),?)",rows);
        var input=new PriceAdjustmentModels.Input(null,d.customer,d.warehouse,d.day.plusDays(1),"百行历史成本容量验证",
                sources.stream().map(source->new PriceAdjustmentModels.LineInput(source,"11",null)).toList());
        long price=id(prices.create(d.actor(d.maker),key(),input));
        var previews=new java.util.ArrayList<Double>();
        for(int round=0;round<3;round++) {
            long started=System.nanoTime();
            assertThat(json.valueToTree(prices.preview(d.actor(d.maker),price)).path("items").size()).isEqualTo(100);
            previews.add((System.nanoTime()-started)/1e6);
        }
        long started=System.nanoTime();var result=approveAndPost(price);double postedMs=(System.nanoTime()-started)/1e6;
        assertThat(result).containsEntry("amount","1000.00").containsEntry("stockCost","1000.00").containsEntry("issuedCost","0.00");
        zeroDifferences();
        var root=java.nio.file.Path.of(System.getenv("ERP_TEST_ROOT")).toRealPath();
        java.nio.file.Files.writeString(root.resolve("B02-采购调价容量.json"),json.writeValueAsString(Map.of("sourceLines",100,"historyMovements",20000,
                "maxHeapBytes",Runtime.getRuntime().maxMemory(),"previewMs",previews,"submitApprovePostMs",postedMs,"amount",result.get("amount"),"stockCost",result.get("stockCost"),"issuedCost",result.get("issuedCost"),"reconciliationDifferences",0)),
                java.nio.file.StandardOpenOption.CREATE_NEW);
    }
    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getErrorCode()).isEqualTo(code));
    }

    private long receive(String qty, String price, String tax) {
        var input = new PurchaseOrderModels.Input(Long.toString(d.customer), d.day, null, true, "0",
                List.of(new PurchaseOrderModels.Line(null, null, Long.toString(d.sku), Long.toString(d.warehouse), Long.toString(d.unit), qty, price, null, null, null, tax)), List.of(), null, null, null);
        long order = id(orders.create(d.actor(d.maker), key(), input));
        var submitted = orders.command(d.actor(d.maker), order, "submit", key(), new PurchaseOrderModels.Command(0L, null, null));
        orders.command(d.actor(d.manager), order, "approve", key(), new PurchaseOrderModels.Command(((Number) submitted.get("version")).longValue(), null, null));
        long line = jdbc.queryForObject("SELECT id FROM purchase_order_line WHERE order_id=?", Long.class, order);
        long receipt = id(receiving.createReceipt(d.actor(d.maker), key(), new ReceivingModels.ReceiptInput(d.customer, d.warehouse, d.day, null,
                List.of(new ReceivingModels.ReceiptLine(line, d.unit, qty)), null)));
        receiving.command(d.actor(d.maker), false, receipt, "submit", key(), 0L);
        receiving.command(d.actor(d.maker), false, receipt, "post", key(), 1L);
        return jdbc.queryForObject("SELECT id FROM goods_receipt_line WHERE receipt_id=?", Long.class, receipt);
    }

    private long adjustment(long source, String unitPrice, String delta) { return id(prices.create(d.actor(d.maker), key(), input(source, unitPrice, delta))); }
    private PriceAdjustmentModels.Input input(long source, String price, String delta) {
        return new PriceAdjustmentModels.Input(null, d.customer, d.warehouse, d.day.plusDays(1), "采购结算调价", List.of(new PriceAdjustmentModels.LineInput(source, price, delta)));
    }
    private Map<String, Object> approveAndPost(long id) { command(id, "submit", d.maker); command(id, "approve", d.manager); return command(id, "post", d.maker); }
    private Map<String, Object> command(long id, String action, long user) {
        return command(id,action,user,null);
    }
    private Map<String,Object> command(long id,String action,long user,String reason) {
        return prices.command(d.actor(user), id, action, key(), new PriceAdjustmentModels.Command(((Number) queries.get(d.actor(user), id).get("version")).longValue(), reason));
    }
    private long returned(long source, String quantity) {
        long id = id(receiving.createReturn(d.actor(d.maker), key(), new ReceivingModels.ReturnInput(d.customer, d.warehouse, d.day.plusDays(2), "调价退货验证",
                List.of(new ReceivingModels.ReturnLine(source, d.unit, quantity)), null)));
        receiving.command(d.actor(d.maker), true, id, "submit", key(), 0L); receiving.command(d.actor(d.maker), true, id, "post", key(), 1L);
        return id;
    }
    private long issue(String qty) {
        long source = Ids.next();
        var value = inventory.issue(d.actor(d.maker), new InventoryModels.Posting(new InventoryModels.Key(d.warehouse, d.sku), new InventoryModels.Source("shipment_line", source, source), d.day, period, "sales_out"), new BigDecimal(qty), null);
        ledger.post(d.actor(d.maker), new LedgerService.Posting("shipment_post", source, 0, period, d.day, List.of(
                new LedgerService.Line("COGS", "D", value.movementCost(), null, null, null, null, source, "测试已出库成本"),
                new LedgerService.Line("INVENTORY", "C", value.movementCost(), null, null, d.warehouse, d.sku, source, "测试已出库成本"))));
        return source;
    }
    private void zeroDifferences() {
        for (String type : List.of("inventory_balance", "inventory_cost", "reservation", "cash_balance", "open_item", "journal_balance", "ledger_inventory", "period_close"))
            assertThat(checks.check(d.actor(d.maker), type, type.equals("period_close") ? period : null)).as(type).isEmpty();
    }
    private BigDecimal value(String sql, long id) { return jdbc.queryForObject(sql, BigDecimal.class, id); }
    private static long id(Map<String, Object> result) { return Long.parseLong(result.get("id").toString()); }
}
