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
public class ReservationService {
    private static final Set<String> RELEASES = Set.of("release_close", "release_cancel", "release_change", "release_revoke");
    private final InventoryStore store;
    private final JdbcTemplate jdbc;
    private org.springframework.context.ApplicationEventPublisher events = unused -> {};

    @org.springframework.beans.factory.annotation.Autowired
    void events(org.springframework.context.ApplicationEventPublisher events) { this.events=events; }

    public ReservationService(InventoryStore store, JdbcTemplate jdbc) { this.store = store; this.jdbc = jdbc; }

    /** 先锁来源行，再按仓库和 SKU 排序锁库存；任何一行失败会回滚整批。 */
    public List<Long> reserve(CompanyAccess scope, List<ReservationRequest> requests) {
        if (requests == null || requests.isEmpty() || requests.size() > 2000) {
            throw new ApiException(400, "VALIDATION_FAILED", "预留明细数量无效");
        }
        if (requests.stream().anyMatch(Objects::isNull) ||
                requests.stream().map(ReservationRequest::orderLineId).distinct().count() != requests.size()) {
            throw new ApiException(400, "VALIDATION_FAILED", "预留订单行不能重复");
        }
        Map<Long,Map<String,Object>> lines = new HashMap<>();
        var lineArgs=new ArrayList<Object>(List.of(scope.tenantId(),scope.companyId()));
        lineArgs.addAll(requests.stream().map(ReservationRequest::orderLineId).sorted().toList());
        for(var row:jdbc.queryForList("SELECT id,order_id,warehouse_id,sku_id,base_qty,closed_base_qty,shipped_base_qty FROM sales_order_line FORCE INDEX(uk_sales_order_line_scope) WHERE tenant_id=? AND company_id=? AND id IN ("+String.join(",",Collections.nCopies(requests.size(),"?"))+") ORDER BY id FOR UPDATE",lineArgs.toArray())) {lines.put(((Number)row.get("id")).longValue(),row);}
        requests.stream().sorted(Comparator.comparingLong(ReservationRequest::orderLineId)).forEach(request -> {
            InventoryNumbers.quantity(request.quantity());
            if(request.key()==null || request.orderLineId()<=0) {
                throw new ApiException(400,"VALIDATION_FAILED","预留订单行和库存维度无效");
            }
            if (request.expiresAt() == null || !request.expiresAt().isAfter(Instant.now())) {
                throw new ApiException(400, "VALIDATION_FAILED", "预留到期时间须晚于当前时间");
            }
            var line = lines.get(request.orderLineId());
            if(line==null || line.get("warehouse_id")==null || !scope.allowsWarehouse(((Number)line.get("warehouse_id")).longValue())) {throw missing();}
            if (((Number)line.get("warehouse_id")).longValue()!=request.key().warehouseId() ||
                    ((Number)line.get("sku_id")).longValue() != request.key().skuId()) {
                throw new ApiException(400, "VALIDATION_FAILED", "预留仓库或商品与订单行不符");
            }
            BigDecimal remaining = decimal(line,"base_qty").subtract(decimal(line,"closed_base_qty"))
                    .subtract(decimal(line,"shipped_base_qty"));
            if (request.quantity().compareTo(remaining) > 0) { throw insufficient(); }
            lines.put(request.orderLineId(), line);
        });
        var balances=store.lockMany(scope,requests.stream().map(ReservationRequest::key).toList(),false);
        var balanceUpdates=new ArrayList<Object[]>();var inserts=new ArrayList<Object[]>();var changes=new ArrayList<Object[]>();var lineUpdates=new ArrayList<Object[]>();
        List<Long> result = new ArrayList<>();
        for (var request : requests) {
            Long active = jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation WHERE tenant_id=? AND company_id=? AND order_line_id=? AND status='active'",
                    Long.class,scope.tenantId(),scope.companyId(),request.orderLineId());
            if (active != null && active > 0) { throw new ApiException(409,"STATE_CONFLICT","该订单行已有有效预留"); }
            var balance=balances.get(request.key());
            var reserved=decimal(balance,"reserved_qty").add(request.quantity());
            if(decimal(balance,"on_hand_qty").subtract(decimal(balance,"blocked_qty")).compareTo(reserved)<0) {throw insufficient();}
            balance.put("reserved_qty",reserved);
            balanceUpdates.add(new Object[]{request.quantity(),scope.tenantId(),scope.companyId(),request.key().warehouseId(),request.key().skuId(),request.quantity()});
            long id=Ids.next();
            inserts.add(new Object[]{id,scope.tenantId(),scope.companyId(),request.orderLineId(),request.key().warehouseId(),request.key().skuId(),request.quantity(),request.quantity(),Timestamp.from(request.expiresAt()),scope.userId()});
            changes.add(new Object[]{Ids.next(),scope.tenantId(),scope.companyId(),id,request.quantity(),((Number)lines.get(request.orderLineId()).get("order_id")).longValue(),request.orderLineId(),scope.userId()});
            lineUpdates.add(new Object[]{scope.tenantId(),scope.companyId(),request.orderLineId()});
            result.add(id);
        }
        for(int changed:jdbc.batchUpdate("""
                    UPDATE inventory_balance SET reserved_qty=reserved_qty+?,version=version+1
                    WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=? AND location_key='' AND lot_key=''
                      AND on_hand_qty-reserved_qty-blocked_qty>=?
                    """,balanceUpdates)) {if(changed!=1) {throw insufficient();}}
        jdbc.batchUpdate("""
                    INSERT INTO inventory_reservation(id,tenant_id,company_id,order_line_id,warehouse_id,sku_id,
                        reserved_qty,remaining_qty,expire_at,created_by) VALUES(?,?,?,?,?,?,?,?,?,?)
                    """,inserts);
        jdbc.batchUpdate("INSERT INTO inventory_reservation_change(id,tenant_id,company_id,reservation_id,change_type,qty_delta,source_type,source_id,source_line_id,created_by) VALUES(?,?,?,?,'reserve',?,'sales_order',?,?,?)",changes);
        jdbc.batchUpdate("UPDATE sales_order_line SET reservation_state='reserved',version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",lineUpdates);
        changed(scope,requests.stream().map(ReservationRequest::key).toList());
        return List.copyOf(result);
    }

    public BigDecimal release(CompanyAccess scope,long reservationId,BigDecimal quantity,String reason,Source source) {
        InventoryNumbers.quantity(quantity);
        if (reason==null || !RELEASES.contains(reason) || source == null || source.type()==null ||
                !source.type().matches("[a-z][a-z0-9_]{0,31}") || source.id()<=0 || source.lineId()<=0) {
            throw new ApiException(400,"VALIDATION_FAILED","预留释放原因或来源无效");
        }
        return releaseInternal(scope,reservationId,quantity,reason,source,null);
    }

    /** 到期任务可以重复调用。被出库消费后返回零。 */
    public BigDecimal expire(CompanyAccess scope,long reservationId,Instant now) {
        if (now == null || now.isAfter(Instant.now().plusSeconds(1))) {
            throw new ApiException(400,"VALIDATION_FAILED","到期检查时间无效");
        }
        return releaseInternal(scope,reservationId,null,"expire",
                new Source("inventory_reservation",reservationId,reservationId),now);
    }

    private BigDecimal releaseInternal(CompanyAccess scope,long id,BigDecimal requested,String reason,Source source,Instant cutoff) {
        var meta = jdbc.queryForList("SELECT order_line_id,warehouse_id,sku_id FROM inventory_reservation WHERE tenant_id=? AND company_id=? AND id=?",
                scope.tenantId(),scope.companyId(),id);
        if (meta.isEmpty()) { throw missing(); }
        var first=meta.getFirst();
        long lineId=((Number)first.get("order_line_id")).longValue();
        store.lockOrderLine(scope,lineId);
        Key key=new Key(((Number)first.get("warehouse_id")).longValue(),((Number)first.get("sku_id")).longValue());
        store.ensureAndLockBalance(scope,key);
        var row=jdbc.queryForMap("SELECT status,remaining_qty,UNIX_TIMESTAMP(expire_at) expire_epoch FROM inventory_reservation WHERE id=? FOR UPDATE",id);
        if (!"active".equals(row.get("status"))) { return BigDecimal.ZERO.setScale(6); }
        if (cutoff != null && Instant.ofEpochMilli(decimal(row,"expire_epoch").movePointRight(3).longValue()).isAfter(cutoff)) {
            return BigDecimal.ZERO.setScale(6);
        }
        BigDecimal quantity=requested==null?decimal(row,"remaining_qty"):requested;
        if (quantity.compareTo(decimal(row,"remaining_qty"))>0) { throw insufficient(); }
        Long existing=jdbc.queryForObject("""
                SELECT COUNT(*) FROM inventory_reservation_change WHERE reservation_id=? AND change_type=? AND source_type=? AND source_line_id=?
                """,Long.class,id,reason,source.type(),source.lineId());
        if(existing!=null && existing>0) { return BigDecimal.ZERO.setScale(6); }
        int changed=jdbc.update("""
                UPDATE inventory_balance SET reserved_qty=reserved_qty-?,version=version+1 WHERE tenant_id=? AND company_id=?
                    AND warehouse_id=? AND sku_id=? AND location_key='' AND lot_key='' AND reserved_qty>=?
                """,quantity,scope.tenantId(),scope.companyId(),key.warehouseId(),key.skuId(),quantity);
        if(changed!=1) { throw insufficient(); }
        jdbc.update("""
                UPDATE inventory_reservation SET remaining_qty=remaining_qty-?,
                  status=IF(remaining_qty=0,?,'active'),version=version+1 WHERE id=? AND status='active' AND remaining_qty>=?
                """,quantity,reason.equals("expire")?"expired":"released",id,quantity);
        store.reservationChange(scope,id,reason,quantity.negate(),source);
        refreshLineState(scope,lineId);
        changed(scope,List.of(key));
        return quantity;
    }

    private void changed(CompanyAccess scope,List<Key> keys) {
        events.publishEvent(new cn.erp.shared.InventoryBalanceChanged(scope.tenantId(),scope.companyId(),
                keys.stream().map(Key::skuId).distinct().sorted().toList()));
    }

    /** 调用方已持有订单行锁；只读取真实预留结果，不为签收或冲销编造到期状态。 */
    public void refreshLineState(CompanyAccess scope,long lineId) {
        var rows=jdbc.queryForList("SELECT status,remaining_qty FROM inventory_reservation WHERE tenant_id=? AND company_id=? AND order_line_id=? ORDER BY id DESC FOR UPDATE",scope.tenantId(),scope.companyId(),lineId);
        jdbc.update("UPDATE sales_order_line SET reservation_state=?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",projectLine(rows).cachedState(),scope.tenantId(),scope.companyId(),lineId);
    }

    public void refreshLineStates(CompanyAccess scope,List<Long> lineIds) {
        if(lineIds.isEmpty()) {return;}
        var ids=lineIds.stream().distinct().sorted().toList();var args=new ArrayList<Object>(List.of(scope.tenantId(),scope.companyId()));args.addAll(ids);
        var grouped=new HashMap<Long,List<Map<String,Object>>>();
        // 排序不能让优化器改走全表主键扫描，否则会锁住其他公司的预留并形成锁环。
        for(var row:jdbc.queryForList("SELECT order_line_id,status,remaining_qty FROM inventory_reservation FORCE INDEX(idx_inventory_reservation_line) WHERE tenant_id=? AND company_id=? AND order_line_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") ORDER BY order_line_id,id DESC FOR UPDATE",args.toArray())) {grouped.computeIfAbsent(((Number)row.get("order_line_id")).longValue(),unused->new ArrayList<>()).add(row);}
        jdbc.batchUpdate("UPDATE sales_order_line SET reservation_state=?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",ids.stream().map(id->new Object[]{projectLine(grouped.getOrDefault(id,List.of())).cachedState(),scope.tenantId(),scope.companyId(),id}).toList());
    }

    public record LineProjection(String state,String cachedState,BigDecimal reservedQty) {}

    /** 有效剩余量优先于较新的历史记录，详情与行缓存共用此规则。记录按编号倒序传入。 */
    public static LineProjection projectLine(List<Map<String,Object>> rows) {
        BigDecimal remaining=rows.stream().filter(row->"active".equals(row.get("status")))
                .map(row->decimal(row,"remaining_qty")).filter(value->value.signum()>0).reduce(BigDecimal.ZERO,BigDecimal::add);
        String state=remaining.signum()>0?"reserved":rows.isEmpty()?"none":rows.getFirst().get("status").toString();
        String cached=Set.of("none","reserved","expired").contains(state)?state:"released";
        return new LineProjection(state,cached,remaining.setScale(6));
    }

    /** 返回当前租户和公司内到期明细，Worker 在每笔独立事务调用 expire。 */
    @Transactional(readOnly=true)
    public List<Long> due(CompanyAccess scope,Instant now,int limit) {
        if(now==null || limit<1 || limit>1000) {throw new ApiException(400,"VALIDATION_FAILED","到期查询参数无效");}
        if(!scope.allWarehouses() && scope.warehouseIds().isEmpty()) {return List.of();}
        var parameters=new ArrayList<Object>(List.of(scope.tenantId(),scope.companyId(),Timestamp.from(now)));
        String warehouseFilter="";
        if(!scope.allWarehouses()) {
            warehouseFilter=" AND warehouse_id IN ("+String.join(",",Collections.nCopies(scope.warehouseIds().size(),"?"))+")";
            parameters.addAll(scope.warehouseIds());
        }
        parameters.add(limit);
        return jdbc.queryForList("""
                SELECT id FROM inventory_reservation WHERE tenant_id=? AND company_id=? AND status='active'
                    AND expire_at<=?
                """+warehouseFilter+" ORDER BY expire_at,id LIMIT ?",Long.class,parameters.toArray());
    }
}
