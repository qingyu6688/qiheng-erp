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
    private static final Set<String> INBOUND=Set.of("opening_in","purchase_in","sales_return_in","sales_reject_in","transfer_in","count_gain","adjust_in","stock_misc_in","stock_misc_return_in");
    private static final Set<String> OUTBOUND=Set.of("sales_out","transfer_out","count_loss","adjust_out","scrap","purchase_return_out","stock_misc_out");
    private static final Set<String> DOCUMENT_LINES=Set.of("shipment_line","goods_receipt_line","purchase_return_line","sales_return_line","stock_misc_line");
    private final InventoryStore store;
    private final JdbcTemplate jdbc;
    private final ReservationService reservations;
    private final InventoryDimensions dimensions;
    private org.springframework.context.ApplicationEventPublisher events = unused -> {};

    @org.springframework.beans.factory.annotation.Autowired
    void events(org.springframework.context.ApplicationEventPublisher events) { this.events=events; }

    public InventoryService(InventoryStore store,JdbcTemplate jdbc,ReservationService reservations,InventoryDimensions dimensions) {this.store=store;this.jdbc=jdbc;this.reservations=reservations;this.dimensions=dimensions;}

    /** 调用者按仓库、SKU 排序传入整笔单据涉及的键，锁持续到外层事务提交。 */
    public void lockKeys(CompanyAccess scope,List<Key> keys) {
        if(keys==null || keys.size()>2000 || keys.stream().anyMatch(Objects::isNull)) {throw invalid("库存锁定范围无效");}
        dimensions.lockSettings(scope,keys);
        store.lockMany(scope,keys,false);
        store.lockMany(scope,keys,true);
    }

    public CostResult receive(CompanyAccess scope,Posting posting,BigDecimal quantity,BigDecimal cost) {
        return receive(scope,posting,quantity,cost,null,null,null);
    }

    public CostResult receive(CompanyAccess scope,Posting posting,BigDecimal quantity,BigDecimal cost,StockSelection selection,String blockingReason) {
        return receive(scope,posting,quantity,cost,selection,blockingReason,null);
    }

    public CostResult receive(CompanyAccess scope,Posting posting,BigDecimal quantity,BigDecimal cost,StockSelection selection,String blockingReason,Long supplier) {
        InventoryNumbers.quantity(quantity);InventoryNumbers.money(cost);
        if(posting==null || posting.movementType()==null || !INBOUND.contains(posting.movementType())) {throw invalid("入库类型无效");}
        store.lockPeriod(scope,posting);
        dimensions.lockSettings(scope,List.of(posting.key()));
        store.ensureAndLockBalance(scope,posting.key());
        var current=store.lockCost(scope,posting.key());
        rejectDuplicate(scope,posting);
        BigDecimal beforeQty=decimal(current,"qty"),beforeCost=decimal(current,"total_cost");
        BigDecimal nextQty=InventoryNumbers.quantity(beforeQty.add(quantity));
        BigDecimal nextCost=InventoryNumbers.money(beforeCost.add(cost));
        BigDecimal unitCost=InventoryNumbers.average(quantity,cost);
        String hold=posting.movementType().equals("sales_reject_in")?"sales_reject":blockingReason;
        var pick=dimensions.inbound(scope,posting,quantity,selection,supplier,hold);
        if(hold==null&&pick.at().batch()!=null&&Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM inv_batch WHERE tenant_id=? AND company_id=? AND id=? AND (status='expired' OR expiry_date<?))",
                Boolean.class,scope.tenantId(),scope.companyId(),pick.at().batch(),InventoryDimensions.today())))hold="expired";
        BigDecimal blocked=hold==null?BigDecimal.ZERO:quantity;
        jdbc.update("""
                UPDATE inventory_balance SET on_hand_qty=on_hand_qty+?,blocked_qty=blocked_qty+?,version=version+1
                WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=? AND location_key='' AND lot_key=''
                """,quantity,blocked,scope.tenantId(),scope.companyId(),posting.key().warehouseId(),posting.key().skuId());
        jdbc.update("UPDATE inventory_cost SET qty=?,total_cost=?,last_in_unit_cost=?,version=version+1 WHERE id=?",
                nextQty,nextCost,unitCost,current.get("id"));
        dimensions.physicalChange(scope,posting.key(),pick.at(),quantity,blocked,true);
        if(hold!=null)dimensions.addHold(scope,posting.key(),pick.at(),hold,posting.source(),quantity);
        long movement=movement(scope,posting,pick.at(),quantity,blocked,unitCost,cost);
        if(hold!=null)dimensions.recordHolds(scope,movement,List.of(new InventoryDimensions.HoldChange(hold,posting.source(),quantity)));
        dimensions.serialMovement(scope,posting,pick,movement,true,hold);
        writePostedSelection(scope,posting,pick);
        return new CostResult(quantity,unitCost,cost,BigDecimal.ZERO.setScale(2),nextQty,nextCost);
    }

    public CostResult issue(CompanyAccess scope,Posting posting,BigDecimal quantity,Long reservationId) {
        return issue(scope,posting,quantity,reservationId,null,null);
    }

    public CostResult issue(CompanyAccess scope,Posting posting,BigDecimal quantity,Long reservationId,StockSelection selection,String reason) {
        return issue(scope,posting,quantity,reservationId,selection,reason,null);
    }

    public CostResult issue(CompanyAccess scope,Posting posting,BigDecimal quantity,Long reservationId,StockSelection selection,String reason,Source held) {
        return outgoing(scope,posting,quantity,reservationId,null,selection,reason,held);
    }

    public CostResult purchaseReturn(CompanyAccess scope,Posting posting,BigDecimal quantity,BigDecimal originalUnitCost) {
        return purchaseReturn(scope,posting,quantity,originalUnitCost,null);
    }

    public CostResult purchaseReturn(CompanyAccess scope,Posting posting,BigDecimal quantity,BigDecimal originalUnitCost,StockSelection selection) {
        return purchaseReturn(scope,posting,quantity,originalUnitCost,selection,null);
    }

    public CostResult purchaseReturn(CompanyAccess scope,Posting posting,BigDecimal quantity,BigDecimal originalUnitCost,StockSelection selection,Source held) {
        InventoryNumbers.unitCost(originalUnitCost);
        if(posting==null || !posting.movementType().equals("purchase_return_out")) {throw invalid("采购退货类型无效");}
        return outgoing(scope,posting,quantity,null,originalUnitCost,selection,held==null?null:"quality_hold",held);
    }

    private CostResult outgoing(CompanyAccess scope,Posting posting,BigDecimal quantity,Long reservationId,BigDecimal originalUnitCost,StockSelection selection,String reason,Source held) {
        InventoryNumbers.quantity(quantity);
        if(posting==null || posting.movementType()==null || !OUTBOUND.contains(posting.movementType())) {throw invalid("出库类型无效");}
        if(posting.movementType().equals("purchase_return_out") && originalUnitCost==null) {throw invalid("采购退货需要原收货单位成本");}
        if(reservationId!=null && !posting.movementType().equals("sales_out")) {throw invalid("只有销售出库可以消费销售预留");}
        store.lockPeriod(scope,posting);
        dimensions.lockSettings(scope,List.of(posting.key()));
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
        boolean scrap=posting.movementType().equals("scrap"), consumeBlocked=scrap||reason!=null;
        String hold=reason;
        if(hold==null&&scrap)hold=expired(scope,posting.key(),selection)?"expired":"ordinary";
        var allocated=posting.movementType().equals("sales_out")?shipmentPick(scope,posting.source().lineId()):null;
        var pick=dimensions.outgoing(scope,posting,quantity,allocated==null?selection:allocated,hold,allocated!=null);
        var changes=consumeBlocked?dimensions.consumeHold(scope,posting.key(),pick.at(),"ordinary".equals(hold)?null:hold,held,quantity):
                List.<InventoryDimensions.HoldChange>of();
        int changed=jdbc.update("""
                UPDATE inventory_balance SET on_hand_qty=on_hand_qty-?,reserved_qty=reserved_qty-?,
                    blocked_qty=blocked_qty-?,version=version+1 WHERE tenant_id=? AND company_id=?
                    AND warehouse_id=? AND sku_id=? AND location_key='' AND lot_key=''
                    AND on_hand_qty>=? AND reserved_qty>=? AND blocked_qty>=?
                    AND on_hand_qty-reserved_qty-blocked_qty>=?
                """,quantity,consumed,consumeBlocked?quantity:BigDecimal.ZERO,scope.tenantId(),scope.companyId(),
                posting.key().warehouseId(),posting.key().skuId(),quantity,consumed,consumeBlocked?quantity:BigDecimal.ZERO,
                consumeBlocked?BigDecimal.ZERO:quantity.subtract(consumed));
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
        BigDecimal blockedDelta=consumeBlocked?quantity.negate():BigDecimal.ZERO;
        dimensions.physicalChange(scope,posting.key(),pick.at(),quantity.negate(),blockedDelta,false);
        long movement=movement(scope,posting,pick.at(),quantity.negate(),blockedDelta,unit,out.negate());
        dimensions.recordHolds(scope,movement,changes);
        dimensions.serialMovement(scope,posting,pick,movement,false,hold);
        writePostedSelection(scope,posting,pick);
        if(allocated!=null)deleteShipmentPick(scope,posting.source().lineId());
        if(adjust.signum()!=0) {adjustment(scope,posting,adjustmentType,adjust);}
        return new CostResult(quantity,unit,out,adjust,nextQty,nextCost);
    }

    private boolean expired(CompanyAccess actor,Key key,StockSelection selection){
        if(selection==null||selection.batchId()==null)return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM inv_batch WHERE tenant_id=? AND company_id=? AND sku_id=? AND id=?
                    AND (status='expired' OR expiry_date<?))
                """,Boolean.class,actor.tenantId(),actor.companyId(),key.skuId(),selection.batchId(),InventoryDimensions.today()));
    }

    public void validateInbound(CompanyAccess scope,Key key,BigDecimal quantity,StockSelection selection,String hold) {
        dimensions.validateInbound(scope,key,quantity,selection,hold,dimensions.config(scope,key));
    }

    /** 订单仍保留仓库级预留，发货提交在同一个汇总锁内占用实际批次和序列号。 */
    public void reserveShipmentPicks(CompanyAccess scope,List<SalesStockPosting.Issue> lines) {
        var keys=lines.stream().map(line->line.posting().key()).toList();
        dimensions.lockSettings(scope,keys);
        var tracked=dimensions.trackedKeys(scope,keys);
        if(!tracked.isEmpty())store.lockMany(scope,new ArrayList<>(tracked),false);
        for(var line:lines){
            if(tracked.contains(line.posting().key()))reserveShipmentPick(scope,line.posting(),line.quantity(),line.stockSelection());
            else dimensions.rejectUnused(line.stockSelection());
        }
    }

    private void reserveShipmentPick(CompanyAccess scope,Posting posting,BigDecimal qty,StockSelection selection) {
        var pick=dimensions.outgoing(scope,posting,qty,selection,null,false);
        dimensions.reserveSerials(scope,posting.key(),pick);
        var selected=dimensions.from(pick.at(),pick.serials());
        jdbc.update("""
                INSERT INTO inv_shipment_pick(tenant_id,company_id,shipment_line_id,warehouse_id,sku_id,batch_id,location_key,lot_key,qty,serial_ids)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """,scope.tenantId(),scope.companyId(),posting.source().lineId(),posting.key().warehouseId(),posting.key().skuId(),
                pick.at().batch(),pick.at().location(),pick.at().lot(),qty,encodeSelection(selected));
        writePostedSelection(scope,posting,pick);
    }

    public void releaseShipmentPick(CompanyAccess scope,Key key,long lineId) {
        var selection=shipmentPick(scope,lineId);
        if(selection==null)return;
        dimensions.unreserveSerials(scope,key,selection.serials()==null?List.of():selection.serials());
        deleteShipmentPick(scope,lineId);
    }

    private StockSelection shipmentPick(CompanyAccess scope,long lineId) {
        return jdbc.queryForList("SELECT serial_ids FROM inv_shipment_pick WHERE tenant_id=? AND company_id=? AND shipment_line_id=? FOR UPDATE",
                String.class,scope.tenantId(),scope.companyId(),lineId).stream().map(this::decodeSelection).findFirst().orElse(null);
    }

    private void deleteShipmentPick(CompanyAccess scope,long lineId) {
        jdbc.update("DELETE FROM inv_shipment_pick WHERE tenant_id=? AND company_id=? AND shipment_line_id=?",scope.tenantId(),scope.companyId(),lineId);
    }

    public StockSelection postedSelection(CompanyAccess scope,Source source,String movementType) {
        var details=jdbc.queryForList("""
                SELECT warehouse_id,lot_key,location_key FROM inventory_movement WHERE tenant_id=? AND company_id=?
                AND source_type=? AND source_id=? AND source_line_id=? AND movement_type=? AND posting_action='post'
                """,scope.tenantId(),scope.companyId(),source.type(),source.id(),source.lineId(),movementType);
        if(details.isEmpty())return null;
        var row=details.getFirst();
        if(!scope.allowsWarehouse(((Number)row.get("warehouse_id")).longValue()))throw missing();
        return dimensions.from(coordinates(row),dimensions.sourceSerials(scope,source,List.of()));
    }

    /** 退回只接受原单的批次和序列号，显式选库位时仍校验仓库归属。 */
    public StockSelection returnSelection(CompanyAccess scope,Key key,Source origin,String movementType,
            StockSelection requested,BigDecimal qty,String hold) {
        return returnSelection(scope,key,origin,movementType,requested,qty,hold,null);
    }

    public StockSelection returnSelection(CompanyAccess scope,Key key,Source origin,String movementType,
            StockSelection requested,BigDecimal qty,String hold,List<String> allowedSerials) {
        var posted=postedSelection(scope,origin,movementType);
        if(posted==null)return requested;
        if(requested!=null&&(requested.batchNo()!=null||requested.productionDate()!=null||requested.expiryDate()!=null||
                requested.batchId()!=null&&!Objects.equals(requested.batchId(),posted.batchId())))throw invalid("退货批次必须与原单据一致");
        List<String> selected=List.of();
        if(dimensions.config(scope,key).serial()){
            var statuses=switch(movementType){
                case "sales_out","stock_misc_out" -> List.of("shipped");
                case "transfer_out" -> List.of("in_transit");
                default -> "quality_hold".equals(hold)?List.of("quality_hold"):List.of("in_stock","returned");
            };
            var available=dimensions.sourceSerials(scope,origin,statuses,Set.of("sales_out","transfer_out","stock_misc_out").contains(movementType));
            if(allowedSerials!=null)available=available.stream().filter(allowedSerials::contains).toList();
            if(requested!=null&&requested.serials()!=null&&!requested.serials().isEmpty()){
                if(!available.containsAll(requested.serials()))throw invalid("序列号必须来自原单据且处于可退货状态");
                selected=requested.serials();
            }else{
                final int count;
                try{count=qty.intValueExact();}catch(ArithmeticException invalidQty){throw invalid("序列号管理商品的基础数量必须为整数");}
                if(available.size()<count)throw InventoryDimensions.serialConflict();
                selected=available.subList(0,count);
            }
        }
        boolean sameWarehouse=Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM inventory_movement WHERE tenant_id=? AND company_id=? AND source_type=?
                    AND source_id=? AND source_line_id=? AND movement_type=? AND posting_action='post' AND warehouse_id=?)
                """,Boolean.class,scope.tenantId(),scope.companyId(),origin.type(),origin.id(),origin.lineId(),movementType,key.warehouseId()));
        Long location=requested!=null&&requested.locationId()!=null?requested.locationId():sameWarehouse?posted.locationId():null;
        return new StockSelection(posted.batchId(),null,null,null,location,selected);
    }

    static InventoryDimensions.Coordinates coordinates(Map<String,Object> row){
        String lot=(String)row.get("lot_key");
        return new InventoryDimensions.Coordinates(lot.isEmpty()?null:Long.valueOf(lot),lot,(String)row.get("location_key"));
    }

    public void finishLostInTransit(CompanyAccess scope,Key key,long event,StockSelection selection){
        if(selection==null||selection.serials()==null)return;
        for(String serial:selection.serials()){
            var row=dimensions.serialRow(scope,key.skuId(),serial,true);
            if(row==null||!"in_transit".equals(row.get("status")))throw InventoryDimensions.serialConflict();
            jdbc.update("UPDATE inv_serial SET status='scrapped',version=version+1 WHERE id=?",row.get("id"));
            jdbc.update("INSERT INTO doc_line_serial(tenant_id,company_id,doc_type,line_id,serial_id) VALUES(?,?,'stock_transfer_event',?,?)",
                    scope.tenantId(),scope.companyId(),event,row.get("id"));
        }
    }

    public BigDecimal countQuantity(CompanyAccess scope,Key key,StockSelection selection){
        var at=dimensions.explicit(scope,key,selection);
        return jdbc.queryForList("""
                SELECT on_hand_qty FROM inventory_balance WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=?
                AND location_key=? AND lot_key=? FOR UPDATE
                """,BigDecimal.class,scope.tenantId(),scope.companyId(),key.warehouseId(),key.skuId(),at.location(),at.lot())
                .stream().findFirst().orElse(BigDecimal.ZERO.setScale(6));
    }

    public void validateCountChange(CompanyAccess scope,Key key,StockSelection selection,BigDecimal difference){
        if(difference.signum()==0){
            if(selection!=null&&selection.serials()!=null&&!selection.serials().isEmpty())throw invalid("盘点零差额无需登记序列号");
            return;
        }
        dimensions.serials(dimensions.config(scope,key),selection,difference.abs(),true);
    }

    public void freeze(CompanyAccess scope,Posting posting,BigDecimal quantity,boolean frozen) {
        freeze(scope,posting,quantity,frozen,null,null);
    }

    public void freeze(CompanyAccess scope,Posting posting,BigDecimal quantity,boolean frozen,StockSelection selection,String reason) {
        changeFreeze(scope,posting,quantity,frozen,selection,reason,null);
    }

    public void releaseHold(CompanyAccess scope,Posting action,StockSelection selection,String reason,Source held,BigDecimal qty) {
        changeFreeze(scope,action,qty,false,selection,reason,held);
    }

    private void changeFreeze(CompanyAccess scope,Posting posting,BigDecimal quantity,boolean frozen,StockSelection selection,String reason,Source exact) {
        InventoryNumbers.quantity(quantity);
        if(posting==null || !(frozen?"block":"unblock").equals(posting.movementType())) {throw invalid("冻结操作类型无效");}
        store.lockPeriod(scope,posting);
        dimensions.lockSettings(scope,List.of(posting.key()));
        store.ensureAndLockBalance(scope,posting.key());
        rejectDuplicate(scope,posting);
        var at=dimensions.explicit(scope,posting.key(),selection);
        if(frozen)dimensions.requireUnpicked(scope,posting.key(),at,quantity);
        String hold=reason==null?"manual":reason;
        var changes=frozen?List.of(new InventoryDimensions.HoldChange(hold,posting.source(),quantity)):
                dimensions.consumeHold(scope,posting.key(),at,reason,exact,quantity);
        BigDecimal delta=frozen?quantity:quantity.negate();
        int changed=jdbc.update("""
                UPDATE inventory_balance SET blocked_qty=blocked_qty+?,version=version+1
                WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=? AND location_key='' AND lot_key=''
                    AND blocked_qty+?>=0 AND on_hand_qty-reserved_qty-blocked_qty-?>=0
                """,delta,scope.tenantId(),scope.companyId(),posting.key().warehouseId(),posting.key().skuId(),delta,delta);
        if(changed!=1) {throw insufficient();}
        dimensions.physicalChange(scope,posting.key(),at,BigDecimal.ZERO,delta,false);
        if(frozen)dimensions.addHold(scope,posting.key(),at,hold,posting.source(),quantity);
        long movement=movement(scope,posting,at,BigDecimal.ZERO,delta,null,BigDecimal.ZERO);
        dimensions.recordHolds(scope,movement,changes);
        writePostedSelection(scope,posting,new InventoryDimensions.Pick(at,List.of()));
        if(!frozen&&exact!=null&&"quality_hold".equals(reason))dimensions.releaseSerials(scope,posting,exact,quantity,movement);
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

    long movement(CompanyAccess scope,Posting p,InventoryDimensions.Coordinates at,BigDecimal quantity,BigDecimal blocked,BigDecimal unitCost,BigDecimal cost) {
        long id=Ids.next();
        jdbc.update("""
                INSERT INTO inventory_movement(id,tenant_id,company_id,warehouse_id,sku_id,location_key,lot_key,movement_type,on_hand_delta,
                    blocked_delta,unit_cost,cost_delta,source_type,source_id,source_line_id,business_date,period_id,posted_at,created_by)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,scope.tenantId(),scope.companyId(),p.key().warehouseId(),p.key().skuId(),at.location(),at.lot(),p.movementType(),
                quantity,blocked,unitCost,cost,p.source().type(),p.source().id(),p.source().lineId(),p.businessDate(),p.periodId(),Timestamp.from(Instant.now()),scope.userId());
        changed(scope,List.of(p.key()));
        return id;
    }

    private void writePostedSelection(CompanyAccess scope,Posting p,InventoryDimensions.Pick pick) {
        String type=p.source().type();
        String table=DOCUMENT_LINES.contains(type)?type:switch(type){
            case "stock_adjustment","stock_transfer","stock_count" -> type+"_line";
            default -> null;
        };
        if(table!=null&&(pick.at().physical()||!pick.serials().isEmpty())) {
            jdbc.update("UPDATE "+table+" SET batch_id=?,location_key=?,stock_selection=? WHERE tenant_id=? AND company_id=? AND id=?",
                    pick.at().batch(),pick.at().location(),encodeSelection(dimensions.from(pick.at(),pick.serials())),scope.tenantId(),scope.companyId(),p.source().lineId());
        }
    }

    public StockSelection decodeSelection(Object raw){return dimensions.decode(raw);}
    public String encodeSelection(StockSelection selection){return dimensions.encode(selection);}
    public Object viewSelection(Object raw){return dimensions.view(raw);}

    public void changed(CompanyAccess scope,List<Key> keys) {
        if (!keys.isEmpty()) events.publishEvent(new cn.erp.shared.InventoryBalanceChanged(scope.tenantId(),scope.companyId(),
                keys.stream().map(Key::skuId).distinct().sorted().toList()));
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
