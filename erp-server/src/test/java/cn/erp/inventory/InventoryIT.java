package cn.erp.inventory;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static cn.erp.inventory.InventoryModels.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
class InventoryIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired InventoryService inventory;
    @Autowired ReservationService reservations;
    @Autowired OrderLineOccupancy occupancy;
    @Autowired InventoryTraceQueries trace;
    @Autowired InventoryExpiryJob expiry;
    @Autowired PlatformTransactionManager manager;
    private CompanyAccess scope;
    private Key key;
    private long tenant,company,warehouse,sku,unit,category,product,partner,period,order;
    private int lineNo;

    @BeforeEach
    void seed() {
        tenant=Ids.next();company=Ids.next();warehouse=Ids.next();sku=Ids.next();unit=Ids.next();
        category=Ids.next();product=Ids.next();partner=Ids.next();period=Ids.next();order=Ids.next();lineNo=0;
        scope=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),Set.of());
        key=new Key(warehouse,sku);
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,?)",tenant,"INV"+tenant,"库存底座测试");
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,?,?)",company,tenant,"A","公司A");
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,?,?)",warehouse,tenant,company,"W","测试仓");
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,?,?)",partner,tenant,"P","测试客户");
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件')",unit,tenant);
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'CAT','测试分类','/')",category,tenant);
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'P','测试商品',?)",product,tenant,category,unit);
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name) VALUES(?,?,?,'S','规格')",sku,tenant,product);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,sku,unit);
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,'202611','2026-11-01','2026-11-30')",period,tenant,company);
        jdbc.update("""
                INSERT INTO sales_order(id,tenant_id,company_id,order_no,customer_id,business_date,price_tax_inclusive,status,revision,approved_at)
                VALUES(?,?,?,'SO-INV',?,'2026-11-15',1,'approved',1,UTC_TIMESTAMP(3))
                """,order,tenant,company,partner);
    }

    @AfterEach
    void cleanup() {
        for(String table:List.of("inventory_reservation_change","inventory_reservation","cost_adjustment","inv_movement_hold",
                "doc_line_serial","inv_movement_serial","inv_serial","inventory_movement","inv_batch",
                "inventory_cost","inventory_balance","sales_order_line","sales_order","unit_conversion","sku","product",
                "product_category","unit","accounting_period","business_partner","warehouse","company")) {
            jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);
        }
        jdbc.update("DELETE FROM tenant WHERE id=?",tenant);
    }

    @Test
    void R6MovingAverageAndPurchaseReturnResidualMatchExamples() {
        inventory.receive(scope,posting("purchase_in"),d("100"),d("1000.00"));
        inventory.receive(scope,posting("purchase_in"),d("50"),d("650.00"));
        var issue=inventory.issue(scope,posting("sales_out"),d("30"),null);
        assertThat(issue.unitCost()).isEqualByComparingTo("11");
        assertThat(issue.movementCost()).isEqualByComparingTo("330");
        inventory.receive(scope,posting("purchase_in"),d("30"),d("435.00"));
        inventory.receive(scope,posting("sales_return_in"),d("5"),d("55.00"));
        assertThat(inventory.issue(scope,posting("sales_out"),d("155"),null).remainingCost()).isEqualByComparingTo("0");

        inventory.receive(scope,posting("purchase_in"),d("10"),d("100.00"));
        inventory.receive(scope,posting("purchase_in"),d("10"),d("140.00"));
        inventory.issue(scope,posting("sales_out"),d("15"),null);
        var returned=inventory.purchaseReturn(scope,posting("purchase_return_out"),d("5"),d("14"));
        assertThat(returned.movementCost()).isEqualByComparingTo("70");
        assertThat(returned.adjustment()).isEqualByComparingTo("10");
        assertThat(returned.remainingCost()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM cost_adjustment WHERE tenant_id=?",BigDecimal.class,tenant)).isEqualByComparingTo("10");
        assertThat(inventory.balance(scope,key).cost()).isEqualByComparingTo("0");
        assertProjectionMatchesHistory();
    }

    @Test
    void purchaseReturnWithRemainingStockUsesCurrentAverageWhenOriginalWouldMakeCostNegative() {
        inventory.receive(scope,posting("purchase_in"),d("10"),d("10.00"));
        var result=inventory.purchaseReturn(scope,posting("purchase_return_out"),d("5"),d("10"));
        assertThat(result.adjustment()).isEqualByComparingTo("45");
        assertThat(result.remainingQuantity()).isEqualByComparingTo("5");
        assertThat(result.remainingCost()).isEqualByComparingTo("5");
    }

    @Test
    void T01LastUnitReservationIsAtomicTwentyTimes() throws Exception {
        for(int round=0;round<20;round++) {
            inventory.receive(scope,posting("opening_in"),d("1"),d("1.00"));
            long first=line(d("1")),second=line(d("1"));
            List<Boolean> results=race(()->reserve(first),()->reserve(second));
            assertThat(results).containsExactlyInAnyOrder(true,false);
            assertThat(inventory.balance(scope,key).available()).isEqualByComparingTo("0");
            long reservation=jdbc.queryForObject("SELECT id FROM inventory_reservation WHERE tenant_id=? AND status='active'",Long.class,tenant);
            inventory.issue(scope,posting("sales_out"),d("1"),reservation);
            assertThat(inventory.balance(scope,key).onHand()).isEqualByComparingTo("0");
            assertThat(inventory.balance(scope,key).reserved()).isEqualByComparingTo("0");
        }
    }

    @Test
    void T02TwoShipmentsCannotOccupyTheSameRemainderTwentyTimes() throws Exception {
        for(int round=0;round<20;round++) {
            long line=line(d("1"));
            assertThat(race(()->{occupancy.occupy(scope,line,d("1"));return true;},
                    ()->{occupancy.occupy(scope,line,d("1"));return true;})).containsExactlyInAnyOrder(true,false);
            occupancy.complete(scope,line,d("1"));
            assertThat(jdbc.queryForObject("SELECT shipped_base_qty FROM sales_order_line WHERE id=?",BigDecimal.class,line)).isEqualByComparingTo("1");
            assertThat(jdbc.queryForObject("SELECT pending_ship_base_qty FROM sales_order_line WHERE id=?",BigDecimal.class,line)).isEqualByComparingTo("0");
        }
    }

    @Test
    void T19ExpiryAndShipmentDoNotReleaseOrConsumeTheSameReservationTwiceTwentyTimes() throws Exception {
        for(int round=0;round<20;round++) {
            inventory.receive(scope,posting("opening_in"),d("1"),d("1.00"));
            long line=line(d("1"));
            long id=reservations.reserve(scope,List.of(new ReservationRequest(line,key,d("1"),Instant.now().plusSeconds(60)))).getFirst();
            jdbc.update("UPDATE inventory_reservation SET expire_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",id);
            List<Boolean> results=race(()->{reservations.expire(scope,id,Instant.now());return true;},
                    ()->{inventory.issue(scope,posting("sales_out"),d("1"),id);return true;});
            assertThat(results).containsExactly(true,true);
            assertThat(inventory.balance(scope,key).onHand()).isEqualByComparingTo("0");
            assertThat(inventory.balance(scope,key).reserved()).isEqualByComparingTo("0");
            assertThat(jdbc.queryForObject("SELECT SUM(qty_delta) FROM inventory_reservation_change WHERE reservation_id=?",BigDecimal.class,id)).isEqualByComparingTo("0");
        }
    }

    @Test
    void O06OnlyActualExpiryProjectsExpiredAndConsumptionProjectsReleased() {
        inventory.receive(scope,posting("opening_in"),d("2"),d("2.00"));long first=line(d("1"));
        long reservation=reservations.reserve(scope,List.of(new ReservationRequest(first,key,d("1"),Instant.now().plusSeconds(60)))).getFirst();
        jdbc.update("UPDATE inventory_reservation SET expire_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",reservation);
        reservations.expire(scope,reservation,Instant.now());
        assertThat(jdbc.queryForObject("SELECT reservation_state FROM sales_order_line WHERE id=?",String.class,first)).isEqualTo("expired");
        long second=line(d("1"));long consumed=reservations.reserve(scope,List.of(new ReservationRequest(second,key,d("1"),Instant.now().plusSeconds(60)))).getFirst();
        inventory.issue(scope,posting("sales_out"),d("1"),consumed);
        assertThat(jdbc.queryForObject("SELECT reservation_state FROM sales_order_line WHERE id=?",String.class,second)).isEqualTo("released");
    }

    @Test
    void O06RefreshAfterConcurrentReleaseOrConsumptionUsesCurrentRowsInsteadOfOldSnapshot() throws Exception {
        for(boolean consume:List.of(false,true)) {
            inventory.receive(scope,posting("opening_in"),d("1"),d("1.00"));
            long source=line(d("1"));
            long reservation=reservations.reserve(scope,List.of(new ReservationRequest(source,key,d("1"),Instant.now().plusSeconds(60)))).getFirst();
            var snapshotReady=new CountDownLatch(1);var changeCommitted=new CountDownLatch(1);
            try(var pool=Executors.newSingleThreadExecutor()) {
                var refreshed=pool.submit(()->{
                    var transaction=new TransactionTemplate(manager);
                    transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
                    return transaction.execute(status->{
                        assertThat(jdbc.queryForObject("SELECT status FROM inventory_reservation WHERE id=?",String.class,reservation)).isEqualTo("active");
                        snapshotReady.countDown();
                        try {assertThat(changeCommitted.await(10,TimeUnit.SECONDS)).isTrue();}
                        catch(InterruptedException error) {Thread.currentThread().interrupt();throw new IllegalStateException("预留投影验收线程被中断",error);}
                        // 再次普通读仍看到旧 active，证明用例确实处于旧的 RR 快照。
                        assertThat(jdbc.queryForObject("SELECT status FROM inventory_reservation WHERE id=?",String.class,reservation)).isEqualTo("active");
                        jdbc.queryForObject("SELECT id FROM sales_order_line WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",Long.class,tenant,company,source);
                        reservations.refreshLineState(scope,source);
                        return jdbc.queryForObject("SELECT reservation_state FROM sales_order_line WHERE id=?",String.class,source);
                    });
                });
                try {
                    assertThat(snapshotReady.await(10,TimeUnit.SECONDS)).isTrue();
                    if(consume)inventory.issue(scope,posting("sales_out"),d("1"),reservation);
                    else reservations.release(scope,reservation,d("1"),"release_close",new Source("inventory_test",Ids.next(),source));
                } finally {changeCommitted.countDown();}
                assertThat(refreshed.get(20,TimeUnit.SECONDS)).isEqualTo("released");
            }
            assertThat(inventory.balance(scope,key).reserved()).isEqualByComparingTo("0");
            assertThat(jdbc.queryForObject("SELECT remaining_qty FROM inventory_reservation WHERE id=?",BigDecimal.class,reservation)).isZero();
        }
    }

    @Test
    void T04FailureRollsBackBalanceCostMovementAndOrderLine() {
        inventory.receive(scope,posting("opening_in"),d("5"),d("10.00"));
        long line=line(d("2"));
        int movements=jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE tenant_id=?",Integer.class,tenant);
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(status->{
            occupancy.occupy(scope,line,d("2"));
            occupancy.complete(scope,line,d("2"));
            inventory.issue(scope,posting("sales_out"),d("2"),null);
            throw new IllegalStateException("模拟库存更新后故障");
        })).hasMessageContaining("故障");
        assertThat(inventory.balance(scope,key).onHand()).isEqualByComparingTo("5");
        assertThat(inventory.balance(scope,key).cost()).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(movements);
        assertThat(jdbc.queryForObject("SELECT shipped_base_qty FROM sales_order_line WHERE id=?",BigDecimal.class,line)).isEqualByComparingTo("0");
    }

    @Test
    void T20RejectsNegativeOverPrecisionOversizedAndCrossCompanyInputs() {
        assertThatThrownBy(()->inventory.receive(scope,posting("opening_in"),d("-1"),d("1.00"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->inventory.receive(scope,posting("opening_in"),d("0.0000001"),d("1.00"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->inventory.receive(scope,posting("opening_in"),d("1E30"),d("1.00"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->inventory.receive(scope,posting("opening_in"),d("1"),d("1.001"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->inventory.receive(scope,posting("opening_in"),d("0.000001"),d("9999999999999999999999.00"))).isInstanceOf(ApiException.class);
        var foreign=new CompanyAccess(tenant,scope.userId(),Ids.next(),true,Set.of(),Set.of());
        assertThatThrownBy(()->inventory.balance(foreign,key)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(404));
        inventory.receive(scope,posting("opening_in"),d("2"),d("2.00"));
        inventory.freeze(scope,posting("block"),d("1"),true);
        assertThatThrownBy(()->inventory.issue(scope,posting("sales_out"),d("2"),null)).isInstanceOf(ApiException.class);
        inventory.freeze(scope,posting("unblock"),d("1"),false);
        assertThat(inventory.balance(scope,key).available()).isEqualByComparingTo("2");
        assertProjectionMatchesHistory();
    }

    @Test
    void reservationBatchRollsBackAllRowsWhenLaterLineHasInsufficientStock() {
        inventory.receive(scope,posting("opening_in"),d("1"),d("1.00"));
        long first=line(d("1")),second=line(d("1"));
        assertThatThrownBy(()->reservations.reserve(scope,List.of(
                new ReservationRequest(first,key,d("1"),Instant.now().plusSeconds(60)),
                new ReservationRequest(second,key,d("1"),Instant.now().plusSeconds(60)))))
                .isInstanceOf(ApiException.class);
        assertThat(inventory.balance(scope,key).reserved()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation WHERE tenant_id=?",Integer.class,tenant)).isZero();
    }

    @Test
    void expiryWorkerCanRepeatWithoutClosingTheApprovedOrder() {
        inventory.receive(scope,posting("opening_in"),d("1"),d("1.00"));
        long line=line(d("1"));
        long reservation=reservations.reserve(scope,List.of(new ReservationRequest(line,key,d("1"),Instant.now().plusSeconds(60)))).getFirst();
        jdbc.update("UPDATE inventory_reservation SET expire_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",reservation);
        var worker=new ReservationExpiryWorker(jdbc,reservations);
        worker.poll();worker.poll();
        assertThat(inventory.balance(scope,key).available()).isEqualByComparingTo("1");
        assertThat(jdbc.queryForObject("SELECT status FROM sales_order WHERE id=?",String.class,order)).isEqualTo("approved");
        assertThat(jdbc.queryForObject("SELECT reservation_state FROM sales_order_line WHERE id=?",String.class,line)).isEqualTo("expired");
    }

    @Test
    void duplicateSourceCannotPostAgainAndClosedPeriodRejectsPosting() {
        Posting posting=posting("opening_in");
        inventory.receive(scope,posting,d("2"),d("2.00"));
        assertThatThrownBy(()->inventory.receive(scope,posting,d("2"),d("2.00")))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getErrorCode()).isEqualTo("STATE_CONFLICT"));
        jdbc.update("UPDATE accounting_period SET status='closed',closed_at=UTC_TIMESTAMP(3) WHERE id=?",period);
        assertThatThrownBy(()->inventory.issue(scope,posting("sales_out"),d("1"),null))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getErrorCode()).isEqualTo("PERIOD_CLOSED"));
    }

    @Test
    void fifoAndFefoSelectDifferentBatchesWithoutChangingWarehouseAverageCost() {
        tracking("fifo",false);
        long first=receiveBatch("先入",InventoryDimensions.today().plusDays(20),List.of());
        long near=receiveBatch("近效",InventoryDimensions.today().plusDays(5),List.of());
        var out=posting("sales_out");
        assertThat(inventory.issue(scope,out,d("1"),null).unitCost()).isEqualByComparingTo("10");
        assertThat(inventory.postedSelection(scope,out.source(),"sales_out").batchId()).isEqualTo(first);
        jdbc.update("UPDATE sku SET issue_strategy='fefo' WHERE id=?",sku);
        long late=receiveBatch("远效",InventoryDimensions.today().plusDays(90),List.of());
        out=posting("sales_out");
        inventory.issue(scope,out,d("1"),null);
        assertThat(inventory.postedSelection(scope,out.source(),"sales_out").batchId()).isEqualTo(near).isNotEqualTo(late);
        assertProjectionMatchesHistory();
    }

    @Test
    void concurrentIssueAndSerialTraceProtectLastPhysicalUnit() throws Exception {
        tracking("fefo",true);
        long batch=receiveBatch("序列批次",InventoryDimensions.today().plusDays(10),List.of("SN-001"));
        assertThatThrownBy(()->receiveBatch("重复批次",InventoryDimensions.today().plusDays(10),List.of("SN-001")))
                .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getErrorCode()).isEqualTo("SERIAL_NOT_AVAILABLE"));
        var first=posting("sales_out");var second=posting("sales_out");
        assertThat(race(()->issuePhysical(first),()->issuePhysical(second))).containsExactlyInAnyOrder(true,false);
        var lifecycle=trace.serial(scope,sku,"SN-001",null,10);
        assertThat(lifecycle.get("serial")).isInstanceOfSatisfying(Map.class,row->assertThat(row.get("status")).isEqualTo("shipped"));
        assertThat(trace.batch(scope,batch,null,10).get("items")).isInstanceOfSatisfying(List.class,rows->assertThat(rows).hasSize(2));
        var otherCompany=new CompanyAccess(tenant,scope.userId(),Ids.next(),true,Set.of(),scope.permissions());
        var otherWarehouse=new CompanyAccess(tenant,scope.userId(),company,false,Set.of(Ids.next()),scope.permissions());
        var otherDepartment=new CompanyAccess(tenant,scope.userId(),company,true,Set.of(),scope.permissions(),
                cn.erp.shared.OrgScope.restricted(Set.of(Ids.next()),Set.of(),0));
        for(var denied:List.of(otherCompany,otherWarehouse,otherDepartment)){
            assertThatThrownBy(()->trace.batch(denied,batch,null,10)).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getStatus()).isEqualTo(404));
            assertThatThrownBy(()->trace.serial(denied,sku,"SN-001",null,10)).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getStatus()).isEqualTo(404));
        }
    }

    @Test
    void elapsedBatchRejectsPickingAndFreezesOnceAfterReleasingIncompatibleReservation() {
        tracking("fefo",false);
        LocalDate today=InventoryDimensions.today();
        jdbc.update("UPDATE accounting_period SET period=?,start_date=?,end_date=? WHERE id=?",today.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM")),today.withDayOfMonth(1),today.withDayOfMonth(today.lengthOfMonth()),period);
        long reference=Ids.next();
        var receipt=new Posting(key,new Source("inventory_test",reference,reference),today,period,"purchase_in");
        inventory.receive(scope,receipt,d("1"),d("10.00"),new StockSelection(null,"过期批次",null,today.plusDays(1),null,List.of()),null);
        long batch=inventory.postedSelection(scope,receipt.source(),"purchase_in").batchId();
        long orderLine=line(d("1"));
        reservations.reserve(scope,List.of(new ReservationRequest(orderLine,key,d("1"),Instant.now().plusSeconds(300))));
        jdbc.update("UPDATE inv_batch SET expiry_date=? WHERE id=?",today.minusDays(1),batch);
        long source=Ids.next();
        var shipment=new Posting(key,new Source("inventory_test",source,source),today,period,"sales_out");
        assertThatThrownBy(()->inventory.issue(scope,shipment,d("1"),null,new StockSelection(batch,null,null,null,null,List.of()),null))
                .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getErrorCode()).isEqualTo("PICK_UNAVAILABLE"));
        expiry.run(tenant);expiry.run(tenant);
        assertThat(inventory.balance(scope,key).blocked()).isEqualByComparingTo("1");
        assertThat(inventory.balance(scope,key).reserved()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE tenant_id=? AND source_type='batch_expiry'",Integer.class,tenant)).isEqualTo(1);
        long disposal=Ids.next();
        inventory.issue(scope,new Posting(key,new Source("inventory_test",disposal,disposal),today,period,"scrap"),d("1"),null,
                new StockSelection(batch,null,null,null,null,List.of()),null);
        assertThat(inventory.balance(scope,key).onHand()).isZero();
        assertThat(inventory.balance(scope,key).blocked()).isZero();
        assertProjectionMatchesHistory();
    }

    private void tracking(String strategy,boolean serial){
        jdbc.update("UPDATE sku SET batch_managed=TRUE,serial_managed=?,issue_strategy=? WHERE id=?",serial,strategy,sku);
    }

    private long receiveBatch(String number,LocalDate expiryDate,List<String> serials){
        var receipt=posting("purchase_in");
        inventory.receive(scope,receipt,d("1"),d("10.00"),new StockSelection(null,number,null,expiryDate,null,serials),null);
        return inventory.postedSelection(scope,receipt.source(),"purchase_in").batchId();
    }

    private boolean issuePhysical(Posting posting){inventory.issue(scope,posting,d("1"),null);return true;}

    private boolean reserve(long line) {
        reservations.reserve(scope,List.of(new ReservationRequest(line,key,d("1"),Instant.now().plusSeconds(60))));return true;
    }

    private Posting posting(String movement) {
        long source=Ids.next();
        return new Posting(key,new Source("inventory_test",source,source),LocalDate.of(2026,11,15),period,movement);
    }

    private long line(BigDecimal quantity) {
        long id=Ids.next();
        jdbc.update("""
                INSERT INTO sales_order_line(id,tenant_id,company_id,order_id,line_no,sku_id,warehouse_id,unit_id,
                  unit_rate,qty,base_qty,unit_price,amount,line_net,receivable_net,tax_rate,untaxed_amount,tax_amount)
                VALUES(?,?,?,?,?,?,?,?,1,?,?,1,1,1,1,0,1,0)
                """,id,tenant,company,order,++lineNo,sku,warehouse,unit,quantity,quantity);
        return id;
    }

    private static BigDecimal d(String value) {return new BigDecimal(value);}

    private void assertProjectionMatchesHistory() {
        var balance=inventory.balance(scope,key);
        BigDecimal movementQty=jdbc.queryForObject("SELECT COALESCE(SUM(on_hand_delta),0) FROM inventory_movement WHERE tenant_id=?",BigDecimal.class,tenant);
        BigDecimal blocked=jdbc.queryForObject("SELECT COALESCE(SUM(blocked_delta),0) FROM inventory_movement WHERE tenant_id=?",BigDecimal.class,tenant);
        BigDecimal movementCost=jdbc.queryForObject("SELECT COALESCE(SUM(cost_delta),0) FROM inventory_movement WHERE tenant_id=?",BigDecimal.class,tenant);
        BigDecimal adjustments=jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM cost_adjustment WHERE tenant_id=?",BigDecimal.class,tenant);
        assertThat(balance.onHand()).isEqualByComparingTo(movementQty);
        assertThat(balance.blocked()).isEqualByComparingTo(blocked);
        assertThat(balance.cost()).isEqualByComparingTo(movementCost.add(adjustments));
    }

    @SafeVarargs
    private static List<Boolean> race(Callable<Boolean>... jobs) throws Exception {
        CountDownLatch ready=new CountDownLatch(jobs.length),go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(jobs.length)) {
            List<Future<Boolean>> futures=new ArrayList<>();
            for(var job:jobs) {futures.add(pool.submit(()->{ready.countDown();go.await();try{return job.call();}catch(ApiException expected){return false;}}));}
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();go.countDown();
            List<Boolean> results=new ArrayList<>();
            for(var future:futures) {results.add(future.get(20,TimeUnit.SECONDS));}
            return results;
        }
    }
}
