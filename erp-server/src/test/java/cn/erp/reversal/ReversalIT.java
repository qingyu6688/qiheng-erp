package cn.erp.reversal;

import cn.erp.ErpApplication;
import cn.erp.fulfillment.ShipmentModels;
import cn.erp.identity.CompanyAccess;
import cn.erp.invoicing.InvoiceService;
import cn.erp.inventory.InventoryModels;
import cn.erp.purchasefinance.TreasuryModels;
import cn.erp.receiving.ReceivingModels;
import cn.erp.salesfinance.FinanceModels;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
class ReversalIT extends ReversalTestData {
    @Autowired PlatformTransactionManager transactions;
    @Test void stockAdjustmentReversalRetainsBothFactsAndRestoresZeroInventory() {
        long source=stock("10","100");reverse("stock_adjustment",source);
        assertThat(inventory.balance(maker,new InventoryModels.Key(warehouse,sku)).onHand()).isEqualByComparingTo("0");
        assertThat(inventory.balance(maker,new InventoryModels.Key(warehouse,sku)).cost()).isEqualByComparingTo("0");
        assertThat(count("inventory_movement")).isEqualTo(2);assertThat(count("journal")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM stock_adjustment WHERE id=?",String.class,source)).isEqualTo("reversed");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE tenant_id=? AND posting_action='reverse' AND reverses_movement_id IS NOT NULL",Long.class,tenant)).isEqualTo(1);
        zero();
    }
    @Test void B23ShipmentReversalDoesNotRestoreConsumedReservation() {
        stock("10","100");long order=order("5",false),source=shipment(order,"2",null);reverse("shipment",source);
        var balance=inventory.balance(maker,new InventoryModels.Key(warehouse,sku));
        assertThat(balance.onHand()).isEqualByComparingTo("10");assertThat(balance.reserved()).isEqualByComparingTo("3");
        assertThat(value("SELECT shipped_base_qty FROM sales_order_line WHERE order_id=?",order)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT reservation_state FROM sales_order_line WHERE order_id=?",String.class,order)).isEqualTo("expired");
        assertThat(value("SELECT SUM(remaining_qty) FROM inventory_reservation WHERE tenant_id=?",tenant)).isEqualByComparingTo("3");zero();
    }
    @Test void signedShipmentReversesReceivableCostReturnedDifferenceAndInvoiceCandidate() {
        stock("10","100");long order=order("3",true),source=shipment(order,"3","2");
        shipments.resolve(maker,source,key(),new ShipmentModels.Resolution(3L,DAY,List.of(new ShipmentModels.ResolveLine(line("shipment_line","shipment_id",source),"returned","拒收退回"))));
        long ar=jdbc.queryForObject("SELECT id FROM receivable WHERE source_id=? AND source_type='shipment_line'",Long.class,source);
        reverse("shipment",source);
        var balance=inventory.balance(maker,new InventoryModels.Key(warehouse,sku));assertThat(balance.onHand()).isEqualByComparingTo("10");assertThat(balance.blocked()).isEqualByComparingTo("0");
        assertThat(value("SELECT signed_base_qty FROM sales_order_line WHERE order_id=?",order)).isEqualByComparingTo("0");
        assertThat(salesQueries.get(maker,order)).containsEntry("settlementStatus","none");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receivable WHERE tenant_id=? AND posting_action='reverse'",Long.class,tenant)).isEqualTo(2);
        assertThat(finances.receivables(maker,partner,false,null,20).get("items")).asList().isEmpty();
        code(()->invoices.create(maker,key(),invoice(ar,"40")),"STATE_CONFLICT");
        long allocation=jdbc.queryForObject("SELECT id FROM settlement_allocation WHERE tenant_id=? AND allocation_type='reversal' ORDER BY id LIMIT 1",Long.class,tenant);
        code(()->settlement.revoke(maker,allocation,"不允许单独撤销"),"STATE_CONFLICT");
        long replacement=shipment(order,"1","1");
        assertThat(value("SELECT SUM(amount) FROM receivable WHERE tenant_id=? AND source_id=? AND posting_action='post'",tenant,replacement)).isEqualByComparingTo("25");zero();
    }
    @Test void salesReturnReversalRestoresReturnAllowanceAndRemovesItsFrozenStock() {
        stock("10","100");long order=order("2",false),sent=shipment(order,"2","2");
        var draft=salesReturns.create(maker,key(),new FinanceModels.ReturnInput(partner,warehouse,DAY,"误录退货",List.of(new FinanceModels.ReturnLine(line("shipment_line","shipment_id",sent),unit,"1","pending")),null));long source=id(draft);
        salesReturns.command(maker,source,"submit",key(),new FinanceModels.Version(0L,null,null));salesReturns.command(maker,source,"post",key(),new FinanceModels.Version(1L,null,null));
        reverse("sales_return",source);
        var balance=inventory.balance(maker,new InventoryModels.Key(warehouse,sku));assertThat(balance.onHand()).isEqualByComparingTo("8");assertThat(balance.blocked()).isEqualByComparingTo("0");
        assertThat(value("SELECT returned_base_qty FROM shipment_line WHERE shipment_id=?",sent)).isEqualByComparingTo("0");
        assertThat(value("SELECT returned_base_qty FROM sales_order_line WHERE order_id=?",order)).isEqualByComparingTo("0");zero();
    }
    @Test void goodsReceiptReversalRestoresQuantityAndAllowsSameFeeOnReplacementReceipt() {
        long order=purchase(true),source=receive(order);reverse("goods_receipt",source);
        assertThat(value("SELECT received_base_qty FROM purchase_order_line WHERE order_id=?",order)).isEqualByComparingTo("0");
        assertThat(purchaseQueries.get(maker,order)).containsEntry("settlementStatus","none").containsEntry("status","approved");
        assertThat(inventory.balance(maker,new InventoryModels.Key(warehouse,sku)).cost()).isEqualByComparingTo("0");
        long replacement=receive(order);assertThat(value("SELECT SUM(amount) FROM payable WHERE source_id=? AND posting_action='post'",replacement)).isEqualByComparingTo("145");zero();
    }
    @Test void purchaseReturnReversalAlsoReversesB12CostAdjustment() {
        stock("10","100");long original=receive(purchase(false));shipment(order("15",false),"15","15");
        var draft=receiving.createReturn(maker,key(),new ReceivingModels.ReturnInput(partner,warehouse,DAY,"误录采购退货",List.of(new ReceivingModels.ReturnLine(line("goods_receipt_line","receipt_id",original),unit,"5")),null));long source=id(draft);
        receiving.command(maker,true,source,"submit",key(),0L);receiving.command(maker,true,source,"post",key(),1L);
        assertThat(value("SELECT SUM(amount) FROM cost_adjustment WHERE tenant_id=?",tenant)).isEqualByComparingTo("10");
        reverse("purchase_return",source);
        var balance=inventory.balance(maker,new InventoryModels.Key(warehouse,sku));assertThat(balance.onHand()).isEqualByComparingTo("5");assertThat(balance.cost()).isEqualByComparingTo("60");
        assertThat(value("SELECT SUM(amount) FROM cost_adjustment WHERE tenant_id=?",tenant)).isEqualByComparingTo("0");
        assertThat(value("SELECT returned_base_qty FROM goods_receipt_line WHERE receipt_id=?",original)).isEqualByComparingTo("0");zero();
    }
    @Test void customerSupplierRefundAndOtherIncomeReceiptsCanBeFullyReversed() {
        cash();for(String type:List.of("customer","supplier_refund","other_income")) {
            long source=receipt(type);reverse("receipt",source);assertThat(value("SELECT balance FROM cash_account_balance WHERE cash_account_id=?",account)).isEqualByComparingTo("1000");
            if(type.equals("customer")) {
                long counter=jdbc.queryForObject("SELECT id FROM receivable WHERE tenant_id=? AND source_type='document_reversal' AND source_line_id=?",Long.class,tenant,source);
                code(()->invoices.create(maker,key(),invoice(counter,"100")),"STATE_CONFLICT");
                var candidates=(List<?>)finances.receivables(maker,partner,false,null,20).get("items");
                assertThat(candidates).noneMatch(row->row instanceof Map<?,?> fields&&Long.toString(counter).equals(fields.get("id")));
                assertThat(value("SELECT invoiced_amount FROM receivable WHERE id=?",counter)).isEqualByComparingTo("0");
            }
        }
        assertThat(value("SELECT SUM(open_amount) FROM partner_open_item WHERE tenant_id=?",tenant)).isEqualByComparingTo("0");zero();
    }
    @Test void supplierAndExpensePaymentsCanBeFullyReversed() {
        cash();for(String type:List.of("supplier","other_expense")) {
            long source=payment(type);reverse("payment",source);assertThat(value("SELECT balance FROM cash_account_balance WHERE cash_account_id=?",account)).isEqualByComparingTo("1000");
        }zero();
    }
    @Test void accountTransferReversalRestoresPrincipalAndFeeAtomically() {
        cash();var draft=transfers.create(maker,key(),new TreasuryModels.Transfer(account,target,"100","2",DAY,null,null));long source=id(draft);
        transfers.command(maker,source,"submit",key(),new TreasuryModels.Version(0L,null));transfers.command(reviewer,source,"confirm",key(),new TreasuryModels.Version(1L,null));
        reverse("account_transfer",source);
        assertThat(value("SELECT balance FROM cash_account_balance WHERE cash_account_id=?",account)).isEqualByComparingTo("1000");assertThat(value("SELECT balance FROM cash_account_balance WHERE cash_account_id=?",target)).isEqualByComparingTo("0");zero();
    }
    @Test void B14DependenciesAreCheckedAtCreationAndAgainAfterApproval() {
        cash();long original=receive(purchase(false)),paid=payment("supplier");long debit=open("payment",paid),credit=open("payable",fact("payable",original));
        long allocation=settlement.allocate(maker,debit,credit,new BigDecimal("50"),"manual",null,null);
        code(()->apply("goods_receipt",original),"DEPENDENCY_EXISTS");settlement.revoke(maker,allocation,"撤销误核销");
        long reversal=apply("goods_receipt",original);reversals.command(reviewer,reversal,"approve",key(),new ReversalModels.Version(0L,null));
        long later=settlement.allocate(maker,debit,credit,new BigDecimal("20"),"manual",null,null);
        code(()->reversals.command(maker,reversal,"post",key(),new ReversalModels.Version(1L,null)),"DEPENDENCY_EXISTS");
        assertThat(reversals.get(maker,reversal)).containsEntry("status","approved");
        assertThat(value("SELECT received_base_qty FROM purchase_order_line WHERE tenant_id=?",tenant)).isEqualByComparingTo("10");
        settlement.revoke(maker,later,"重新处理依赖");reversals.command(maker,reversal,"post",key(),new ReversalModels.Version(1L,null));zero();
    }
    @Test void activeInvoiceAndLiveReturnBlockShipmentReversalWithDocumentDetails() {
        stock("10","100");long source=shipment(order("2",false),"2","2"),ar=fact("receivable",source);
        var invoice=invoices.create(maker,key(),invoice(ar,"40"));code(()->apply("shipment",source),"DEPENDENCY_EXISTS");
        invoices.voidInvoice(maker,id(invoice),version(invoice),key());
        var returned=salesReturns.create(maker,key(),new FinanceModels.ReturnInput(partner,warehouse,DAY,"待退",List.of(new FinanceModels.ReturnLine(line("shipment_line","shipment_id",source),unit,"1","passed")),null));
        assertThatThrownBy(()->apply("shipment",source)).isInstanceOfSatisfying(cn.erp.shared.ApiException.class,error->{assertThat(error.getErrorCode()).isEqualTo("DEPENDENCY_EXISTS");assertThat(error.getDetails().toString()).contains("sales_return",returned.get("id").toString());});
        salesReturns.command(maker,id(returned),"submit",key(),new FinanceModels.Version(0L,null,null));salesReturns.command(maker,id(returned),"void",key(),new FinanceModels.Version(1L,null,null));reverse("shipment",source);zero();
    }
    @Test void approvalVersionUniquenessRejectionAndReplayAreEnforced() {
        long source=stock("1","10"),id=apply("stock_adjustment",source);
        code(()->reversals.command(maker,id,"approve",key(),new ReversalModels.Version(0L,null)),"SAME_OPERATOR_NOT_ALLOWED");
        code(()->reversals.command(reviewer,id,"approve",key(),new ReversalModels.Version(5L,null)),"VERSION_CONFLICT");
        code(()->apply("stock_adjustment",source),"STATE_CONFLICT");
        reversals.command(reviewer,id,"reject",key(),new ReversalModels.Version(0L,"退回重新申请"));
        long replacement=apply("stock_adjustment",source);reversals.command(reviewer,replacement,"approve",key(),new ReversalModels.Version(0L,null));
        String key=key();var input=new ReversalModels.Version(1L,null);var result=reversals.command(maker,replacement,"post",key,input);
        assertThat(reversals.command(maker,replacement,"post",key,input).get("id")).isEqualTo(result.get("id"));
        code(()->reversals.command(reviewer,replacement,"post",key,input),"FORBIDDEN");
        code(()->reversals.command(maker,replacement,"post",key(),new ReversalModels.Version(2L,null)),"STATE_CONFLICT");zero();
    }
    @Test void previousClosedPeriodCanBeReversedInOpenPeriodButClosedTargetRollsBack() {
        long previous=Ids.next();jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,'202610','2026-10-01','2026-10-31')",previous,tenant,company);
        long source=stock("1","10",LocalDate.of(2026,10,31));jdbc.update("UPDATE accounting_period SET status='closed',closed_by=?,closed_at=UTC_TIMESTAMP(3) WHERE id=?",user,previous);
        long reversal=apply("stock_adjustment",source);reversals.command(reviewer,reversal,"approve",key(),new ReversalModels.Version(0L,null));
        jdbc.update("UPDATE accounting_period SET status='closed',closed_by=?,closed_at=UTC_TIMESTAMP(3) WHERE id=?",user,period);
        code(()->reversals.command(maker,reversal,"post",key(),new ReversalModels.Version(1L,null)),"PERIOD_CLOSED");
        assertThat(reversals.get(maker,reversal)).containsEntry("status","approved");assertThat(count("inventory_movement")).isEqualTo(1);
        jdbc.update("UPDATE accounting_period SET status='open',closed_by=NULL,closed_at=NULL WHERE id=?",period);
        reversals.command(maker,reversal,"post",key(),new ReversalModels.Version(1L,null));
        assertThat(jdbc.queryForObject("SELECT period_id FROM inventory_movement WHERE tenant_id=? AND posting_action='reverse'",Long.class,tenant)).isEqualTo(period);zero();
    }
    @Test void unavailableInventoryRejectsWholeReversalWithoutReversingJournal() {
        long source=stock("5","50");shipment(order("5",false),"5","5");long reversal=apply("stock_adjustment",source);
        reversals.command(reviewer,reversal,"approve",key(),new ReversalModels.Version(0L,null));
        code(()->reversals.command(maker,reversal,"post",key(),new ReversalModels.Version(1L,null)),"INVENTORY_REVERSAL_NOT_ALLOWED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal WHERE tenant_id=? AND posting_action='reverse'",Long.class,tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM stock_adjustment WHERE id=?",String.class,source)).isEqualTo("posted");
        for(String type:cn.erp.reconciliation.ReconciliationChecks.TYPES)if(!type.equals("period_close"))assertThat(checks.check(maker,type,null)).as(type).isEmpty();
        assertThat(checks.check(maker,"period_close",period)).anySatisfy(diff->assertThat(diff.key()).isEqualTo("document_reversal"));
    }
    @Test void twentyConcurrentSameKeyPostingsProduceOneReverseFactEach() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            for(int round=0;round<20;round++) {
                long source=stock("1","1"),id=apply("stock_adjustment",source);reversals.command(reviewer,id,"approve",key(),new ReversalModels.Version(0L,null));
                String key=key();var start=new CountDownLatch(1);var request=new ReversalModels.Version(1L,null);
                var first=pool.submit(()->{start.await();return reversals.command(maker,id,"post",key,request);});
                var second=pool.submit(()->{start.await();return reversals.command(maker,id,"post",key,request);});start.countDown();
                assertThat(first.get(15,TimeUnit.SECONDS).get("id")).isEqualTo(second.get(15,TimeUnit.SECONDS).get("id"));
            }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE tenant_id=? AND posting_action='reverse'",Long.class,tenant)).isEqualTo(20);zero();
    }
    @Test void dependencyCheckUsesCurrentRowsAfterTransactionSnapshotWasEstablished() throws Exception {
        cash();long source=receive(purchase(false)),payment=payment("supplier"),reversal=apply("goods_receipt",source);
        reversals.command(reviewer,reversal,"approve",key(),new ReversalModels.Version(0L,null));
        long debit=open("payment",payment),credit=open("payable",fact("payable",source));var allocation=new java.util.concurrent.atomic.AtomicLong();
        try(var pool=Executors.newSingleThreadExecutor()) {
            code(()->new TransactionTemplate(transactions).execute(status->{
                jdbc.queryForObject("SELECT status FROM document_reversal WHERE id=?",String.class,reversal);
                try {allocation.set(pool.submit(()->settlement.allocate(maker,debit,credit,new BigDecimal("20"),"manual",null,null)).get(15,TimeUnit.SECONDS));}
                catch(Exception error){throw new IllegalStateException("并发核销未完成",error);}
                return reversals.command(maker,reversal,"post",key(),new ReversalModels.Version(1L,null));
            }),"DEPENDENCY_EXISTS");
        }
        assertThat(jdbc.queryForObject("SELECT status FROM goods_receipt WHERE id=?",String.class,source)).isEqualTo("posted");
        settlement.revoke(maker,allocation.get(),"处理并发产生的依赖");reversals.command(maker,reversal,"post",key(),new ReversalModels.Version(1L,null));zero();
    }
    @Test void concurrentSigningAfterSnapshotIsIncludedInAllReverseFacts() throws Exception {
        stock("10","100");long order=order("2",false),source=shipment(order,"2",null),reversal=apply("shipment",source);
        reversals.command(reviewer,reversal,"approve",key(),new ReversalModels.Version(0L,null));
        try(var pool=Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactions).executeWithoutResult(status->{
                jdbc.queryForObject("SELECT status FROM document_reversal WHERE id=?",String.class,reversal);
                try {pool.submit(()->shipments.sign(maker,source,key(),new ShipmentModels.Signed(2L,DAY,List.of(new ShipmentModels.SignedLine(line("shipment_line","shipment_id",source),"2")),List.of()))).get(15,TimeUnit.SECONDS);}
                catch(Exception error){throw new IllegalStateException("并发签收未完成",error);}
                reversals.command(maker,reversal,"post",key(),new ReversalModels.Version(1L,null));
            });
        }
        assertThat(value("SELECT shipped_base_qty FROM sales_order_line WHERE order_id=?",order)).isEqualByComparingTo("0");
        assertThat(value("SELECT signed_base_qty FROM sales_order_line WHERE order_id=?",order)).isEqualByComparingTo("0");
        assertThat(value("SELECT recognized_amount FROM sales_order_line WHERE order_id=?",order)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receivable WHERE tenant_id=? AND posting_action='reverse'",Long.class,tenant)).isEqualTo(1);zero();
    }
    private InvoiceService.Input invoice(long ar,String amount){return new InvoiceService.Input("sales",partner,"REV-INV-"+key(),DAY,amount,"0",List.of(new InvoiceService.Line(ar,amount)),null);}
}
