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
import cn.erp.salesfinance.FinanceModels;
import cn.erp.salesfinance.SalesReturnService;
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
        assertThat(invoices.create(actor(d.maker),key(),input)).containsEntry("taxDiffAmount","0.00");zeroDifferences();
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
