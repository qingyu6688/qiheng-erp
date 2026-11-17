package cn.erp.wms;

import static cn.erp.inventory.InventoryModels.*;
import static org.assertj.core.api.Assertions.assertThat;

import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryService;
import cn.erp.inventory.SalesStockPosting;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import cn.erp.shared.OrderCreationRaceData;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class PickingConcurrencyIT extends OrderCreationRaceData {
    @Autowired InventoryService inventory;
    @Autowired ShipmentWork shipmentWork;
    @Autowired PickTasks picks;
    @Autowired PlatformTransactionManager transactions;

    @Test void concurrentPdaScansMoveTheLastSerialExactlyOnce() throws Exception {
        var actor = actors[0];
        var day = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        long period = createPeriod(actor,day);
        long storage = location(actor,"S01","storage");
        long shipping = location(actor,"D01","shipping");
        enableTracking(actor,storage,shipping);
        long shipment = prepareShipment(actor,day);
        long line = jdbc.queryForObject("SELECT id FROM shipment_line WHERE tenant_id=? AND shipment_id=?",Long.class,tenant,shipment);
        reserveOnlyUnit(actor,day,period,shipment,line,storage);
        shipmentWork.submitted(actor,shipment);
        long task = PickTasks.value(picks.head(actor,"t.source_id",shipment,false),"id");
        long pickLine = PickTasks.value(picks.lines(actor,task,false).getFirst(),"id");
        picks.claim(actor,task,key(),new PickTasks.Revision(0L,null));

        var scan = new PickTasks.Scan(1L,"L:S01","L:D01",null,"1",List.of("RACE-SN"));
        var ready = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> scan(actor,task,pickLine,scan,ready));
            var second = pool.submit(() -> scan(actor,task,pickLine,scan,ready));
            var results = List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS));
            assertThat(results).extracting(Result::connection).doesNotHaveDuplicates();
            assertThat(results).extracting(Result::outcome).containsExactlyInAnyOrder("SUCCESS","VERSION_CONFLICT");
        }

        verifySingleMove(actor,task,pickLine,line,storage,shipping);
    }

    private record Result(long connection,String outcome) {}

    private Result scan(CompanyAccess actor,long task,long line,PickTasks.Scan input,CyclicBarrier ready) {
        var transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        var connection = new long[1];
        try {
            return transaction.execute(status -> {
                connection[0] = jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class);
                await(ready);
                picks.confirm(actor,task,line,key(),input);
                return new Result(connection[0],"SUCCESS");
            });
        } catch (ApiException conflict) {
            return new Result(connection[0],conflict.getErrorCode());
        }
    }

    private void verifySingleMove(CompanyAccess actor,long task,long pickLine,long line,long storage,long shipping) {
        var head = picks.head(actor,"t.id",task,false);
        assertThat(PickTasks.value(head,"version")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT done_qty FROM wms_task_line WHERE id=?",BigDecimal.class,pickLine)).isEqualByComparingTo("1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wms_task_confirmation WHERE tenant_id=? AND task_id=?",Long.class,tenant,task)).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT on_hand_delta FROM inventory_movement WHERE tenant_id=? AND company_id=?
                    AND source_type='wms_task_confirmation' AND source_id=? ORDER BY on_hand_delta
                """,BigDecimal.class,tenant,actor.companyId(),task))
                .usingElementComparator(BigDecimal::compareTo).containsExactly(BigDecimal.ONE.negate(),BigDecimal.ONE);
        assertThat(physical(actor,storage)).isZero();
        assertThat(physical(actor,shipping)).isEqualByComparingTo("1");
        var aggregate = inventory.balance(actor,new Key(warehouses[0],sku));
        assertThat(aggregate.onHand()).isEqualByComparingTo("1");
        assertThat(aggregate.cost()).isEqualByComparingTo("10");
        assertThat(jdbc.queryForMap("""
                SELECT location_key,status FROM inv_serial WHERE tenant_id=? AND company_id=? AND serial_no='RACE-SN'
                """,tenant,actor.companyId())).containsEntry("location_key",id(shipping)).containsEntry("status","reserved");
        var reservation = jdbc.queryForMap("""
                SELECT location_key,qty,serial_ids FROM inv_shipment_pick
                WHERE tenant_id=? AND company_id=? AND shipment_line_id=?
                """,tenant,actor.companyId(),line);
        assertThat(reservation).containsEntry("location_key",id(shipping));
        assertThat((BigDecimal)reservation.get("qty")).isEqualByComparingTo("1");
        assertThat(inventory.decodeSelection(reservation.get("serial_ids")).serials()).containsExactly("RACE-SN");
    }

    private long prepareShipment(CompanyAccess actor,LocalDate day) {
        long order = Ids.next(), orderLine = Ids.next(), shipment = Ids.next();
        jdbc.update("""
                INSERT INTO sales_order(id,tenant_id,company_id,order_no,customer_id,business_date,status,revision,approved_at,
                    price_tax_inclusive,goods_amount,total_amount,untaxed_amount)
                VALUES(?,?,?,'SO-RACE',?,?,'approved',1,UTC_TIMESTAMP(3),1,10,10,10)
                """,order,tenant,actor.companyId(),partner,day);
        jdbc.update("""
                INSERT INTO sales_order_line(id,tenant_id,company_id,order_id,line_no,sku_id,warehouse_id,unit_id,unit_rate,
                    qty,base_qty,unit_price,amount,line_discount,line_net,order_discount_alloc,receivable_net,tax_rate,untaxed_amount,tax_amount)
                VALUES(?,?,?,?,1,?,?,?,1,1,1,10,10,0,10,0,10,0,10,0)
                """,orderLine,tenant,actor.companyId(),order,sku,warehouses[0],unit);
        jdbc.update("""
                INSERT INTO shipment(id,tenant_id,company_id,shipment_no,customer_id,warehouse_id,business_date,status,submitted_at)
                VALUES(?,?,?,'SH-RACE',?,?,?,'submitted',UTC_TIMESTAMP(3))
                """,shipment,tenant,actor.companyId(),partner,warehouses[0],day);
        jdbc.update("""
                INSERT INTO shipment_line(id,tenant_id,company_id,shipment_id,line_no,order_line_id,sku_id,unit_id,unit_rate,qty,base_qty)
                VALUES(?,?,?,?,1,?,?,?,1,1,1)
                """,Ids.next(),tenant,actor.companyId(),shipment,orderLine,sku,unit);
        return shipment;
    }

    private void reserveOnlyUnit(CompanyAccess actor,LocalDate day,long period,long shipment,long line,long storage) {
        long opening = Ids.next();
        var dimensions = new StockSelection(null,null,null,null,storage,List.of("RACE-SN"));
        var key = new Key(warehouses[0],sku);
        inventory.receive(actor,new Posting(key,new Source("opening_entry",opening,opening),day,period,"opening_in"),
                BigDecimal.ONE,new BigDecimal("10.00"),dimensions,null);
        inventory.reserveShipmentPicks(actor,List.of(new SalesStockPosting.Issue(
                new Posting(key,new Source("shipment_line",shipment,line),day,period,"sales_out"),BigDecimal.ONE,null,dimensions)));
    }

    private long createPeriod(CompanyAccess actor,LocalDate day) {
        long period = Ids.next();
        var month = YearMonth.from(day);
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,?,?,?)",
                period,tenant,actor.companyId(),month.format(DateTimeFormatter.ofPattern("uuuuMM")),month.atDay(1),month.atEndOfMonth());
        return period;
    }

    private long location(CompanyAccess actor,String code,String type) {
        long zone = Ids.next(), bin = Ids.next();
        jdbc.update("INSERT INTO wms_zone(id,tenant_id,company_id,warehouse_id,code,name,type) VALUES(?,?,?,?,?,?,?)",
                zone,tenant,actor.companyId(),warehouses[0],code,code,type);
        jdbc.update("INSERT INTO wms_location(id,tenant_id,company_id,warehouse_id,zone_id,code,name) VALUES(?,?,?,?,?,?,?)",
                bin,tenant,actor.companyId(),warehouses[0],zone,code,code);
        return bin;
    }

    private void enableTracking(CompanyAccess actor,long storage,long shipping) {
        jdbc.update("UPDATE sku SET serial_managed=1 WHERE tenant_id=? AND id=?",tenant,sku);
        jdbc.update("""
                UPDATE warehouse SET location_managed=1,wms_task_mode='required',
                    default_receiving_location_id=?,default_shipping_location_id=?
                WHERE tenant_id=? AND company_id=? AND id=?
                """,storage,shipping,tenant,actor.companyId(),warehouses[0]);
    }

    private BigDecimal physical(CompanyAccess actor,long location) {
        return jdbc.queryForObject("""
                SELECT on_hand_qty FROM inventory_balance WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=?
                    AND location_key=? AND lot_key=''
                """,BigDecimal.class,tenant,actor.companyId(),warehouses[0],sku,id(location));
    }

    @AfterEach void cleanupWarehouseRace() {
        for (String table : List.of("idempotency_record","audit_log","outbox_event","wms_task_confirmation","wms_task_line","wms_task",
                "inv_shipment_pick","shipment_line","shipment","inv_movement_hold","doc_line_serial","inv_movement_serial","inv_serial",
                "inventory_movement","inventory_cost","inventory_balance","accounting_period")) {
            jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);
        }
        jdbc.update("""
                UPDATE warehouse SET default_receiving_location_id=NULL,default_shipping_location_id=NULL,location_managed=0
                WHERE tenant_id=?
                """,tenant);
        jdbc.update("DELETE FROM wms_location WHERE tenant_id=?",tenant);
        jdbc.update("DELETE FROM wms_zone WHERE tenant_id=?",tenant);
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10,TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PDA 并发拣货验收被中断",interrupted);
        } catch (java.util.concurrent.BrokenBarrierException | java.util.concurrent.TimeoutException timeout) {
            throw new IllegalStateException("两个 PDA 未在确认前同时取得 MySQL 连接",timeout);
        }
    }

    private static String key() { return UUID.randomUUID().toString(); }
}
