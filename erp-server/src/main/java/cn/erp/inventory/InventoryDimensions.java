package cn.erp.inventory;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import java.time.LocalDate;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import static cn.erp.inventory.InventoryModels.*;
import static cn.erp.inventory.InventoryStore.*;

/** 空维度是仓库 SKU 的汇总及锁。启用跟踪后实体数量另外按批次和库位维护。 */
@Component
class InventoryDimensions {
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    InventoryDimensions(JdbcTemplate jdbc,ObjectMapper json) {this.jdbc=jdbc;this.json=json;}

    record Config(boolean batch,boolean serial,int shelf,String strategy,boolean location,Long receiving,Long shipping,String taskMode) {
        boolean tracked(){return batch||serial||location;}
    }
    record Coordinates(Long batch,String lot,String location) {
        boolean physical(){return !lot.isEmpty()||!location.isEmpty();}
        static Coordinates empty(){return new Coordinates(null,"","");}
    }
    record Pick(Coordinates at,List<String> serials) {}
    record HoldChange(String reason,Source source,BigDecimal delta) {}
    private record PickPosition(Key key,Coordinates at) {}
    record MoveSerial(long id, String status, Object previousMovement) {}

    static final class PickPreview {
        private final Map<PickPosition,BigDecimal> quantities=new HashMap<>();
        private final Map<Key,Set<String>> serials=new HashMap<>();

        void add(Key key,Pick pick,BigDecimal quantity) {
            quantities.merge(new PickPosition(key,pick.at()),quantity,BigDecimal::add);
            serials.computeIfAbsent(key,unused->new HashSet<>()).addAll(pick.serials());
        }

        BigDecimal quantity(Key key,Coordinates at) {
            return quantities.getOrDefault(new PickPosition(key,at),BigDecimal.ZERO);
        }

        Set<String> serials(Key key) {
            return serials.getOrDefault(key,Set.of());
        }
    }

    Config config(CompanyAccess scope,Key key) {
        if (!scope.allowsWarehouse(key.warehouseId())) throw missing();
        return jdbc.query("""
                SELECT s.batch_managed,s.serial_managed,s.shelf_life_days,s.issue_strategy,w.location_managed,
                  w.default_receiving_location_id,w.default_shipping_location_id,w.wms_task_mode
                FROM warehouse w JOIN sku s ON s.tenant_id=w.tenant_id AND s.id=?
                WHERE w.tenant_id=? AND w.company_id=? AND w.id=?
                """+(TransactionSynchronizationManager.isCurrentTransactionReadOnly()?"":" FOR SHARE OF w,s"),
                (r,i)->new Config(r.getBoolean(1),r.getBoolean(2),r.getInt(3),r.getString(4),r.getBoolean(5),
                        r.getObject(6,Long.class),r.getObject(7,Long.class),r.getString(8)),key.skuId(),scope.tenantId(),scope.companyId(),key.warehouseId())
                .stream().findFirst().orElseThrow(InventoryStore::missing);
    }

    void lockSettings(CompanyAccess actor,List<Key> keys) {
        if(keys.isEmpty())return;
        if(keys.stream().anyMatch(key->!actor.allowsWarehouse(key.warehouseId())))throw missing();
        lockResource(actor,"warehouse",keys.stream().map(Key::warehouseId).distinct().sorted().toList());
        lockResource(actor,"sku",keys.stream().map(Key::skuId).distinct().sorted().toList());
    }

    Set<Key> trackedKeys(CompanyAccess actor,List<Key> keys){
        if(keys.isEmpty())return Set.of();
        var ordered=keys.stream().distinct().sorted().toList();
        var args=new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId()));
        for(var key:ordered){args.add(key.warehouseId());args.add(key.skuId());}
        return new HashSet<>(jdbc.query("""
                SELECT w.id,s.id FROM warehouse w JOIN sku s ON s.tenant_id=w.tenant_id
                WHERE w.tenant_id=? AND w.company_id=? AND (w.id,s.id) IN (%s)
                AND (w.location_managed OR s.batch_managed OR s.serial_managed) FOR SHARE OF w,s
                """.formatted(String.join(",",Collections.nCopies(ordered.size(),"(?,?)"))),
                (r,i)->new Key(r.getLong(1),r.getLong(2)),args.toArray()));
    }

    private void lockResource(CompanyAccess actor,String resource,List<Long> ids) {
        var args=new ArrayList<Object>();args.add(actor.tenantId());
        boolean warehouse=resource.equals("warehouse");
        if(warehouse)args.add(actor.companyId());
        args.addAll(ids);
        var rows=jdbc.queryForList("SELECT id FROM "+resource+" WHERE tenant_id=?"+(warehouse?" AND company_id=?":"")
                +" AND id IN("+String.join(",",Collections.nCopies(ids.size(),"?"))+") ORDER BY id FOR SHARE",Long.class,args.toArray());
        if(rows.size()!=ids.size())throw missing();
    }

    Pick inbound(CompanyAccess actor,Posting posting,BigDecimal qty,StockSelection input,Long supplier,String hold) {
        var cfg=config(actor,posting.key());
        validateInbound(actor,posting.key(),qty,input,hold,cfg);
        if(!cfg.tracked())return new Pick(Coordinates.empty(),List.of());
        Long batch=cfg.batch()?batchForReceipt(actor,posting,input,supplier,cfg.shelf()):null;
        if(!cfg.batch()&&requestsBatch(input))throw invalid("该商品未启用批次管理");
        Long requested=input==null?null:input.locationId();
        if(cfg.location()&&requested==null&&"quality_hold".equals(hold))requested=quarantine(actor,posting.key().warehouseId());
        String position=location(actor,posting.key(),cfg.location(),requested==null?cfg.receiving():requested);
        return new Pick(new Coordinates(batch,batch==null?"":batch.toString(),position),serials(cfg,input,qty,true));
    }

    void validateInbound(CompanyAccess actor,Key key,BigDecimal qty,StockSelection input,String hold,Config cfg) {
        if(!cfg.tracked()){rejectUnused(input);return;}
        if(cfg.batch()){
            if(input==null)throw invalid("批次管理商品入库必须登记批次");
            if(input.batchId()!=null)requireBatch(actor,key.skuId(),input.batchId());
            else {
                text(input.batchNo(),64,"批次号");
                if(cfg.shelf()>0&&input.productionDate()==null&&input.expiryDate()==null)throw invalid("请登记生产日期或到期日");
            }
        }else if(requestsBatch(input))throw invalid("该商品未启用批次管理");
        Long requested=input==null?null:input.locationId();
        if(cfg.location()&&requested==null&&"quality_hold".equals(hold)) {
            requested=quarantine(actor,key.warehouseId());
            if(requested==null)throw invalid("仓库未配置可用待检库位");
        }
        location(actor,key,cfg.location(),requested==null?cfg.receiving():requested);
        serials(cfg,input,qty,true);
    }

    Pick outgoing(CompanyAccess actor,Posting posting,BigDecimal qty,StockSelection input,String hold,boolean includeOwnPick) {
        return outgoing(actor,posting,qty,input,hold,includeOwnPick,null);
    }

    Pick outgoing(CompanyAccess actor,Posting posting,BigDecimal qty,StockSelection input,String hold,boolean includeOwnPick,PickPreview preview) {
        Key key=posting.key();Config cfg=config(actor,key);
        if(!cfg.tracked()){rejectUnused(input);return new Pick(Coordinates.empty(),List.of());}
        if(input!=null&&(input.batchNo()!=null||input.productionDate()!=null||input.expiryDate()!=null))throw invalid("出库请选择已有批次");
        Long batch=input==null?null:input.batchId();
        Long place=input==null?null:input.locationId();
        if(!cfg.batch()&&batch!=null)throw invalid("该商品未启用批次管理");
        if(cfg.batch()&&batch==null&&"manual".equals(cfg.strategy()))throw invalid("请手工选择出库批次");
        if(batch!=null)requireBatch(actor,key.skuId(),batch);
        if(place!=null)location(actor,key,cfg.location(),place);
        var serials=serials(cfg,input,qty,false);
        var selected=pick(actor,posting,qty,cfg,batch,place,hold,includeOwnPick,serials,preview);
        if(serials.isEmpty()&&cfg.serial())serials=availableSerials(actor,key,selected,qty,preview);
        return new Pick(selected,serials);
    }

    private Coordinates pick(CompanyAccess actor,Posting posting,BigDecimal qty,Config cfg,Long batch,Long place,String hold,boolean includeOwnPick,List<String> serials,PickPreview preview) {
        Key key=posting.key();
        // 序列号列表可以定位批次与库位。锁内再次检查，防止并发占用相同货物。
        var values=new ArrayList<Object>(Arrays.asList(actor.tenantId(),actor.companyId(),key.warehouseId(),key.skuId()));
        StringBuilder sql=new StringBuilder("""
                SELECT b.location_key,b.lot_key,d.id,d.expiry_date,d.status,b.id balance_id,b.on_hand_qty,b.blocked_qty
                FROM inventory_balance b LEFT JOIN inv_batch d ON d.tenant_id=b.tenant_id AND d.company_id=b.company_id
                    AND d.sku_id=b.sku_id AND CAST(d.id AS CHAR)=b.lot_key
                WHERE b.tenant_id=? AND b.company_id=? AND b.warehouse_id=? AND b.sku_id=?
                """);
        if(cfg.batch())sql.append(" AND d.id IS NOT NULL");
        else sql.append(" AND b.lot_key=''");
        if(cfg.location())sql.append(" AND b.location_key<>'' AND EXISTS (SELECT 1 FROM wms_location wl JOIN wms_zone wz ON wz.tenant_id=wl.tenant_id AND wz.company_id=wl.company_id AND wz.warehouse_id=wl.warehouse_id AND wz.id=wl.zone_id WHERE wl.tenant_id=b.tenant_id AND wl.company_id=b.company_id AND wl.warehouse_id=b.warehouse_id AND CAST(wl.id AS CHAR)=b.location_key AND wl.status='active' AND wz.status='active')");
        else sql.append(" AND b.location_key=''");
        if(batch!=null){sql.append(" AND b.lot_key=?");values.add(batch.toString());}
        if(place!=null){sql.append(" AND b.location_key=?");values.add(place.toString());}
        if(hold==null){
            sql.append(" AND (d.id IS NULL OR (d.status='normal' AND (d.expiry_date IS NULL OR d.expiry_date>=?)))");
            values.add(today());
            sql.append(" AND b.on_hand_qty-b.blocked_qty>=?");values.add(qty);
        }
        sql.append(" ORDER BY ");
        if(cfg.batch()){
            if("fefo".equals(cfg.strategy()))sql.append("d.expiry_date IS NULL,d.expiry_date,");
            sql.append("d.created_at,d.id,");
        }
        if(cfg.location()){
            if("shipment_line".equals(posting.source().type()) && !"off".equals(cfg.taskMode())){
                sql.append("b.on_hand_qty-b.blocked_qty,");
            } else if(cfg.shipping()!=null){
                sql.append("b.location_key<>?,");
                values.add(cfg.shipping().toString());
            }
            sql.append("COALESCE((SELECT x.pick_sequence FROM wms_location x WHERE x.tenant_id=b.tenant_id AND x.company_id=b.company_id AND x.warehouse_id=b.warehouse_id AND x.id=CAST(b.location_key AS UNSIGNED)),0),");
        }
        sql.append("b.created_at,b.id LIMIT 500");
        if(preview==null) sql.append(" FOR UPDATE OF b");
        var rows=jdbc.queryForList(sql.toString(),values.toArray());
        for(var row:rows){
            var at=new Coordinates(asLong(row.get("id")),(String)row.get("lot_key"),(String)row.get("location_key"));
            BigDecimal available=hold==null?decimal(row,"on_hand_qty").subtract(decimal(row,"blocked_qty")):held(row.get("balance_id"),hold);
            if(hold==null)available=available.subtract(picked(actor,key,at,includeOwnPick?posting.source().lineId():0,preview==null));
            if(preview!=null)available=available.subtract(preview.quantity(key,at));
            if(available.compareTo(qty)>=0&&(serials.isEmpty()||validSerialPlace(actor,key,at,serials,hold,includeOwnPick,preview)))return at;
        }
        throw new ApiException(422,"PICK_UNAVAILABLE","符合批次、效期和库位的可用数量不足，请拆分发货或选择其他批次");
    }

    private BigDecimal held(Object id,String hold) {
        String filter="ordinary".equals(hold)?"reason IN('legacy','manual','sales_reject')":"reason=?";
        Object[] args="ordinary".equals(hold)?new Object[]{id}:new Object[]{id,hold};
        return sum(jdbc.queryForList("SELECT qty FROM inv_block_detail WHERE balance_id=? AND "+filter+" FOR SHARE",BigDecimal.class,args));
    }

    private BigDecimal picked(CompanyAccess actor,Key key,Coordinates at,long ignore) {
        return picked(actor,key,at,ignore,true);
    }

    private BigDecimal picked(CompanyAccess actor,Key key,Coordinates at,long ignore,boolean lock) {
        return sum(jdbc.queryForList("""
                SELECT qty FROM inv_shipment_pick WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=?
                  AND location_key=? AND lot_key=? AND shipment_line_id<>?
                """+(lock?" FOR SHARE":""),BigDecimal.class,actor.tenantId(),actor.companyId(),key.warehouseId(),key.skuId(),at.location(),at.lot(),ignore));
    }

    void requireUnpicked(CompanyAccess actor,Key key,Coordinates at,BigDecimal quantity){
        requireUnpicked(actor,key,at,quantity,0);
    }

    void requireUnpicked(CompanyAccess actor,Key key,Coordinates at,BigDecimal quantity,long ownShipmentLine){
        if(quantity.signum()<=0||!config(actor,key).tracked())return;
        var amount=picked(actor,key,at,ownShipmentLine);
        if(amount.signum()==0)return;
        var row=jdbc.queryForMap("""
                SELECT on_hand_qty,blocked_qty FROM inventory_balance WHERE tenant_id=? AND company_id=? AND warehouse_id=?
                  AND sku_id=? AND location_key=? AND lot_key=? FOR UPDATE
                """,actor.tenantId(),actor.companyId(),key.warehouseId(),key.skuId(),at.location(),at.lot());
        if(decimal(row,"on_hand_qty").subtract(decimal(row,"blocked_qty")).subtract(amount).compareTo(quantity)<0){
            throw new ApiException(422,"PICK_UNAVAILABLE","此批次或库位已有发货占用，请先处理相关发货单");
        }
    }

    private BigDecimal sum(List<BigDecimal> values){return values.stream().reduce(BigDecimal.ZERO,BigDecimal::add);}

    private boolean validSerialPlace(CompanyAccess actor,Key key,Coordinates at,List<String> serials,String hold,boolean ownPick,PickPreview preview) {
        for(String serial:serials) {
            if(preview!=null&&preview.serials(key).contains(serial)) return false;
            var found=serialRow(actor,key.skuId(),serial,preview==null);
            if(found==null || !Objects.equals(asLong(found.get("batch_id")),at.batch()) || !at.location().equals(found.get("location_key"))
                    || ((Number)found.get("warehouse_id")).longValue()!=key.warehouseId()
                    || !Set.of("in_stock","returned","reserved","quality_hold").contains(found.get("status"))
                    || "quality_hold".equals(found.get("status"))&&!"quality_hold".equals(hold)
                    || "reserved".equals(found.get("status"))&&!ownPick)return false;
        }
        return true;
    }

    private List<String> availableSerials(CompanyAccess actor,Key key,Coordinates at,BigDecimal qty,PickPreview preview) {
        var excluded=preview==null?Set.<String>of():preview.serials(key);
        var rows=jdbc.queryForList("""
                SELECT serial_no FROM inv_serial WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=?
                  AND batch_id<=>? AND location_key=? AND status IN('in_stock','returned') ORDER BY id LIMIT ?
                """+(preview==null?" FOR UPDATE":""),String.class,actor.tenantId(),actor.companyId(),key.warehouseId(),key.skuId(),
                at.batch(),at.location(),qty.intValueExact()+excluded.size()).stream().filter(code->!excluded.contains(code))
                .limit(qty.intValueExact()).toList();
        if(rows.size()!=qty.intValueExact())throw insufficient();
        return rows;
    }

    Coordinates explicit(CompanyAccess scope,Key key,StockSelection input) {
        var cfg=config(scope,key);
        if(input==null){
            if(cfg.batch()||cfg.location())throw invalid("请指定冻结或解冻的批次和库位");
            return Coordinates.empty();
        }
        Long batch=input.batchId();
        if(cfg.batch()){if(batch==null)throw invalid("请选择批次");requireBatch(scope,key.skuId(),batch);}
        else if(requestsBatch(input))throw invalid("该商品未启用批次管理");
        return new Coordinates(batch,batch==null?"":batch.toString(),location(scope,key,cfg.location(),input.locationId()));
    }

    long physicalId(CompanyAccess actor,Key key,Coordinates at,boolean create) {
        if(create)jdbc.update("""
                INSERT INTO inventory_balance(id,tenant_id,company_id,warehouse_id,sku_id,location_key,lot_key)
                VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id
                """,Ids.next(),actor.tenantId(),actor.companyId(),key.warehouseId(),key.skuId(),at.location(),at.lot());
        return jdbc.queryForList("""
                SELECT id FROM inventory_balance WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=?
                  AND location_key=? AND lot_key=? FOR UPDATE
                """,Long.class,actor.tenantId(),actor.companyId(),key.warehouseId(),key.skuId(),at.location(),at.lot())
                .stream().findFirst().orElseThrow(InventoryStore::insufficient);
    }

    void physicalChange(CompanyAccess actor,Key key,Coordinates at,BigDecimal quantity,BigDecimal blocked,boolean create) {
        if(!at.physical())return;
        if(quantity.signum()>0&&!at.location().isEmpty())checkCapacity(actor,key,at,quantity);
        long id=physicalId(actor,key,at,create);
        if(jdbc.update("""
                UPDATE inventory_balance SET on_hand_qty=on_hand_qty+?,blocked_qty=blocked_qty+?,version=version+1
                WHERE id=? AND blocked_qty+?>=0 AND on_hand_qty+?-blocked_qty-?>=0
                """,quantity,blocked,id,blocked,quantity,blocked)!=1)throw insufficient();
    }

    private void checkCapacity(CompanyAccess actor,Key key,Coordinates at,BigDecimal quantity) {
        var rules=jdbc.queryForList("""
                SELECT capacity_qty,mixed_sku_allowed,mixed_batch_allowed FROM wms_location
                WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND id=? AND status='active' FOR UPDATE
                """,actor.tenantId(),actor.companyId(),key.warehouseId(),at.location());
        if(rules.isEmpty())throw missing();
        var rule=rules.getFirst();
        var stock=jdbc.queryForList("""
                SELECT sku_id,lot_key,on_hand_qty FROM inventory_balance WHERE tenant_id=? AND company_id=? AND warehouse_id=?
                  AND location_key=? AND on_hand_qty>0 FOR SHARE
                """,actor.tenantId(),actor.companyId(),key.warehouseId(),at.location());
        if(!Boolean.TRUE.equals(rule.get("mixed_sku_allowed"))&&stock.stream().anyMatch(row->((Number)row.get("sku_id")).longValue()!=key.skuId()))
            throw invalid("库位禁止混放不同商品");
        if(!Boolean.TRUE.equals(rule.get("mixed_batch_allowed"))&&stock.stream().anyMatch(row->((Number)row.get("sku_id")).longValue()==key.skuId()&&!at.lot().equals(row.get("lot_key"))))
            throw invalid("库位禁止混放同一商品的不同批次");
        BigDecimal capacity=(BigDecimal)rule.get("capacity_qty");
        if(capacity!=null&&capacity.signum()>0&&quantity.add(stock.stream().map(row->decimal(row,"on_hand_qty")).reduce(BigDecimal.ZERO,BigDecimal::add)).compareTo(capacity)>0)
            throw invalid("入库数量超过库位容量");
    }

    private Long batchForReceipt(CompanyAccess actor,Posting posting,StockSelection input,Long supplier,int shelf) {
        var key=posting.key();
        if(input.batchId()!=null){
            if(input.batchNo()!=null||input.productionDate()!=null||input.expiryDate()!=null)throw invalid("已有批次无需重复填写批次资料");
            var batch=requireBatch(actor,key.skuId(),input.batchId());
            if("purchase_in".equals(posting.movementType())&&batch.get("supplier_id")!=null&&!Objects.equals(supplier,asLong(batch.get("supplier_id"))))throw invalid("该批次属于其他供应商");
            return input.batchId();
        }
        String code=text(input.batchNo(),64,"批次号");
        LocalDate production=input.productionDate(),expiry=input.expiryDate();
        if(production!=null&&production.isAfter(posting.businessDate()))throw invalid("生产日期不能晚于业务日期");
        if(shelf>0&&production==null&&expiry==null)throw invalid("请登记生产日期或到期日");
        if(expiry==null&&production!=null&&shelf>0)expiry=production.plusDays(shelf);
        if(production!=null&&expiry!=null&&expiry.isBefore(production))throw invalid("到期日不能早于生产日期");
        long requested=Ids.next();
        jdbc.update("""
                INSERT INTO inv_batch(id,tenant_id,company_id,sku_id,batch_no,production_date,expiry_date,supplier_id,source_type,source_id)
                VALUES(?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id
                """,requested,actor.tenantId(),actor.companyId(),key.skuId(),code,production,expiry,supplier,posting.source().type(),posting.source().id());
        var old=jdbc.queryForMap("SELECT id,production_date,expiry_date,supplier_id FROM inv_batch WHERE tenant_id=? AND company_id=? AND sku_id=? AND batch_no=? FOR UPDATE",
                actor.tenantId(),actor.companyId(),key.skuId(),code);
        if(!Objects.equals(date(old.get("production_date")),production)||!Objects.equals(date(old.get("expiry_date")),expiry)
                ||!Objects.equals(asLong(old.get("supplier_id")),supplier))throw invalid("该批次已存在且生产日期、到期日或供应商不同");
        return ((Number)old.get("id")).longValue();
    }

    private Map<String,Object> requireBatch(CompanyAccess actor,long sku,long batch) {
        return jdbc.queryForList("SELECT id,supplier_id,status,expiry_date FROM inv_batch WHERE tenant_id=? AND company_id=? AND sku_id=? AND id=?",
                actor.tenantId(),actor.companyId(),sku,batch).stream().findFirst().orElseThrow(InventoryStore::missing);
    }

    private String location(CompanyAccess actor,Key key,boolean managed,Long place) {
        if(!managed){if(place!=null)throw invalid("该仓库未启用库位管理");return "";}
        if(place==null)throw invalid("请选择所在仓库的库位");
        if(jdbc.queryForList("""
                SELECT l.id FROM wms_location l JOIN wms_zone z ON z.tenant_id=l.tenant_id AND z.company_id=l.company_id
                   AND z.warehouse_id=l.warehouse_id AND z.id=l.zone_id WHERE l.tenant_id=? AND l.company_id=? AND l.warehouse_id=?
                   AND l.id=? AND l.status='active' AND z.status='active'
                """,Long.class,actor.tenantId(),actor.companyId(),key.warehouseId(),place).isEmpty())throw missing();
        return place.toString();
    }

    private Long quarantine(CompanyAccess actor,long warehouse) {
        return jdbc.queryForList("""
                SELECT l.id FROM wms_location l JOIN wms_zone z ON z.tenant_id=l.tenant_id AND z.company_id=l.company_id
                   AND z.warehouse_id=l.warehouse_id AND z.id=l.zone_id WHERE l.tenant_id=? AND l.company_id=? AND l.warehouse_id=?
                   AND l.status='active' AND z.status='active' AND z.type='quarantine' ORDER BY l.pick_sequence,l.id LIMIT 1
                """,Long.class,actor.tenantId(),actor.companyId(),warehouse).stream().findFirst().orElse(null);
    }

    List<String> serials(Config cfg,StockSelection input,BigDecimal qty,boolean required) {
        List<String> inputSerials=input==null||input.serials()==null?List.of():input.serials();
        if(!cfg.serial()) {if(!inputSerials.isEmpty())throw invalid("该商品未启用序列号管理");return List.of();}
        final int count;
        try{count=qty.intValueExact();}catch(ArithmeticException notIntegral){throw invalid("序列号管理商品的基础数量必须为整数");}
        if(count<1||count>500||required&&inputSerials.size()!=count||!inputSerials.isEmpty()&&inputSerials.size()!=count)
            throw invalid("序列号数量必须与基础数量一致，每行最多500个序列号");
        var serials=inputSerials.stream().map(s->text(s,100,"序列号")).sorted().toList();
        if(serials.size()!=new HashSet<>(serials).size())throw invalid("同一行有重复的序列号");
        return serials;
    }

    void serialMovement(CompanyAccess actor,Posting posting,Pick pick,long movement,boolean inbound,String hold) {
        Key key=posting.key();var at=pick.at();
        for(String code:pick.serials()){
            var old=serialRow(actor,key.skuId(),code,true);
            String prior=old==null?null:(String)old.get("status");
            String state=inbound?("quality_hold".equals(hold)?"quality_hold":prior!=null&&prior.equals("shipped")?"returned":"in_stock"):
                    switch(posting.movementType()){
                        case "scrap" -> "scrapped";
                        case "transfer_out" -> "in_transit";
                        case "purchase_return_out" -> "supplier_returned";
                        default -> "shipped";
                    };
            long serial=old==null?Ids.next():((Number)old.get("id")).longValue();
            if(inbound&&old==null) {
                try{jdbc.update("""
                        INSERT INTO inv_serial(id,tenant_id,company_id,sku_id,serial_no,batch_id,warehouse_id,location_key,status,last_movement_id)
                        VALUES(?,?,?,?,?,?,?,?,?,?)
                        """,serial,actor.tenantId(),actor.companyId(),key.skuId(),code,at.batch(),key.warehouseId(),at.location(),state,movement);}
                catch(org.springframework.dao.DuplicateKeyException duplicate){throw serialConflict();}
            }else{
                if(old==null)throw missing();
                if(inbound&&!("reversed".equals(prior)||"in_transit".equals(prior)&&"transfer_in".equals(posting.movementType())
                        ||"shipped".equals(prior)&&Set.of("sales_return_in","sales_reject_in","stock_misc_return_in").contains(posting.movementType())
                        ||"supplier_returned".equals(prior)&&"purchase_in".equals(posting.movementType())))throw serialConflict();
                if(!inbound&&(!Set.of("in_stock","returned","reserved","quality_hold").contains(prior)
                        ||"quality_hold".equals(prior)&&!"quality_hold".equals(hold)
                        ||!Objects.equals(asLong(old.get("batch_id")),at.batch())||!Objects.equals(old.get("location_key"),at.location())
                        ||((Number)old.get("warehouse_id")).longValue()!=key.warehouseId()))throw serialConflict();
                jdbc.update("UPDATE inv_serial SET batch_id=?,warehouse_id=?,location_key=?,status=?,last_movement_id=?,version=version+1 WHERE id=?",
                        at.batch(),key.warehouseId(),at.location(),state,movement,serial);
            }
            linkSerial(actor,movement,serial,"reserved".equals(prior)?statusAtLastMovement(actor,old):prior,state,old==null?null:old.get("last_movement_id"));
            jdbc.update("INSERT IGNORE INTO doc_line_serial(tenant_id,company_id,doc_type,line_id,serial_id) VALUES(?,?,?,?,?)",
                    actor.tenantId(),actor.companyId(),posting.source().type(),posting.source().lineId(),serial);
        }
    }

    private String statusAtLastMovement(CompanyAccess actor,Map<String,Object> row) {
        return jdbc.queryForObject("SELECT current_status FROM inv_movement_serial WHERE tenant_id=? AND company_id=? AND movement_id=? AND serial_id=?",
                String.class,actor.tenantId(),actor.companyId(),row.get("last_movement_id"),row.get("id"));
    }

    void reserveSerials(CompanyAccess actor,Key key,Pick pick){
        for(String code:pick.serials()){
            int changed=jdbc.update("UPDATE inv_serial SET status='reserved',version=version+1 WHERE tenant_id=? AND company_id=? AND sku_id=? AND serial_no=? AND status IN('in_stock','returned')",
                    actor.tenantId(),actor.companyId(),key.skuId(),code);
            if(changed!=1)throw serialConflict();
        }
    }

    void unreserveSerials(CompanyAccess actor,Key key,List<String> codes){
        for(String code:codes){
            var row=serialRow(actor,key.skuId(),code,true);
            if(row!=null&&"reserved".equals(row.get("status")))jdbc.update("UPDATE inv_serial SET status=?,version=version+1 WHERE id=?",
                    statusAtLastMovement(actor,row),row.get("id"));
        }
    }

    void releaseSerials(CompanyAccess actor,Posting posting,Source original,BigDecimal quantity,long movement) {
        if(!config(actor,posting.key()).serial())return;
        if(quantity.stripTrailingZeros().scale()>0)throw invalid("质检释放的序列号数量必须为整数");
        var serials=sourceSerials(actor,original,List.of("quality_hold"));
        if(serials.size()<quantity.intValueExact())throw serialConflict();
        for(String code:serials.subList(0,quantity.intValueExact())){
            var row=serialRow(actor,posting.key().skuId(),code,true);
            if(row==null||!"quality_hold".equals(row.get("status")))throw serialConflict();
            jdbc.update("UPDATE inv_serial SET status='in_stock',last_movement_id=?,version=version+1 WHERE id=?",movement,row.get("id"));
            linkSerial(actor,movement,asLong(row.get("id")),"quality_hold","in_stock",row.get("last_movement_id"));
        }
    }

    List<String> sourceSerials(CompanyAccess actor,Source source,List<String> statuses) {
        return sourceSerials(actor,source,statuses,false);
    }

    List<String> sourceSerials(CompanyAccess actor,Source source,List<String> statuses,boolean exactMovement) {
        var args=new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId(),source.type(),source.lineId()));
        args.addAll(statuses);
        if(exactMovement)args.add(source.id());
        return jdbc.queryForList("""
                SELECT s.serial_no FROM doc_line_serial l JOIN inv_serial s ON s.tenant_id=l.tenant_id AND s.company_id=l.company_id AND s.id=l.serial_id
                WHERE l.tenant_id=? AND l.company_id=? AND l.doc_type=? AND l.line_id=?
                """+(statuses.isEmpty()?"":" AND s.status IN ("+String.join(",",Collections.nCopies(statuses.size(),"?"))+")")+
                (exactMovement?" AND EXISTS (SELECT 1 FROM inventory_movement m WHERE m.tenant_id=l.tenant_id AND m.company_id=l.company_id AND m.id=s.last_movement_id AND m.source_type=l.doc_type AND m.source_line_id=l.line_id AND m.source_id=?)":"")
                +" ORDER BY s.id FOR UPDATE OF s",String.class,args.toArray());
    }

    private void linkSerial(CompanyAccess actor,long movement,long serial,String prior,String state,Object priorMovement) {
        jdbc.update("INSERT INTO inv_movement_serial(tenant_id,company_id,movement_id,serial_id,prior_status,current_status,prior_movement_id) VALUES(?,?,?,?,?,?,?)",
                actor.tenantId(),actor.companyId(),movement,serial,prior,state,priorMovement);
    }

    List<MoveSerial> lockMoveSerials(CompanyAccess actor, Key key, Coordinates from, List<String> codes) {
        return lockMoveSerials(actor,key,from,codes,false);
    }

    List<MoveSerial> lockMoveSerials(CompanyAccess actor,Key key,Coordinates from,List<String> codes,boolean reserved) {
        var locked = new ArrayList<MoveSerial>();
        for (String code : codes) {
            var row = serialRow(actor, key.skuId(), code, true);
            if (row == null || !Objects.equals(asLong(row.get("batch_id")), from.batch())
                    || asLong(row.get("warehouse_id")) != key.warehouseId() || !from.location().equals(row.get("location_key"))
                    || !(reserved ? "reserved".equals(row.get("status")) : Set.of("in_stock","returned").contains(row.get("status")))) {
                throw serialConflict();
            }
            // 暂存流水沿用预留前的库存状态，取消发货时可由最后一条流水恢复该状态。
            locked.add(new MoveSerial(asLong(row.get("id")), reserved ? statusAtLastMovement(actor,row) : (String)row.get("status"),
                    row.get("last_movement_id")));
        }
        return locked;
    }

    void moveSerials(CompanyAccess actor, Source source, Coordinates to, List<MoveSerial> serials, long out, long in) {
        for (var serial : serials) {
            jdbc.update("""
                    UPDATE inv_serial SET location_key=?,last_movement_id=?,version=version+1
                    WHERE tenant_id=? AND company_id=? AND id=?
                    """, to.location(), in, actor.tenantId(), actor.companyId(), serial.id());
            linkSerial(actor, out, serial.id(), serial.status(), serial.status(), serial.previousMovement());
            linkSerial(actor, in, serial.id(), serial.status(), serial.status(), out);
            jdbc.update("""
                    INSERT INTO doc_line_serial(tenant_id,company_id,doc_type,line_id,serial_id) VALUES(?,?,?,?,?)
                    """, actor.tenantId(), actor.companyId(), source.type(), source.lineId(), serial.id());
        }
    }

    Map<String,Object> serialRow(CompanyAccess actor,long sku,String serial,boolean lock) {
        return jdbc.queryForList("SELECT id,batch_id,warehouse_id,location_key,status,last_movement_id FROM inv_serial WHERE tenant_id=? AND company_id=? AND sku_id=? AND serial_no=?"+(lock?" FOR UPDATE":""),
                actor.tenantId(),actor.companyId(),sku,serial).stream().findFirst().orElse(null);
    }

    void addHold(CompanyAccess actor,Key key,Coordinates at,String reason,Source source,BigDecimal qty) {
        long id=physicalId(actor,key,at,false);
        jdbc.update("""
                INSERT INTO inv_block_detail(balance_id,tenant_id,company_id,reason,source_type,source_id,source_line_id,qty)
                VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE qty=qty+VALUES(qty)
                """,id,actor.tenantId(),actor.companyId(),reason,source.type(),source.id(),source.lineId(),qty);
    }

    List<HoldChange> consumeHold(CompanyAccess actor,Key key,Coordinates at,String reason,Source exact,BigDecimal qty) {
        long id=physicalId(actor,key,at,false);
        var args=new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId(),id));
        String extra="";
        if(reason!=null){extra+=" AND reason=?";args.add(reason);}else extra+=" AND reason IN('legacy','manual','sales_reject')";
        if(exact!=null){extra+=" AND source_type=? AND source_id=? AND source_line_id=?";args.add(exact.type());args.add(exact.id());args.add(exact.lineId());}
        var holds=jdbc.queryForList("SELECT reason,source_type,source_id,source_line_id,qty FROM inv_block_detail WHERE tenant_id=? AND company_id=? AND balance_id=? AND qty>0"+extra+" ORDER BY reason,source_type,source_id,source_line_id FOR UPDATE",args.toArray());
        var remaining=qty;
        var changes=new ArrayList<HoldChange>();
        for(var row:holds){
            var used=remaining.min(decimal(row,"qty"));
            jdbc.update("UPDATE inv_block_detail SET qty=qty-? WHERE balance_id=? AND reason=? AND source_type=? AND source_id=? AND source_line_id=?",
                    used,id,row.get("reason"),row.get("source_type"),row.get("source_id"),row.get("source_line_id"));
            remaining=remaining.subtract(used);
            changes.add(new HoldChange((String)row.get("reason"),new Source((String)row.get("source_type"),asLong(row.get("source_id")),asLong(row.get("source_line_id"))),used.negate()));
            if(remaining.signum()==0)return changes;
        }
        throw insufficient();
    }

    void recordHolds(CompanyAccess actor,long movement,List<HoldChange> changes) {
        if(changes.isEmpty())return;
        jdbc.batchUpdate("""
                INSERT INTO inv_movement_hold(tenant_id,company_id,movement_id,reason,source_type,source_id,source_line_id,qty_delta)
                VALUES(?,?,?,?,?,?,?,?)
                """,changes.stream().map(h->new Object[]{actor.tenantId(),actor.companyId(),movement,h.reason(),h.source().type(),
                        h.source().id(),h.source().lineId(),h.delta()}).toList());
    }

    void reverseHolds(CompanyAccess actor,Key key,Coordinates at,Map<String,Object> original,long inverse){
        if(decimal(original,"blocked_delta").signum()==0)return;
        var history=jdbc.query("""
                SELECT reason,source_type,source_id,source_line_id,qty_delta FROM inv_movement_hold
                WHERE tenant_id=? AND company_id=? AND movement_id=? FOR SHARE
                """,(r,i)->new HoldChange(r.getString(1),new Source(r.getString(2),r.getLong(3),r.getLong(4)),r.getBigDecimal(5)),
                actor.tenantId(),actor.companyId(),original.get("id"));
        if(history.isEmpty())history=List.of(new HoldChange("legacy",new Source("migration",0,0),decimal(original,"blocked_delta")));
        for(var old:history){
            if(old.delta().signum()>0)consumeHold(actor,key,at,old.reason(),old.source(),old.delta());
            else addHold(actor,key,at,old.reason(),old.source(),old.delta().negate());
        }
        recordHolds(actor,inverse,history.stream().map(old->new HoldChange(old.reason(),old.source(),old.delta().negate())).toList());
    }

    void reverseSerials(CompanyAccess actor,Key key,Map<String,Object> original,long inverse){
        var history=jdbc.queryForList("""
                SELECT x.*,s.last_movement_id,s.status,prior.warehouse_id prior_warehouse,prior.location_key prior_location,prior.lot_key prior_lot
                FROM inv_movement_serial x
                JOIN inv_serial s ON s.tenant_id=x.tenant_id AND s.company_id=x.company_id AND s.id=x.serial_id
                LEFT JOIN inventory_movement prior ON prior.tenant_id=x.tenant_id AND prior.company_id=x.company_id AND prior.id=x.prior_movement_id
                WHERE x.tenant_id=? AND x.company_id=? AND x.movement_id=? ORDER BY x.serial_id FOR UPDATE OF s
                """,actor.tenantId(),actor.companyId(),original.get("id"));
        for(var row:history){
            if(!Objects.equals(asLong(row.get("last_movement_id")),asLong(original.get("id")))
                    || !Objects.equals(row.get("status"),row.get("current_status")))throw serialConflict();
            String previous=(String)row.get("prior_status"),lot=(String)row.get("prior_lot");
            String status=previous==null?"reversed":previous;
            Object restoreMovement=row.get("prior_movement_id")==null?inverse:row.get("prior_movement_id");
            var fallback=InventoryService.coordinates(original);
            jdbc.update("""
                    UPDATE inv_serial SET batch_id=?,warehouse_id=?,location_key=?,status=?,last_movement_id=?,version=version+1
                    WHERE tenant_id=? AND company_id=? AND id=? AND sku_id=?
                    """,lot==null?fallback.batch():lot.isEmpty()?null:Long.valueOf(lot),row.get("prior_warehouse")==null?key.warehouseId():row.get("prior_warehouse"),
                    row.get("prior_location")==null?fallback.location():row.get("prior_location"),status,restoreMovement,
                    actor.tenantId(),actor.companyId(),row.get("serial_id"),key.skuId());
            linkSerial(actor,inverse,asLong(row.get("serial_id")),(String)row.get("current_status"),status,original.get("id"));
        }
    }

    StockSelection from(Coordinates at,List<String> serials) {
        if(!at.physical()&&serials.isEmpty())return null;
        return new StockSelection(at.batch(),null,null,null,at.location().isEmpty()?null:Long.valueOf(at.location()),serials);
    }

    String encode(StockSelection value){
        if(value==null)return null;
        var row=new LinkedHashMap<String,Object>();
        if(value.batchId()!=null)row.put("batchId",value.batchId().toString());
        if(value.batchNo()!=null)row.put("batchNo",value.batchNo());
        if(value.productionDate()!=null)row.put("productionDate",value.productionDate().toString());
        if(value.expiryDate()!=null)row.put("expiryDate",value.expiryDate().toString());
        if(value.locationId()!=null)row.put("locationId",value.locationId().toString());
        if(value.serials()!=null&&!value.serials().isEmpty())row.put("serials",value.serials());
        return json.writeValueAsString(row);
    }
    StockSelection decode(Object value){return value==null?null:json.readValue(value.toString(),StockSelection.class);}
    Object view(Object value){return value==null?null:json.readTree(value.toString());}
    static boolean requestsBatch(StockSelection value){return value!=null&&(value.batchId()!=null||value.batchNo()!=null||value.productionDate()!=null||value.expiryDate()!=null);}
    void rejectUnused(StockSelection value){if(value!=null&&(requestsBatch(value)||value.locationId()!=null||value.serials()!=null&&!value.serials().isEmpty()))throw invalid("该商品和仓库未启用批次、序列号或库位管理");}
    private String text(String value,int maximum,String name){if(value==null||value.isBlank()||value.strip().length()>maximum||value.codePoints().anyMatch(Character::isISOControl))throw invalid(name+"为空或格式无效");return value.strip();}
    static LocalDate today(){return LocalDate.now(ZONE);}
    static Long asLong(Object value){return value==null?null:((Number)value).longValue();}
    private LocalDate date(Object value){return value instanceof java.sql.Date jdbcDate?jdbcDate.toLocalDate():value instanceof LocalDate day?day:null;}
    static ApiException serialConflict(){return new ApiException(422,"SERIAL_NOT_AVAILABLE","序列号重复、状态不符或已被其他单据占用");}
    static ApiException invalid(String message){return new ApiException(400,"VALIDATION_FAILED",message);}
}
