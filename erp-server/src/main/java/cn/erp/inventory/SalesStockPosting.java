package cn.erp.inventory;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.inventory.InventoryModels.*;
import static cn.erp.inventory.InventoryStore.*;

/** 发货整单出库：锁定全部维度后逐行计算成本，批量落库仍共用调用方事务。 */
@Service
@Transactional
public class SalesStockPosting {
    private final InventoryStore store;
    private final JdbcTemplate jdbc;
    private final ReservationService reservations;
    private org.springframework.context.ApplicationEventPublisher events = unused -> {};

    @org.springframework.beans.factory.annotation.Autowired
    void events(org.springframework.context.ApplicationEventPublisher events) { this.events=events; }

    public SalesStockPosting(InventoryStore store,JdbcTemplate jdbc,ReservationService reservations) {
        this.store=store;this.jdbc=jdbc;this.reservations=reservations;
    }

    public record Issue(Posting posting,BigDecimal quantity,Long reservationId) {}

    public List<CostResult> issue(CompanyAccess scope,List<Issue> inputs) {
        if(inputs==null || inputs.isEmpty() || inputs.size()>500 || inputs.stream().anyMatch(Objects::isNull)) {throw new ApiException(400,"VALIDATION_FAILED","发货出库明细无效");}
        var first=inputs.getFirst().posting();
        if(first==null) {throw missing();}
        var sourceLines=new HashSet<Long>();
        for(var input:inputs) {
            var p=input.posting();InventoryNumbers.quantity(input.quantity());
            if(p==null || p.source()==null || !"shipment_line".equals(p.source().type()) || !"sales_out".equals(p.movementType()) || p.source().id()<=0 || p.source().lineId()<=0 || !sourceLines.add(p.source().lineId()) || p.periodId()!=first.periodId() || !Objects.equals(p.businessDate(),first.businessDate())) {throw new ApiException(400,"VALIDATION_FAILED","发货出库来源、日期或期间无效");}
        }
        store.lockPeriod(scope,first);
        var ids=inputs.stream().map(Issue::reservationId).filter(Objects::nonNull).distinct().sorted().toList();
        var reservationRows=reservationRows(scope,ids,false);
        var lineIds=reservationRows.values().stream().map(row->((Number)row.get("order_line_id")).longValue()).distinct().sorted().toList();
        if(!lineIds.isEmpty()) {
            var args=args(scope,lineIds);
            var lines=jdbc.queryForList("SELECT id,warehouse_id FROM sales_order_line FORCE INDEX(uk_sales_order_line_scope) WHERE tenant_id=? AND company_id=? AND id IN ("+marks(lineIds.size())+") ORDER BY id FOR UPDATE",args);
            if(lines.size()!=lineIds.size() || lines.stream().anyMatch(row->row.get("warehouse_id")==null || !scope.allowsWarehouse(((Number)row.get("warehouse_id")).longValue()))) {throw missing();}
        }
        var keys=inputs.stream().map(input->input.posting().key()).toList();
        var balances=store.lockMany(scope,keys,false);var costs=store.lockMany(scope,keys,true);
        reservationRows=reservationRows(scope,ids,true);
        var duplicateArgs=new ArrayList<Object>(List.of(scope.tenantId(),scope.companyId()));duplicateArgs.addAll(sourceLines);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE tenant_id=? AND company_id=? AND source_type='shipment_line' AND movement_type='sales_out' AND posting_action='post' AND source_line_id IN ("+marks(sourceLines.size())+")",Long.class,duplicateArgs.toArray())>0) {throw new ApiException(409,"STATE_CONFLICT","该来源已完成库存过账");}
        var writes=new Writes();var result=new ArrayList<CostResult>();
        for(var input:inputs) {result.add(prepare(scope,input,balances,costs,reservationRows,writes));}
        writes.flush(jdbc);
        reservations.refreshLineStates(scope,lineIds);
        events.publishEvent(new cn.erp.shared.InventoryBalanceChanged(scope.tenantId(),scope.companyId(),
                keys.stream().map(Key::skuId).distinct().sorted().toList()));
        return List.copyOf(result);
    }

    private CostResult prepare(CompanyAccess scope,Issue input,Map<Key,Map<String,Object>> balances,
            Map<Key,Map<String,Object>> costs,Map<Long,Map<String,Object>> reservations,Writes writes) {
        var p=input.posting();var qty=input.quantity();var balance=balances.get(p.key());var cost=costs.get(p.key());
        var reservation=input.reservationId()==null?null:reservations.get(input.reservationId());
        if(input.reservationId()!=null && (reservation==null || ((Number)reservation.get("warehouse_id")).longValue()!=p.key().warehouseId() || ((Number)reservation.get("sku_id")).longValue()!=p.key().skuId())) {throw missing();}
        BigDecimal consumed=reservation!=null && "active".equals(reservation.get("status"))?qty.min(decimal(reservation,"remaining_qty")):BigDecimal.ZERO;
        BigDecimal onHand=decimal(balance,"on_hand_qty").subtract(qty),reserved=decimal(balance,"reserved_qty").subtract(consumed);
        if(onHand.signum()<0 || reserved.signum()<0 || onHand.subtract(reserved).subtract(decimal(balance,"blocked_qty")).signum()<0 || decimal(cost,"qty").compareTo(qty)<0) {throw insufficient();}
        balance.put("on_hand_qty",onHand);balance.put("reserved_qty",reserved);
        BigDecimal unit=InventoryNumbers.average(decimal(cost,"qty"),decimal(cost,"total_cost")),out=InventoryNumbers.roundedCost(qty.multiply(unit));
        BigDecimal remaining=decimal(cost,"qty").subtract(qty),remainingCost=decimal(cost,"total_cost").subtract(out),adjust=BigDecimal.ZERO.setScale(2);
        if(remaining.signum()==0) {adjust=remainingCost.negate();remainingCost=BigDecimal.ZERO.setScale(2);}
        InventoryNumbers.money(remainingCost);cost.put("qty",remaining);cost.put("total_cost",remainingCost);
        writes.balances.add(new Object[]{qty,consumed,balance.get("id"),qty,consumed,qty.subtract(consumed)});
        writes.costs.add(new Object[]{remaining,remainingCost,cost.get("id")});
        writes.movements.add(new Object[]{Ids.next(),scope.tenantId(),scope.companyId(),p.key().warehouseId(),p.key().skuId(),qty.negate(),unit,out.negate(),p.source().id(),p.source().lineId(),p.businessDate(),p.periodId(),scope.userId()});
        if(consumed.signum()>0) {
            var left=decimal(reservation,"remaining_qty").subtract(consumed);reservation.put("remaining_qty",left);reservation.put("status",left.signum()==0?"consumed":"active");
            writes.reservations.add(new Object[]{consumed,input.reservationId()});
            writes.changes.add(new Object[]{Ids.next(),scope.tenantId(),scope.companyId(),input.reservationId(),consumed.negate(),p.source().id(),p.source().lineId(),scope.userId()});
        }
        if(adjust.signum()!=0) {writes.adjustments.add(new Object[]{Ids.next(),scope.tenantId(),scope.companyId(),p.key().warehouseId(),p.key().skuId(),adjust,p.source().id(),p.source().lineId(),p.businessDate(),p.periodId(),scope.userId()});}
        return new CostResult(qty,unit,out,adjust,remaining,remainingCost);
    }

    private Map<Long,Map<String,Object>> reservationRows(CompanyAccess scope,List<Long> ids,boolean lock) {
        var result=new HashMap<Long,Map<String,Object>>();if(ids.isEmpty()) {return result;}
        if(lock) {
            for(long id:ids) {
                var rows=jdbc.queryForList("SELECT * FROM inventory_reservation WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",scope.tenantId(),scope.companyId(),id);
                if(rows.isEmpty()) {throw missing();}result.put(id,rows.getFirst());
            }
            return result;
        }
        for(var row:jdbc.queryForList("SELECT * FROM inventory_reservation WHERE tenant_id=? AND company_id=? AND id IN ("+marks(ids.size())+") ORDER BY id"+(lock?" FOR UPDATE":""),args(scope,ids))) {result.put(((Number)row.get("id")).longValue(),row);}
        if(result.size()!=ids.size()) {throw missing();}return result;
    }
    private static String marks(int count) {return String.join(",",Collections.nCopies(count,"?"));}
    private static Object[] args(CompanyAccess scope,List<Long> ids) {var args=new ArrayList<Object>(List.of(scope.tenantId(),scope.companyId()));args.addAll(ids);return args.toArray();}

    private static class Writes {
        final List<Object[]> balances=new ArrayList<>(),costs=new ArrayList<>(),movements=new ArrayList<>(),reservations=new ArrayList<>(),changes=new ArrayList<>(),adjustments=new ArrayList<>();
        void flush(JdbcTemplate jdbc) {
            for(int changed:jdbc.batchUpdate("UPDATE inventory_balance SET on_hand_qty=on_hand_qty-?,reserved_qty=reserved_qty-?,version=version+1 WHERE id=? AND on_hand_qty>=? AND reserved_qty>=? AND on_hand_qty-reserved_qty-blocked_qty>=?",balances)) {if(changed!=1) {throw insufficient();}}
            jdbc.batchUpdate("UPDATE inventory_cost SET qty=?,total_cost=?,version=version+1 WHERE id=?",costs);
            jdbc.batchUpdate("INSERT INTO inventory_movement(id,tenant_id,company_id,warehouse_id,sku_id,movement_type,on_hand_delta,blocked_delta,unit_cost,cost_delta,source_type,source_id,source_line_id,business_date,period_id,posted_at,created_by) VALUES(?,?,?,?,?,'sales_out',?,0,?,?,'shipment_line',?,?,?,?,UTC_TIMESTAMP(3),?)",movements);
            if(!reservations.isEmpty()) {jdbc.batchUpdate("UPDATE inventory_reservation SET remaining_qty=remaining_qty-?,status=IF(remaining_qty=0,'consumed','active'),version=version+1 WHERE id=?",reservations);}
            if(!changes.isEmpty()) {jdbc.batchUpdate("INSERT INTO inventory_reservation_change(id,tenant_id,company_id,reservation_id,change_type,qty_delta,source_type,source_id,source_line_id,created_by) VALUES(?,?,?,?,'consume',?,'shipment_line',?,?,?)",changes);}
            if(!adjustments.isEmpty()) {jdbc.batchUpdate("INSERT INTO cost_adjustment(id,tenant_id,company_id,warehouse_id,sku_id,adjust_type,amount,source_type,source_id,source_line_id,business_date,period_id,created_by) VALUES(?,?,?,?,?,'zero_qty_residue',?,'shipment_line',?,?,?,?,?)",adjustments);}
        }
    }
}
