package cn.erp.opening;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryModels.Key;
import cn.erp.stockcount.StockCountModels;
import cn.erp.stockcount.StockCountService;
import java.math.BigDecimal;
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
class StockCountIT extends OpeningTestData {
    @Autowired StockCountService counts;
    @Autowired PlatformTransactionManager transactions;

    @Test void T11SnapshotDifferencePreservesReceiptsAndIssuesDuringCounting() {
        adjust("opening","10","100");
        var draft=counts.create(maker,key(),document(sku));long id=id(draft);
        var started=countCommand(maker,id,"start",version(draft));
        assertThat(lines(started).getFirst()).containsEntry("bookQty","10.000000");
        adjust("gain","5","100");adjust("loss","3",null);
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).onHand()).isEqualByComparingTo("12");
        var recorded=record(id,version(started),"8",null);
        var approved=approve(id,recorded);var posted=countCommand(maker,id,"post",version(approved));
        assertThat(posted).containsEntry("status","posted");
        var balance=inventory.balance(maker,new Key(warehouse,sku));
        assertThat(balance.onHand()).isEqualByComparingTo("10");
        assertThat(balance.cost()).isEqualByComparingTo("133.33");
        assertThat(lines(counts.get(maker,id)).getFirst()).containsEntry("diffQty","-2.000000").containsEntry("unitCost","13.33333333").containsEntry("costAmount","26.67");
        assertZero();
    }

    @Test void firstGainNeedsApprovedInputPriceAndZeroQuantityUsesLastInboundPrice() {
        var draft=counts.create(maker,key(),document(zeroSku));long first=id(draft);
        var started=countCommand(maker,first,"start",version(draft));
        var noPrice=record(first,version(started),"3",null);
        assertCode(()->countCommand(maker,first,"submit",version(noPrice)),"VALIDATION_FAILED");
        var priced=record(first,version(noPrice),"3","12.3456789");
        var approved=approve(first,priced);countCommand(maker,first,"post",version(approved));
        assertThat(inventory.balance(maker,new Key(warehouse,zeroSku)).cost()).isEqualByComparingTo("37.04");
        adjust("opening","3","21");adjust("loss","3",null);
        var second=counts.create(maker,key(),document(sku));long id=id(second);
        var snapshot=countCommand(maker,id,"start",version(second));var recorded=record(id,version(snapshot),"2",null);
        var allowed=approve(id,recorded);countCommand(maker,id,"post",version(allowed));
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).onHand()).isEqualByComparingTo("2");
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).cost()).isEqualByComparingTo("14");assertZero();
    }

    @Test void zeroCountCreatesNoMovementAndApprovalVersionStateRulesAreEnforced() {
        var draft=counts.create(maker,key(),document(sku));long id=id(draft);
        assertCode(()->countCommand(maker,id,"submit",version(draft)),"STATE_CONFLICT");
        assertCode(()->countCommand(maker,id,"post",version(draft)),"STATE_CONFLICT");
        var started=countCommand(maker,id,"start",version(draft));
        assertCode(()->counts.counts(maker,id,new StockCountModels.Counts(version(draft),List.of(new StockCountModels.Count(id(lines(started).getFirst()),"0",null)))),"VERSION_CONFLICT");
        assertCode(()->countCommand(maker,id,"submit",version(started)),"VALIDATION_FAILED");
        var recorded=record(id,version(started),"0",null);var submitted=countCommand(maker,id,"submit",version(recorded));
        assertThat(submitted.get("allowedActions")).asList().doesNotContain("approve");
        assertCode(()->countCommand(maker,id,"approve",version(submitted)),"SAME_OPERATOR_NOT_ALLOWED");
        assertCode(()->countCommand(reviewer,id,"reject",version(submitted)),"VALIDATION_FAILED");
        var rejected=counts.command(reviewer,id,"reject",key(),new StockCountModels.Version(version(submitted),"重新核对实盘"));
        assertThat(rejected).containsEntry("status","counting");
        var resubmitted=countCommand(maker,id,"submit",version(rejected));var approved=countCommand(reviewer,id,"approve",version(resubmitted));
        countCommand(maker,id,"post",version(approved));assertThat(count("inventory_movement")).isZero();assertThat(count("journal")).isZero();
        assertCode(()->counts.create(maker,key(),new StockCountModels.Document(warehouse,DAY,"freeze",null,List.of(new StockCountModels.Item(sku)),null)),"VALIDATION_FAILED");assertZero();
    }

    @Test void draftEditAndCountRowsValidateWholeDocumentWithoutPartialUpdates() {
        var draft=counts.create(maker,key(),document(sku));long id=id(draft);
        var edited=counts.update(maker,id,new StockCountModels.Document(warehouse,DAY,"watermark","选择另一商品",List.of(new StockCountModels.Item(zeroSku)),version(draft)));
        var started=countCommand(maker,id,"start",version(edited));long line=id(lines(started).getFirst());
        assertThat(lines(started).getFirst()).containsEntry("skuId",Long.toString(zeroSku));
        assertCode(()->counts.update(maker,id,new StockCountModels.Document(warehouse,DAY,"watermark",null,List.of(new StockCountModels.Item(sku)),version(draft))),"VERSION_CONFLICT");
        assertCode(()->counts.counts(maker,id,new StockCountModels.Counts(version(started),List.of(new StockCountModels.Count(line,"1",null),new StockCountModels.Count(line,"2",null)))),"VALIDATION_FAILED");
        assertCode(()->counts.counts(maker,id,new StockCountModels.Counts(version(started),List.of(new StockCountModels.Count(line+1,"1",null)))),"VALIDATION_FAILED");
        assertCode(()->record(id,version(started),"0.0000001",null),"VALIDATION_FAILED");
        assertThat(counts.get(maker,id).get("version")).isEqualTo(started.get("version"));
        assertThat(lines(counts.get(maker,id)).getFirst()).containsEntry("countedQty",null);
        countCommand(maker,id,"void",version(started));assertZero();
    }

    @Test void twentyConcurrentReplaysProduceExactlyOneCountGain() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            for(int round=0;round<20;round++) {
                var draft=counts.create(maker,key(),document(sku));long id=id(draft);
                var started=countCommand(maker,id,"start",version(draft));
                var recorded=record(id,version(started),Integer.toString(round+1),"1");var approved=approve(id,recorded);
                String key=key();var input=new StockCountModels.Version(version(approved),null);var start=new CountDownLatch(1);
                Callable<Map<String,Object>> work=()->{start.await();return counts.command(maker,id,"post",key,input);};
                var first=pool.submit(work);var second=pool.submit(work);start.countDown();
                assertThat(first.get(10,TimeUnit.SECONDS).get("id")).isEqualTo(second.get(10,TimeUnit.SECONDS).get("id"));
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE tenant_id=? AND source_type='stock_count' AND source_id=?",Long.class,tenant,id)).isEqualTo(1);
            }
        }
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).onHand()).isEqualByComparingTo("20");
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).cost()).isEqualByComparingTo("20");assertZero();
    }

    @Test void snapshotUsesCurrentLockedBalanceWhenAnEarlierTransactionCommitsWhileWaiting() throws Exception {
        adjust("opening","10","100");var gain=approvedAdjustment("gain","5","50");long gainId=id(gain);
        var draft=counts.create(maker,key(),document(sku));long id=id(draft);var changed=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var writer=pool.submit(()->new TransactionTemplate(transactions).execute(status->{
                command(maker,true,gainId,"post",version(gain));changed.countDown();
                try {if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("等待提交协调超时");}
                catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException("测试提交被中断",error);}return null;
            }));
            assertThat(changed.await(10,TimeUnit.SECONDS)).isTrue();
            var reader=pool.submit(()->countCommand(maker,id,"start",version(draft)));
            try {assertThatThrownBy(()->reader.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}
            finally {release.countDown();}
            writer.get(10,TimeUnit.SECONDS);var started=reader.get(10,TimeUnit.SECONDS);
            assertThat(lines(started).getFirst()).containsEntry("bookQty","15.000000");
            countCommand(maker,id,"void",version(started));
        }
        assertZero();
    }

    @Test void scopeCostRevocationAndClosedPeriodProtectSnapshotsAndPosting() {
        String key=key();var draft=counts.create(maker,key,document(sku));long id=id(draft);
        var started=countCommand(maker,id,"start",version(draft));var recorded=record(id,version(started),"1","5");
        var hiddenPermissions=new HashSet<>(maker.permissions());hiddenPermissions.remove("cost:view");
        var hidden=new CompanyAccess(tenant,user,company,true,Set.of(),hiddenPermissions);
        assertThat(lines(counts.get(hidden,id)).getFirst()).doesNotContainKeys("inputUnitCost","unitCost","costAmount");
        var limited=new CompanyAccess(tenant,user,company,false,Set.of(otherWarehouse),maker.permissions());
        assertCode(()->counts.get(limited,id),"NOT_FOUND");assertCode(()->results.get(limited,"stock_count.create",key),"NOT_FOUND");
        assertThat(counts.list(limited,null,null,20).get("items")).asList().isEmpty();
        assertCode(()->counts.get(new CompanyAccess(tenant,user,otherCompany,true,Set.of(),maker.permissions()),id),"NOT_FOUND");
        var approved=approve(id,recorded);jdbc.update("UPDATE accounting_period SET status='closed',closed_at=UTC_TIMESTAMP(3) WHERE id=?",period);
        assertCode(()->countCommand(maker,id,"post",version(approved)),"PERIOD_CLOSED");
        assertThat(count("inventory_movement")).isZero();assertThat(count("journal")).isZero();
        assertThat(counts.get(maker,id)).containsEntry("status","approved");
        jdbc.update("UPDATE accounting_period SET status='open',closed_at=NULL WHERE id=?",period);countCommand(maker,id,"post",version(approved));assertZero();
    }

    @Test void closedPeriodCannotStartCountingOrCreateSnapshotRows() {
        var draft=counts.create(maker,key(),document(sku));long id=id(draft);
        jdbc.update("UPDATE accounting_period SET status='closed',closed_at=UTC_TIMESTAMP(3) WHERE id=?",period);
        assertCode(()->countCommand(maker,id,"start",version(draft)),"PERIOD_CLOSED");
        assertThat(counts.get(maker,id)).containsEntry("status","draft").containsEntry("snapshotAt",null);
        assertThat(count("inventory_balance")).isZero();assertThat(count("inventory_cost")).isZero();assertThat(count("inventory_movement")).isZero();
        jdbc.update("UPDATE accounting_period SET status='open',closed_at=NULL WHERE id=?",period);assertZero();
    }

    private StockCountModels.Document document(long sku){return new StockCountModels.Document(warehouse,DAY,"watermark","盘点验收",List.of(new StockCountModels.Item(sku)),null);}
    private Map<String,Object> countCommand(CompanyAccess scope,long id,String action,long version){return counts.command(scope,id,action,key(),new StockCountModels.Version(version,null));}
    private Map<String,Object> record(long id,long version,String qty,String unitCost){return counts.counts(maker,id,new StockCountModels.Counts(version,List.of(new StockCountModels.Count(id(lines(counts.get(maker,id)).getFirst()),qty,unitCost))));}
    private Map<String,Object> approve(long id,Map<String,Object> recorded){var submitted=countCommand(maker,id,"submit",version(recorded));return countCommand(reviewer,id,"approve",version(submitted));}
    private Map<String,Object> approvedAdjustment(String type,String qty,String total){
        var draft=service.createStock(maker,key(),new OpeningService.Stock(type,warehouse,DAY,"库存业务验收",List.of(new OpeningService.StockLine(sku,unit,qty,total)),null));long id=id(draft);
        var pending=command(maker,true,id,"submit",version(draft));return command(reviewer,true,id,"approve",version(pending));
    }
    private void adjust(String type,String qty,String total){var approved=approvedAdjustment(type,qty,total);command(maker,true,id(approved),"post",version(approved));}
}
