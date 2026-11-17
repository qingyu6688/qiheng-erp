package cn.erp.inventory;

import static cn.erp.inventory.InventoryModels.Key;
import static cn.erp.inventory.InventoryModels.Posting;
import static cn.erp.inventory.InventoryModels.Source;
import static cn.erp.inventory.InventoryStore.decimal;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 在库存汇总锁内移动发货占用和对应实物、序列号。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ShipmentLocationMoveService {
    private final InventoryDimensions dimensions;
    private final InventoryStore store;
    private final JdbcTemplate jdbc;

    ShipmentLocationMoveService(InventoryDimensions dimensions,InventoryStore store,JdbcTemplate jdbc) {
        this.dimensions = dimensions;
        this.store = store;
        this.jdbc = jdbc;
    }

    public record Move(long warehouseId,long skuId,Long batchId,long fromId,long toId,BigDecimal quantity,
                       List<String> serialNos,long shipmentLineId,long taskId,long confirmationId,LocalDate day,long period) {}

    public void stage(CompanyAccess actor,Move input) {
        relocate(actor,input,false);
    }

    public boolean returnToSource(CompanyAccess actor,Move input) {
        return relocate(actor,input,true);
    }

    private boolean relocate(CompanyAccess actor,Move input,boolean returning) {
        var key = new Key(input.warehouseId(),input.skuId());
        var source = new Source("wms_task_confirmation",input.taskId(),input.confirmationId());
        var outgoing = new Posting(key,source,input.day(),input.period(),"location_move_out");
        store.lockPeriod(actor,outgoing);
        dimensions.lockSettings(actor,List.of(key));
        var config = dimensions.config(actor,key);
        if (!config.location()) throw conflict("当前仓库未启用库位管理");
        store.ensureAndLockBalance(actor,key);
        var allocations = jdbc.queryForList("""
                SELECT batch_id,location_key,lot_key,qty,serial_ids FROM inv_shipment_pick
                WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=? AND shipment_line_id=? FOR UPDATE
                """,actor.tenantId(),actor.companyId(),key.warehouseId(),key.skuId(),input.shipmentLineId());
        if (allocations.isEmpty() && returning && expired(actor,key,input.batchId())) {
            // 临期冻结任务已取消该占用，冻结后的库存保留在当前物理库位。
            return false;
        }
        if (allocations.isEmpty()) throw conflict("发货占用已失效，请处理发货单");
        var allocation = allocations.getFirst();
        var selection = dimensions.decode(allocation.get("serial_ids"));
        var allocated = selection == null || selection.serials()==null ? List.<String>of() : selection.serials().stream().sorted().toList();
        if (!Objects.equals(input.batchId(),InventoryDimensions.asLong(allocation.get("batch_id")))
                || !Long.toString(input.fromId()).equals(allocation.get("location_key"))
                || input.quantity().compareTo(decimal(allocation,"qty"))!=0
                || !input.serialNos().stream().sorted().toList().equals(allocated))
            throw conflict("拣货信息与当前发货占用不一致");
        if (!returning) checkBatch(actor,key,input.batchId());
        var from = coordinates(input.batchId(),input.fromId());
        var to = coordinates(input.batchId(),input.toId());
        moveStock(actor,input,returning,key,source,outgoing,from,to,allocated);
        var moved = dimensions.from(to,allocated);
        String json = dimensions.encode(moved);
        jdbc.update("""
                UPDATE inv_shipment_pick SET location_key=?,serial_ids=?
                WHERE tenant_id=? AND company_id=? AND shipment_line_id=?
                """,to.location(),json,actor.tenantId(),actor.companyId(),input.shipmentLineId());
        jdbc.update("""
                UPDATE shipment_line SET location_key=?,stock_selection=?
                WHERE tenant_id=? AND company_id=? AND id=?
                """,to.location(),json,actor.tenantId(),actor.companyId(),input.shipmentLineId());
        return true;
    }

    private void moveStock(CompanyAccess actor,Move input,boolean returning,Key key,Source source,
                           Posting outgoing,InventoryDimensions.Coordinates from,InventoryDimensions.Coordinates to,List<String> serials) {
        long shipping = returning ? input.fromId() : input.toId();
        long first = Math.min(input.fromId(),input.toId());
        long second = Math.max(input.fromId(),input.toId());
        lockPosition(actor,key,first,first==shipping);
        if (second!=first) lockPosition(actor,key,second,second==shipping);
        if (input.fromId() <= input.toId()) {
            dimensions.physicalId(actor,key,from,false);
            if (first!=second) dimensions.physicalId(actor,key,to,true);
        } else {
            dimensions.physicalId(actor,key,to,true);
            dimensions.physicalId(actor,key,from,false);
        }
        dimensions.requireUnpicked(actor,key,from,input.quantity(),input.shipmentLineId());
        var locked = dimensions.lockMoveSerials(actor,key,from,serials,true);
        if (first==second) {
            var free = jdbc.queryForObject("""
                    SELECT on_hand_qty-blocked_qty FROM inventory_balance WHERE tenant_id=? AND company_id=?
                      AND warehouse_id=? AND sku_id=? AND location_key=? AND lot_key=? FOR UPDATE
                    """,BigDecimal.class,actor.tenantId(),actor.companyId(),key.warehouseId(),key.skuId(),from.location(),from.lot());
            if (free==null || free.compareTo(input.quantity())<0) throw conflict("发货位可用库存不足");
            return;
        }
        dimensions.physicalChange(actor,key,from,input.quantity().negate(),BigDecimal.ZERO,false);
        dimensions.physicalChange(actor,key,to,input.quantity(),BigDecimal.ZERO,false);
        long out = store.appendMovement(actor,outgoing,from,input.quantity().negate(),BigDecimal.ZERO,null,BigDecimal.ZERO);
        long in = store.appendMovement(actor,new Posting(key,source,input.day(),input.period(),"location_move_in"),
                to,input.quantity(),BigDecimal.ZERO,null,BigDecimal.ZERO);
        dimensions.moveSerials(actor,source,to,locked,out,in);
    }

    private void lockPosition(CompanyAccess actor,Key key,long id,boolean shipping) {
        var types = jdbc.queryForList("""
                SELECT z.type FROM wms_location l JOIN wms_zone z ON z.tenant_id=l.tenant_id AND z.company_id=l.company_id
                  AND z.warehouse_id=l.warehouse_id AND z.id=l.zone_id AND z.status='active'
                WHERE l.tenant_id=? AND l.company_id=? AND l.warehouse_id=? AND l.id=? AND l.status='active'
                FOR UPDATE OF l FOR SHARE OF z
                """,String.class,actor.tenantId(),actor.companyId(),key.warehouseId(),id);
        if (types.isEmpty() || shipping && !"shipping".equals(types.getFirst()))
            throw conflict("库位已停用或发货位不属于发货区");
    }

    private void checkBatch(CompanyAccess actor,Key key,Long batchId) {
        if (batchId==null) return;
        var valid = jdbc.queryForList("""
                SELECT status='normal' AND (expiry_date IS NULL OR expiry_date>=?)
                FROM inv_batch WHERE tenant_id=? AND company_id=? AND sku_id=? AND id=? FOR SHARE
                """,Boolean.class,InventoryDimensions.today(),actor.tenantId(),actor.companyId(),key.skuId(),batchId);
        if (valid.isEmpty() || !valid.getFirst()) throw conflict("拣货批次已冻结或过期");
    }

    private boolean expired(CompanyAccess actor,Key key,Long batchId) {
        if (batchId==null) return false;
        return jdbc.queryForList("""
                SELECT status='expired' FROM inv_batch WHERE tenant_id=? AND company_id=? AND sku_id=? AND id=? FOR SHARE
                """,Boolean.class,actor.tenantId(),actor.companyId(),key.skuId(),batchId).stream().findFirst().orElse(false);
    }

    private InventoryDimensions.Coordinates coordinates(Long batch,long location) {
        return new InventoryDimensions.Coordinates(batch,Objects.toString(batch,""),Long.toString(location));
    }

    private ApiException conflict(String message) {
        return new ApiException(422,"PICK_UNAVAILABLE",message);
    }
}
