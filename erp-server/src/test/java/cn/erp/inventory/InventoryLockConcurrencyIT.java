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
        for(String table:List.of("cost_adjustment","inventory_movement","inventory_cost","inventory_balance","accounting_period"))jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);
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
