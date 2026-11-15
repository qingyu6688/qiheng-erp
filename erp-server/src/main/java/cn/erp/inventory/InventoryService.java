package cn.erp.inventory;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.inventory.InventoryModels.*;
import static cn.erp.inventory.InventoryStore.*;

@Service
@Transactional
public class InventoryService {
    private static final Set<String> INBOUND=Set.of("opening_in","purchase_in","sales_return_in","sales_reject_in","transfer_in","count_gain","adjust_in");
    private static final Set<String> OUTBOUND=Set.of("sales_out","transfer_out","count_loss","adjust_out","scrap","purchase_return_out");
    private final InventoryStore store;
    private final JdbcTemplate jdbc;
    private final ReservationService reservations;

    public InventoryService(InventoryStore store,JdbcTemplate jdbc,ReservationService reservations) {this.store=store;this.jdbc=jdbc;this.reservations=reservations;}

    /** 调用者按仓库、SKU 排序传入整笔单据涉及的键，锁持续到外层事务提交。 */
    public void lockKeys(CompanyAccess scope,List<Key> keys) {
        if(keys==null || keys.size()>2000 || keys.stream().anyMatch(Objects::isNull)) {throw invalid("库存锁定范围无效");}
        keys.stream().distinct().sorted().forEach(key->store.ensureAndLockBalance(scope,key));
        keys.stream().distinct().sorted().forEach(key->store.lockCost(scope,key));
    }

    public CostResult receive(CompanyAccess scope,Posting posting,BigDecimal quantity,BigDecimal cost) {
        InventoryNumbers.quantity(quantity);InventoryNumbers.money(cost);
        if(posting==null || posting.movementType()==null || !INBOUND.contains(posting.movementType())) {throw invalid("入库类型无效");}
        store.lockPeriod(scope,posting);
        store.ensureAndLockBalance(scope,posting.key());
        var current=store.lockCost(scope,posting.key());
        rejectDuplicate(scope,posting);
        BigDecimal beforeQty=decimal(current,"qty"),beforeCost=decimal(current,"total_cost");
        BigDecimal nextQty=InventoryNumbers.quantity(beforeQty.add(quantity));
        BigDecimal nextCost=InventoryNumbers.money(beforeCost.add(cost));
        BigDecimal unitCost=InventoryNumbers.average(quantity,cost);
        BigDecimal blocked=posting.movementType().equals("sales_reject_in")?quantity:BigDecimal.ZERO;
        jdbc.update("""
                UPDATE inventory_balance SET on_hand_qty=on_hand_qty+?,blocked_qty=blocked_qty+?,version=version+1
                WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=? AND location_key='' AND lot_key=''
                """,quantity,blocked,scope.tenantId(),scope.companyId(),posting.key().warehouseId(),posting.key().skuId());
        jdbc.update("UPDATE inventory_cost SET qty=?,total_cost=?,last_in_unit_cost=?,version=version+1 WHERE id=?",
                nextQty,nextCost,unitCost,current.get("id"));
        movement(scope,posting,quantity,blocked,unitCost,cost);
        return new CostResult(quantity,unitCost,cost,BigDecimal.ZERO.setScale(2),nextQty,nextCost);
    }

    public CostResult issue(CompanyAccess scope,Posting posting,BigDecimal quantity,Long reservationId) {
        return outgoing(scope,posting,quantity,reservationId,null);
    }

    public CostResult purchaseReturn(CompanyAccess scope,Posting posting,BigDecimal quantity,BigDecimal originalUnitCost) {
        InventoryNumbers.unitCost(originalUnitCost);
        if(posting==null || !posting.movementType().equals("purchase_return_out")) {throw invalid("采购退货类型无效");}
        return outgoing(scope,posting,quantity,null,originalUnitCost);
    }

    private CostResult outgoing(CompanyAccess scope,Posting posting,BigDecimal quantity,Long reservationId,BigDecimal originalUnitCost) {
        InventoryNumbers.quantity(quantity);
        if(posting==null || posting.movementType()==null || !OUTBOUND.contains(posting.movementType())) {throw invalid("出库类型无效");}
        if(posting.movementType().equals("purchase_return_out") && originalUnitCost==null) {throw invalid("采购退货需要原收货单位成本");}
        if(reservationId!=null && !posting.movementType().equals("sales_out")) {throw invalid("只有销售出库可以消费销售预留");}
        store.lockPeriod(scope,posting);
        Map<String,Object> reservationMeta=reservationId==null?null:reservation(scope,reservationId,false);
        if(reservationMeta!=null) {
            if(((Number)reservationMeta.get("warehouse_id")).longValue()!=posting.key().warehouseId() ||
                    ((Number)reservationMeta.get("sku_id")).longValue()!=posting.key().skuId()) {throw missing();}
            store.lockOrderLine(scope,((Number)reservationMeta.get("order_line_id")).longValue());
        }
        store.ensureAndLockBalance(scope,posting.key());
        var current=store.lockCost(scope,posting.key());
        var reservation=reservationId==null?null:reservation(scope,reservationId,true);
        rejectDuplicate(scope,posting);
        BigDecimal consumed=reservation!=null && "active".equals(reservation.get("status"))
                ?quantity.min(decimal(reservation,"remaining_qty")):BigDecimal.ZERO;
        boolean scrap=posting.movementType().equals("scrap");
        int changed=jdbc.update("""
                UPDATE inventory_balance SET on_hand_qty=on_hand_qty-?,reserved_qty=reserved_qty-?,
                    blocked_qty=blocked_qty-?,version=version+1 WHERE tenant_id=? AND company_id=?
                    AND warehouse_id=? AND sku_id=? AND location_key='' AND lot_key=''
                    AND on_hand_qty>=? AND reserved_qty>=? AND blocked_qty>=?
                    AND on_hand_qty-reserved_qty-blocked_qty>=?
                """,quantity,consumed,scrap?quantity:BigDecimal.ZERO,scope.tenantId(),scope.companyId(),
                posting.key().warehouseId(),posting.key().skuId(),quantity,consumed,scrap?quantity:BigDecimal.ZERO,
                scrap?BigDecimal.ZERO:quantity.subtract(consumed));
        if(changed!=1) {throw insufficient();}
        if(consumed.signum()>0) {
            jdbc.update("""
                    UPDATE inventory_reservation SET remaining_qty=remaining_qty-?,
                        status=IF(remaining_qty=0,'consumed','active'),version=version+1 WHERE id=?
                    """,consumed,reservationId);
            store.reservationChange(scope,reservationId,"consume",consumed.negate(),posting.source());
            reservations.refreshLineState(scope,((Number)reservation.get("order_line_id")).longValue());
        }
        BigDecimal beforeQty=decimal(current,"qty"),beforeCost=decimal(current,"total_cost");
        if(beforeQty.compareTo(quantity)<0) {throw insufficient();}
        BigDecimal unit=originalUnitCost==null?InventoryNumbers.average(beforeQty,beforeCost):originalUnitCost;
        BigDecimal out=InventoryNumbers.roundedCost(quantity.multiply(unit));
        BigDecimal nextQty=beforeQty.subtract(quantity),nextCost=beforeCost.subtract(out),adjust=BigDecimal.ZERO.setScale(2);
        String adjustmentType="zero_qty_residue";
        if(nextQty.signum()==0) {
            adjust=nextCost.negate();nextCost=BigDecimal.ZERO.setScale(2);
        } else if(nextCost.signum()<0 && originalUnitCost!=null) {
            BigDecimal actual=InventoryNumbers.roundedCost(quantity.multiply(InventoryNumbers.average(beforeQty,beforeCost)));
            adjust=out.subtract(actual);nextCost=beforeCost.subtract(actual);adjustmentType="purchase_return_diff";
        }
        InventoryNumbers.money(nextCost);
        jdbc.update("UPDATE inventory_cost SET qty=?,total_cost=?,version=version+1 WHERE id=?",nextQty,nextCost,current.get("id"));
        movement(scope,posting,quantity.negate(),scrap?quantity.negate():BigDecimal.ZERO,unit,out.negate());
        if(adjust.signum()!=0) {adjustment(scope,posting,adjustmentType,adjust);}
        return new CostResult(quantity,unit,out,adjust,nextQty,nextCost);
    }

    public void freeze(CompanyAccess scope,Posting posting,BigDecimal quantity,boolean frozen) {
        InventoryNumbers.quantity(quantity);
        if(posting==null || !(frozen?"block":"unblock").equals(posting.movementType())) {throw invalid("冻结操作类型无效");}
        store.lockPeriod(scope,posting);
        store.ensureAndLockBalance(scope,posting.key());
        rejectDuplicate(scope,posting);
        BigDecimal delta=frozen?quantity:quantity.negate();
        int changed=jdbc.update("""
                UPDATE inventory_balance SET blocked_qty=blocked_qty+?,version=version+1
                WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=? AND location_key='' AND lot_key=''
                    AND blocked_qty+?>=0 AND on_hand_qty-reserved_qty-blocked_qty-?>=0
                """,delta,scope.tenantId(),scope.companyId(),posting.key().warehouseId(),posting.key().skuId(),delta,delta);
        if(changed!=1) {throw insufficient();}
        movement(scope,posting,BigDecimal.ZERO,delta,null,BigDecimal.ZERO);
    }

    @Transactional(readOnly=true)
    public Balance balance(CompanyAccess scope,Key key) {
        store.visible(scope,key);
        var rows=jdbc.queryForList("""
                SELECT b.on_hand_qty,b.reserved_qty,b.blocked_qty,COALESCE(c.total_cost,0) total_cost
                FROM inventory_balance b LEFT JOIN inventory_cost c ON c.tenant_id=b.tenant_id AND c.company_id=b.company_id
                    AND c.warehouse_id=b.warehouse_id AND c.sku_id=b.sku_id WHERE b.tenant_id=? AND b.company_id=?
                    AND b.warehouse_id=? AND b.sku_id=? AND b.location_key='' AND b.lot_key=''
                """,scope.tenantId(),scope.companyId(),key.warehouseId(),key.skuId());
        return rows.isEmpty()?new Balance(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO):
                new Balance(decimal(rows.getFirst(),"on_hand_qty"),decimal(rows.getFirst(),"reserved_qty"),
                        decimal(rows.getFirst(),"blocked_qty"),decimal(rows.getFirst(),"total_cost"));
    }

    private Map<String,Object> reservation(CompanyAccess scope,long id,boolean lock) {
        var rows=jdbc.queryForList("SELECT order_line_id,warehouse_id,sku_id,status,remaining_qty FROM inventory_reservation WHERE tenant_id=? AND company_id=? AND id=?"+(lock?" FOR UPDATE":""),
                scope.tenantId(),scope.companyId(),id);
        if(rows.isEmpty()) {throw missing();}return rows.getFirst();
    }

    private void rejectDuplicate(CompanyAccess scope,Posting posting) {
        Long count=jdbc.queryForObject("""
                SELECT COUNT(*) FROM inventory_movement WHERE tenant_id=? AND company_id=? AND source_type=?
                    AND source_line_id=? AND movement_type=? AND posting_action='post'
                """,Long.class,scope.tenantId(),scope.companyId(),posting.source().type(),posting.source().lineId(),posting.movementType());
        if(count!=null && count>0) {throw new ApiException(409,"STATE_CONFLICT","该来源已完成库存过账");}
    }

    private void movement(CompanyAccess scope,Posting p,BigDecimal quantity,BigDecimal blocked,BigDecimal unitCost,BigDecimal cost) {
        jdbc.update("""
                INSERT INTO inventory_movement(id,tenant_id,company_id,warehouse_id,sku_id,movement_type,on_hand_delta,
                    blocked_delta,unit_cost,cost_delta,source_type,source_id,source_line_id,business_date,period_id,posted_at,created_by)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,Ids.next(),scope.tenantId(),scope.companyId(),p.key().warehouseId(),p.key().skuId(),p.movementType(),
                quantity,blocked,unitCost,cost,p.source().type(),p.source().id(),p.source().lineId(),p.businessDate(),p.periodId(),Timestamp.from(Instant.now()),scope.userId());
    }

    private void adjustment(CompanyAccess scope,Posting p,String type,BigDecimal amount) {
        jdbc.update("""
                INSERT INTO cost_adjustment(id,tenant_id,company_id,warehouse_id,sku_id,adjust_type,amount,source_type,
                    source_id,source_line_id,business_date,period_id,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,Ids.next(),scope.tenantId(),scope.companyId(),p.key().warehouseId(),p.key().skuId(),type,amount,
                p.source().type(),p.source().id(),p.source().lineId(),p.businessDate(),p.periodId(),scope.userId());
    }

    private static ApiException invalid(String message) {return new ApiException(400,"VALIDATION_FAILED",message);}
}
