package cn.erp.purchasefinance;

import cn.erp.ErpApplication;
import cn.erp.accounting.*;
import cn.erp.identity.CompanyAccess;
import cn.erp.opening.OpeningService;
import cn.erp.reconciliation.ReconciliationChecks;
import cn.erp.salesfinance.*;
import cn.erp.purchasing.*;
import cn.erp.receiving.*;
import cn.erp.shared.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
class TreasuryIT {
    private static final LocalDate DAY=LocalDate.of(2026,11,1);
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentService payments;
    @Autowired AccountTransferService transfers;
    @Autowired ReceiptService receipts;
    @Autowired SalesFinanceQueries queries;
    @Autowired LedgerService ledger;
    @Autowired SettlementService settlement;
    @Autowired ReconciliationChecks checks;
    @Autowired PurchaseOrderService orders;
    @Autowired PurchaseOrderQueries orderQueries;
    @Autowired ReceivingService receiving;
    @Autowired OpeningService opening;
    private long tenant,company,partner,account,target,period,expense;
    private CompanyAccess maker,reviewer;

    @BeforeEach void prepare() {
        tenant=Ids.next();company=Ids.next();partner=Ids.next();account=Ids.next();target=Ids.next();period=Ids.next();expense=Ids.next();
        var permissions=new HashSet<>(jdbc.queryForList("SELECT code FROM sys_permission",String.class));
        maker=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),permissions);
        reviewer=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),permissions);
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'采购资金测试')",tenant,"TREASURY"+tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','资金公司')",company,tenant);
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'PARTNER','双重角色往来')",partner,tenant);
        for(String role:List.of("supplier","customer")) {jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(?,?,?,?)",Ids.next(),tenant,partner,role);}
        for(long id:List.of(account,target)) {
            jdbc.update("INSERT INTO cash_account(id,tenant_id,company_id,code,name,account_type) VALUES(?,?,?,?,?,'bank')",id,tenant,company,"BANK"+id,id==account?"付款账户":"转入账户");
            jdbc.update("INSERT INTO cash_account_balance(id,tenant_id,company_id,cash_account_id) VALUES(?,?,?,?)",Ids.next(),tenant,company,id);
        }
        jdbc.update("INSERT INTO income_expense_item(id,tenant_id,item_type,code,name) VALUES(?,?,'expense','EXP','办公支出')",expense,tenant);
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,'202611','2026-11-01','2026-11-30')",period,tenant,company);
    }

    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM inbox_event WHERE event_id IN(SELECT id FROM outbox_event WHERE tenant_id=?)",tenant);
        for(String table:List.of("outbox_event","ledger_balance","journal_line","journal","settlement_allocation","partner_open_item",
                "receipt","payment","account_transfer","payable","receivable","opening_entry","inventory_movement","inventory_balance","inventory_cost","goods_receipt_line","goods_receipt","purchase_order_fee","purchase_order_line","purchase_order","audit_log","approval_record","idempotency_record",
                "document_sequence","cash_account_balance","cash_account","income_expense_item","partner_role","unit_conversion","sku","product","product_category","unit","warehouse","accounting_period","company","business_partner")) {
            jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);
        }
        jdbc.update("DELETE FROM tenant WHERE id=?",tenant);
    }

    @Test void B21Payment100Point01FailsBut100SucceedsAndRetryHasOneEffect() {
        cashOpening("100.00");
        var draft=payments.create(maker,key(),input("100.01",null,null));long id=id(draft);
        assertThat(balance(account)).isEqualByComparingTo("100.00");
        assertCode(()->payments.command(reviewer,id,"confirm",key(),version(draft)),"STATE_CONFLICT");
        var pending=payments.command(maker,id,"submit",key(),version(draft));
        assertThat(payments.get(maker,id).get("allowedActions")).asList().doesNotContain("confirm");
        assertThat(payments.get(reviewer,id).get("allowedActions")).asList().contains("confirm");
        assertCode(()->payments.command(maker,id,"confirm",key(),version(pending)),"SAME_OPERATOR_NOT_ALLOWED");
        assertCode(()->payments.command(reviewer,id,"confirm",key(),version(pending)),"CASH_BALANCE_INSUFFICIENT");
        assertThat(payments.get(maker,id)).containsEntry("status","pending_confirm");
        assertThat(balance(account)).isEqualByComparingTo("100");
        assertThat(count("SELECT COUNT(*) FROM journal WHERE tenant_id=? AND source_type='payment'")).isZero();
        var returned=payments.command(maker,id,"return",key(),version(pending));
        var edited=payments.update(maker,id,input("100",null,number(returned,"version")));
        var resubmitted=payments.command(maker,id,"submit",key(),version(edited));
        String confirmation=key();var request=version(resubmitted);
        var confirmed=payments.command(reviewer,id,"confirm",confirmation,request);
        assertThat(payments.command(reviewer,id,"confirm",confirmation,request).get("id")).isEqualTo(confirmed.get("id"));
        assertThat(balance(account)).isEqualByComparingTo("0");
        assertThat(count("SELECT COUNT(*) FROM journal WHERE tenant_id=? AND source_type='payment'")).isEqualTo(1);
        assertThat(open("payment",id)).isEqualByComparingTo("100");
        assertCode(()->payments.command(reviewer,id,"confirm",key(),version(confirmed)),"STATE_CONFLICT");
        assertZero();
    }

    @Test void transferFeeHasOneBalancedAtomicEffectWithoutCreatingOpenItems() {
        cashOpening("100");
        var draft=transfers.create(maker,key(),transfer(account,target,"60","2",null));long id=id(draft);
        var pending=transfers.command(maker,id,"submit",key(),version(draft));
        assertThat(balance(account)).isEqualByComparingTo("100");
        assertThat(balance(target)).isEqualByComparingTo("0");
        assertCode(()->transfers.command(maker,id,"confirm",key(),version(pending)),"SAME_OPERATOR_NOT_ALLOWED");
        String key=key();var request=version(pending);
        var confirmed=transfers.command(reviewer,id,"confirm",key,request);
        assertThat(transfers.command(reviewer,id,"confirm",key,request).get("id")).isEqualTo(confirmed.get("id"));
        assertThat(balance(account)).isEqualByComparingTo("38");
        assertThat(balance(target)).isEqualByComparingTo("60");
        assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM journal_line WHERE tenant_id=? AND account_code='TRANSFER_FEE' AND cash_account_id=?",BigDecimal.class,tenant,account)).isEqualByComparingTo("2");
        assertThat(count("SELECT COUNT(*) FROM journal_line WHERE tenant_id=? AND account_code='OTHER_EXPENSE'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM partner_open_item WHERE tenant_id=?")).isZero();
        assertThat(confirmed).containsEntry("fromAccountId",Long.toString(account)).containsEntry("toAccountId",Long.toString(target));
        assertThat(confirmed).doesNotContainKeys("accountNo","bankAccountNo");
        assertCode(()->transfers.command(reviewer,id,"confirm",key(),version(confirmed)),"STATE_CONFLICT");
        assertZero();
    }

    @Test void failedTransferRollsBackTargetFundsJournalOutboxAndStatus() {
        cashOpening("100");
        var draft=transfers.create(maker,key(),transfer(account,target,"100","0.01",null));long id=id(draft);
        var pending=transfers.command(maker,id,"submit",key(),version(draft));
        long journals=count("SELECT COUNT(*) FROM journal WHERE tenant_id=?"),events=count("SELECT COUNT(*) FROM outbox_event WHERE tenant_id=?");
        assertCode(()->transfers.command(reviewer,id,"confirm",key(),version(pending)),"CASH_BALANCE_INSUFFICIENT");
        assertThat(transfers.get(maker,id)).containsEntry("status","pending_confirm");
        assertThat(balance(account)).isEqualByComparingTo("100");assertThat(balance(target)).isEqualByComparingTo("0");
        assertThat(count("SELECT COUNT(*) FROM journal WHERE tenant_id=?")).isEqualTo(journals);
        assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE tenant_id=?")).isEqualTo(events);
        var returned=transfers.command(maker,id,"return",key(),version(pending));
        assertCode(()->transfers.update(maker,id,transfer(account,target,"50","0",0L)),"VERSION_CONFLICT");
        var edited=transfers.update(maker,id,transfer(account,target,"50","0",number(returned,"version")));
        assertThat(edited).containsEntry("status","draft");
        assertCode(()->transfers.create(maker,key(),transfer(account,account,"1","0",null)),"VALIDATION_FAILED");
        var outside=new CompanyAccess(tenant,maker.userId(),Ids.next(),true,Set.of(),maker.permissions());
        assertCode(()->transfers.get(outside,id),"NOT_FOUND");
        assertZero();
    }

    @Test void supplierRefundAndManualApSettlementCannotConsumeCustomerCredit() {
        cashOpening("100");
        long payment=confirmedPayment("30",null);
        long charge=payable("charge","30");
        allocate(paymentItem(payment),charge,"30");
        assertThat(open("payment",payment)).isEqualByComparingTo("0");
        var customer=confirmedReceipt("customer","5");
        long debit=payable("credit","5"),customerItem=receiptItem(customer);
        assertCode(()->allocate(debit,customerItem,"5"),"SETTLEMENT_NOT_ALLOWED");
        assertThat(openId(debit)).isEqualByComparingTo("5");
        assertThat(openId(customerItem)).isEqualByComparingTo("5");
        long supplier=confirmedReceipt("supplier_refund","5");
        var ap=queries.openItems(maker,partner,"ap",true,null,200);
        @SuppressWarnings("unchecked") var items=(List<Map<String,Object>>)ap.get("items");
        assertThat(items).extracting(item->item.get("id")).contains(Long.toString(debit),Long.toString(receiptItem(supplier)));
        allocate(debit,receiptItem(supplier),"5");
        assertThat(openId(debit)).isEqualByComparingTo("0");
        assertThat(openId(receiptItem(supplier))).isEqualByComparingTo("0");
        assertThat(openId(customerItem)).isEqualByComparingTo("5");
        assertThat(balance(account)).isEqualByComparingTo("80");
        assertZero();
    }

    @Test void B21ConcurrentPaymentsKeepFundsNonnegativeForTwentyRounds() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            for(int round=0;round<20;round++) {
                cashOpening("10");
                var first=payments.create(maker,key(),input("7",null,null));long left=id(first);
                var second=payments.create(maker,key(),input("7",null,null));long right=id(second);
                var a=payments.command(maker,left,"submit",key(),version(first));
                var b=payments.command(maker,right,"submit",key(),version(second));
                var start=new CountDownLatch(1);
                var fa=pool.submit(()->attemptPayment(start,left,a));var fb=pool.submit(()->attemptPayment(start,right,b));start.countDown();
                boolean x=fa.get(10,TimeUnit.SECONDS),y=fb.get(10,TimeUnit.SECONDS);
                assertThat(List.of(x,y)).containsExactlyInAnyOrder(true,false);
                assertThat(balance(account)).isEqualByComparingTo("3");
                long loser=x?right:left;
                payments.command(maker,loser,"return",key(),version(payments.get(maker,loser)));
                long reset=Ids.next();
                ledger.post(maker,new LedgerService.Posting("opening_entry",reset,0,period,DAY,List.of(
                        entry("OPENING_EQUITY","D","3",null,null),entry("FUNDS","C","3",null,account))));
            }
        }
        assertThat(balance(account)).isEqualByComparingTo("0");
        assertThat(count("SELECT COUNT(*) FROM payment WHERE tenant_id=? AND status='confirmed'")).isEqualTo(20);
        assertZero();
    }

    @Test void expensePaymentRequiresActiveExpenseAndClosedPeriodBlocksConfirmation() {
        cashOpening("100");
        var draft=payments.create(maker,key(),new TreasuryModels.Payment("other_expense",null,expense,account,"10",DAY,null,null,"办公付款",null));long id=id(draft);
        assertThat(draft).containsEntry("expenseItemId",Long.toString(expense));
        var pending=payments.command(maker,id,"submit",key(),version(draft));
        jdbc.update("UPDATE accounting_period SET status='closed',closed_at=UTC_TIMESTAMP(3) WHERE id=?",period);
        assertCode(()->payments.command(reviewer,id,"confirm",key(),version(pending)),"PERIOD_CLOSED");
        assertThat(balance(account)).isEqualByComparingTo("100");
        jdbc.update("UPDATE accounting_period SET status='open',closed_at=NULL WHERE id=?",period);
        payments.command(reviewer,id,"confirm",key(),version(pending));
        assertThat(balance(account)).isEqualByComparingTo("90");
        assertThat(count("SELECT COUNT(*) FROM partner_open_item WHERE tenant_id=?")).isZero();
        jdbc.update("UPDATE income_expense_item SET status='disabled' WHERE id=?",expense);
        assertCode(()->payments.create(maker,key(),new TreasuryModels.Payment("other_expense",null,expense,account,"1",DAY,null,null,null,null)),"MASTER_DATA_DISABLED");
        assertZero();
    }

    @Test void receiptOnlyAllocatesItsOwnPurchaseOrderPrepaymentThenManualSettlementCompletesAp() {
        var openingDraft=opening.createEntry(maker,key(),new OpeningService.Entry("payable",partner,null,"C","5",DAY,null,null));
        long openingId=id(openingDraft);
        var openingApproved=opening.command(reviewer,false,openingId,"approve",key(),new OpeningService.Version(number(openingDraft,"version"),null));
        opening.command(reviewer,false,openingId,"post",key(),new OpeningService.Version(number(openingApproved,"version"),null));
        long warehouse=Ids.next(),unit=Ids.next(),category=Ids.next(),product=Ids.next(),sku=Ids.next();
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'W','验收收货仓')",warehouse,tenant,company);
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件')",unit,tenant);
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'CAT','验收分类','/')",category,tenant);
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'PROD','验收商品',?)",product,tenant,category,unit);
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name,default_tax_rate,purchase_price) VALUES(?,?,?,'SKU','验收规格',0,10)",sku,tenant,product);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,sku,unit);
        cashOpening("100");
        var order=orders.create(maker,key(),new PurchaseOrderModels.Input(Long.toString(partner),DAY,null,true,"0",List.of(
            new PurchaseOrderModels.Line(null,null,Long.toString(sku),Long.toString(warehouse),Long.toString(unit),"10","10",null,null,null,"0")),List.of(),null,null,null));
        long orderId=id(order);
        var pending=orders.command(maker,orderId,"submit",key(),new PurchaseOrderModels.Command(number(order,"version"),null,null));
        orders.command(reviewer,orderId,"approve",key(),new PurchaseOrderModels.Command(number(pending,"version"),null,null));
        long prepayment=confirmedPayment("20",orderId),unrelated=confirmedPayment("10",null);
        long orderLine=jdbc.queryForObject("SELECT id FROM purchase_order_line WHERE order_id=?",Long.class,orderId);
        var receipt=receiving.createReceipt(maker,key(),new ReceivingModels.ReceiptInput(partner,warehouse,DAY,null,List.of(new ReceivingModels.ReceiptLine(orderLine,unit,"10")),null));
        long receiptId=id(receipt);
        var submitted=receiving.command(maker,false,receiptId,"submit",key(),number(receipt,"version"));
        receiving.command(maker,false,receiptId,"post",key(),number(submitted,"version"));
        assertThat(open("payment",prepayment)).isEqualByComparingTo("0");
        assertThat(open("payment",unrelated)).isEqualByComparingTo("10");
        long credit=jdbc.queryForObject("SELECT i.id FROM partner_open_item i JOIN payable p ON p.id=i.source_id AND i.item_type='payable' WHERE p.tenant_id=? AND p.source_id=?",Long.class,tenant,receiptId);
        assertThat(openId(credit)).isEqualByComparingTo("80");
        assertThat(balance(account)).isEqualByComparingTo("70");
        assertThat(orderQueries.get(maker,orderId)).containsEntry("settlementStatus","partial");
        var allPayables=payableRows(queries.payables(maker,partner,false,null,20));
        assertThat(allPayables).hasSize(2);
        assertThat(allPayables).allSatisfy(row->assertThat(row).containsEntry("supplierId",Long.toString(partner)).containsEntry("supplierName","双重角色往来")
                .containsEntry("kind","charge").containsEntry("taxAmount","0.00").containsEntry("invoicedAmount","0.00").containsEntry("businessDate",DAY.toString()));
        assertThat(allPayables.stream().filter(row->"opening_entry".equals(row.get("sourceType"))).findFirst().orElseThrow())
                .containsEntry("sourceId",Long.toString(openingId)).containsEntry("sourceLineId",Long.toString(openingId))
                .containsEntry("sourceNo",openingDraft.get("documentNo")).containsEntry("amount","5.00").containsEntry("untaxedAmount","5.00").containsEntry("openAmount","5.00");
        var receiptPayable=allPayables.stream().filter(row->"goods_receipt_line".equals(row.get("sourceType"))).findFirst().orElseThrow();
        assertThat(receiptPayable).containsEntry("sourceId",Long.toString(receiptId)).containsEntry("sourceNo",receipt.get("documentNo"))
                .containsEntry("amount","100.00").containsEntry("untaxedAmount","100.00").containsEntry("openAmount","80.00");
        assertThat(receiptPayable.get("id")).isInstanceOf(String.class);
        assertThat(receiptPayable.get("sourceLineId")).isInstanceOf(String.class);
        assertThat(payableRows(queries.payables(maker,Ids.next(),false,null,20))).isEmpty();
        long otherCompany=Ids.next();
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'B','隔离公司')",otherCompany,tenant);
        var otherScope=new CompanyAccess(tenant,maker.userId(),otherCompany,true,Set.of(),maker.permissions());
        assertThat(payableRows(queries.payables(otherScope,partner,false,null,20))).isEmpty();
        var firstPage=queries.payables(maker,partner,false,null,1);
        assertThat(payableRows(firstPage)).hasSize(1);
        assertThat(payableRows(queries.payables(maker,partner,false,Long.parseLong(firstPage.get("nextCursor").toString()),1)))
                .singleElement().satisfies(row->assertThat(row).containsEntry("sourceId",Long.toString(openingId)));
        allocate(paymentItem(unrelated),credit,"10");
        long finalPayment=confirmedPayment("70",null);
        allocate(paymentItem(finalPayment),credit,"70");
        assertThat(openId(credit)).isEqualByComparingTo("0");
        assertThat(balance(account)).isEqualByComparingTo("0");
        assertThat(orderQueries.get(maker,orderId)).containsEntry("settlementStatus","full");
        assertThat(payableRows(queries.payables(maker,partner,true,null,20))).singleElement()
                .satisfies(row->assertThat(row).containsEntry("sourceId",Long.toString(openingId)));
        assertThat(payableRows(queries.payables(maker,partner,false,null,20))).hasSize(2);
        assertZero();
    }

    private boolean attemptPayment(CountDownLatch start,long id,Map<String,Object> pending) throws InterruptedException {
        start.await();
        try {payments.command(reviewer,id,"confirm",key(),version(pending));return true;}
        catch(ApiException error) {if(!"CASH_BALANCE_INSUFFICIENT".equals(error.getErrorCode())) {throw error;}return false;}
    }
    private TreasuryModels.Payment input(String amount,Long order,Long version) {return new TreasuryModels.Payment("supplier",partner,null,account,amount,DAY,order,null,null,version);}
    private TreasuryModels.Transfer transfer(long from,long to,String amount,String fee,Long version) {return new TreasuryModels.Transfer(from,to,amount,fee,DAY,null,version);}
    private long confirmedPayment(String amount,Long order) {var draft=payments.create(maker,key(),input(amount,order,null));long id=id(draft);var pending=payments.command(maker,id,"submit",key(),version(draft));payments.command(reviewer,id,"confirm",key(),version(pending));return id;}
    private long confirmedReceipt(String type,String amount) {var draft=receipts.create(maker,key(),new FinanceModels.Receipt(type,partner,null,account,amount,DAY,null,null,null,null));long id=id(draft);var pending=receipts.command(maker,id,"submit",key(),new FinanceModels.Version(number(draft,"version"),null,null));receipts.command(reviewer,id,"confirm",key(),new FinanceModels.Version(number(pending,"version"),null,null));return id;}
    private void cashOpening(String amount) {long id=Ids.next();ledger.post(maker,new LedgerService.Posting("opening_entry",id,0,period,DAY,List.of(entry("FUNDS","D",amount,null,account),entry("OPENING_EQUITY","C",amount,null,null))));}
    private long payable(String kind,String amount) {
        long id=Ids.next();
        jdbc.update("""
            INSERT INTO payable(id,tenant_id,company_id,supplier_id,kind,source_type,source_id,source_line_id,amount,untaxed_amount,tax_amount,business_date,period_id)
            VALUES(?,?,?,?,?,'opening_entry',?,?,?,?,0,?,?)
            """,id,tenant,company,partner,kind,id,id,d(amount),d(amount),DAY,period);
        ledger.post(maker,new LedgerService.Posting("opening_entry",id,0,period,DAY,List.of(
                entry("AP",kind.equals("charge")?"C":"D",amount,partner,null),entry("OPENING_EQUITY",kind.equals("charge")?"D":"C",amount,null,null))));
        return settlement.open(maker,new SettlementService.OpenItem("payable",id,partner,kind.equals("charge")?"C":"D",d(amount),DAY));
    }
    private Map<String,Object> allocate(long debit,long credit,String amount) {return queries.allocate(maker,key(),new FinanceModels.Allocations(partner,List.of(new FinanceModels.Allocation(debit,credit,amount))));}
    private long paymentItem(long id) {return jdbc.queryForObject("SELECT id FROM partner_open_item WHERE tenant_id=? AND item_type='payment' AND source_id=?",Long.class,tenant,id);}
    private long receiptItem(long id) {return jdbc.queryForObject("SELECT id FROM partner_open_item WHERE tenant_id=? AND item_type='receipt' AND source_id=?",Long.class,tenant,id);}
    private BigDecimal open(String type,long id) {return jdbc.queryForObject("SELECT open_amount FROM partner_open_item WHERE tenant_id=? AND item_type=? AND source_id=?",BigDecimal.class,tenant,type,id);}
    private BigDecimal openId(long id) {return jdbc.queryForObject("SELECT open_amount FROM partner_open_item WHERE id=?",BigDecimal.class,id);}
    private BigDecimal balance(long id) {return jdbc.queryForObject("SELECT balance FROM cash_account_balance WHERE cash_account_id=?",BigDecimal.class,id);}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> payableRows(Map<String,Object> result) {return (List<Map<String,Object>>)result.get("items");}
    private long count(String sql) {return jdbc.queryForObject(sql,Long.class,tenant);}
    private void assertZero() {for(String type:ReconciliationChecks.TYPES) {assertThat(checks.check(maker,type,type.equals("period_close")?period:null)).as(type).isEmpty();}}
    private LedgerService.Line entry(String code,String direction,String amount,Long partner,Long account) {return new LedgerService.Line(code,direction,d(amount),partner,account,null,null,null,"资金验收");}
    private static BigDecimal d(String value) {return new BigDecimal(value);}
    private static String key() {return UUID.randomUUID().toString();}
    private static long id(Map<String,Object> row) {return Long.parseLong(row.get("id").toString());}
    private static long number(Map<String,Object> row,String name) {return ((Number)row.get(name)).longValue();}
    private static TreasuryModels.Version version(Map<String,Object> row) {return new TreasuryModels.Version(number(row,"version"),null);}
    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable work,String code) {assertThatThrownBy(work).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getErrorCode()).isEqualTo(code));}
}
