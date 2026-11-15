package cn.erp.salesorder;

import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryService;
import cn.erp.inventory.ReservationService;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static cn.erp.inventory.InventoryModels.*;
import static cn.erp.salesorder.SalesOrderQueries.*;

/** 订单变更只调整差量，保留未变化的预留及其原始有效期。 */
@Service
class SalesOrderReservations {
    private final JdbcTemplate jdbc;
    private final InventoryService inventory;
    private final ReservationService reservations;
    SalesOrderReservations(JdbcTemplate jdbc,InventoryService inventory,ReservationService reservations) {
        this.jdbc=jdbc;this.inventory=inventory;this.reservations=reservations;
    }

    void adjust(CompanyAccess scope,List<Map<String,Object>> old,List<Map<String,Object>> current,long changeId,
                Instant newExpiry,boolean deliveryChanged) {
        Map<Long,Map<String,Object>> oldById=new HashMap<>(),newById=new HashMap<>();
        old.forEach(line->oldById.put(number(line,"id"),line));current.forEach(line->newById.put(number(line,"id"),line));
        var keys=new ArrayList<Key>();
        for(var line:old) {
            var next=newById.get(number(line,"id"));
            if(next==null||!sameKey(line,next)||decimal(line,"base_qty").compareTo(decimal(next,"base_qty"))!=0) {
                keys.add(key(line));if(next!=null) {keys.add(key(next));}
            }
        }
        for(var line:current) {if(!oldById.containsKey(number(line,"id"))) {keys.add(key(line));}}
        if(!keys.isEmpty()) {inventory.lockKeys(scope,keys);}
        Map<Long,Instant> releasedExpiry=new HashMap<>();
        for(var line:old) {
            var next=newById.get(number(line,"id"));
            if(next==null||!sameKey(line,next)) {
                var reservation=active(scope,number(line,"id"));
                if(reservation!=null) {
                    Instant expiry=instant(reservation.get("expire_at"));
                    if(expiry.isAfter(Instant.now())) {releasedExpiry.put(number(line,"id"),expiry);}
                    release(scope,reservation,decimal(reservation,"remaining_qty"),changeId);
                }
            }
        }
        for(var line:current) {
            long lineId=number(line,"id");var previous=oldById.get(lineId);
            var reservation=active(scope,lineId);
            if(previous==null||!sameKey(previous,line)) {
                BigDecimal needed=remaining(line);
                if(needed.signum()>0) {
                    // 改仓不延长旧的有效预留；新增行使用新预留的正常有效期。
                    Instant expiry=newExpiry;
                    if(previous!=null&&!deliveryChanged) {expiry=releasedExpiry.getOrDefault(lineId,newExpiry);}
                    reservations.reserve(scope,List.of(new ReservationRequest(lineId,key(line),needed,expiry)));
                }
            } else {
                BigDecimal delta=remaining(line).subtract(remaining(previous));
                if(delta.signum()<0&&reservation!=null) {release(scope,reservation,delta.negate().min(decimal(reservation,"remaining_qty")),changeId);}
                if(delta.signum()>0) {increase(scope,line,reservation,delta,newExpiry,new Source("document_change",changeId,Ids.next()));}
            }
            if(deliveryChanged) {
                jdbc.update("UPDATE inventory_reservation SET expire_at=?,version=version+1 WHERE tenant_id=? AND company_id=? AND order_line_id=? AND status='active'",
                        Timestamp.from(newExpiry),scope.tenantId(),scope.companyId(),lineId);
            }
        }
    }

    void reserveAll(CompanyAccess scope,List<Map<String,Object>> lines,Instant expiry,long orderId) {
        inventory.lockKeys(scope,lines.stream().map(SalesOrderReservations::key).toList());
        var fresh=new ArrayList<ReservationRequest>();
        for(var line:lines) {
            var reservation=active(scope,number(line,"id"));
            BigDecimal needed=remaining(line).subtract(reservation==null?BigDecimal.ZERO:decimal(reservation,"remaining_qty"));
            if(needed.signum()>0) {
                if(reservation==null) {fresh.add(new ReservationRequest(number(line,"id"),key(line),needed,expiry));}
                else {increase(scope,line,reservation,needed,expiry,new Source("sales_order",orderId,Ids.next()));}
            }
        }
        if(!fresh.isEmpty()) {reservations.reserve(scope,fresh);}
    }

    private void increase(CompanyAccess scope,Map<String,Object> line,Map<String,Object> reservation,BigDecimal delta,Instant expiry,Source source) {
        long lineId=number(line,"id");
        if(reservation==null) {
            reservations.reserve(scope,List.of(new ReservationRequest(lineId,key(line),delta,expiry)));return;
        }
        Key key=key(line);
        if(jdbc.update("""
            UPDATE inventory_balance SET reserved_qty=reserved_qty+?,version=version+1 WHERE tenant_id=? AND company_id=?
              AND warehouse_id=? AND sku_id=? AND location_key='' AND lot_key='' AND on_hand_qty-reserved_qty-blocked_qty>=?
            """,delta,scope.tenantId(),scope.companyId(),key.warehouseId(),key.skuId(),delta)!=1) {
            throw new ApiException(422,"INVENTORY_INSUFFICIENT","可用库存不足，无法增加订单预留");
        }
        long id=number(reservation,"id");
        jdbc.update("UPDATE inventory_reservation SET reserved_qty=reserved_qty+?,remaining_qty=remaining_qty+?,version=version+1 WHERE id=?",
                delta,delta,id);
        jdbc.update("""
            INSERT INTO inventory_reservation_change(id,tenant_id,company_id,reservation_id,change_type,qty_delta,source_type,source_id,source_line_id,created_by)
            VALUES(?,?,?,?,'reserve',?,?,?,?,?)
            """,Ids.next(),scope.tenantId(),scope.companyId(),id,delta,source.type(),source.id(),source.lineId(),scope.userId());
        jdbc.update("UPDATE sales_order_line SET reservation_state='reserved',version=version+1 WHERE id=?",lineId);
    }

    private void release(CompanyAccess scope,Map<String,Object> reservation,BigDecimal qty,long changeId) {
        if(qty.signum()>0) {reservations.release(scope,number(reservation,"id"),qty,"release_change",new Source("document_change",changeId,Ids.next()));}
    }
    private Map<String,Object> active(CompanyAccess scope,long lineId) {
        var rows=jdbc.queryForList("SELECT * FROM inventory_reservation WHERE tenant_id=? AND company_id=? AND order_line_id=? AND status='active' FOR UPDATE",
                scope.tenantId(),scope.companyId(),lineId);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private static BigDecimal remaining(Map<String,Object> line) {return decimal(line,"base_qty").subtract(decimal(line,"closed_base_qty")).subtract(decimal(line,"shipped_base_qty"));}
    private static Key key(Map<String,Object> line) {return new Key(number(line,"warehouse_id"),number(line,"sku_id"));}
    private static boolean sameKey(Map<String,Object> first,Map<String,Object> second) {return Objects.equals(first.get("warehouse_id"),second.get("warehouse_id"))&&Objects.equals(first.get("sku_id"),second.get("sku_id"));}
    private static Instant instant(Object value) {return value instanceof Timestamp timestamp?timestamp.toInstant():((java.time.LocalDateTime)value).toInstant(java.time.ZoneOffset.UTC);}
}
