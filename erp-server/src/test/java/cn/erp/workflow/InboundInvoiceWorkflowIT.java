package cn.erp.workflow;

import cn.erp.ErpApplication;
import cn.erp.adjustment.PriceAdjustmentModels;
import cn.erp.adjustment.PriceAdjustmentService;
import cn.erp.accounting.LedgerService;
import cn.erp.accounting.SettlementService;
import cn.erp.einvoice.InboundInvoiceMatching;
import cn.erp.identity.AccessService;
import cn.erp.identity.CompanyAccess;
import cn.erp.purchasing.PurchaseOrderModels;
import cn.erp.purchasing.PurchaseOrderService;
import cn.erp.purchasefees.PurchaseFeeModels;
import cn.erp.purchasefees.PurchaseFeeQueries;
import cn.erp.purchasefees.PurchaseFeeService;
import cn.erp.purchasefinance.PaymentService;
import cn.erp.purchasefinance.TreasuryModels;
import cn.erp.receiving.ReceivingModels;
import cn.erp.receiving.ReceivingService;
import cn.erp.reconciliation.ReconciliationChecks;
import cn.erp.reversal.ReversalModels;
import cn.erp.reversal.ReversalService;
import cn.erp.salesorder.SalesOrderService;
import cn.erp.reporting.ReportQuery;
import cn.erp.reporting.ReportService;
import cn.erp.reporting.ReportType;
import cn.erp.exporting.ExportCsv;
import cn.erp.shared.ApiException;
import cn.erp.shared.CommandResultAccess;
import cn.erp.shared.Ids;
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
import tools.jackson.databind.ObjectMapper;
import static cn.erp.workflow.WorkflowTestData.key;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
class InboundInvoiceWorkflowIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessService access;
    @Autowired SalesOrderService sales;
    @Autowired WorkflowModels models;
    @Autowired ProcessEngine engine;
    @Autowired PurchaseOrderService orders;
    @Autowired ReceivingService receiving;
    @Autowired PriceAdjustmentService prices;
    @Autowired InboundInvoiceMatching invoices;
    @Autowired ReversalService reversals;
    @Autowired ReconciliationChecks checks;
    @Autowired CommandResultAccess results;
    @Autowired ObjectMapper json;
    @Autowired ReportService reports;
    @Autowired PurchaseFeeService fees;
    @Autowired PurchaseFeeQueries feeQueries;
    @Autowired PaymentService payments;
    @Autowired LedgerService ledger;
    @Autowired SettlementService settlement;
    private WorkflowTestData d;
    private long period;

    @BeforeEach void prepare() {
        d=new WorkflowTestData(jdbc,access,sales,models);
        jdbc.update("UPDATE inventory_balance SET on_hand_qty=0 WHERE tenant_id=?",d.tenant);
        jdbc.update("UPDATE inventory_cost SET qty=0,total_cost=0 WHERE tenant_id=?",d.tenant);
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(?,?,?,'supplier')",Ids.next(),d.tenant,d.customer);
        period=jdbc.queryForObject("SELECT id FROM accounting_period WHERE tenant_id=?",Long.class,d.tenant);
    }
    @AfterEach void cleanup() {if(d!=null)d.cleanup(engine);}

    @Test void receiptReversalReleasesTheWholeInvoiceWithoutChangingOriginalPayables() {
        long receipt=receive(false),payable=payable(receipt,"goods_receipt_line");
        long invoice=invoice("B04-RECEIPT","100","0");
        allocate(invoice,List.of(line(payable,"100","0")));
        var original=jdbc.queryForMap("SELECT * FROM payable WHERE id=?",payable);
        reverse("goods_receipt",receipt);
        var current=invoices.detail(d.actor(d.maker),invoice);
        assertThat(current).containsEntry("matchStatus","pending").containsEntry("payableId",null);
        assertThat(current.get("matchNotice").toString()).contains("冲销");
        assertThat(history(invoice)).hasSize(1);
        assertThat(candidates(invoice)).isEmpty();
        assertThat(jdbc.queryForMap("SELECT * FROM payable WHERE id=?",payable)).isEqualTo(original);
        zeroDifferences();
    }

    @Test void returnPostingAndItsReversalRecalculateQuotaAndReleaseAffectedInvoices() {
        long receipt=receive(false),payable=payable(receipt,"goods_receipt_line");
        long first=invoice("B04-BEFORE-RETURN","100","0");
        allocate(first,List.of(line(payable,"100","0")));
        long receiptLine=jdbc.queryForObject("SELECT id FROM goods_receipt_line WHERE receipt_id=?",Long.class,receipt);
        long returned=id(receiving.createReturn(d.actor(d.maker),key(),new ReceivingModels.ReturnInput(d.customer,d.warehouse,d.day.plusDays(2),"发票核对退货验证",
                List.of(new ReceivingModels.ReturnLine(receiptLine,d.unit,"2")),null)));
        receiving.command(d.actor(d.maker),true,returned,"submit",key(),0L);
        receiving.command(d.actor(d.maker),true,returned,"post",key(),1L);
        assertThat(invoices.detail(d.actor(d.maker),first)).containsEntry("matchStatus","pending");
        assertThat(candidates(first).getFirst()).containsEntry("availableUntaxedAmount","80.00");
        long second=invoice("B04-AFTER-RETURN","80","0");
        allocate(second,List.of(line(payable,"80","0")));
        reverse("purchase_return",returned);
        assertThat(invoices.detail(d.actor(d.maker),second)).containsEntry("matchStatus","pending");
        assertThat(candidates(second).getFirst()).containsEntry("availableUntaxedAmount","100.00");
        zeroDifferences();
    }

    @Test void pricePostingResetsMatchingAndAnOldRetryDoesNotRecreateRelations() {
        long receipt=receive(false),payable=payable(receipt,"goods_receipt_line");
        long invoice=invoice("B04-PRICE","100","0");
        String originalKey=key();
        var allocation=new InboundInvoiceMatching.Allocation(0L,List.of(line(payable,"100","0")));
        var old=invoices.allocate(d.actor(d.maker),invoice,originalKey,allocation);
        long receiptLine=jdbc.queryForObject("SELECT id FROM goods_receipt_line WHERE receipt_id=?",Long.class,receipt);
        long price=id(prices.create(d.actor(d.maker),key(),new PriceAdjustmentModels.Input(null,d.customer,d.warehouse,d.day.plusDays(1),"结算折让",
                List.of(new PriceAdjustmentModels.LineInput(receiptLine,"9",null)))));
        prices.command(d.actor(d.maker),price,"submit",key(),new PriceAdjustmentModels.Command(0L,null));
        prices.command(d.actor(d.manager),price,"approve",key(),new PriceAdjustmentModels.Command(1L,null));
        prices.command(d.actor(d.maker),price,"post",key(),new PriceAdjustmentModels.Command(2L,null));
        assertThat(invoices.detail(d.actor(d.maker),invoice)).containsEntry("matchStatus","pending");
        assertThat(candidates(invoice).getFirst()).containsEntry("availableUntaxedAmount","90.00");
        var replay=invoices.allocate(d.actor(d.maker),invoice,originalKey,allocation);
        assertThat(json.readTree(json.writeValueAsString(replay))).isEqualTo(json.readTree(json.writeValueAsString(old)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM einv_inbound_match WHERE invoice_id=? AND released_at IS NULL",Integer.class,invoice)).isZero();
        var rights=new java.util.HashSet<>(d.actor(d.maker).permissions());rights.remove("cost:view");
        var noCost=new CompanyAccess(d.tenant,d.maker,d.company,true,Set.of(),rights);
        assertCode(()->invoices.detail(noCost,invoice),"NOT_FOUND");
        assertCode(()->results.verify(noCost,"einv.inbound_allocate",old),"NOT_FOUND");
        zeroDifferences();
    }

    @Test void receiptWithAutomaticFeesMatchesBothSourcesAndReversesTogether() {
        long receipt=receive(true);
        long goods=payable(receipt,"goods_receipt_line"),freight=payable(receipt,"purchase_order_fee");
        long invoice=invoice("B04-FREIGHT","111.30","1.30");
        allocate(invoice,List.of(line(goods,"100","0"),line(freight,"10","1.30")));
        assertThat(invoices.detail(d.actor(d.maker),invoice)).containsEntry("fullyAllocated",true);
        reverse("goods_receipt",receipt);
        assertThat(invoices.detail(d.actor(d.maker),invoice)).containsEntry("matchStatus","pending");
        assertThat(history(invoice)).hasSize(2);
        assertThat(candidates(invoice)).isEmpty();
        zeroDifferences();
    }

    @Test void positivePriceAndItsReversalReleaseAllAllocationsOfTheInvoice() {
        long receipt=receive(false),goods=payable(receipt,"goods_receipt_line");
        long receiptLine=jdbc.queryForObject("SELECT id FROM goods_receipt_line WHERE receipt_id=?",Long.class,receipt);
        long price=price(receiptLine,"12");long delta=payable(price,"price_adjustment_line");
        long invoice=invoice("B04-POSITIVE-PRICE","120","0");
        allocate(invoice,List.of(line(goods,"100","0"),line(delta,"20","0")));
        assertThat(uninvoiced(java.time.LocalDate.now()).items()).isEmpty();
        reverse("price_adjustment",price);
        assertThat(invoices.detail(d.actor(d.maker),invoice)).containsEntry("matchStatus","pending");
        assertThat(history(invoice)).hasSize(2);
        assertThat(uninvoiced(d.day.plusDays(4)).summary()).containsEntry("uninvoicedAmount","100.00");
        zeroDifferences();
    }

    @Test void carrierFeeIsCountedOnceEvenWhenItIsAllocatedToSeveralReceipts() {
        long first=receive(false),second=receive(false),carrier=Ids.next();
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'B04-CARRIER','发票验收承运商')",carrier,d.tenant);
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(?,?,?,'supplier')",Ids.next(),d.tenant,carrier);
        var sourceLines=jdbc.queryForList("SELECT id FROM goods_receipt_line WHERE receipt_id IN(?,?)",Long.class,first,second);
        long document=id(fees.create(d.actor(d.maker),key(),new PurchaseFeeModels.Input(null,carrier,d.day.plusDays(1),"amount","两单合并承运费",
                List.of(new PurchaseFeeModels.FeeInput("freight","11.30","0.13",null)),sourceLines)));
        for(String action:List.of("submit","approve","post")) {
            var current=feeQueries.get(d.actor(d.maker),document);
            fees.command(d.actor(action.equals("approve")?d.manager:d.maker),document,action,key(),new PurchaseFeeModels.Command(((Number)current.get("version")).longValue(),null));
        }
        var query=new ReportQuery(d.day,d.day.plusDays(2),null,null,carrier,null,false);
        var before=reports.snapshot(d.actor(d.maker),ReportType.UNINVOICED_RECEIPTS,query);
        assertThat(before.items()).hasSize(1);assertThat(before.summary()).containsEntry("uninvoicedAmount","11.30");
        assertThat(before.items().getFirst()).containsEntry("sourceUrl","/purchase-fee-documents/"+document);
        long feePayable=payable(document,"purchase_fee_doc_line");
        long invoice=id(invoices.register(d.actor(d.maker),key(),new InboundInvoiceMatching.Manual(null,carrier,"B04-CARRIER","TEST87654321",null,d.day,d.day,"11.30","1.30",null)));
        allocate(invoice,List.of(line(feePayable,"10","1.30")));
        assertThat(uninvoiced(java.time.LocalDate.now()).summary()).containsEntry("uninvoicedAmount","200.00");
        reverse("purchase_fee_doc",document);
        assertThat(invoices.detail(d.actor(d.maker),invoice)).containsEntry("matchStatus","pending");
        assertThat(candidates(invoice)).isEmpty();assertThat(history(invoice)).hasSize(1);zeroDifferences();
    }

    @Test void uninvoicedReportAndStatementRestoreHistoricalMatchingAndExportExactAmounts() {
        long receipt=receive(false),payable=payable(receipt,"goods_receipt_line");
        assertThat(uninvoiced(d.day).summary()).containsEntry("uninvoicedAmount","100.00");
        long invoice=invoice("B04-REPORT","40","0");allocate(invoice,List.of(line(payable,"40","0")));
        var current=uninvoiced(java.time.LocalDate.now());
        assertThat(current.summary()).containsEntry("uninvoicedAmount","60.00");
        assertThat(current.items().getFirst()).containsEntry("invoicingStatus","partial").containsEntry("originalReceiptId",Long.toString(receipt));
        var statement=reports.snapshot(d.actor(d.maker),ReportType.PARTNER_STATEMENT,new ReportQuery(d.day,java.time.LocalDate.now(),null,null,d.customer,null,false));
        assertThat(statement.items()).anySatisfy(row->assertThat(row).containsEntry("sourceId",Long.toString(receipt)).containsEntry("invoicingStatus","partial"));
        var header=invoices.detail(d.actor(d.maker),invoice);
        invoices.allocate(d.actor(d.maker),invoice,key(),new InboundInvoiceMatching.Allocation(((Number)header.get("version")).longValue(),List.of()));
        jdbc.update("UPDATE einv_inbound_match SET matched_date=?,matched_at='2026-11-16 01:00:00',released_date=?,released_at='2026-11-18 01:00:00' WHERE invoice_id=?",d.day.plusDays(1),d.day.plusDays(3),invoice);
        assertThat(uninvoiced(d.day).summary()).containsEntry("uninvoicedAmount","100.00");
        var historical=uninvoiced(d.day.plusDays(2));
        assertThat(historical.summary()).containsEntry("uninvoicedAmount","60.00").containsEntry("invoiceCutoff",d.day.plusDays(2).toString());
        assertThat(uninvoiced(d.day.plusDays(3)).summary()).containsEntry("uninvoicedAmount","100.00");
        var csv=new String(ExportCsv.writeReport(ReportType.UNINVOICED_RECEIPTS,false,true,historical.items(),json),java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("未到票合计","部分到票","'60.00");
        var rights=new java.util.HashSet<>(d.actor(d.maker).permissions());rights.remove("report:uninvoiced_receipts");
        assertCode(()->reports.snapshot(new CompanyAccess(d.tenant,d.maker,d.company,true,Set.of(),rights),ReportType.UNINVOICED_RECEIPTS,new ReportQuery(d.day,d.day,null,null,null,null,false)),"FORBIDDEN");
        var emptyScope=new CompanyAccess(d.tenant,d.maker,d.company,false,Set.of(),d.actor(d.maker).permissions());
        assertThat(reports.snapshot(emptyScope,ReportType.UNINVOICED_RECEIPTS,new ReportQuery(d.day,d.day,null,null,null,null,false)).items()).isEmpty();
        zeroDifferences();
    }

    @Test void fullyPaidPayableStillNeedsAnInvoiceAndTailDifferenceClosesItsWholeQuota() {
        long receipt=receive(false),payable=payable(receipt,"goods_receipt_line"),account=Ids.next();
        jdbc.update("INSERT INTO cash_account(id,tenant_id,company_id,code,name,account_type) VALUES(?,?,?,'B04-BANK','发票验收付款账户','bank')",account,d.tenant,d.company);
        jdbc.update("INSERT INTO cash_account_balance(id,tenant_id,company_id,cash_account_id) VALUES(?,?,?,?)",Ids.next(),d.tenant,d.company,account);
        var hundred=new java.math.BigDecimal("100.00");
        ledger.post(d.actor(d.maker),new LedgerService.Posting("opening_entry",Ids.next(),0,period,d.day,List.of(
                new LedgerService.Line("FUNDS","D",hundred,null,account,null,null,null,"发票验收资金期初"),
                new LedgerService.Line("OPENING_EQUITY","C",hundred,null,null,null,null,null,"发票验收资金期初"))));
        long payment=id(payments.create(d.actor(d.maker),key(),new TreasuryModels.Payment("supplier",d.customer,null,account,"100",d.day,null,null,null,null)));
        payments.command(d.actor(d.maker),payment,"submit",key(),new TreasuryModels.Version(0L,null));
        payments.command(d.actor(d.financeA),payment,"confirm",key(),new TreasuryModels.Version(1L,null));
        long debit=jdbc.queryForObject("SELECT id FROM partner_open_item WHERE tenant_id=? AND item_type='payment' AND source_id=?",Long.class,d.tenant,payment);
        long credit=jdbc.queryForObject("SELECT id FROM partner_open_item WHERE tenant_id=? AND item_type='payable' AND source_id=?",Long.class,d.tenant,payable);
        settlement.allocate(d.actor(d.maker),debit,credit,hundred,"manual",null,null);
        assertThat(jdbc.queryForObject("SELECT open_amount FROM partner_open_item WHERE id=?",java.math.BigDecimal.class,credit)).isEqualByComparingTo("0");
        var original=jdbc.queryForMap("SELECT * FROM payable WHERE id=?",payable);
        var cash=jdbc.queryForMap("SELECT * FROM cash_account_balance WHERE cash_account_id=?",account);
        assertThat(uninvoiced(java.time.LocalDate.now()).summary()).containsEntry("uninvoicedAmount","100.00");
        long invoice=invoice("B04-PAID-TAIL","99.99","0");
        invoices.allocate(d.actor(d.maker),invoice,key(),new InboundInvoiceMatching.Allocation(0L,List.of(new InboundInvoiceMatching.Line(payable,"99.99","0","100","0"))));
        assertThat(invoices.detail(d.actor(d.maker),invoice)).containsEntry("matchStatus","difference").containsEntry("fullyAllocated",true).containsEntry("absoluteDifference","0.01");
        assertThat(uninvoiced(java.time.LocalDate.now()).items()).isEmpty();
        var statement=reports.snapshot(d.actor(d.maker),ReportType.PARTNER_STATEMENT,new ReportQuery(d.day,java.time.LocalDate.now(),null,null,d.customer,null,false));
        assertThat(statement.items()).anySatisfy(row->assertThat(row).containsEntry("sourceId",Long.toString(receipt)).containsEntry("invoicingStatus","invoiced"));
        assertThat(jdbc.queryForMap("SELECT * FROM payable WHERE id=?",payable)).isEqualTo(original);
        assertThat(jdbc.queryForMap("SELECT * FROM cash_account_balance WHERE cash_account_id=?",account)).isEqualTo(cash);
        zeroDifferences();
    }

    @Test void sourcePeriodFiltersAfterNettingTheWholeReceiptFamily() {
        long receipt=receive(false);
        long receiptLine=jdbc.queryForObject("SELECT id FROM goods_receipt_line WHERE receipt_id=?",Long.class,receipt);
        long price=price(receiptLine,"12");
        long returned=id(receiving.createReturn(d.actor(d.maker),key(),new ReceivingModels.ReturnInput(d.customer,d.warehouse,d.day.plusDays(2),"期间净额验证",
                List.of(new ReceivingModels.ReturnLine(receiptLine,d.unit,"2")),null)));
        receiving.command(d.actor(d.maker),true,returned,"submit",key(),0L);
        receiving.command(d.actor(d.maker),true,returned,"post",key(),1L);
        var before=reports.snapshot(d.actor(d.maker),ReportType.UNINVOICED_RECEIPTS,new ReportQuery(d.day.plusDays(1),d.day.plusDays(1),null,d.sku,d.customer,null,false));
        assertThat(before.summary()).containsEntry("uninvoicedAmount","20.00");
        var after=reports.snapshot(d.actor(d.maker),ReportType.UNINVOICED_RECEIPTS,new ReportQuery(d.day.plusDays(1),d.day.plusDays(2),null,d.sku,d.customer,null,false));
        assertThat(after.items()).singleElement().satisfies(row->assertThat(row).containsEntry("sourceId",Long.toString(price)).containsEntry("originalReceiptId",Long.toString(receipt)));
        assertThat(after.summary()).containsEntry("uninvoicedAmount","16.00");
        zeroDifferences();
    }

    private long receive(boolean freight) {
        var fees=freight?List.of(new PurchaseOrderModels.Fee(null,"freight","buyer","11.30","0.13")):List.<PurchaseOrderModels.Fee>of();
        var input=new PurchaseOrderModels.Input(Long.toString(d.customer),d.day,null,true,"0",
                List.of(new PurchaseOrderModels.Line(null,null,Long.toString(d.sku),Long.toString(d.warehouse),Long.toString(d.unit),"10","10",null,null,null,"0")),fees,null,null,null);
        long order=id(orders.create(d.actor(d.maker),key(),input));
        orders.command(d.actor(d.maker),order,"submit",key(),new PurchaseOrderModels.Command(0L,null,null));
        orders.command(d.actor(d.manager),order,"approve",key(),new PurchaseOrderModels.Command(1L,null,null));
        long orderLine=jdbc.queryForObject("SELECT id FROM purchase_order_line WHERE order_id=?",Long.class,order);
        long receipt=id(receiving.createReceipt(d.actor(d.maker),key(),new ReceivingModels.ReceiptInput(d.customer,d.warehouse,d.day,null,
                List.of(new ReceivingModels.ReceiptLine(orderLine,d.unit,"10")),null)));
        receiving.command(d.actor(d.maker),false,receipt,"submit",key(),0L);receiving.command(d.actor(d.maker),false,receipt,"post",key(),1L);
        return receipt;
    }
    private long invoice(String no,String amount,String tax) {
        return id(invoices.register(d.actor(d.maker),key(),new InboundInvoiceMatching.Manual(null,d.customer,no,"TEST12345678",null,d.day,d.day,amount,tax,null)));
    }
    private ReportService.Snapshot uninvoiced(java.time.LocalDate cutoff) {return reports.snapshot(d.actor(d.maker),ReportType.UNINVOICED_RECEIPTS,new ReportQuery(d.day,cutoff,null,null,null,null,false));}
    private long price(long receiptLine,String unitPrice) {
        long price=id(prices.create(d.actor(d.maker),key(),new PriceAdjustmentModels.Input(null,d.customer,d.warehouse,d.day.plusDays(1),"核对调价验证",List.of(new PriceAdjustmentModels.LineInput(receiptLine,unitPrice,null)))));
        prices.command(d.actor(d.maker),price,"submit",key(),new PriceAdjustmentModels.Command(0L,null));
        prices.command(d.actor(d.manager),price,"approve",key(),new PriceAdjustmentModels.Command(1L,null));
        prices.command(d.actor(d.maker),price,"post",key(),new PriceAdjustmentModels.Command(2L,null));return price;
    }
    private void allocate(long invoice,List<InboundInvoiceMatching.Line> lines) {invoices.allocate(d.actor(d.maker),invoice,key(),new InboundInvoiceMatching.Allocation(0L,lines));}
    private InboundInvoiceMatching.Line line(long payable,String untaxed,String tax) {return new InboundInvoiceMatching.Line(payable,untaxed,tax,untaxed,tax);}
    private long payable(long source,String type) {return jdbc.queryForObject("SELECT id FROM payable WHERE tenant_id=? AND source_id=? AND source_type=? AND posting_action='post'",Long.class,d.tenant,source,type);}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> candidates(long invoice) {return (List<Map<String,Object>>)invoices.candidates(d.actor(d.maker),invoice,null,100).get("items");}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> history(long invoice) {return (List<Map<String,Object>>)invoices.history(d.actor(d.maker),invoice,null,100).get("items");}
    private void reverse(String type,long source) {
        long reversal=id(reversals.create(d.actor(d.maker),key(),new ReversalModels.Input(type,source,d.day.plusDays(3),"发票核对冲销验证")));
        reversals.command(d.actor(d.manager),reversal,"approve",key(),new ReversalModels.Version(0L,null));
        reversals.command(d.actor(d.maker),reversal,"post",key(),new ReversalModels.Version(1L,null));
    }
    private void zeroDifferences() {
        for(String type:List.of("inventory_balance","inventory_cost","reservation","cash_balance","open_item","journal_balance","ledger_inventory","period_close"))
            assertThat(checks.check(d.actor(d.maker),type,type.equals("period_close")?period:null)).as(type).isEmpty();
    }
    private static long id(Map<String,Object> row) {return Long.parseLong(row.get("id").toString());}
    private static void assertCode(Runnable work,String code) {assertThatThrownBy(work::run).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getErrorCode()).isEqualTo(code));}
}
