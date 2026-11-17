package cn.erp.inventory;

import cn.erp.shared.Ids;
import cn.erp.shared.OrderCreationRaceData;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static cn.erp.inventory.InventoryModels.*;
import static org.assertj.core.api.Assertions.*;

/** 已有维度反复加锁不应再次插入聚簇索引尾部，首建仍依靠唯一键防重。 */
class InventoryLockConcurrencyIT extends OrderCreationRaceData {
    @Autowired InventoryStore store;
    @Autowired InventoryService inventory;
    @Autowired ReservationService reservations;
    @Autowired PlatformTransactionManager transactions;
    private final long[] periods=new long[2],skus=new long[2];
    private final Key[] keys=new Key[2];

    @BeforeEach void seedBalances() {
        skus[0]=sku;skus[1]=Ids.next();
        long product=jdbc.queryForObject("SELECT product_id FROM sku WHERE id=?",Long.class,sku);
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name,default_tax_rate) VALUES(?,?,?,'SKU2','另一并发规格',0)",skus[1],tenant,product);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,skus[1],unit);
        for(int index=0;index<2;index++) {
            periods[index]=Ids.next();keys[index]=new Key(warehouses[index],skus[index]);
            jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,'202611','2026-11-01','2026-11-30')",periods[index],tenant,companies[index]);
            inventory.receive(actors[index],posting(index,keys[index]),new BigDecimal("4"),new BigDecimal("40"));
        }
    }

    @AfterEach void cleanupBalances() {
        for(String table:List.of("inventory_reservation_change","inventory_reservation","sales_order_line","sales_order","cost_adjustment","inv_movement_hold","inventory_movement","inventory_cost","inventory_balance","accounting_period"))jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);
    }

    @Test void repeatedExistingDimensionLocksAcrossCompaniesCommitOnceWithoutTailGapDeadlock() throws Exception {
        var locked=new CyclicBarrier(2);
        race(index->{
            store.ensureAndLockBalance(actors[index],keys[index]);store.lockCost(actors[index],keys[index]);
            await(locked);
            for(int repeat=0;repeat<3;repeat++) {store.ensureAndLockBalance(actors[index],keys[index]);store.lockCost(actors[index],keys[index]);}
            inventory.receive(actors[index],posting(index,keys[index]),BigDecimal.ONE,new BigDecimal("10"));
        });
        for(int index=0;index<2;index++) {
            var balance=inventory.balance(actors[index],keys[index]);
            assertThat(balance.onHand()).isEqualByComparingTo("5");assertThat(balance.cost()).isEqualByComparingTo("50");
            assertThat(count("inventory_balance",keys[index])).isEqualTo(1);assertThat(count("inventory_cost",keys[index])).isEqualTo(1);
            assertThat(count("inventory_movement",keys[index])).isEqualTo(2);
        }
    }

    @Test void concurrentFirstCreationOfSameDimensionHasOneBalanceAndCostRowWithBothFacts() throws Exception {
        var fresh=new Key(warehouses[0],skus[1]);var ready=new CyclicBarrier(2);
        race(index->{await(ready);inventory.receive(actors[0],posting(0,fresh),BigDecimal.ONE,new BigDecimal("10"));});
        var balance=inventory.balance(actors[0],fresh);
        assertThat(balance.onHand()).isEqualByComparingTo("2");assertThat(balance.cost()).isEqualByComparingTo("20");
        assertThat(count("inventory_balance",fresh)).isEqualTo(1);assertThat(count("inventory_cost",fresh)).isEqualTo(1);
        assertThat(count("inventory_movement",fresh)).isEqualTo(2);
    }

    @Test void bulkLockingMixedExistingAndMissingDimensionsAcrossCompaniesKeepsBothTransactionsAtomic() throws Exception {
        var ready=new CyclicBarrier(2);
        var fresh=new Key[]{new Key(warehouses[0],skus[1]),new Key(warehouses[1],skus[0])};
        race(index->{
            await(ready);inventory.lockKeys(actors[index],List.of(keys[index],fresh[index]));
            inventory.receive(actors[index],posting(index,keys[index]),BigDecimal.ONE,new BigDecimal("10"));
            inventory.receive(actors[index],posting(index,fresh[index]),BigDecimal.ONE,new BigDecimal("10"));
        });
        for(int index=0;index<2;index++) {
            assertThat(inventory.balance(actors[index],keys[index]).onHand()).isEqualByComparingTo("5");
            var added=inventory.balance(actors[index],fresh[index]);
            assertThat(added.onHand()).isEqualByComparingTo("1");assertThat(added.cost()).isEqualByComparingTo("10");
            assertThat(count("inventory_balance",fresh[index])).isEqualTo(1);assertThat(count("inventory_cost",fresh[index])).isEqualTo(1);
            assertThat(count("inventory_movement",fresh[index])).isEqualTo(1);
        }
    }

    @Test void projectingHundredReservationLinesDoesNotWaitForAnotherCompanyReservationLocks() throws Exception {
        var lines=new ArrayList<List<Long>>();
        for(int company=0;company<2;company++) {
            long order=Ids.next();var ids=new ArrayList<Long>();var holds=new ArrayList<ReservationRequest>();
            jdbc.update("INSERT INTO sales_order(id,tenant_id,company_id,order_no,customer_id,business_date,price_tax_inclusive,status,goods_amount,total_amount,untaxed_amount,revision,approved_at) VALUES(?,?,?,?,?,?,1,'approved',1,1,1,1,UTC_TIMESTAMP(3))",order,tenant,companies[company],"PROJ"+order,partner,DAY);
            for(int number=1;number<=100;number++) {
                long line=Ids.next();ids.add(line);
                jdbc.update("INSERT INTO sales_order_line(id,tenant_id,company_id,order_id,line_no,sku_id,warehouse_id,unit_id,unit_rate,qty,base_qty,unit_price,price_tax_inclusive,amount,line_discount,line_net,order_discount_alloc,receivable_net,tax_rate,untaxed_amount,tax_amount) VALUES(?,?,?,?,?,?,?,?,1,0.01,0.01,1,1,0.01,0,0.01,0,0.01,0,0.01,0)",line,tenant,companies[company],order,number,skus[company],warehouses[company],unit);
                holds.add(new ReservationRequest(line,keys[company],new BigDecimal("0.01"),java.time.Instant.now().plusSeconds(3600)));
            }
            reservations.reserve(actors[company],holds);lines.add(ids);
        }
        var held=new CountDownLatch(1);var projected=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var owner=pool.submit(()->new TransactionTemplate(transactions).executeWithoutResult(status->{
                jdbc.queryForList("SELECT id FROM inventory_reservation FORCE INDEX(idx_inventory_reservation_line) WHERE tenant_id=? AND company_id=? FOR UPDATE",tenant,companies[0]);
                held.countDown();await(projected,"本公司投影不应等待另一家公司持有的预留锁");
            }));
            var reader=pool.submit(()->{
                await(held,"未取得另一家公司的预留锁");
                new TransactionTemplate(transactions).executeWithoutResult(status->{
                    for(long id:lines.get(1)) {store.lockOrderLine(actors[1],id);}
                    reservations.refreshLineStates(actors[1],lines.get(1));projected.countDown();
                });
            });
            owner.get(15,TimeUnit.SECONDS);reader.get(15,TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_order_line WHERE tenant_id=? AND company_id=? AND reservation_state='reserved'",Long.class,tenant,companies[1])).isEqualTo(100);
    }

    private static void await(CountDownLatch latch,String message) {
        try {if(!latch.await(5,TimeUnit.SECONDS)) {throw new IllegalStateException(message);}}
        catch(InterruptedException error) {Thread.currentThread().interrupt();throw new IllegalStateException("预留投影并发验收被中断",error);}
    }

    private void race(java.util.function.IntConsumer action) throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var jobs=new ArrayList<Future<?>>();
            for(int index=0;index<2;index++) {int actor=index;jobs.add(pool.submit(()->new TransactionTemplate(transactions).executeWithoutResult(status->action.accept(actor))));}
            for(var job:jobs)job.get(30,TimeUnit.SECONDS);
        }
    }
    private static void await(CyclicBarrier barrier) {
        try {barrier.await(10,TimeUnit.SECONDS);}
        catch(InterruptedException error) {Thread.currentThread().interrupt();throw new IllegalStateException("库存锁并发验收被中断",error);}
        catch(BrokenBarrierException|TimeoutException error) {throw new IllegalStateException("两条库存事务未在锁边界相遇",error);}
    }
    private Posting posting(int index,Key key) {long id=Ids.next();return new Posting(key,new Source("inventory_lock_test",id,id),DAY,periods[index],"opening_in");}
    private long count(String table,Key key) {return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=? AND warehouse_id=? AND sku_id=?",Long.class,tenant,key.warehouseId(),key.skuId());}
}
