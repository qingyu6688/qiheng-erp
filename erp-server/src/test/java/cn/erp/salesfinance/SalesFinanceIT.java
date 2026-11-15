package cn.erp.salesfinance;

import cn.erp.ErpApplication;
import cn.erp.accounting.*;
import cn.erp.fulfillment.*;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.*;
import cn.erp.reconciliation.ReconciliationChecks;
import cn.erp.salesorder.*;
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
import static cn.erp.inventory.InventoryModels.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
class SalesFinanceIT {
    private static final LocalDate DATE=LocalDate.of(2026,11,1);
    @Autowired JdbcTemplate jdbc;
    @Autowired ReceiptService receipts;
    @Autowired SalesReturnService returns;
    @Autowired RefundApplicationService refunds;
    @Autowired SalesFinanceQueries queries;
    @Autowired InventoryService inventory;
    @Autowired LedgerService ledger;
    @Autowired SettlementService settlement;
    @Autowired ReconciliationChecks checks;
    @Autowired SalesOrderService salesOrders;
    @Autowired ShipmentService shipments;
    private long tenant,company,customer,account,warehouse,unit,category,product,sku,period,order,orderLine,shipment,shipmentLine;
    private CompanyAccess maker,reviewer;
    private long chargeItem;

    @BeforeEach void seed() {
        tenant=Ids.next();company=Ids.next();customer=Ids.next();account=Ids.next();warehouse=Ids.next();unit=Ids.next();
        category=Ids.next();product=Ids.next();sku=Ids.next();period=Ids.next();order=Ids.next();orderLine=Ids.next();shipment=Ids.next();shipmentLine=Ids.next();
        var permissions=Set.of("receipt:view","receipt:create","receipt:update","receipt:submit","receipt:return","receipt:confirm",
                "sales_return:view","sales_return:create","sales_return:update","sales_return:submit","sales_return:void","sales_return:post",
                "refund_request:view","refund_request:create","refund_request:update","refund_request:submit","refund_request:approve","refund_request:reject","refund_request:cancel","refund_request:register_paid",
                "settlement:view","settlement:allocate","settlement:revoke","cost:view",
                "sales_order:view","sales_order:reserve","sales_order:cancel","shipment:create","shipment:view","shipment:submit","shipment:void");
        maker=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),permissions);
        reviewer=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),permissions);
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,?)",tenant,"SF"+tenant,"销售资金闭环测试");
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,?,?)",company,tenant,"A","样例公司");
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,?,?)",customer,tenant,"C","样例客户");
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(?,?,?,'customer')",Ids.next(),tenant,customer);
        jdbc.update("INSERT INTO cash_account(id,tenant_id,company_id,code,name,account_type) VALUES(?,?,?,?,?,'bank')",account,tenant,company,"BANK","银行账户");
        jdbc.update("INSERT INTO cash_account_balance(id,tenant_id,company_id,cash_account_id) VALUES(?,?,?,?)",Ids.next(),tenant,company,account);
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,?,?)",warehouse,tenant,company,"W","样例仓库");
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件')",unit,tenant);
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'CAT','样例分类','/')",category,tenant);
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'P','样例商品',?)",product,tenant,category,unit);
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name) VALUES(?,?,?,'S','样例规格')",sku,tenant,product);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,sku,unit);
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,'202611','2026-11-01','2026-11-30')",period,tenant,company);
        jdbc.update("""
                INSERT INTO sales_order(id,tenant_id,company_id,order_no,customer_id,business_date,price_tax_inclusive,goods_amount,total_amount,untaxed_amount,tax_amount,status,revision,approved_at)
                VALUES(?,?,?,'SO-SAMPLE',?,?,1,137.50,137.50,121.68,15.82,'approved',1,UTC_TIMESTAMP(3))
                """,order,tenant,company,customer,DATE);
        jdbc.update("""
                INSERT INTO sales_order_line(id,tenant_id,company_id,order_id,line_no,sku_id,warehouse_id,unit_id,unit_rate,qty,base_qty,unit_price,
                amount,line_net,receivable_net,tax_rate,untaxed_amount,tax_amount,shipped_base_qty,signed_base_qty,recognized_amount,recognized_tax)
                VALUES(?,?,?,?,1,?,?,?,1,3,3,45.83333333,137.50,137.50,137.50,0.13,121.68,15.82,3,3,137.50,15.82)
                """,orderLine,tenant,company,order,sku,warehouse,unit);
        jdbc.update("""
                INSERT INTO shipment(id,tenant_id,company_id,shipment_no,customer_id,warehouse_id,business_date,status,receivable_amount,untaxed_amount,tax_amount,cost_amount,signed_cost_amount,period_id,posted_at,sign_date,sign_period_id,signed_at)
                VALUES(?,?,?,'SH-SAMPLE',?,?,?,'signed',137.50,121.68,15.82,33,33,?,UTC_TIMESTAMP(3),?,?,UTC_TIMESTAMP(3))
                """,shipment,tenant,company,customer,warehouse,DATE,period,DATE,period);
        jdbc.update("""
                INSERT INTO shipment_line(id,tenant_id,company_id,shipment_id,line_no,order_line_id,sku_id,unit_id,unit_rate,qty,base_qty,
                receivable_amount,untaxed_amount,tax_amount,unit_cost,cost_amount,signed_base_qty,signed_cost_amount,diff_cost_amount)
                VALUES(?,?,?,?,1,?,?,?,1,3,3,137.50,121.68,15.82,11,33,3,33,0)
                """,shipmentLine,tenant,company,shipment,orderLine,sku,unit);
        long opening=Ids.next();
        inventory.receive(maker,posting("opening_in",opening),d("3"),d("33"));
        ledger.post(maker,new LedgerService.Posting("opening_inventory",opening,0,period,DATE,List.of(
                line("INVENTORY","D","33",null,warehouse,sku),line("OPENING_EQUITY","C","33",null,null,null))));
        inventory.issue(maker,posting("sales_out",shipmentLine),d("3"),null);
        ledger.post(maker,new LedgerService.Posting("shipment",shipment,0,period,DATE,List.of(
                line("GOODS_SHIPPED","D","33",null,warehouse,sku),line("INVENTORY","C","33",null,warehouse,sku))));
        ledger.post(maker,new LedgerService.Posting("shipment_sign",shipment,0,period,DATE,List.of(
                line("AR","D","137.50",customer,null,null),line("SALES_REVENUE","C","121.68",null,null,null),
                line("OUTPUT_VAT","C","15.82",null,null,null),line("COGS","D","33",null,null,null),line("GOODS_SHIPPED","C","33",null,warehouse,sku))));
        long receivable=Ids.next();
        jdbc.update("""
                INSERT INTO receivable(id,tenant_id,company_id,customer_id,kind,source_type,source_id,source_line_id,amount,untaxed_amount,tax_amount,business_date,period_id)
                VALUES(?,?,?,?,'charge','shipment_line',?,?,137.50,121.68,15.82,?,?)
                """,receivable,tenant,company,customer,shipment,shipmentLine,DATE,period);
        chargeItem=settlement.open(maker,new SettlementService.OpenItem("receivable",receivable,customer,"D",d("137.50"),DATE));
    }

    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM inbox_event WHERE event_id IN(SELECT id FROM outbox_event WHERE tenant_id=?)",tenant);
        for(String table:List.of("outbox_event","idempotency_record","audit_log","settlement_allocation","partner_open_item","refund_request",
                "receipt","receivable","sales_return_line","sales_return","inventory_reservation_change","inventory_reservation",
                "cost_adjustment","inventory_movement","inventory_cost","inventory_balance","ledger_balance","journal_line","journal",
                "shipment_line","shipment","sales_order_line","sales_order","document_sequence","cash_account_balance","cash_account","accounting_period",
                "unit_conversion","sku","product","product_category","unit","partner_role","business_partner","warehouse","company")) {
            jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);
        }
        jdbc.update("DELETE FROM tenant WHERE id=?",tenant);
    }

    @Test void completeReturnRefundSampleHasNoReconciliationDifferencesAndPaidRetryIsStable() {
        long receipt=confirmedReceipt("137.50");
        long receiptItem=jdbc.queryForObject("SELECT id FROM partner_open_item WHERE tenant_id=? AND item_type='receipt' AND source_id=?",Long.class,tenant,receipt);
        queries.allocate(maker,key(),new FinanceModels.Allocations(customer,List.of(new FinanceModels.Allocation(chargeItem,receiptItem,"137.50"))));
        var returned=returns.create(maker,key(),returnInput("1","pending",null));
        long returnId=identifier(returned);
        returns.command(maker,returnId,"submit",key(),version(returned));
        var submitted=returns.get(maker,returnId);
        assertThat(jdbc.queryForObject("SELECT pending_return_base_qty FROM shipment_line WHERE id=?",BigDecimal.class,shipmentLine)).isEqualByComparingTo("1");
        var posted=returns.command(maker,returnId,"post",key(),version(submitted));
        assertThat(posted).containsEntry("creditAmount","45.83").containsEntry("taxAmount","5.27");
        assertThat(posted).doesNotContainKey("costAmount");
        var balance=inventory.balance(maker,new Key(warehouse,sku));
        assertThat(balance.onHand()).isEqualByComparingTo("1");
        assertThat(balance.blocked()).isEqualByComparingTo("1");
        assertThat(balance.cost()).isEqualByComparingTo("11.00");
        long credit=returnCredit(returnId);
        var created=refunds.create(maker,key(),new FinanceModels.Refund(customer,receipt,account,"45.83",DATE,"退货退款",List.of(credit),null));
        long refund=identifier(created);
        var pending=refunds.command(maker,refund,"submit",key(),version(created));
        assertThat(jdbc.queryForObject("SELECT refund_pending_amount FROM receipt WHERE id=?",BigDecimal.class,receipt)).isEqualByComparingTo("45.83");
        var excess=refunds.create(maker,key(),new FinanceModels.Refund(customer,receipt,account,"10.00",DATE,"重复申请检查",List.of(credit),null));
        assertThatThrownBy(()->refunds.command(maker,identifier(excess),"submit",key(),version(excess)))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getErrorCode()).isEqualTo("REFUND_AMOUNT_EXCEEDED"));
        assertThatThrownBy(()->refunds.command(maker,refund,"approve",key(),version(pending)))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getErrorCode()).isEqualTo("SAME_OPERATOR_NOT_ALLOWED"));
        var approved=refunds.command(reviewer,refund,"approve",key(),version(pending));
        String paidKey=key();var input=new FinanceModels.Paid(number(approved,"version"),DATE,"BANK-REFUND-SAMPLE");
        var paid=refunds.paid(reviewer,refund,paidKey,input);
        var replay=refunds.paid(reviewer,refund,paidKey,input);
        assertThat(replay).containsEntry("id",paid.get("id"));
        assertThat(number(replay,"version")).isEqualTo(number(paid,"version"));
        assertThat(paid).containsEntry("status","refunded");
        assertThat(jdbc.queryForObject("SELECT balance FROM cash_account_balance WHERE cash_account_id=?",BigDecimal.class,account)).isEqualByComparingTo("91.67");
        assertThat(jdbc.queryForObject("SELECT refunded_amount FROM receipt WHERE id=?",BigDecimal.class,receipt)).isEqualByComparingTo("45.83");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal WHERE tenant_id=? AND source_type='refund_request' AND source_id=?",Long.class,tenant,refund)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT SUM(open_amount) FROM partner_open_item WHERE tenant_id=?",BigDecimal.class,tenant)).isEqualByComparingTo("0.00");
        var remaining=returns.create(maker,key(),returnInput("2","passed",null));
        long secondReturn=identifier(remaining);
        var remainingSubmitted=returns.command(maker,secondReturn,"submit",key(),version(remaining));
        var remainingPosted=returns.command(maker,secondReturn,"post",key(),version(remainingSubmitted));
        assertThat(remainingPosted).containsEntry("creditAmount","91.67").containsEntry("taxAmount","10.55");
        long otherSale=Ids.next(),otherReceivable=Ids.next();
        jdbc.update("""
                INSERT INTO receivable(id,tenant_id,company_id,customer_id,kind,source_type,source_id,source_line_id,amount,untaxed_amount,tax_amount,business_date,period_id)
                VALUES(?,?,?,?,'charge','sample_sale',?,?,91.67,81.12,10.55,?,?)
                """,otherReceivable,tenant,company,customer,otherSale,otherSale,DATE,period);
        ledger.post(maker,new LedgerService.Posting("sample_sale",otherSale,0,period,DATE,List.of(
                line("AR","D","91.67",customer,null,null),line("SALES_REVENUE","C","81.12",null,null,null),line("OUTPUT_VAT","C","10.55",null,null,null))));
        long otherCharge=settlement.open(maker,new SettlementService.OpenItem("receivable",otherReceivable,customer,"D",d("91.67"),DATE));
        queries.allocate(maker,key(),new FinanceModels.Allocations(customer,List.of(new FinanceModels.Allocation(otherCharge,returnCredit(secondReturn),"91.67"))));
        assertThat(jdbc.queryForObject("SELECT SUM(open_amount) FROM partner_open_item WHERE tenant_id=?",BigDecimal.class,tenant)).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("SELECT balance FROM cash_account_balance WHERE cash_account_id=?",BigDecimal.class,account)).isEqualByComparingTo("91.67");
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).cost()).isEqualByComparingTo("33.00");
        for(String type:List.of("inventory_balance","inventory_cost","reservation","cash_balance","open_item","journal_balance","ledger_inventory")) {
            assertThat(checks.check(maker,type,null)).as(type+"必须零差异").isEmpty();
        }
        assertThat(checks.check(maker,"period_close",period)).as("完整退货退款样例可通过期间检查").isEmpty();
    }

    @Test void pendingReturnOccupancyCannotBeUsedTwiceAndVoidReleasesIt() {
        var first=returns.create(maker,key(),returnInput("2","passed",null));
        var second=returns.create(maker,key(),returnInput("2","passed",null));
        var pending=returns.command(maker,identifier(first),"submit",key(),version(first));
        assertThatThrownBy(()->returns.command(maker,identifier(second),"submit",key(),version(second)))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getErrorCode()).isEqualTo("RETURN_QTY_EXCEEDED"));
        returns.command(maker,identifier(first),"void",key(),version(pending));
        returns.command(maker,identifier(second),"submit",key(),version(second));
        var partial=new CompanyAccess(tenant,maker.userId(),company,false,Set.of(Ids.next()),maker.permissions());
        assertThatThrownBy(()->returns.get(partial,identifier(first))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test void twoPartialReturnsConsumeOriginalAmountTaxAndCostExactly() {
        for(String qty:List.of("1","2")) {
            var created=returns.create(maker,key(),returnInput(qty,"passed",null));
            var submitted=returns.command(maker,identifier(created),"submit",key(),version(created));
            returns.command(maker,identifier(created),"post",key(),version(submitted));
        }
        var sum=jdbc.queryForMap("SELECT SUM(credit_amount) a,SUM(tax_amount) t,SUM(cost_amount) c FROM sales_return WHERE tenant_id=?",tenant);
        assertThat((BigDecimal)sum.get("a")).isEqualByComparingTo("137.50");
        assertThat((BigDecimal)sum.get("t")).isEqualByComparingTo("15.82");
        assertThat((BigDecimal)sum.get("c")).isEqualByComparingTo("33.00");
        assertThat(jdbc.queryForObject("SELECT shipped_base_qty FROM sales_order_line WHERE id=?",BigDecimal.class,orderLine)).isEqualByComparingTo("3");
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).blocked()).isEqualByComparingTo("0");
    }

    @Test void T10ConcurrentReturnSubmissionsNeverExceedSignedQuantityForTwentyRounds() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            for(int round=0;round<20;round++) {
                var first=returns.create(maker,key(),returnInput("2","passed",null));
                var second=returns.create(maker,key(),returnInput("2","passed",null));
                var start=new CountDownLatch(1);
                var a=pool.submit(()->attemptReturn(start,first));var b=pool.submit(()->attemptReturn(start,second));start.countDown();
                long left=a.get(10,TimeUnit.SECONDS),right=b.get(10,TimeUnit.SECONDS);
                assertThat((left>0?1:0)+(right>0?1:0)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT pending_return_base_qty FROM shipment_line WHERE id=?",BigDecimal.class,shipmentLine)).isEqualByComparingTo("2");
                long winner=left>0?left:right;
                returns.command(maker,winner,"void",key(),version(returns.get(maker,winner)));
            }
        }
        assertThat(jdbc.queryForObject("SELECT pending_return_base_qty FROM shipment_line WHERE id=?",BigDecimal.class,shipmentLine)).isEqualByComparingTo("0");
    }

    @Test void rejectedRefundCanChangeAmountAndResubmitAndCancellationRestoresBothCaps() {
        long receipt=confirmedReceipt("100.00");
        var created=refunds.create(maker,key(),new FinanceModels.Refund(customer,receipt,account,"30.00",DATE,"多收退回",null,null));
        long id=identifier(created);
        var pending=refunds.command(maker,id,"submit",key(),version(created));
        var rejected=refunds.command(reviewer,id,"reject",key(),new FinanceModels.Version(number(pending,"version"),null,"金额更正"));
        var changed=refunds.update(maker,id,new FinanceModels.Refund(customer,receipt,account,"20.00",DATE,"更正金额",null,number(rejected,"version")));
        pending=refunds.command(maker,id,"submit",key(),version(changed));
        var approved=refunds.command(reviewer,id,"approve",key(),version(pending));
        refunds.command(reviewer,id,"cancel",key(),version(approved));
        assertThat(jdbc.queryForObject("SELECT refund_pending_amount FROM receipt WHERE id=?",BigDecimal.class,receipt)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT open_amount FROM partner_open_item WHERE tenant_id=? AND item_type='receipt' AND source_id=?",BigDecimal.class,tenant,receipt)).isEqualByComparingTo("100");
    }

    @Test void refundWithoutAvailableCreditReturnsBusinessErrorAndKeepsReservationZero() {
        long receipt=confirmedReceipt("137.50");
        long receiptItem=jdbc.queryForObject("SELECT id FROM partner_open_item WHERE tenant_id=? AND item_type='receipt' AND source_id=?",Long.class,tenant,receipt);
        queries.allocate(maker,key(),new FinanceModels.Allocations(customer,List.of(new FinanceModels.Allocation(chargeItem,receiptItem,"137.50"))));
        var created=refunds.create(maker,key(),new FinanceModels.Refund(customer,receipt,account,"10.00",DATE,"额度不足检查",null,null));
        long id=identifier(created);
        assertThatThrownBy(()->refunds.command(maker,id,"submit",key(),version(created)))
                .isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.getStatus()).isEqualTo(422);assertThat(e.getErrorCode()).isEqualTo("REFUND_AMOUNT_EXCEEDED");});
        assertThat(jdbc.queryForObject("SELECT refund_pending_amount FROM receipt WHERE id=?",BigDecimal.class,receipt)).isEqualByComparingTo("0");
        assertThat(refunds.get(maker,id)).containsEntry("status","draft");
    }

    @Test void B10ConcurrentRefundsPreserveBothCapsForTwentyRounds() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            for(int round=0;round<20;round++) {
                long original=confirmedReceipt("10.00",null),otherPath=confirmedReceipt("10.00",null);
                long credit=receiptItem(original),otherCredit=receiptItem(otherPath);
                BigDecimal cash=cashBalance();
                // 可用贷方额度20元，独立争用原收款10元上限。
                var first=refundDraft(original,"7.00",List.of(credit,otherCredit));
                var second=refundDraft(original,"7.00",List.of(credit,otherCredit));
                long winner=competeRefunds(pool,first,second);
                assertThat(receiptPending(original)).isEqualByComparingTo("7.00");
                assertThat(receiptPending(otherPath)).isEqualByComparingTo("0.00");
                assertThat(openAmount(credit).add(openAmount(otherCredit))).isEqualByComparingTo("13.00");
                releaseRefund(winner);
                assertThat(receiptPending(original)).isEqualByComparingTo("0.00");

                // 原收款路径各有10元可退，独立争用同一笔10元贷方额度。
                first=refundDraft(original,"7.00",List.of(credit));
                second=refundDraft(otherPath,"7.00",List.of(credit));
                winner=competeRefunds(pool,first,second);
                assertThat(receiptPending(original).add(receiptPending(otherPath))).isEqualByComparingTo("7.00");
                assertThat(openAmount(credit)).isEqualByComparingTo("3.00");
                releaseRefund(winner);
                assertThat(receiptPending(original).add(receiptPending(otherPath))).isEqualByComparingTo("0.00");
                assertThat(openAmount(credit)).isEqualByComparingTo("10.00");
                assertThat(openAmount(otherCredit)).isEqualByComparingTo("10.00");
                assertThat(cashBalance()).isEqualByComparingTo(cash);
            }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement_allocation WHERE tenant_id=? AND status IN('reserved','active')",Long.class,tenant)).isZero();
        assertThat(checks.check(maker,"cash_balance",null)).isEmpty();
        assertThat(checks.check(maker,"open_item",null)).isEmpty();
    }

    @Test void T09B17CancelRejectsPendingShipmentThenKeepsConfirmedPrepaymentAfterVoid() {
        long cancellable=Ids.next(),line=Ids.next(),opening=Ids.next();
        inventory.receive(maker,posting("opening_in",opening),d("2"),d("22"));
        ledger.post(maker,new LedgerService.Posting("opening_inventory",opening,0,period,DATE,List.of(
                line("INVENTORY","D","22",null,warehouse,sku),line("OPENING_EQUITY","C","22",null,null,null))));
        jdbc.update("""
                INSERT INTO sales_order(id,tenant_id,company_id,order_no,customer_id,business_date,price_tax_inclusive,goods_amount,total_amount,untaxed_amount,tax_amount,status,revision,approved_at)
                VALUES(?,?,?,?,?,?,1,20,20,20,0,'approved',1,UTC_TIMESTAMP(3))
                """,cancellable,tenant,company,"SO-CANCEL-"+cancellable,customer,DATE);
        jdbc.update("""
                INSERT INTO sales_order_line(id,tenant_id,company_id,order_id,line_no,sku_id,warehouse_id,unit_id,unit_rate,qty,base_qty,unit_price,
                amount,line_net,receivable_net,tax_rate,untaxed_amount,tax_amount)
                VALUES(?,?,?,?,1,?,?,?,1,2,2,10,20,20,20,0,20,0)
                """,line,tenant,company,cancellable,sku,warehouse,unit);
        salesOrders.command(maker,cancellable,"reserve",key(),new SalesOrderModels.Command(0L,null,null));
        long receipt=confirmedReceipt("30.00",cancellable),credit=receiptItem(receipt);
        BigDecimal cash=cashBalance(),prepayment=openAmount(credit);
        var draft=shipments.create(maker,key(),new ShipmentModels.Input(customer,warehouse,DATE,"delivery",null,
                List.of(new ShipmentModels.InputLine(line,unit,"1")),null));
        long shipmentId=identifier(draft);
        var pending=shipments.submit(maker,shipmentId,key(),number(draft,"version"));
        assertThatThrownBy(()->salesOrders.command(maker,cancellable,"cancel",key(),orderCommand(cancellable,"客户撤单")))
                .isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.getStatus()).isEqualTo(422);assertThat(e.getErrorCode()).isEqualTo("DEPENDENCY_EXISTS");});
        assertThat(jdbc.queryForObject("SELECT status FROM sales_order WHERE id=?",String.class,cancellable)).isEqualTo("approved");
        assertThat(jdbc.queryForObject("SELECT pending_ship_base_qty FROM sales_order_line WHERE id=?",BigDecimal.class,line)).isEqualByComparingTo("1");
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).reserved()).isEqualByComparingTo("2");
        assertThat(cashBalance()).isEqualByComparingTo(cash);
        assertThat(openAmount(credit)).isEqualByComparingTo(prepayment);
        shipments.voidSubmitted(maker,shipmentId,key(),number(pending,"version"));
        var cancelled=salesOrders.command(maker,cancellable,"cancel",key(),orderCommand(cancellable,"发货已作废，客户撤单"));
        assertThat(cancelled).containsEntry("status","cancelled");
        assertThat(jdbc.queryForObject("SELECT pending_ship_base_qty FROM sales_order_line WHERE id=?",BigDecimal.class,line)).isEqualByComparingTo("0");
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).reserved()).isEqualByComparingTo("0");
        assertThat(receipts.get(maker,receipt)).containsEntry("status","confirmed").containsEntry("relatedOrderId",Long.toString(cancellable));
        assertThat(openAmount(credit)).isEqualByComparingTo(prepayment).isEqualByComparingTo("30.00");
        assertThat(cashBalance()).isEqualByComparingTo(cash).isEqualByComparingTo("30.00");
        assertThat(receiptPending(receipt)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT refunded_amount FROM receipt WHERE id=?",BigDecimal.class,receipt)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement_allocation WHERE tenant_id=?",Long.class,tenant)).isZero();
        for(String type:List.of("inventory_balance","inventory_cost","reservation","cash_balance","open_item","ledger_inventory")) {assertThat(checks.check(maker,type,null)).as(type).isEmpty();}
    }

    @Test void B20SalesReturnIllegalTransitionsReturn409WithoutMovingStock() {
        var draft=returns.create(maker,key(),returnInput("1","pending",null));long id=identifier(draft);
        assertStateConflict(()->returns.command(maker,id,"post",key(),version(draft)));
        assertStateConflict(()->returns.command(maker,id,"void",key(),version(draft)));
        var submitted=returns.command(maker,id,"submit",key(),version(draft));
        assertStateConflict(()->returns.command(maker,id,"submit",key(),version(submitted)));
        var voided=returns.command(maker,id,"void",key(),version(submitted));
        assertStateConflict(()->returns.command(maker,id,"post",key(),version(voided)));
        assertThat(jdbc.queryForObject("SELECT pending_return_base_qty FROM shipment_line WHERE id=?",BigDecimal.class,shipmentLine)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE tenant_id=? AND source_type='sales_return_line'",Long.class,tenant)).isZero();
    }

    @Test void B20RefundIllegalTransitionsReturn409WithoutChangingMoneyOrReservations() {
        long receipt=confirmedReceipt("30.00",null);var draft=refundDraft(receipt,"10.00",List.of(receiptItem(receipt)));long id=identifier(draft);
        assertStateConflict(()->refunds.command(reviewer,id,"approve",key(),version(draft)));
        assertStateConflict(()->refunds.command(maker,id,"cancel",key(),version(draft)));
        assertStateConflict(()->refunds.paid(reviewer,id,key(),new FinanceModels.Paid(number(draft,"version"),DATE,"ILLEGAL-PAID")));
        var submitted=refunds.command(maker,id,"submit",key(),version(draft));
        assertStateConflict(()->refunds.command(maker,id,"submit",key(),version(submitted)));
        assertStateConflict(()->refunds.paid(reviewer,id,key(),new FinanceModels.Paid(number(submitted,"version"),DATE,"ILLEGAL-PAID")));
        var approved=refunds.command(reviewer,id,"approve",key(),version(submitted));
        assertStateConflict(()->refunds.command(reviewer,id,"approve",key(),version(approved)));
        var cancelled=refunds.command(reviewer,id,"cancel",key(),version(approved));
        assertStateConflict(()->refunds.paid(reviewer,id,key(),new FinanceModels.Paid(number(cancelled,"version"),DATE,"ILLEGAL-PAID")));
        assertThat(cashBalance()).isEqualByComparingTo("30.00");
        assertThat(receiptPending(receipt)).isEqualByComparingTo("0");
        assertThat(openAmount(receiptItem(receipt))).isEqualByComparingTo("30.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal WHERE tenant_id=? AND source_type='refund_request'",Long.class,tenant)).isZero();
    }

    private long confirmedReceipt(String amount) {
        return confirmedReceipt(amount,order);
    }
    private long confirmedReceipt(String amount,Long relatedOrder) {
        BigDecimal before=cashBalance();
        var created=receipts.create(maker,key(),new FinanceModels.Receipt("customer",customer,null,account,amount,DATE,relatedOrder,"BANK-IN-"+key(),null,null));
        long id=identifier(created);
        var pending=receipts.command(maker,id,"submit",key(),version(created));
        assertThat(cashBalance()).isEqualByComparingTo(before);
        assertThatThrownBy(()->receipts.command(maker,id,"confirm",key(),version(pending))).isInstanceOf(ApiException.class);
        receipts.command(reviewer,id,"confirm",key(),version(pending));return id;
    }
    private Map<String,Object> refundDraft(long original,String amount,List<Long> credits) {
        return refunds.create(maker,key(),new FinanceModels.Refund(customer,original,account,amount,DATE,"并发额度检查",credits,null));
    }
    private long competeRefunds(ExecutorService pool,Map<String,Object> first,Map<String,Object> second) throws Exception {
        var start=new CountDownLatch(1);
        var a=pool.submit(()->attemptRefund(start,first));var b=pool.submit(()->attemptRefund(start,second));start.countDown();
        long left=a.get(10,TimeUnit.SECONDS),right=b.get(10,TimeUnit.SECONDS);
        assertThat((left>0?1:0)+(right>0?1:0)).isEqualTo(1);
        long winner=left>0?left:right,loser=left>0?identifier(second):identifier(first);
        assertThat(refunds.get(maker,winner)).containsEntry("status","pending_review");
        assertThat(refunds.get(maker,loser)).containsEntry("status","draft");
        return winner;
    }
    private long attemptRefund(CountDownLatch start,Map<String,Object> document) throws InterruptedException {
        start.await();
        try {refunds.command(maker,identifier(document),"submit",key(),version(document));return identifier(document);}
        catch(ApiException error) {if(error.getErrorCode().equals("REFUND_AMOUNT_EXCEEDED")) {return 0;}throw error;}
    }
    private void releaseRefund(long id) {
        var pending=refunds.get(maker,id);
        refunds.command(reviewer,id,"reject",key(),new FinanceModels.Version(number(pending,"version"),null,"释放本轮测试占用"));
    }
    private SalesOrderModels.Command orderCommand(long id,String reason) {
        long version=jdbc.queryForObject("SELECT version FROM sales_order WHERE id=?",Long.class,id);
        return new SalesOrderModels.Command(version,null,reason);
    }
    private long receiptItem(long receipt) {return jdbc.queryForObject("SELECT id FROM partner_open_item WHERE tenant_id=? AND item_type='receipt' AND source_id=?",Long.class,tenant,receipt);}
    private BigDecimal cashBalance() {return jdbc.queryForObject("SELECT balance FROM cash_account_balance WHERE cash_account_id=?",BigDecimal.class,account);}
    private BigDecimal receiptPending(long receipt) {return jdbc.queryForObject("SELECT refund_pending_amount FROM receipt WHERE id=?",BigDecimal.class,receipt);}
    private BigDecimal openAmount(long item) {return jdbc.queryForObject("SELECT open_amount FROM partner_open_item WHERE id=?",BigDecimal.class,item);}
    private void assertStateConflict(Runnable work) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.getStatus()).isEqualTo(409);assertThat(e.getErrorCode()).isEqualTo("STATE_CONFLICT");});
    }
    private long attemptReturn(CountDownLatch start,Map<String,Object> document) throws InterruptedException {
        start.await();
        try {returns.command(maker,identifier(document),"submit",key(),version(document));return identifier(document);}
        catch(ApiException error) {if(error.getErrorCode().equals("RETURN_QTY_EXCEEDED")) {return 0;}throw error;}
    }
    private FinanceModels.ReturnInput returnInput(String qty,String quality,Long version) {return new FinanceModels.ReturnInput(customer,warehouse,DATE,"样例退货",List.of(new FinanceModels.ReturnLine(shipmentLine,unit,qty,quality)),version);}
    private long returnCredit(long returnId) {return jdbc.queryForObject("SELECT i.id FROM partner_open_item i JOIN receivable r ON r.tenant_id=i.tenant_id AND r.company_id=i.company_id AND i.item_type='receivable' AND r.id=i.source_id WHERE r.tenant_id=? AND r.source_type='sales_return_line' AND r.source_id=?",Long.class,tenant,returnId);}
    private Posting posting(String type,long source) {return new Posting(new Key(warehouse,sku),new Source("sample",source,source),DATE,period,type);}
    private LedgerService.Line line(String code,String direction,String amount,Long partner,Long wh,Long item) {return new LedgerService.Line(code,direction,d(amount),partner,null,wh,item,null,"样例交易");}
    private static BigDecimal d(String value) {return new BigDecimal(value);}
    private static String key() {return UUID.randomUUID().toString();}
    private static long identifier(Map<String,Object> row) {return Long.parseLong(row.get("id").toString());}
    private static long number(Map<String,Object> row,String field) {return ((Number)row.get(field)).longValue();}
    private static FinanceModels.Version version(Map<String,Object> row) {return new FinanceModels.Version(number(row,"version"),null,null);}
}
