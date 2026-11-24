package cn.erp.workflow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.flowable.engine.ProcessEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import cn.erp.ErpApplication;
import cn.erp.adjustment.*;
import cn.erp.fulfillment.ShipmentModels;
import cn.erp.fulfillment.ShipmentService;
import cn.erp.identity.AccessService;
import cn.erp.identity.CompanyAccess;
import cn.erp.invoicing.InvoiceService;
import cn.erp.invoicing.ManualInvoiceRedService;
import cn.erp.purchasing.PurchaseOrderModels;
import cn.erp.purchasing.PurchaseOrderService;
import cn.erp.receiving.ReceivingModels;
import cn.erp.receiving.ReceivingService;
import cn.erp.reconciliation.ReconciliationChecks;
import cn.erp.reversal.ReversalModels;
import cn.erp.reversal.ReversalService;
import cn.erp.reporting.ReportQuery;
import cn.erp.reporting.ReportService;
import cn.erp.reporting.ReportType;
import cn.erp.salesfinance.FinanceModels;
import cn.erp.salesfinance.SalesReturnService;
import cn.erp.salesfinance.SalesFinanceQueries;
import cn.erp.salesorder.SalesOrderModels;
import cn.erp.salesorder.SalesOrderService;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import cn.erp.shared.SalesInvoiceAmounts;
import cn.erp.shared.CommandResults;
import static cn.erp.workflow.WorkflowTestData.key;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
class SalesPriceAdjustmentIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessService access;
    @Autowired SalesOrderService sales;
    @Autowired WorkflowModels models;
    @Autowired ProcessEngine engine;
    @Autowired PurchaseOrderService purchases;
    @Autowired ReceivingService receiving;
    @Autowired ShipmentService shipments;
    @Autowired SalesReturnService returns;
    @Autowired SalesFinanceQueries finance;
    @Autowired SalesPriceAdjustmentService prices;
    @Autowired SalesPriceAdjustmentQueries queries;
    @Autowired PriceAdjustmentQueries purchasePrices;
    @Autowired InvoiceService invoices;
    @Autowired ManualInvoiceRedService reds;
    @Autowired SalesInvoiceAmounts amounts;
    @Autowired ReversalService reversals;
    @Autowired ReconciliationChecks checks;
    @Autowired WorkflowTasks tasks;
    @Autowired CommandResults results;
    @Autowired ReportService reports;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    private WorkflowTestData d;
    private long period;

    @BeforeEach void setup() {
        d=new WorkflowTestData(jdbc,access,sales,models);
        jdbc.update("UPDATE inventory_balance SET on_hand_qty=0 WHERE tenant_id=?",d.tenant);
        jdbc.update("UPDATE inventory_cost SET qty=0,total_cost=0 WHERE tenant_id=?",d.tenant);
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(?,?,?,'supplier')",Ids.next(),d.tenant,d.customer);
        jdbc.update("UPDATE partner_role SET term_type='days',term_value=30 WHERE tenant_id=? AND role='customer'",d.tenant);
        period=jdbc.queryForObject("SELECT id FROM accounting_period WHERE tenant_id=?",Long.class,d.tenant);
        receive();
    }
    @AfterEach void cleanup() {
        if(d==null)return;
        for(String table:List.of("invoice_red_registration","invoice_registration_line","invoice_registration","sales_return_line","sales_return"))jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",d.tenant);
        jdbc.update("DELETE FROM receivable WHERE tenant_id=? AND reverses_receivable_id IS NOT NULL",d.tenant);
        d.cleanup(engine);
    }

    @Test void supplementAndReversalPreserveInventoryCostAndOriginalRevenueWithoutCostRights() {
        long source=ship("10","10","0",null);
        var stock=jdbc.queryForList("SELECT * FROM inventory_cost WHERE tenant_id=?",d.tenant);
        long adjustment=adjust(source,"12",null);
        assertThat(post(adjustment)).containsEntry("amount","20.00").doesNotContainKeys("stockCost","issuedCost");
        assertThat(jdbc.queryForList("SELECT * FROM inventory_cost WHERE tenant_id=?",d.tenant)).isEqualTo(stock);
        assertThat(value("SELECT receivable_amount FROM shipment_line WHERE id=?",source)).isEqualByComparingTo("100");
        assertThat(value("SELECT signed_cost_amount FROM shipment_line WHERE id=?",source)).isEqualByComparingTo("50");
        assertThat(jdbc.queryForObject("SELECT due_date FROM receivable WHERE source_type='price_adjustment_line' AND source_id=?",java.sql.Date.class,adjustment))
                .isEqualTo(java.sql.Date.valueOf(d.day.plusDays(30)));
        reverse("sales_price_adjustment",adjustment);
        assertThat(queries.get(actor(d.maker),adjustment)).containsEntry("status","reversed");
        assertThat(value("SELECT price_adjusted_amount FROM shipment_line WHERE id=?",source)).isZero();
        assertThat(jdbc.queryForObject("SELECT pricing_return_basis FROM shipment_line WHERE id=?",String.class,source)).isNull();
        zeroDifferences();
    }

    @Test void partialSignAndSuccessiveReturnsUseNewRevenueAndOriginalCost() {
        long source=ship("10","10","0","6");
        assertThat(post(adjust(source,"12",null))).containsEntry("amount","12.00");
        long first=returned(source,"2",d.day.plusDays(3));
        assertThat(value("SELECT credit_amount FROM sales_return WHERE id=?",first)).isEqualByComparingTo("24");
        assertThat(value("SELECT cost_amount FROM sales_return WHERE id=?",first)).isEqualByComparingTo("10");
        assertThat(post(adjust(source,"15",null))).containsEntry("amount","12.00");
        long last=returned(source,"4",d.day.plusDays(3));
        assertThat(value("SELECT credit_amount FROM sales_return WHERE id=?",last)).isEqualByComparingTo("60");
        assertThat(value("SELECT cost_amount FROM sales_return WHERE id=?",last)).isEqualByComparingTo("20");
        assertThat(value("SELECT credited_amount FROM shipment_line WHERE id=?",source)).isEqualByComparingTo("84");
        assertThat(value("SELECT receivable_amount FROM shipment_line WHERE id=?",source)).isEqualByComparingTo("60");
        assertThat(value("SELECT signed_cost_amount FROM shipment_line WHERE id=?",source)).isEqualByComparingTo("30");
    }

    @Test void ordinaryVoidCannotAuthorizeDiscountAndRealManualRedAllowsOnlyNetInvoicing() {
        long source=ship("10","10","0",null),ar=originalAr(source);
        long blue=id(invoices.create(actor(d.maker),key(),invoice(ar,"100","0")));
        long adjustment=adjust(source,"8",null);approve(adjustment);
        code(()->command(adjustment,"post",d.maker),"SALES_RED_REQUIRED");
        invoices.voidInvoice(actor(d.maker),blue,0,key());
        code(()->command(adjustment,"post",d.maker),"SALES_RED_REQUIRED");
        long red=id(reds.create(actor(d.maker),key(),new ManualInvoiceRedService.Input(blue,1L,"RED-"+blue,d.day.plusDays(3),"100","0","原票整票红字")));
        reds.command(actor(d.manager),red,"confirm",key(),new ManualInvoiceRedService.Revision(0L));
        assertThat(command(adjustment,"post",d.maker)).containsEntry("amount","-20.00");
        invoices.create(actor(d.maker),key(),invoice(ar,"80","0"));
        code(()->invoices.create(actor(d.maker),key(),invoice(ar,"0.01","0")),"INVOICE_AMOUNT_EXCEEDED");
        zeroDifferences();
    }

    @Test void supplementAfterIssuedOriginalHasItsOwnVatQuotaAndFrozenDueDate() {
        long source=ship("10","11.30","0.13",null),ar=originalAr(source);
        invoices.create(actor(d.maker),key(),invoice(ar,"113","13"));
        long adjustment=adjust(source,"12.43",null);
        assertThat(post(adjustment)).containsEntry("amount","11.30").containsEntry("taxAmount","1.30");
        long extra=jdbc.queryForObject("SELECT id FROM receivable WHERE source_type='price_adjustment_line' AND source_id=?",Long.class,adjustment);
        var capacities=amounts.capacities(actor(d.maker),List.of(ar,extra));
        assertThat(capacities.get(ar).available()).isZero();assertThat(capacities.get(extra).available()).isEqualByComparingTo("11.30");
        assertThat(invoices.create(actor(d.maker),key(),invoice(extra,"11.30","1.30"))).containsEntry("taxDiffAmount","0.00");
        zeroDifferences();
    }

    @Test void earlierReturnReducesOriginalQuotaBeforeNewChargeAndMixedInvoiceBalancesTax() {
        long source=ship("10","11.30","0.13",null),ar=originalAr(source);
        returned(source,"2",d.day.plusDays(1));
        long adjustment=adjust(source,"12.43",null);assertThat(post(adjustment)).containsEntry("amount","9.04");
        long extra=jdbc.queryForObject("SELECT id FROM receivable WHERE source_type='price_adjustment_line' AND source_id=?",Long.class,adjustment);
        var input=new InvoiceService.Input("sales",d.customer,key(),d.day.plusDays(3),"99.44","11.44",
                List.of(new InvoiceService.Line(ar,"90.40"),new InvoiceService.Line(extra,"9.04")),null);
        var before=jdbc.queryForList("SELECT id,amount,tax_amount,invoiced_amount FROM receivable WHERE tenant_id=? ORDER BY id",d.tenant);
        var candidates=finance.receivables(actor(d.maker),d.customer,false,null,200,true);
        assertThat(before).isEqualTo(jdbc.queryForList("SELECT id,amount,tax_amount,invoiced_amount FROM receivable WHERE tenant_id=? ORDER BY id",d.tenant));
        @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)candidates.get("items");
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().filter(row->row.get("id").equals(""+ar)).findFirst().orElseThrow())
                .containsEntry("amount","113.00").containsEntry("invoiceAvailableAmount","90.40").containsEntry("invoiceAvailableTaxAmount","10.40");
        var adjustmentRow=rows.stream().filter(row->row.get("id").equals(""+extra)).findFirst().orElseThrow();
        assertThat(adjustmentRow).containsEntry("invoiceAvailableAmount","9.04").containsEntry("invoiceAvailableTaxAmount","1.04")
                .containsEntry("sourceNo",queries.get(actor(d.maker),adjustment).get("documentNo"));
        assertThat(adjustmentRow.get("skuName")).isNotNull();
        var first=finance.receivables(actor(d.maker),d.customer,false,null,1,true);
        assertThat(first.get("nextCursor")).isNotNull();
        @SuppressWarnings("unchecked") var next=(List<Map<String,Object>>)finance.receivables(actor(d.maker),d.customer,false,Long.parseLong(first.get("nextCursor").toString()),1,true).get("items");
        assertThat(next).hasSize(1);
        var blue=invoices.create(actor(d.maker),key(),input);
        assertThat(blue).containsEntry("taxDiffAmount","0.00");
        @SuppressWarnings("unchecked") var sources=(List<Map<String,Object>>)blue.get("lines");
        assertThat(sources).hasSize(2).allSatisfy(row->{assertThat(row.get("sourceNo")).isNotNull();assertThat(row.get("skuName")).isNotNull();assertThat(row).doesNotContainKeys("costAmount","signedCostAmount");});
        assertThat(sources.stream().map(row->row.get("id"))).doesNotContain(""+ar,""+extra);
        @SuppressWarnings("unchecked") var used=(List<Map<String,Object>>)finance.receivables(actor(d.maker),d.customer,false,null,200,true).get("items");
        assertThat(used).allSatisfy(row->assertThat(new BigDecimal(row.get("invoiceAvailableAmount").toString())).isZero());
        var restricted=new CompanyAccess(d.tenant,d.maker,d.company,false,Set.of(Ids.next()),actor(d.maker).permissions());
        assertThat((List<?>)finance.receivables(restricted,d.customer,false,null,200,true).get("items")).isEmpty();
        zeroDifferences();
    }

    @Test void differentReviewerClosedPeriodAndChangedSourceAreEnforced() {
        long source=ship("10","10","0",null),adjustment=adjust(source,"12",null);
        command(adjustment,"submit",d.maker);code(()->command(adjustment,"approve",d.maker),"TWO_PERSON_REQUIRED");
        command(adjustment,"approve",d.manager);returned(source,"1",d.day.plusDays(1));
        code(()->command(adjustment,"post",d.maker),"PRICE_SOURCE_CHANGED");command(adjustment,"void",d.maker);
        long next=adjust(source,"12",null);approve(next);
        jdbc.update("UPDATE accounting_period SET status='closed',closed_at=UTC_TIMESTAMP(3) WHERE id=?",period);
        code(()->command(next,"post",d.maker),"PERIOD_CLOSED");
    }

    @Test void actualWorkflowReviewAndCrossKindQueriesRespectSalesPermissionsWithoutCost() {
        long source=ship("10","10","0",null);
        jdbc.update("DELETE rp FROM sys_role_permission rp JOIN sys_permission p ON p.id=rp.permission_id WHERE rp.tenant_id=? AND p.code='cost:view'",d.tenant);
        d.bind(WorkflowTestData.root(WorkflowTestData.approve("review",List.of(d.manager),"or",null)),"sales_price_adjustment");
        long adjustment=adjust(source,"12",null);command(adjustment,"submit",d.maker);
        code(()->command(adjustment,"approve",d.manager),"WORKFLOW_IN_PROGRESS");
        var task=jdbc.queryForMap("SELECT t.id,t.version FROM wf_task_ext t JOIN wf_instance i ON i.tenant_id=t.tenant_id AND i.company_id=t.company_id AND i.id=t.instance_id WHERE i.tenant_id=? AND i.business_type='sales_price_adjustment' AND i.business_id=? AND t.status='pending'",d.tenant,adjustment);
        tasks.decide(d.actor(d.manager),((Number)task.get("id")).longValue(),"approve",key(),new WorkflowTasks.Decision(((Number)task.get("version")).longValue(),null,null,null,null,null,null));
        assertThat(command(adjustment,"post",d.maker)).containsEntry("status","posted");
        code(()->purchasePrices.get(d.actor(d.maker),adjustment),"FORBIDDEN");
        var outside=new CompanyAccess(d.tenant,d.maker,d.company,false,Set.of(Ids.next()),d.actor(d.maker).permissions());
        code(()->queries.get(outside,adjustment),"NOT_FOUND");
    }

    @Test void aSixteenCharacterCompanyCodeProducesACompleteSalesAdjustmentNumber() {
        jdbc.update("UPDATE company SET code='ABCDEFGHIJKLMNOP' WHERE id=?",d.company);
        long source=ship("10","10","0",null),adjustment=adjust(source,"12",null);
        assertThat(queries.get(actor(d.maker),adjustment).get("documentNo").toString()).hasSize(33).startsWith("SA-ABCDEFGHIJKLMNOP-");
    }

    @Test void zeroPriceSignCanBeSupplementedAndFullyReturnedWithoutRepricingCost() {
        long source=ship("10","0","0",null);
        assertThat(post(adjust(source,"2",null))).containsEntry("amount","20.00");
        long returned=returned(source,"10",d.day.plusDays(3));
        assertThat(value("SELECT credit_amount FROM sales_return WHERE id=?",returned)).isEqualByComparingTo("20");
        assertThat(value("SELECT cost_amount FROM sales_return WHERE id=?",returned)).isEqualByComparingTo("50");
        zeroDifferences();
    }

    @Test void cachedWholeInvoiceSummaryIsHiddenAfterOneOfItsSourceWarehousesIsRevoked() {
        long source=ship("10","10","0",null),ar=originalAr(source),otherWarehouse=Ids.next(),otherShipment=Ids.next(),otherAr=Ids.next();
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'CACHE-B','摘要范围仓')",otherWarehouse,d.tenant,d.company);
        jdbc.update("INSERT INTO shipment(id,tenant_id,company_id,shipment_no,customer_id,warehouse_id,business_date) VALUES(?,?,?,'CACHE-SOURCE',?,?,?)",otherShipment,d.tenant,d.company,d.customer,otherWarehouse,d.day);
        jdbc.update("INSERT INTO receivable(id,tenant_id,company_id,customer_id,kind,source_type,source_id,source_line_id,amount,untaxed_amount,tax_amount,business_date,period_id) VALUES(?,?,?,?,'charge','shipment_line',?,?,100,100,0,?,?)",
                otherAr,d.tenant,d.company,d.customer,otherShipment,Ids.next(),d.day,period);
        invoices.create(actor(d.maker),key(),new InvoiceService.Input("sales",d.customer,key(),d.day,"200","0",List.of(new InvoiceService.Line(ar,"100"),new InvoiceService.Line(otherAr,"100")),null));
        var input=new SalesPriceAdjustmentModels.Input(null,d.customer,d.warehouse,d.day.plusDays(2),"原票范围回归",List.of(new SalesPriceAdjustmentModels.LineInput(source,"8",null)));
        long adjustment=id(prices.create(actor(d.maker),"cached-sales-price",input));
        var limited=new CompanyAccess(d.tenant,d.maker,d.company,false,Set.of(d.warehouse),actor(d.maker).permissions(),actor(d.maker).orgScope());
        var lines=(List<?>)queries.get(limited,adjustment).get("lines");
        assertThat(((Map<?,?>)lines.getFirst()).get("redInvoices")).asList().isEmpty();
        code(()->prices.create(limited,"cached-sales-price",input),"NOT_FOUND");
        code(()->results.get(limited,"sales_price_adjustment.create","cached-sales-price"),"NOT_FOUND");
    }

    @Test void salesMarginAgingAndBoundCustomerStatementIncludePriceChangesWithoutAddingQuantity() {
        long source=ship("10","11.30","0.13",null),price=adjust(source,"12.43",null);
        post(price);
        var query=new ReportQuery(d.day,d.day.plusDays(5),null,null,d.customer,null,true);
        salesMeasures(query,"10","124.30","110","14.30","50","60");
        var detail=reports.snapshot(actor(d.maker),ReportType.SALES_SUMMARY,query);
        assertThat(detail.items()).anySatisfy(row->{
            assertThat(row.get("sourceId")).isEqualTo(Long.toString(price));
            assertThat(row).containsEntry("sourceType","sales_price_adjustment").containsEntry("sourceUrl","/sales-price-adjustments/"+price);
            assertThat(row).doesNotContainKeys("costAmount","grossMargin");
        });
        var aging=reports.snapshot(actor(d.maker),ReportType.RECEIVABLE_AGING,query);
        assertThat(aging.items()).anySatisfy(row->{
            assertThat(row.get("sourceId")).isEqualTo(Long.toString(price));
            assertThat(row).containsEntry("sourceType","sales_price_adjustment").containsEntry("sourceUrl","/sales-price-adjustments/"+price);
            assertThat(row.get("sourceNo")).isEqualTo(queries.get(actor(d.maker),price).get("documentNo"));
        });
        var customer=reports.customerStatement(d.tenant,d.company,d.customer,d.day,d.day.plusDays(5));
        metric(customer.summary(),"closingBalance","124.30");
        assertThat(customer.items()).anySatisfy(row->assertThat(row.get("sourceNo")).isEqualTo(queries.get(actor(d.maker),price).get("documentNo")));
        metric(reports.customerStatement(d.tenant,d.company,d.customer+1,d.day,d.day.plusDays(5)).summary(),"closingBalance","0");
        jdbc.update("UPDATE price_adjustment SET owner_department_id=? WHERE id=?",d.parentDepartment,price);
        var child=new CompanyAccess(d.tenant,d.maker,d.company,true,Set.of(),actor(d.maker).permissions(),cn.erp.shared.OrgScope.restricted(Set.of(d.department),Set.of(),d.department));
        metric(reports.snapshot(child,ReportType.SALES_SUMMARY,query).summary(),"amount","113");
        var parent=new CompanyAccess(d.tenant,d.maker,d.company,true,Set.of(),actor(d.maker).permissions(),cn.erp.shared.OrgScope.restricted(Set.of(d.parentDepartment),Set.of(),d.parentDepartment));
        var parentRows=reports.snapshot(parent,ReportType.SALES_SUMMARY,query);
        metric(parentRows.summary(),"amount","11.30");metric(parentRows.summary(),"qty","0");
        long returned=returned(source,"2",d.day.plusDays(3));
        salesMeasures(query,"8","99.44","88","11.44","40","48");
        metric(reports.customerStatement(d.tenant,d.company,d.customer,d.day,d.day.plusDays(5)).summary(),"closingBalance","99.44");
        reverse("sales_return",returned);reverse("sales_price_adjustment",price);
        long discount=adjust(source,"9.04",null);post(discount);
        salesMeasures(query,"10","90.40","80","10.40","50","30");
        metric(reports.customerStatement(d.tenant,d.company,d.customer,d.day,d.day.plusDays(5)).summary(),"closingBalance","90.40");
        reverse("sales_price_adjustment",discount);
        salesMeasures(query,"10","113","100","13","50","50");
        zeroDifferences();
    }

    private void salesMeasures(ReportQuery query,String qty,String gross,String untaxed,String tax,String cost,String margin) {
        var sales=reports.snapshot(actor(d.maker),ReportType.SALES_SUMMARY,query);
        metric(sales.summary(),"qty",qty);metric(sales.summary(),"amount",gross);
        metric(sales.summary(),"untaxedAmount",untaxed);metric(sales.summary(),"taxAmount",tax);
        assertThat(sales.summary()).doesNotContainKeys("costAmount","grossMargin");
        var profits=reports.snapshot(d.actor(d.maker),ReportType.GROSS_MARGIN,query);
        metric(profits.summary(),"costAmount",cost);metric(profits.summary(),"grossMargin",margin);
    }

    private static void metric(Map<String,Object> row,String field,String value) {
        assertThat(new BigDecimal(row.get(field).toString())).as(field).isEqualByComparingTo(value);
    }

    @Test void discountAndBlueRegistrationCannotPassEachOtherAtTheOriginalAmount() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            for(int round=0;round<10;round++) {
                long source=ship("1","10","0",null),ar=originalAr(source),adjustment=adjust(source,"8",null);
                approve(adjustment);
                var start=new CountDownLatch(1);
                var price=pool.submit(()->{start.await();return attempt(()->command(adjustment,"post",d.maker));});
                var blue=pool.submit(()->{start.await();return attempt(()->invoices.create(actor(d.maker),key(),invoice(ar,"10","0")));});
                start.countDown();
                String priceResult=price.get(15,TimeUnit.SECONDS),blueResult=blue.get(15,TimeUnit.SECONDS);
                if(priceResult.equals("ok")) {
                    assertThat(blueResult).isEqualTo("INVOICE_AMOUNT_EXCEEDED");
                    assertThat(value("SELECT price_adjusted_amount FROM shipment_line WHERE id=?",source)).isEqualByComparingTo("-2");
                    assertThat(value("SELECT invoiced_amount FROM receivable WHERE id=?",ar)).isZero();
                } else {
                    assertThat(priceResult).isEqualTo("SALES_RED_REQUIRED");
                    assertThat(blueResult).isEqualTo("ok");
                    assertThat(value("SELECT price_adjusted_amount FROM shipment_line WHERE id=?",source)).isZero();
                    assertThat(value("SELECT invoiced_amount FROM receivable WHERE id=?",ar)).isEqualByComparingTo("10");
                    assertThat(command(adjustment,"void",d.maker)).containsEntry("status","voided");
                }
            }
        }
        zeroDifferences();
    }

    @Test void competingApprovedAdjustmentsPostOnlyOneSourceVersion() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            for(int round=0;round<10;round++) {
                long source=ship("1","10","0",null),first=adjust(source,"12",null),second=adjust(source,"14",null);
                approve(first);approve(second);
                var start=new CountDownLatch(1);
                var one=pool.submit(()->{start.await();return attempt(()->command(first,"post",d.maker));});
                var two=pool.submit(()->{start.await();return attempt(()->command(second,"post",d.maker));});
                start.countDown();
                String firstResult=one.get(15,TimeUnit.SECONDS),secondResult=two.get(15,TimeUnit.SECONDS);
                assertThat(List.of(firstResult,secondResult)).containsExactlyInAnyOrder("ok","PRICE_SOURCE_CHANGED");
                long winner=firstResult.equals("ok")?first:second,loser=winner==first?second:first;
                assertThat(queries.get(actor(d.maker),winner)).containsEntry("status","posted");
                assertThat(queries.get(actor(d.maker),loser)).containsEntry("status","approved");
                assertThat(value("SELECT price_adjusted_amount FROM shipment_line WHERE id=?",source)).isEqualByComparingTo(winner==first?"2":"4");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receivable WHERE source_type='price_adjustment_line' AND source_id IN (?,?)",Long.class,first,second)).isEqualTo(1);
                assertThat(command(loser,"void",d.maker)).containsEntry("status","voided");
            }
        }
        zeroDifferences();
    }

    @Test
    @SuppressWarnings("unchecked")
    void redInvoicePagesKeepAnOlderHiddenBlockerAndRecheckWholeInvoiceRights() {
        long source=ship("1","10","0",null);seedReleasedSalesInvoiceHistory(List.of(originalAr(source)),25);
        long oldest=jdbc.queryForObject("SELECT MIN(invoice_id) FROM invoice_red_registration WHERE tenant_id=?",Long.class,d.tenant);
        jdbc.update("UPDATE invoice_red_registration SET status='cancelled',confirmed_by=NULL,confirmed_at=NULL WHERE tenant_id=? AND invoice_id=?",d.tenant,oldest);
        long adjustment=adjust(source,null,"-1");var detail=queries.get(actor(d.maker),adjustment);
        var first=(List<Map<String,Object>>)detail.get("redInvoices");
        assertThat(first).hasSize(20).allSatisfy(row->assertThat(row.get("redComplete")).isIn(true,1L,1));
        assertThat((List<Map<String,Object>>)detail.get("lines")).allSatisfy(row->assertThat(row).containsEntry("needsRed",true));
        var next=queries.redInvoices(actor(d.maker),adjustment,null,Long.valueOf(detail.get("redInvoiceCursor").toString()),20);
        assertThat((List<?>)next.get("items")).hasSize(5);assertThat(next.get("nextCursor")).isNull();
        assertThat((List<Map<String,Object>>)next.get("items")).anySatisfy(row->assertThat(row).containsEntry("invoiceId",""+oldest));
        var wrongWarehouse=new CompanyAccess(d.tenant,d.maker,d.company,false,Set.of(Ids.next()),actor(d.maker).permissions());
        code(()->queries.redInvoices(wrongWarehouse,adjustment,null,null,20),"NOT_FOUND");
        var permissions=new HashSet<>(actor(d.maker).permissions());permissions.remove("invoice_registration:view");
        var noInvoiceView=new CompanyAccess(d.tenant,d.maker,d.company,true,Set.of(),permissions);
        code(()->queries.redInvoices(noInvoiceView,adjustment,null,null,20),"FORBIDDEN");
        assertThat((List<?>)queries.get(noInvoiceView,adjustment).get("redInvoices")).isEmpty();
        code(()->queries.redInvoices(actor(d.maker),adjustment,Ids.next(),null,20),"NOT_FOUND");
        approve(adjustment);code(()->command(adjustment,"post",d.maker),"SALES_RED_REQUIRED");
        jdbc.update("UPDATE invoice_red_registration SET status='confirmed',confirmed_by=?,confirmed_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND invoice_id=?",d.manager,d.tenant,oldest);
        assertThat(command(adjustment,"post",d.maker)).containsEntry("amount","-1.00");zeroDifferences();
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="ERP_SALES_B02_CAPACITY",matches="true")
    @SuppressWarnings("unchecked")
    void hundredSourcesWithReleasedInvoiceHistoryKeepNetAmountsAndCostsExact() throws Exception {
        final int sourceCount=100,historyCount=20_000;
        final double maxBatchMilliseconds=5_000;
        var report=java.nio.file.Path.of(System.getenv("ERP_TEST_ROOT")).toRealPath().resolve("B02-销售调价容量.json");
        assertThat(java.nio.file.Files.exists(report)).as("容量证据不可覆盖").isFalse();
        assertThat(Runtime.getRuntime().maxMemory()).as("容量验收Java堆上限").isLessThanOrEqualTo(1024L*1024*1024);
        var sources=new java.util.ArrayList<Long>();
        for(int index=0;index<sourceCount;index++) {
            sources.add(ship("1","10","0",null));
            if((index+1)%25==0) capacityStage("已完成真实销售签收来源 "+(index+1));
        }
        var originalCosts=jdbc.queryForList("SELECT id,cost_amount,signed_cost_amount,receivable_amount,tax_amount FROM shipment_line WHERE tenant_id=? ORDER BY id",d.tenant);
        var originalArs=sources.stream().map(this::originalAr).toList();
        seedReleasedSalesInvoiceHistory(originalArs,historyCount);
        capacityStage("已准备两万张整票红字确认后的历史蓝票");
        long started=System.nanoTime();
        var created=prices.create(actor(d.maker),key(),new SalesPriceAdjustmentModels.Input(null,d.customer,d.warehouse,d.day.plusDays(2),"百行历史红字折让容量",
                sources.stream().map(source->new SalesPriceAdjustmentModels.LineInput(source,null,"-1")).toList()));
        long adjustment=id(created);double createdMs=elapsedMs(started);
        assertThat(createdMs).as("本机百行草稿耗时").isLessThan(maxBatchMilliseconds);
        int responseBytes=json.writeValueAsBytes(created).length;
        assertThat(responseBytes).as("百行首屏不得携带全部历史票据").isLessThan(256*1024);
        assertThat((List<?>)created.get("redInvoices")).hasSize(20);
        capacityStage("百行折让草稿已保存，毫秒 "+createdMs);
        var previewMs=new java.util.ArrayList<Double>();
        for(int round=0;round<3;round++) {
            started=System.nanoTime();
            var preview=prices.preview(actor(d.maker),adjustment);
            var previewRows=(List<Map<String,Object>>)preview.get("items");
            assertThat(previewRows).hasSize(sourceCount).allSatisfy(row->assertThat(row).containsEntry("needsRed",false));
            previewMs.add(elapsedMs(started));
        }
        started=System.nanoTime();assertThat(post(adjustment)).containsEntry("amount","-100.00");double postedMs=elapsedMs(started);
        capacityStage("百行折让已提交、批准并过账，毫秒 "+postedMs);
        started=System.nanoTime();int visitedInvoices=visitRedInvoicePages(adjustment,created);double redPagesMs=elapsedMs(started);
        assertThat(visitedInvoices).isEqualTo(historyCount);
        started=System.nanoTime();var candidates=allInvoiceCandidates();double candidatesMs=elapsedMs(started);
        assertThat(candidatesMs).as("本机百行净额度候选耗时").isLessThan(maxBatchMilliseconds);
        assertThat(candidates).hasSize(sourceCount).allSatisfy(row->assertThat(row).containsEntry("amount","10.00").containsEntry("invoiceAvailableAmount","9.00").containsEntry("invoiceAvailableTaxAmount","0.00"));
        started=System.nanoTime();var blue=invoices.create(actor(d.maker),key(),new InvoiceService.Input("sales",d.customer,key(),d.day.plusDays(3),"900","0",
                originalArs.stream().map(ar->new InvoiceService.Line(ar,"9")).toList(),null));double invoiceMs=elapsedMs(started);
        assertThat(invoiceMs).as("本机百行净额开票耗时").isLessThan(maxBatchMilliseconds);
        assertThat(blue).containsEntry("amount","900.00").containsEntry("taxDiffAmount","0.00");
        assertThat((List<?>)blue.get("lines")).hasSize(sourceCount);
        assertThat(jdbc.queryForList("SELECT id,cost_amount,signed_cost_amount,receivable_amount,tax_amount FROM shipment_line WHERE tenant_id=? ORDER BY id",d.tenant)).isEqualTo(originalCosts);
        assertThat(value("SELECT SUM(amount) FROM receivable WHERE tenant_id=? AND source_type='shipment_line'",d.tenant)).isEqualByComparingTo("1000");
        zeroDifferences();
        var metrics=new java.util.LinkedHashMap<String,Object>();
        metrics.put("sourceLines",sourceCount);metrics.put("releasedInvoices",historyCount);metrics.put("maxHeapBytes",Runtime.getRuntime().maxMemory());
        metrics.put("initialResponseBytes",responseBytes);metrics.put("visitedRedInvoices",visitedInvoices);metrics.put("redInvoicePaginationMs",redPagesMs);
        metrics.put("createMs",createdMs);metrics.put("previewMs",previewMs);metrics.put("submitApprovePostMs",postedMs);metrics.put("candidatePaginationMs",candidatesMs);metrics.put("netInvoiceSaveMs",invoiceMs);
        metrics.put("netInvoiceAmount",blue.get("amount"));metrics.put("originalCostUnchanged",true);metrics.put("reconciliationDifferences",0);
        java.nio.file.Files.writeString(report,json.writeValueAsString(metrics),java.nio.file.StandardOpenOption.CREATE_NEW);
    }

    private void seedReleasedSalesInvoiceHistory(List<Long> originalArs,int historyCount) {
        var heads=new java.util.ArrayList<Object[]>();var lines=new java.util.ArrayList<Object[]>();var reds=new java.util.ArrayList<Object[]>();
        for(int index=0;index<historyCount;index++) {
            long invoice=Ids.next();
            heads.add(new Object[]{invoice,d.tenant,d.company,d.customer,"CAP-BLUE-"+index,d.day,d.maker,d.manager,d.maker,d.department});
            lines.add(new Object[]{Ids.next(),d.tenant,d.company,invoice,originalArs.get(index%originalArs.size()),d.maker});
            reds.add(new Object[]{Ids.next(),d.tenant,d.company,invoice,"CAP-RED-"+index,d.day,d.maker,d.manager,d.manager});
        }
        // 历史规模通过SQL准备已释放且经双人确认的票据元数据；真实购销及本次开票仍走业务命令。
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status->{
        jdbc.batchUpdate("INSERT INTO invoice_registration(id,tenant_id,company_id,invoice_type,partner_id,invoice_no,invoice_date,amount,tax_amount,status,version,created_by,updated_by,owner_user_id,owner_department_id)"
                +" VALUES(?,?,?,'sales',?,?,?,'0.01',0,'voided',1,?,?,?,?)",heads);
        jdbc.batchUpdate("INSERT INTO invoice_registration_line(id,tenant_id,company_id,invoice_id,receivable_id,amount,created_by) VALUES(?,?,?,?,?,'0.01',?)",lines);
        jdbc.batchUpdate("INSERT INTO invoice_red_registration(id,tenant_id,company_id,invoice_id,red_invoice_no,red_invoice_date,amount,tax_amount,reason,status,source_version,created_by,confirmed_by,confirmed_at,updated_by)"
                +" VALUES(?,?,?,?,?,?,'0.01',0,'容量历史整票红字','confirmed',0,?,?,UTC_TIMESTAMP(3),?)",reds);
        });
    }

    @SuppressWarnings("unchecked")
    private int visitRedInvoicePages(long adjustment,Map<String,Object> first) {
        var visited=new HashSet<String>();var page=first;
        while(true) {
            var items=(List<Map<String,Object>>)page.get(page.containsKey("redInvoices")?"redInvoices":"items");
            for(var item:items)assertThat(visited.add(item.get("invoiceId").toString())).as("原票分页不能重复").isTrue();
            Object cursor=page.get(page.containsKey("redInvoiceCursor")?"redInvoiceCursor":"nextCursor");
            if(cursor==null)return visited.size();
            page=queries.redInvoices(actor(d.maker),adjustment,null,Long.valueOf(cursor.toString()),200);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> allInvoiceCandidates() {
        var rows=new java.util.ArrayList<Map<String,Object>>();Long cursor=null;
        do {
            var page=finance.receivables(actor(d.maker),d.customer,false,cursor,37,true);
            rows.addAll((List<Map<String,Object>>)page.get("items"));
            cursor=page.get("nextCursor")==null?null:Long.valueOf(page.get("nextCursor").toString());
        } while(cursor!=null);
        assertThat(rows.stream().map(row->row.get("id"))).doesNotHaveDuplicates();return rows;
    }

    private static void capacityStage(String stage) {org.slf4j.LoggerFactory.getLogger(SalesPriceAdjustmentIT.class).info("销售专项容量：{}",stage);}
    private static double elapsedMs(long started) {return (System.nanoTime()-started)/1e6;}

    private static String attempt(Supplier<Map<String,Object>> work) {
        try {work.get();return "ok";}
        catch(ApiException error) {return error.getErrorCode();}
    }

    private void receive() {
        var input=new PurchaseOrderModels.Input(""+d.customer,d.day,null,true,"0",List.of(new PurchaseOrderModels.Line(null,null,""+d.sku,""+d.warehouse,""+d.unit,"100","5",null,null,null,"0")),List.of(),null,null,null);
        long order=id(purchases.create(d.actor(d.maker),key(),input));var submitted=purchases.command(d.actor(d.maker),order,"submit",key(),new PurchaseOrderModels.Command(0L,null,null));
        purchases.command(d.actor(d.manager),order,"approve",key(),new PurchaseOrderModels.Command(version(submitted),null,null));
        long line=jdbc.queryForObject("SELECT id FROM purchase_order_line WHERE order_id=?",Long.class,order);
        long receipt=id(receiving.createReceipt(d.actor(d.maker),key(),new ReceivingModels.ReceiptInput(d.customer,d.warehouse,d.day,null,List.of(new ReceivingModels.ReceiptLine(line,d.unit,"100")),null)));
        receiving.command(d.actor(d.maker),false,receipt,"submit",key(),0L);receiving.command(d.actor(d.maker),false,receipt,"post",key(),1L);
    }
    private long ship(String qty,String price,String tax,String signed) {
        var input=new SalesOrderModels.Input(""+d.customer,d.day,null,true,"0",List.of(new SalesOrderModels.Line(null,""+d.sku,""+d.warehouse,""+d.unit,qty,price,null,"0",null,tax)),List.of(),null,null,null);
        long order=id(sales.create(d.actor(d.maker),key(),input));sales.command(d.actor(d.maker),order,"submit",key(),new SalesOrderModels.Command(0L,null,null));sales.command(d.actor(d.manager),order,"approve",key(),new SalesOrderModels.Command(1L,null,null));
        long orderLine=jdbc.queryForObject("SELECT id FROM sales_order_line WHERE order_id=?",Long.class,order);
        long shipment=id(shipments.create(d.actor(d.maker),key(),new ShipmentModels.Input(d.customer,d.warehouse,d.day,signed==null?"pickup":"delivery",null,List.of(new ShipmentModels.InputLine(orderLine,d.unit,qty)),null)));
        shipments.submit(d.actor(d.maker),shipment,key(),0);var posted=shipments.post(d.actor(d.manager),shipment,key(),1);
        long line=jdbc.queryForObject("SELECT id FROM shipment_line WHERE shipment_id=?",Long.class,shipment);
        if(signed!=null)shipments.sign(d.actor(d.maker),shipment,key(),new ShipmentModels.Signed(version(posted),d.day,List.of(new ShipmentModels.SignedLine(line,signed)),List.of()));
        return line;
    }
    private long returned(long line,String qty,LocalDate day) {
        long id=id(returns.create(d.actor(d.maker),key(),new FinanceModels.ReturnInput(d.customer,d.warehouse,day,"调价前后退货",List.of(new FinanceModels.ReturnLine(line,d.unit,qty,"passed")),null)));
        returns.command(d.actor(d.maker),id,"submit",key(),new FinanceModels.Version(0L,null,null));returns.command(d.actor(d.maker),id,"post",key(),new FinanceModels.Version(1L,null,null));return id;
    }
    private long adjust(long source,String price,String delta){return id(prices.create(actor(d.maker),key(),new SalesPriceAdjustmentModels.Input(null,d.customer,d.warehouse,d.day.plusDays(2),"客户结算调价",List.of(new SalesPriceAdjustmentModels.LineInput(source,price,delta)))));}
    private void approve(long id){command(id,"submit",d.maker);command(id,"approve",d.manager);}
    private Map<String,Object> post(long id){approve(id);return command(id,"post",d.maker);}
    private Map<String,Object> command(long id,String action,long user){return prices.command(actor(user),id,action,key(),new PriceAdjustmentModels.Command(version(queries.get(actor(user),id)),null));}
    private void reverse(String type,long id){long reversal=id(reversals.create(actor(d.maker),key(),new ReversalModels.Input(type,id,d.day.plusDays(4),"撤销误调价")));reversals.command(actor(d.manager),reversal,"approve",key(),new ReversalModels.Version(0L,null));reversals.command(actor(d.maker),reversal,"post",key(),new ReversalModels.Version(1L,null));}
    private CompanyAccess actor(long user){var original=d.actor(user);var permissions=new HashSet<>(original.permissions());permissions.remove("cost:view");return new CompanyAccess(original.tenantId(),original.userId(),original.companyId(),original.allWarehouses(),original.warehouseIds(),permissions,original.orgScope());}
    private long originalAr(long source){return jdbc.queryForObject("SELECT id FROM receivable WHERE source_type='shipment_line' AND source_line_id=? AND posting_action='post'",Long.class,source);}
    private InvoiceService.Input invoice(long ar,String amount,String tax){return new InvoiceService.Input("sales",d.customer,key(),d.day.plusDays(3),amount,tax,List.of(new InvoiceService.Line(ar,amount)),null);}
    private void zeroDifferences(){for(String type:List.of("inventory_balance","inventory_cost","reservation","cash_balance","open_item","journal_balance","ledger_inventory","period_close"))assertThat(checks.check(d.actor(d.maker),type,type.equals("period_close")?period:null)).as(type).isEmpty();}
    private BigDecimal value(String sql,long id){return jdbc.queryForObject(sql,BigDecimal.class,id);}
    private static long id(Map<String,Object> row){return Long.parseLong(row.get("id").toString());}
    private static long version(Map<String,Object> row){return ((Number)row.get("version")).longValue();}
    private static void code(org.assertj.core.api.ThrowableAssert.ThrowingCallable work,String code){assertThatThrownBy(work).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getErrorCode()).isEqualTo(code));}
}
