package cn.erp.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryModels.Key;
import cn.erp.inventory.InventoryModels.Posting;
import cn.erp.inventory.InventoryModels.Source;
import cn.erp.inventory.InventoryModels.StockSelection;
import cn.erp.shared.ApiException;

/** 上架移入同仓存储库位，调度方传入当次确认的持久化编号。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class LocationMoveService {
    private static final String SOURCE = "wms_task_confirmation";
    private static final String MOVE_OUT = "location_move_out";
    private static final String MOVE_IN = "location_move_in";
    private final InventoryStore store;
    private final InventoryDimensions dimensions;
    private final JdbcTemplate jdbc;

    LocationMoveService(InventoryStore store, InventoryDimensions dimensions, JdbcTemplate jdbc) {
        this.store = store;
        this.dimensions = dimensions;
        this.jdbc = jdbc;
    }

    public void move(CompanyAccess actor, long warehouseId, long skuId, Long batchId, long fromLocationId,
                     long toLocationId, BigDecimal quantity, List<String> serialNos, long sourceId, long sourceLineId,
                     LocalDate day, long period) {
        move(actor,new Key(warehouseId,skuId),batchId,fromLocationId,toLocationId,quantity,serialNos,
                new Source(SOURCE,sourceId,sourceLineId),day,period,true);
    }

    public void manualMove(CompanyAccess actor,long warehouseId,long skuId,Long batchId,long fromLocationId,
                           long toLocationId,BigDecimal quantity,List<String> serialNos,long sourceId,long sourceLineId,
                           LocalDate day,long period,boolean replenish) {
        move(actor,new Key(warehouseId,skuId),batchId,fromLocationId,toLocationId,quantity,serialNos,
                new Source("wms_move_line",sourceId,sourceLineId),day,period,replenish);
    }

    private void move(CompanyAccess actor,Key key,Long batchId,long fromLocationId,long toLocationId,BigDecimal quantity,
                      List<String> serialNos,Source source,LocalDate day,long period,boolean storageOnly) {
        if (fromLocationId <= 0 || toLocationId <= 0 || fromLocationId == toLocationId || source.id() <= 0 || source.lineId() <= 0) {
            throw new ApiException(400, "VALIDATION_FAILED", "请选择不同的移出、移入库位并提供有效的移库来源");
        }
        BigDecimal moved = InventoryNumbers.quantity(quantity);
        var outgoing = new Posting(key, source, day, period, MOVE_OUT);
        store.lockPeriod(actor, outgoing);
        dimensions.lockSettings(actor, List.of(key));
        var config = dimensions.config(actor, key);
        if (!config.location()) throw new ApiException(422, "WMS_LOCATION_DISABLED", "仓库未启用库位管理");
        if (config.batch() != (batchId != null)) {
            throw new ApiException(400, "VALIDATION_FAILED", config.batch() ? "移库须选择批次" : "商品未启用批次管理");
        }
        var serialCodes = dimensions.serials(config, new StockSelection(batchId, null, null, null, null, serialNos), moved, true);
        var from = new InventoryDimensions.Coordinates(batchId, batchId == null ? "" : batchId.toString(), Long.toString(fromLocationId));
        var to = new InventoryDimensions.Coordinates(batchId, from.lot(), Long.toString(toLocationId));

        // 汇总余额是批次库存、发货占用和序列号操作的共同锁，库位按固定顺序锁定。
        store.ensureAndLockBalance(actor, key);
        requireAvailableBatch(actor, key, batchId, InventoryDimensions.today());
        long firstLocation = Math.min(fromLocationId, toLocationId);
        long secondLocation = Math.max(fromLocationId, toLocationId);
        lockLocation(actor,key,firstLocation,firstLocation==toLocationId,storageOnly);
        lockLocation(actor,key,secondLocation,secondLocation==toLocationId,storageOnly);
        if (fromLocationId < toLocationId) {
            dimensions.physicalId(actor, key, from, false);
            dimensions.physicalId(actor, key, to, true);
        } else {
            dimensions.physicalId(actor, key, to, true);
            dimensions.physicalId(actor, key, from, false);
        }
        dimensions.requireUnpicked(actor, key, from, moved);
        var serials = dimensions.lockMoveSerials(actor, key, from, serialCodes);
        dimensions.physicalChange(actor, key, from, moved.negate(), BigDecimal.ZERO, false);
        dimensions.physicalChange(actor, key, to, moved, BigDecimal.ZERO, false);
        long out = store.appendMovement(actor, outgoing, from, moved.negate(),
                BigDecimal.ZERO, null, BigDecimal.ZERO);
        long in = store.appendMovement(actor, new Posting(key, source, day, period, MOVE_IN), to, moved,
                BigDecimal.ZERO, null, BigDecimal.ZERO);
        dimensions.moveSerials(actor, source, to, serials, out, in);
    }

    private void lockLocation(CompanyAccess actor,Key key,long locationId,boolean destination,boolean storageOnly) {
        var types = jdbc.queryForList("""
                SELECT z.type FROM wms_location l
                JOIN wms_zone z ON z.tenant_id=l.tenant_id AND z.company_id=l.company_id
                  AND z.warehouse_id=l.warehouse_id AND z.id=l.zone_id
                JOIN warehouse w ON w.tenant_id=l.tenant_id AND w.company_id=l.company_id AND w.id=l.warehouse_id
                WHERE l.tenant_id=? AND l.company_id=? AND l.warehouse_id=? AND l.id=?
                  AND l.status='active' AND z.status='active' AND w.status='active'
                FOR UPDATE OF l FOR SHARE OF z,w
                """, String.class, actor.tenantId(), actor.companyId(), key.warehouseId(), locationId);
        if (types.isEmpty()) throw InventoryStore.missing();
        if (destination && storageOnly && !"storage".equals(types.getFirst())) {
            throw new ApiException(422, "WMS_STORAGE_LOCATION_REQUIRED", "上架目标须为当前仓库已启用的存储库位");
        }
        if (destination && "quarantine".equals(types.getFirst())) {
            throw new ApiException(422,"WMS_LOCATION_UNAVAILABLE","请使用质检作业处理隔离库位库存");
        }
    }

    private void requireAvailableBatch(CompanyAccess actor, Key key, Long batchId, LocalDate day) {
        if (batchId == null) return;
        var states = jdbc.queryForList("""
                SELECT status='normal' AND (expiry_date IS NULL OR expiry_date>=?)
                FROM inv_batch WHERE tenant_id=? AND company_id=? AND sku_id=? AND id=? FOR SHARE
                """, Boolean.class, day, actor.tenantId(), actor.companyId(), key.skuId(), batchId);
        if (states.isEmpty()) throw InventoryStore.missing();
        if (!states.getFirst()) throw new ApiException(422, "WMS_BATCH_UNAVAILABLE", "该批次已冻结或过期，无法移库");
    }
}
