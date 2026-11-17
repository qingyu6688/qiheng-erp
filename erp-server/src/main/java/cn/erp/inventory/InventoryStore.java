package cn.erp.inventory;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import static cn.erp.inventory.InventoryModels.*;

@Repository
class InventoryStore {
    final JdbcTemplate jdbc;

    InventoryStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 按唯一维度索引统一锁序；缺失项先创建，避免对空范围反复加间隙锁。 */
    Map<Key,Map<String,Object>> lockMany(CompanyAccess scope,List<Key> keys,boolean cost) {
        return inventoryRows(scope,keys,cost,true);
    }

    Map<Key,Map<String,Object>> readMany(CompanyAccess scope,List<Key> keys,boolean cost) {
        return inventoryRows(scope,keys,cost,false);
    }

    private Map<Key,Map<String,Object>> inventoryRows(CompanyAccess scope,List<Key> keys,boolean cost,boolean lock) {
        var ordered=keys.stream().distinct().sorted().toList();
        if(ordered.isEmpty()) {return Map.of();}
        for(var key:ordered) {if(!scope.allowsWarehouse(key.warehouseId())) {throw missing();}}
        var args=new ArrayList<Object>(List.of(scope.tenantId(),scope.companyId()));
        for(var key:ordered) {args.add(key.warehouseId());args.add(key.skuId());}
        String dimensions="(warehouse_id,sku_id) IN ("+String.join(",",Collections.nCopies(ordered.size(),"(?,?)"))+")";
        String table=cost?"inventory_cost":"inventory_balance";
        String filter="tenant_id=? AND company_id=? AND "+dimensions+(cost?"":" AND location_key='' AND lot_key=''");
        var visibleArgs=new ArrayList<Object>();visibleArgs.add(scope.tenantId());visibleArgs.add(scope.companyId());
        for(var key:ordered) {visibleArgs.add(key.warehouseId());visibleArgs.add(key.skuId());}
        Long visible=jdbc.queryForObject("SELECT COUNT(*) FROM warehouse w JOIN sku s ON s.tenant_id=w.tenant_id WHERE w.tenant_id=? AND w.company_id=? AND (w.id,s.id) IN ("+String.join(",",Collections.nCopies(ordered.size(),"(?,?)"))+")",Long.class,visibleArgs.toArray());
        if(visible==null || visible!=ordered.size()) {throw missing();}
        if(lock) {
            var existing=new HashSet<Key>();
            for(var row:jdbc.queryForList("SELECT warehouse_id,sku_id FROM "+table+" WHERE "+filter,args.toArray())) {existing.add(key(row));}
            var inserts=new ArrayList<Object[]>();
            for(var key:ordered) {if(!existing.contains(key)) {inserts.add(new Object[]{Ids.next(),scope.tenantId(),scope.companyId(),key.warehouseId(),key.skuId()});}}
            if(!inserts.isEmpty()) {jdbc.batchUpdate("INSERT INTO "+table+"(id,tenant_id,company_id,warehouse_id,sku_id) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",inserts);}
        }
        var result=new LinkedHashMap<Key,Map<String,Object>>();
        for(var row:jdbc.queryForList("SELECT * FROM "+table+" FORCE INDEX ("+(cost?"uk_inventory_cost":"uk_inventory_balance")+") WHERE "+filter+" ORDER BY warehouse_id,sku_id"+(lock?" FOR UPDATE":""),args.toArray())) {result.put(key(row),row);}
        return result;
    }

    private static Key key(Map<String,Object> row) {return new Key(((Number)row.get("warehouse_id")).longValue(),((Number)row.get("sku_id")).longValue());}

    void visible(CompanyAccess scope, Key key) {
        if (scope.companyId() <= 0 || key == null || key.warehouseId() <= 0 || key.skuId() <= 0 ||
                !scope.allowsWarehouse(key.warehouseId())) { throw missing(); }
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM warehouse w JOIN sku s ON s.tenant_id=w.tenant_id
                WHERE w.tenant_id=? AND w.company_id=? AND w.id=? AND s.id=?
                """, Long.class, scope.tenantId(), scope.companyId(), key.warehouseId(), key.skuId());
        if (count == null || count == 0) { throw missing(); }
    }

    void lockPeriod(CompanyAccess scope, Posting posting) {
        if (posting == null || posting.source() == null || posting.source().type() == null
                || !posting.source().type().matches("[a-z][a-z0-9_]{0,31}")
                || posting.source().id() <= 0 || posting.source().lineId() <= 0 ||
                posting.businessDate() == null || posting.businessDate().isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")))) {
            throw new ApiException(400, "VALIDATION_FAILED", "库存过账来源或业务日期无效");
        }
        visible(scope, posting.key());
        var rows = jdbc.queryForList("""
                SELECT status,start_date,end_date FROM accounting_period WHERE tenant_id=? AND company_id=? AND id=? FOR SHARE
                """, scope.tenantId(), scope.companyId(), posting.periodId());
        if (rows.isEmpty()) { throw missing(); }
        var period = rows.getFirst();
        if (!"open".equals(period.get("status"))) { throw new ApiException(422, "PERIOD_CLOSED", "业务期间已关闭"); }
        LocalDate start = ((java.sql.Date)period.get("start_date")).toLocalDate();
        LocalDate end = ((java.sql.Date)period.get("end_date")).toLocalDate();
        if (posting.businessDate().isBefore(start) || posting.businessDate().isAfter(end)) {
            throw new ApiException(400, "VALIDATION_FAILED", "业务日期不在所选期间");
        }
    }

    void ensureAndLockBalance(CompanyAccess scope, Key key) {
        visible(scope, key);
        // 已存在的维度直接点锁，避免同一事务反复用新主键 UPSERT 争用聚簇索引尾部。
        var existing=jdbc.queryForList("""
                SELECT id FROM inventory_balance WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=?
                AND location_key='' AND lot_key=''
                """,Long.class,scope.tenantId(),scope.companyId(),key.warehouseId(),key.skuId());
        if(existing.isEmpty())jdbc.update("""
                INSERT INTO inventory_balance(id,tenant_id,company_id,warehouse_id,sku_id) VALUES(?,?,?,?,?)
                ON DUPLICATE KEY UPDATE id=id
                """, Ids.next(), scope.tenantId(), scope.companyId(), key.warehouseId(), key.skuId());
        jdbc.queryForObject("""
                SELECT id FROM inventory_balance WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=?
                AND location_key='' AND lot_key='' FOR UPDATE
                """, Long.class, scope.tenantId(), scope.companyId(), key.warehouseId(), key.skuId());
    }

    Map<String,Object> lockCost(CompanyAccess scope, Key key) {
        var existing=jdbc.queryForList("SELECT id FROM inventory_cost WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=?",
                Long.class,scope.tenantId(),scope.companyId(),key.warehouseId(),key.skuId());
        if(existing.isEmpty())jdbc.update("""
                INSERT INTO inventory_cost(id,tenant_id,company_id,warehouse_id,sku_id) VALUES(?,?,?,?,?)
                ON DUPLICATE KEY UPDATE id=id
                """, Ids.next(), scope.tenantId(), scope.companyId(), key.warehouseId(), key.skuId());
        return jdbc.queryForMap("""
                SELECT id,qty,total_cost,last_in_unit_cost FROM inventory_cost WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=? FOR UPDATE
                """, scope.tenantId(), scope.companyId(), key.warehouseId(), key.skuId());
    }

    Map<String,Object> lockOrderLine(CompanyAccess scope, long lineId) {
        var rows = jdbc.queryForList("""
                SELECT id,order_id,warehouse_id,sku_id,base_qty,closed_base_qty,shipped_base_qty,pending_ship_base_qty
                FROM sales_order_line WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE
                """, scope.tenantId(), scope.companyId(), lineId);
        if (rows.isEmpty()) { throw missing(); }
        var row = rows.getFirst();
        if (row.get("warehouse_id") == null || !scope.allowsWarehouse(((Number)row.get("warehouse_id")).longValue())) {
            throw missing();
        }
        return row;
    }

    void reservationChange(CompanyAccess scope, long id, String type, BigDecimal quantity, Source source) {
        jdbc.update("""
                INSERT INTO inventory_reservation_change(id,tenant_id,company_id,reservation_id,change_type,
                    qty_delta,source_type,source_id,source_line_id,created_by) VALUES(?,?,?,?,?,?,?,?,?,?)
                """, Ids.next(), scope.tenantId(), scope.companyId(), id, type, quantity, source.type(),
                source.id(), source.lineId(), scope.userId());
    }

    long appendMovement(CompanyAccess scope, Posting posting, InventoryDimensions.Coordinates at, BigDecimal quantity,
                        BigDecimal blocked, BigDecimal unitCost, BigDecimal cost) {
        long id = Ids.next();
        jdbc.update("""
                INSERT INTO inventory_movement(id,tenant_id,company_id,warehouse_id,sku_id,location_key,lot_key,movement_type,on_hand_delta,
                    blocked_delta,unit_cost,cost_delta,source_type,source_id,source_line_id,business_date,period_id,posted_at,created_by)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, scope.tenantId(), scope.companyId(), posting.key().warehouseId(), posting.key().skuId(), at.location(),
                at.lot(), posting.movementType(), quantity, blocked, unitCost, cost, posting.source().type(), posting.source().id(),
                posting.source().lineId(), posting.businessDate(), posting.periodId(), Timestamp.from(Instant.now()), scope.userId());
        return id;
    }

    static BigDecimal decimal(Map<String,Object> row, String key) { return (BigDecimal) row.get(key); }
    static ApiException missing() { return new ApiException(404, "NOT_FOUND", "库存资料不存在或无权查看"); }
    static ApiException insufficient() { return new ApiException(422, "INVENTORY_INSUFFICIENT", "可用库存或预留数量不足"); }
}
