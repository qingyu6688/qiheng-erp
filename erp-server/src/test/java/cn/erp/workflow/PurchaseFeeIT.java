package cn.erp.workflow;

import java.math.BigDecimal;
import java.util.*;
import org.flowable.engine.ProcessEngine;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import cn.erp.ErpApplication;
import cn.erp.accounting.LedgerService;
import cn.erp.identity.AccessService;
import cn.erp.inventory.InventoryModels;
import cn.erp.inventory.InventoryService;
import cn.erp.purchasefees.*;
import cn.erp.purchasing.PurchaseOrderModels;
import cn.erp.purchasing.PurchaseOrderService;
import cn.erp.receiving.ReceivingModels;
import cn.erp.receiving.ReceivingService;
import cn.erp.reconciliation.ReconciliationChecks;
import cn.erp.reversal.ReversalModels;
import cn.erp.reversal.ReversalService;
import cn.erp.salesorder.SalesOrderService;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import static cn.erp.workflow.WorkflowTestData.key;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
class PurchaseFeeIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessService access;
    @Autowired SalesOrderService sales;
    @Autowired WorkflowModels models;
    @Autowired ProcessEngine engine;
    @Autowired PurchaseOrderService orders;
    @Autowired cn.erp.purchasing.PurchaseOrderQueries orderQueries;
    @Autowired ReceivingService receiving;
    @Autowired PurchaseFeeService fees;
    @Autowired PurchaseFeeQueries queries;
    @Autowired InventoryService inventory;
    @Autowired LedgerService ledger;
    @Autowired ReversalService reversals;
    @Autowired ReconciliationChecks checks;
    @Autowired cn.erp.reporting.ReportService reports;
    @Autowired cn.erp.salesfinance.SalesFinanceQueries finance;
    @Autowired cn.erp.receiving.ReceivingQueries receipts;
    @Autowired tools.jackson.databind.ObjectMapper json;
    private WorkflowTestData d;
    private long period,carrier;
    @BeforeEach void prepare() {
        d=new WorkflowTestData(jdbc,access,sales,models);
        jdbc.update("UPDATE inventory_balance SET on_hand_qty=0 WHERE tenant_id=?",d.tenant);
        jdbc.update("UPDATE inventory_cost SET qty=0,total_cost=0 WHERE tenant_id=?",d.tenant);
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(?,?,?,'supplier')",Ids.next(),d.tenant,d.customer);
        carrier=Ids.next();
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'CARRIER','费用承运商')",carrier,d.tenant);
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role,term_type,term_value) VALUES(?,?,?,'supplier','days',14)",Ids.next(),d.tenant,carrier);
        period=jdbc.queryForObject("SELECT id FROM accounting_period WHERE tenant_id=?",Long.class,d.tenant);
    }
    @AfterEach void cleanup() {if(d!=null)d.cleanup(engine);}

    @Test void manualCarrierFeeSeparatesIssuedCostKeepsReceiptSnapshotAndReverses() {
        long receipt=receive(order("10",false),"10"),source=source(receipt);
        var before=jdbc.queryForMap("SELECT payable_amount,tax_amount,unit_cost,cost_amount,version FROM goods_receipt_line WHERE id=?",source);
        issue("6");long document=create(source,"amount","11.30","0.13");
        var preview=fees.preview(d.actor(d.maker),document);
        assertThat(preview.get("items")).asList().hasSize(1);
        var posted=approveAndPost(document);
        assertThat(posted).containsEntry("stockCost","4.00").containsEntry("issuedCost","6.00").containsEntry("amount","11.30");
        var summary=(Map<?,?>)((List<?>)queries.list(d.actor(d.maker),null,null,null,20).get("items")).getFirst();
        assertThat(summary.get("partnerName")).isEqualTo("费用承运商");
        assertThat(summary.containsKey("allocations")).isFalse();
        assertThat(balance()).isEqualByComparingTo("44");
        assertThat(receipts.get(d.actor(d.maker),false,receipt)).containsEntry("goodsCostAmount","100.00").containsEntry("purchaseFeeCost","10.00").containsEntry("landedCostAmount","110.00");
        var report=reports.snapshot(d.actor(d.maker),cn.erp.reporting.ReportType.PURCHASE_SUMMARY,new cn.erp.reporting.ReportQuery(d.day,d.day.plusDays(2),null,null,null,null,true));
        assertThat(report.summary()).containsEntry("amount","111.30");
        assertThat(report.items()).anySatisfy(row->assertThat(row).containsEntry("sourceId",Long.toString(document)).containsEntry("sourceUrl","/purchase-fee-documents/"+document));
        assertThat(finance.payables(d.actor(d.maker),carrier,false,null,100).get("items")).asList().hasSize(1);
        assertThat(jdbc.queryForMap("SELECT payable_amount,tax_amount,unit_cost,cost_amount,version FROM goods_receipt_line WHERE id=?",source)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT due_date FROM payable WHERE source_type='purchase_fee_doc_line' AND source_id=?",java.sql.Date.class,document)).isEqualTo(java.sql.Date.valueOf(d.day.plusDays(15)));
        zeroDifferences();reverse("purchase_fee_doc",document);
        assertThat(queries.get(d.actor(d.maker),document)).containsEntry("status","reversed");
        assertThat(balance()).isEqualByComparingTo("40");zeroDifferences();
    }
    @Test void automaticFeesFollowPartialReceiptProgressAndStayWithinOriginalFeeTotal() {
        long order=order("10",true),first=receive(order,"4");
        assertThat(value("SELECT recognized_amount FROM purchase_order_fee WHERE order_id=?",order)).isEqualByComparingTo("4.52");
        assertThat(value("SELECT recognized_tax FROM purchase_order_fee WHERE order_id=?",order)).isEqualByComparingTo("0.52");
        assertThat(balance()).isEqualByComparingTo("44");
        long second=receive(order,"6");
        assertThat(value("SELECT recognized_amount FROM purchase_order_fee WHERE order_id=?",order)).isEqualByComparingTo("11.30");
        assertThat(balance()).isEqualByComparingTo("110");
        assertThat(receipts.get(d.actor(d.maker),false,first)).containsEntry("purchaseFeeCost","4.00").containsEntry("landedCostAmount","44.00");
        assertThat(receipts.get(d.actor(d.maker),false,second)).containsEntry("purchaseFeeCost","6.00").containsEntry("landedCostAmount","66.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT source_line_id) FROM payable WHERE tenant_id=? AND source_type='purchase_order_fee'",Long.class,d.tenant)).isEqualTo(2);
        assertThat(value("SELECT SUM(amount) FROM payable WHERE tenant_id=? AND source_type='purchase_order_fee'",d.tenant)).isEqualByComparingTo("11.30");
        assertThat(value("SELECT cost_amount FROM goods_receipt WHERE id=?",first)).isEqualByComparingTo("40");
        assertThat(value("SELECT cost_amount FROM goods_receipt WHERE id=?",second)).isEqualByComparingTo("60");zeroDifferences();
    }
    @Test void reversingEarlierPartialReceiptRestoresOnlyItsFeeProgressAndAllowsReceivingAgain() {
        long order=order("10",true),first=receive(order,"4");receive(order,"6");
        reverse("goods_receipt",first);
        assertThat(value("SELECT recognized_amount FROM purchase_order_fee WHERE order_id=?",order)).isEqualByComparingTo("6.78");
        assertThat(value("SELECT recognized_tax FROM purchase_order_fee WHERE order_id=?",order)).isEqualByComparingTo("0.78");
        assertThat(balance()).isEqualByComparingTo("66");zeroDifferences();
        receive(order,"4");assertThat(balance()).isEqualByComparingTo("110");
        assertThat(value("SELECT recognized_amount FROM purchase_order_fee WHERE order_id=?",order)).isEqualByComparingTo("11.30");zeroDifferences();
    }
    @Test void disabledCapitalisationKeepsFirstReceiptExpenseAndItsReversal() {
        jdbc.update("UPDATE company_policy SET purchase_fee_cost_enabled=0 WHERE tenant_id=?",d.tenant);
        long order=order("10",true),receipt=receive(order,"4");
        assertThat(balance()).isEqualByComparingTo("40");
        assertThat(value("SELECT recognized_amount FROM purchase_order_fee WHERE order_id=?",order)).isEqualByComparingTo("11.30");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_fee_doc WHERE tenant_id=?",Long.class,d.tenant)).isZero();
        assertThat(value("SELECT SUM(l.amount) FROM journal_line l WHERE l.tenant_id=? AND l.account_code='PURCHASE_FEE'",d.tenant)).isEqualByComparingTo("10");
        assertCode(()->create(source(receipt),"amount","1","0"),"VALIDATION_FAILED");
        reverse("goods_receipt",receipt);assertThat(balance()).isZero();
        assertThat(value("SELECT recognized_amount FROM purchase_order_fee WHERE order_id=?",order)).isZero();zeroDifferences();
        jdbc.update("UPDATE company_policy SET purchase_fee_cost_enabled=1 WHERE tenant_id=?",d.tenant);
        receive(order,"4");assertThat(balance()).isEqualByComparingTo("44");zeroDifferences();
    }
    @Test void missingWeightFailsWithoutCommittingDraftOrPartialReceiptPosting() {
        long order=order("10",false),receipt=receive(order,"10");
        assertCode(()->create(source(receipt),"weight","10","0"),"VALIDATION_FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_fee_doc WHERE tenant_id=?",Long.class,d.tenant)).isZero();
        jdbc.update("UPDATE company_policy SET purchase_fee_allocation_method='weight' WHERE tenant_id=?",d.tenant);
        long next=order("10",true);
        assertCode(()->receive(next,"4"),"VALIDATION_FAILED");
        assertThat(balance()).isEqualByComparingTo("100");
        assertThat(value("SELECT recognized_amount FROM purchase_order_fee WHERE order_id=?",next)).isZero();
    }
    @Test void sourceChangeAndSelfReviewAreRejectedAndPostedManualFeeBlocksReceiptReversal() {
        long receipt=receive(order("10",false),"10"),source=source(receipt),document=create(source,"quantity","10","0");
        command(document,"submit",d.maker);
        assertCode(()->command(document,"approve",d.maker),"TWO_PERSON_REQUIRED");
        command(document,"approve",d.manager);command(document,"post",d.maker);
        assertCode(()->reverse("goods_receipt",receipt),"DEPENDENCY_EXISTS");
        issue("1");assertCode(()->reverse("purchase_fee_doc",document),"DEPENDENCY_EXISTS");
    }
    @Test void warehouseOperatorCanPostAutomaticFeeWithoutCompanyFinancialOrCostViewScope() {
        long order=order("10",true),line=jdbc.queryForObject("SELECT id FROM purchase_order_line WHERE order_id=?",Long.class,order);
        var full=d.actor(d.maker);var permissions=new HashSet<>(full.permissions());permissions.remove("cost:view");
        var actor=new cn.erp.identity.CompanyAccess(full.tenantId(),full.userId(),full.companyId(),false,Set.of(d.warehouse),permissions);
        long receipt=id(receiving.createReceipt(actor,key(),new ReceivingModels.ReceiptInput(d.customer,d.warehouse,d.day,null,List.of(new ReceivingModels.ReceiptLine(line,d.unit,"4")),null)));
        receiving.command(actor,false,receipt,"submit",key(),0L);
        var result=receiving.command(actor,false,receipt,"post",key(),1L);
        assertThat(result).doesNotContainKeys("costAmount","purchaseFeeCost","landedCostAmount","feeDocuments");
        assertThat(balance()).isEqualByComparingTo("44");zeroDifferences();
    }
    @Test void allSourceWarehousesAreRequiredEvenForDraftListsAndOldIdempotentResponses() {
        long other=Ids.next();jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'SECOND','第二仓')",other,d.tenant,d.company);
        long first=source(receive(order("10",false),"10")),second=source(receive(order("10",false,other),"10"));
        var input=new PurchaseFeeModels.Input(null,carrier,d.day.plusDays(1),"quantity","跨仓运费",List.of(new PurchaseFeeModels.FeeInput("loading","20","0",null)),List.of(first,second));
        String createKey=key();long document=id(fees.create(d.actor(d.maker),createKey,input));
        var full=d.actor(d.maker);var restricted=new cn.erp.identity.CompanyAccess(full.tenantId(),full.userId(),full.companyId(),false,Set.of(d.warehouse),full.permissions());
        assertCode(()->queries.get(restricted,document),"NOT_FOUND");
        assertThat(queries.list(restricted,null,null,null,20).get("items")).asList().isEmpty();
        assertCode(()->fees.create(restricted,createKey,input),"NOT_FOUND");
        assertThat(approveAndPost(document)).containsEntry("stockCost","20.00");
        assertThat(finance.payables(restricted,carrier,false,null,100).get("items")).asList().isEmpty();zeroDifferences();
    }
    @Test void returningSourceBeforeSubmitRequiresUpdatingTheFeeDraft() {
        long source=source(receive(order("10",false),"10")),document=create(source,"amount","10","0");
        long returned=id(receiving.createReturn(d.actor(d.maker),key(),new ReceivingModels.ReturnInput(d.customer,d.warehouse,d.day,"退回部分来源",List.of(new ReceivingModels.ReturnLine(source,d.unit,"2")),null)));
        receiving.command(d.actor(d.maker),true,returned,"submit",key(),0L);receiving.command(d.actor(d.maker),true,returned,"post",key(),1L);
        assertCode(()->command(document,"submit",d.maker),"FEE_SOURCE_CHANGED");
        assertThat(queries.get(d.actor(d.maker),document)).containsEntry("status","draft");zeroDifferences();
    }
    @Test void weightPolicyAndManualFeeUseSkuBasicUnitWeight() {
        jdbc.update("UPDATE sku SET weight_kg=2.5 WHERE id=?",d.sku);
        jdbc.update("UPDATE company_policy SET purchase_fee_allocation_method='weight' WHERE tenant_id=?",d.tenant);
        long receipt=receive(order("10",true),"4"),document=create(source(receipt),"weight","10","0");
        assertThat(value("SELECT basis_weight FROM purchase_fee_allocation WHERE document_id=?",document)).isEqualByComparingTo("10");
        assertThat(approveAndPost(document)).containsEntry("stockCost","10.00");assertThat(balance()).isEqualByComparingTo("54");zeroDifferences();
    }
    @Test void companyWithoutExplicitPolicyUsesDefaultsForReceiptsAndManualFees() {
        jdbc.update("DELETE FROM company_policy WHERE tenant_id=?",d.tenant);
        long receipt=receive(order("10",true),"4");
        assertThat(balance()).isEqualByComparingTo("44");
        assertThat(queries.metadata(d.actor(d.maker))).containsEntry("costEnabled",true).containsEntry("allocationMethod","amount");
        assertThat(approveAndPost(create(source(receipt),"amount","10","0"))).containsEntry("stockCost","10.00");zeroDifferences();
    }
    @Test void disablingCapitalisationMidOrderFinishesRecognisedFeesAndUsesOldPathForNewFees() {
        long started=order("10",true);receive(started,"4");
        jdbc.update("UPDATE company_policy SET purchase_fee_cost_enabled=0 WHERE tenant_id=?",d.tenant);
        receive(started,"6");assertThat(balance()).isEqualByComparingTo("110");
        assertThat(value("SELECT recognized_amount FROM purchase_order_fee WHERE order_id=?",started)).isEqualByComparingTo("11.30");
        long next=order("10",true);receive(next,"4");
        assertThat(balance()).isEqualByComparingTo("150");
        assertThat(value("SELECT recognized_amount FROM purchase_order_fee WHERE order_id=?",next)).isEqualByComparingTo("11.30");zeroDifferences();
    }
    @Test void deletingOrderFeeWithReversedHistoryReturnsBusinessErrorAndPreservesItsTrace() {
        long order=order("10",true),receipt=receive(order,"10");reverse("goods_receipt",receipt);
        var input=new PurchaseOrderModels.Input(Long.toString(d.customer),d.day,null,true,"0",List.of(new PurchaseOrderModels.Line(null,null,Long.toString(d.sku),Long.toString(d.warehouse),Long.toString(d.unit),"10","10",null,null,null,"0")),List.of(),null,
                ((Number)orderQueries.get(d.actor(d.maker),order).get("version")).longValue(),"取消历史费用");
        var changed=orders.change(d.actor(d.maker),order,key(),input);
        long change=Long.parseLong(((Map<?,?>)changed.get("change")).get("id").toString());
        assertCode(()->orders.decideChange(d.actor(d.manager),change,"approve",key(),new PurchaseOrderModels.Command(0L,null,null)),"CHANGE_NOT_ALLOWED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order_fee WHERE order_id=?",Long.class,order)).isEqualTo(1);
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="ERP_B03_CAPACITY",matches="true")
    void hundredSourcesAndTwentyThousandReversedHistoryMovementsKeepFeeCostBalanced() throws Exception {
        var sources=new ArrayList<Long>();
        for(int index=0;index<100;index++) sources.add(source(receive(order("10",false),"10")));
        var movements=new ArrayList<Object[]>();
        for(int index=0;index<10000;index++) {
            long movement=Ids.next(),inverse=Ids.next(),source=Ids.next();
            movements.add(new Object[]{movement,d.tenant,d.company,d.warehouse,d.sku,"sales_out","post",source,source,-1,-10,null,d.day,period,d.maker});
            movements.add(new Object[]{inverse,d.tenant,d.company,d.warehouse,d.sku,"sales_out","reverse",source,source,1,10,movement,d.day,period,d.maker});
        }
        // 历史样本成对追加，净数量和净成本为零，不伪造实际销售单据。
        jdbc.batchUpdate("INSERT INTO inventory_movement(id,tenant_id,company_id,warehouse_id,sku_id,movement_type,posting_action,source_type,source_id,source_line_id,on_hand_delta,cost_delta,reverses_movement_id,business_date,period_id,posted_at,created_by)"
                +" VALUES(?,?,?,?,?,?,?,'capacity_history',?,?,?,?,?,?,?,UTC_TIMESTAMP(3),?)",movements);
        long document=id(fees.create(d.actor(d.maker),key(),new PurchaseFeeModels.Input(null,carrier,d.day.plusDays(1),"amount","百行历史费用容量验证",
                List.of(new PurchaseFeeModels.FeeInput("freight","1000","0",null)),sources)));
        var previews=new ArrayList<Double>();
        for(int round=0;round<3;round++) {
            long started=System.nanoTime();assertThat(fees.preview(d.actor(d.maker),document).get("items")).asList().hasSize(100);
            previews.add((System.nanoTime()-started)/1e6);
        }
        long started=System.nanoTime();var posted=approveAndPost(document);double postedMs=(System.nanoTime()-started)/1e6;
        assertThat(posted).containsEntry("amount","1000.00").containsEntry("stockCost","1000.00").containsEntry("issuedCost","0.00");zeroDifferences();
        var root=java.nio.file.Path.of(System.getenv("ERP_TEST_ROOT")).toRealPath();
        java.nio.file.Files.writeString(root.resolve("B03-采购费用容量.json"),json.writeValueAsString(Map.of("sourceLines",100,"historyMovements",20000,
                "maxHeapBytes",Runtime.getRuntime().maxMemory(),"previewMs",previews,"submitApprovePostMs",postedMs,"amount",posted.get("amount"),
                "stockCost",posted.get("stockCost"),"issuedCost",posted.get("issuedCost"),"reconciliationDifferences",0)),java.nio.file.StandardOpenOption.CREATE_NEW);
    }
    private long order(String qty,boolean withFee) {
        return order(qty,withFee,d.warehouse);
    }
    private long order(String qty,boolean withFee,long warehouse) {
        var input=new PurchaseOrderModels.Input(Long.toString(d.customer),d.day,null,true,"0",List.of(new PurchaseOrderModels.Line(null,null,Long.toString(d.sku),Long.toString(warehouse),Long.toString(d.unit),qty,"10",null,null,null,"0")),
                withFee?List.of(new PurchaseOrderModels.Fee(null,"freight","buyer","11.30","0.13")):List.of(),null,null,null);
        long order=id(orders.create(d.actor(d.maker),key(),input));
        var submitted=orders.command(d.actor(d.maker),order,"submit",key(),new PurchaseOrderModels.Command(0L,null,null));
        orders.command(d.actor(d.manager),order,"approve",key(),new PurchaseOrderModels.Command(((Number)submitted.get("version")).longValue(),null,null));return order;
    }
    private long receive(long order,String qty) {
        long line=jdbc.queryForObject("SELECT id FROM purchase_order_line WHERE order_id=?",Long.class,order);
        long warehouse=jdbc.queryForObject("SELECT warehouse_id FROM purchase_order_line WHERE id=?",Long.class,line);
        long receipt=id(receiving.createReceipt(d.actor(d.maker),key(),new ReceivingModels.ReceiptInput(d.customer,warehouse,d.day,null,List.of(new ReceivingModels.ReceiptLine(line,d.unit,qty)),null)));
        receiving.command(d.actor(d.maker),false,receipt,"submit",key(),0L);receiving.command(d.actor(d.maker),false,receipt,"post",key(),1L);return receipt;
    }
    private long source(long receipt) {return jdbc.queryForObject("SELECT id FROM goods_receipt_line WHERE receipt_id=?",Long.class,receipt);}
    private long create(long source,String method,String amount,String tax) {
        return id(fees.create(d.actor(d.maker),key(),new PurchaseFeeModels.Input(null,carrier,d.day.plusDays(1),method,"采购运杂费用",List.of(new PurchaseFeeModels.FeeInput("freight",amount,tax,null)),List.of(source))));
    }
    private Map<String,Object> approveAndPost(long document) {command(document,"submit",d.maker);command(document,"approve",d.manager);return command(document,"post",d.maker);}
    private Map<String,Object> command(long document,String action,long user) {
        return fees.command(d.actor(user),document,action,key(),new PurchaseFeeModels.Command(((Number)queries.get(d.actor(user),document).get("version")).longValue(),null));
    }
    private void reverse(String type,long source) {
        long reversal=id(reversals.create(d.actor(d.maker),key(),new ReversalModels.Input(type,source,d.day.plusDays(2),"费用与收货冲销验证")));
        reversals.command(d.actor(d.manager),reversal,"approve",key(),new ReversalModels.Version(0L,null));
        reversals.command(d.actor(d.maker),reversal,"post",key(),new ReversalModels.Version(1L,null));
    }
    private void issue(String qty) {
        long source=Ids.next();
        var value=inventory.issue(d.actor(d.maker),new InventoryModels.Posting(new InventoryModels.Key(d.warehouse,d.sku),new InventoryModels.Source("shipment_line",source,source),d.day,period,"sales_out"),new BigDecimal(qty),null);
        ledger.post(d.actor(d.maker),new LedgerService.Posting("shipment_post",source,0,period,d.day,List.of(new LedgerService.Line("COGS","D",value.movementCost(),null,null,null,null,source,"费用测试出库"),new LedgerService.Line("INVENTORY","C",value.movementCost(),null,null,d.warehouse,d.sku,source,"费用测试出库"))));
    }
    private void zeroDifferences() {
        for(String type:List.of("inventory_balance","inventory_cost","reservation","cash_balance","open_item","journal_balance","ledger_inventory","period_close"))
            assertThat(checks.check(d.actor(d.maker),type,type.equals("period_close")?period:null)).as(type).isEmpty();
    }
    private BigDecimal balance() {return inventory.balance(d.actor(d.maker),new InventoryModels.Key(d.warehouse,d.sku)).cost();}
    private BigDecimal value(String sql,long id) {return jdbc.queryForObject(sql,BigDecimal.class,id);}
    private static long id(Map<String,Object> row) {return Long.parseLong(row.get("id").toString());}
    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,String code) {assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getErrorCode()).isEqualTo(code));}
}
