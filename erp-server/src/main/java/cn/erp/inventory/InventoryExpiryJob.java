package cn.erp.inventory;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.Ids;
import cn.erp.shared.ManagedJobs;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static cn.erp.inventory.InventoryModels.*;
import static cn.erp.inventory.InventoryStore.decimal;
import static cn.erp.inventory.InventoryStore.insufficient;

@Component
class InventoryExpiryJob {
    private static final Logger LOG=LoggerFactory.getLogger(InventoryExpiryJob.class);
    private final JdbcTemplate jdbc;
    private final InventoryService inventory;
    private final ReservationService reservations;
    private final TransactionTemplate tx;

    InventoryExpiryJob(JdbcTemplate jdbc,InventoryService inventory,ReservationService reservations,
            PlatformTransactionManager manager,ManagedJobs jobs){
        this.jdbc=jdbc;
        this.inventory=inventory;
        this.reservations=reservations;
        this.tx=new TransactionTemplate(manager);
        jobs.register("inventory.batch-expiry",this::run);
    }

    void run(long tenant){
        LocalDate today=InventoryDimensions.today();
        jdbc.update("UPDATE inv_batch SET status='expired' WHERE tenant_id=? AND expiry_date<? AND status<>'expired'",tenant,today);
        var expired=jdbc.queryForList("""
                SELECT x.id,x.company_id,x.warehouse_id,x.sku_id,x.lot_key,x.location_key,p.id period_id,b.id batch_id
                FROM inv_batch b JOIN inventory_balance x ON x.tenant_id=b.tenant_id AND x.company_id=b.company_id
                    AND x.sku_id=b.sku_id AND x.lot_key=CAST(b.id AS CHAR)
                JOIN accounting_period p ON p.tenant_id=x.tenant_id AND p.company_id=x.company_id
                    AND p.status='open' AND ? BETWEEN p.start_date AND p.end_date
                WHERE b.tenant_id=? AND b.status='expired' AND x.on_hand_qty>x.blocked_qty
                ORDER BY b.expiry_date,x.id LIMIT 200
                """,today,tenant);
        boolean failed=false;
        for(var row:expired){
            try{tx.executeWithoutResult(status->freeze(tenant,row,today));}
            catch(RuntimeException error){
                LOG.warn("批次 {} 库位余额 {} 暂未冻结，下轮重试，异常类型 {}",row.get("batch_id"),row.get("id"),error.getClass().getSimpleName());
                failed=true;
            }
        }
        if(failed)throw new IllegalStateException("部分过期批次冻结未完成");
    }

    private void freeze(long tenant,Map<String,Object> candidate,LocalDate day){
        long company=number(candidate,"company_id"),warehouse=number(candidate,"warehouse_id");
        long batch=number(candidate,"batch_id"),balance=number(candidate,"id"),period=number(candidate,"period_id");
        var actor=new CompanyAccess(tenant,0,company,false,Set.of(warehouse),Set.of());
        var key=new Key(warehouse,number(candidate,"sku_id"));
        jdbc.queryForList("SELECT id FROM accounting_period WHERE tenant_id=? AND company_id=? AND id=? FOR SHARE",Long.class,tenant,company,period);
        var orderLines=jdbc.queryForList("""
                SELECT DISTINCT order_line_id FROM inventory_reservation
                WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=? AND status='active' ORDER BY order_line_id
                """,Long.class,tenant,company,warehouse,key.skuId());
        for(long line:orderLines)jdbc.queryForList("SELECT id FROM sales_order_line WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",Long.class,tenant,company,line);
        inventory.lockKeys(actor,List.of(key));
        var at=jdbc.queryForMap("SELECT on_hand_qty,blocked_qty FROM inventory_balance WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",tenant,company,balance);
        BigDecimal qty=decimal(at,"on_hand_qty").subtract(decimal(at,"blocked_qty"));
        if(qty.signum()==0)return;
        var total=jdbc.queryForMap("""
                SELECT on_hand_qty,blocked_qty,reserved_qty FROM inventory_balance
                WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=? AND lot_key='' AND location_key='' FOR UPDATE
                """,tenant,company,warehouse,key.skuId());
        BigDecimal available=decimal(total,"on_hand_qty").subtract(decimal(total,"blocked_qty")).subtract(decimal(total,"reserved_qty"));
        var source=new Source("batch_expiry",batch,Ids.next());
        releaseUnavailableReservations(actor,key,source,qty.subtract(available),Set.copyOf(orderLines));
        for(long line:jdbc.queryForList("""
                SELECT shipment_line_id FROM inv_shipment_pick WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=?
                AND lot_key=? AND location_key=? ORDER BY shipment_line_id FOR UPDATE
                """,Long.class,tenant,company,warehouse,key.skuId(),candidate.get("lot_key"),candidate.get("location_key"))){
            inventory.releaseShipmentPick(actor,key,line);
        }
        String position=candidate.get("location_key").toString();
        var selection=new StockSelection(batch,null,null,null,position.isEmpty()?null:Long.valueOf(position),List.of());
        inventory.freeze(actor,new Posting(key,source,day,period,"block"),qty,true,selection,"expired");
    }

    private void releaseUnavailableReservations(CompanyAccess actor,Key key,Source trigger,BigDecimal remaining,Set<Long> locked){
        if(remaining.signum()<=0)return;
        var rows=jdbc.queryForList("""
                SELECT id,order_line_id,remaining_qty FROM inventory_reservation
                WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=? AND status='active'
                ORDER BY expire_at DESC,id DESC FOR UPDATE
                """,actor.tenantId(),actor.companyId(),key.warehouseId(),key.skuId());
        for(var row:rows){
            if(!locked.contains(number(row,"order_line_id")))continue;
            remaining=remaining.subtract(reservations.releaseForBatch(actor,number(row,"id"),remaining.min(decimal(row,"remaining_qty")),trigger));
            if(remaining.signum()==0)return;
        }
        throw insufficient();
    }

    private static long number(Map<String,Object> row,String name){return ((Number)row.get(name)).longValue();}
}
