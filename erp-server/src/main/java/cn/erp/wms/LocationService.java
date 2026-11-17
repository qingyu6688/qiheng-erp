package cn.erp.wms;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiRows;
import cn.erp.shared.AuditService;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class LocationService {
    private static final ZoneId BUSINESS_ZONE=ZoneId.of("Asia/Shanghai");
    private static final Set<String> ZONE_TYPES=Set.of("storage","receiving","shipping","quarantine","return");
    private static final int MAX_GENERATED=500;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    LocationService(JdbcTemplate jdbc,AuditService audit) {this.jdbc=jdbc;this.audit=audit;}

    record ZoneInput(String id,String code,String name,String type,boolean enabled,Long version) {}
    record LocationInput(String id,String zoneId,String code,String name,String aisle,String shelf,String layer,String position,
                         BigDecimal capacityQty,Boolean mixedSkuAllowed,Boolean mixedBatchAllowed,Integer pickSequence,
                         boolean enabled,Long version) {}
    record BatchInput(String zoneId,String prefix,int aisles,int shelves,int layers,int positions) {}
    record WarehouseInput(String receivingId,String shippingId,long version) {}

    Map<String,Object> layout(CompanyAccess actor,long warehouse) {
        var wh=visibleWarehouse(actor,warehouse,false);
        var zones=jdbc.queryForList("""
                SELECT id,code,name,type,status,version FROM wms_zone
                WHERE tenant_id=? AND company_id=? AND warehouse_id=? ORDER BY code,id LIMIT 1001
                """,actor.tenantId(),actor.companyId(),warehouse);
        return Map.of("warehouse",ApiRows.row(wh),"zones",zones.stream().map(ApiRows::row).toList(),"locations",locations(actor,warehouse));
    }

    @Transactional
    Map<String,Object> saveZone(CompanyAccess actor,long warehouse,ZoneInput input) {
        visibleWarehouse(actor,warehouse,true);
        if(input.type()==null||!ZONE_TYPES.contains(input.type()))throw invalid("库区用途无效");
        String code=code(input.code(),32),name=text(input.name(),100,"库区名称");
        Long current=id(input.id());
        long target=current==null?Ids.next():current;
        var before=current==null?null:zone(actor,warehouse,current);
        if(before!=null && !input.enabled() && exists("""
                SELECT EXISTS(SELECT 1 FROM wms_location WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND zone_id=? AND status='active')
                """,actor.tenantId(),actor.companyId(),warehouse,target)) throw conflict("库区仍有启用的库位");
        try {
            if(before==null) jdbc.update("INSERT INTO wms_zone(id,tenant_id,company_id,warehouse_id,code,name,type,status) VALUES(?,?,?,?,?,?,?,?)",
                    target,actor.tenantId(),actor.companyId(),warehouse,code,name,input.type(),status(input.enabled()));
            else changed(jdbc.update("""
                    UPDATE wms_zone SET code=?,name=?,type=?,status=?,version=version+1 WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND id=? AND version=?
                    """,code,name,input.type(),status(input.enabled()),actor.tenantId(),actor.companyId(),warehouse,target,input.version()));
        } catch(DuplicateKeyException duplicate) {throw conflict("库区编码已存在");}
        var after=zone(actor,warehouse,target);
        audit.record(actor,"wms_zone.save","wms_zone",target,before,after);
        return ApiRows.row(after);
    }

    @Transactional
    Map<String,Object> saveLocation(CompanyAccess actor,long warehouse,LocationInput input) {
        var wh=visibleWarehouse(actor,warehouse,true);
        long zoneId=requiredId(input.zoneId());
        var zone=zone(actor,warehouse,zoneId);
        if(input.enabled()&&!"active".equals(zone.get("status"))) throw conflict("请选择已启用的库区");
        Long current=id(input.id());
        long target=current==null?Ids.next():current;
        var before=current==null?null:location(actor,warehouse,target);
        if(before!=null&&!input.enabled()) checkDisable(actor,warehouse,target,wh);
        if(before!=null && defaultSlot(wh,target) && !"active".equals(zone.get("status")))
            throw conflict("默认库位须属于启用的库区");
        var data=locationData(input);
        if(before!=null) validateCapacity(data,storedStock(actor,warehouse,Long.toString(target)));
        try {
            if(before==null) insert(actor,warehouse,zoneId,target,data);
            else update(actor,warehouse,zoneId,target,data,input.version());
        } catch(DuplicateKeyException duplicate) {throw conflict("库位编码已存在");}
        var after=location(actor,warehouse,target);
        audit.record(actor,"wms_location.save","wms_location",target,before,after);
        return ApiRows.row(after);
    }

    @Transactional
    Map<String,Object> generate(CompanyAccess actor,long warehouse,BatchInput input) {
        visibleWarehouse(actor,warehouse,true);
        long zone=requiredId(input.zoneId());
        if(!"active".equals(zone(actor,warehouse,zone).get("status"))) throw conflict("请选择已启用的库区");
        String prefix=code(input.prefix(),24);
        int[] dimensions={input.aisles(),input.shelves(),input.layers(),input.positions()};
        if(Arrays.stream(dimensions).anyMatch(count->count<1||count>99)
                ||Arrays.stream(dimensions).reduce(1,(a,b)->a*b)>MAX_GENERATED) throw invalid("每次可生成 1 到 500 个库位，每个维度最多 99");
        var rows=new ArrayList<Object[]>();
        for(int a=1;a<=dimensions[0];a++) for(int s=1;s<=dimensions[1];s++)
            for(int l=1;l<=dimensions[2];l++) for(int p=1;p<=dimensions[3];p++) {
                String code=String.format(Locale.ROOT,"%s-%02d-%02d-%02d-%02d",prefix,a,s,l,p);
                rows.add(new Object[]{Ids.next(),actor.tenantId(),actor.companyId(),warehouse,zone,code,code,
                    Integer.toString(a),Integer.toString(s),Integer.toString(l),Integer.toString(p),rows.size()+1});
            }
        try {
            jdbc.batchUpdate("""
                    INSERT INTO wms_location(id,tenant_id,company_id,warehouse_id,zone_id,code,name,aisle,shelf,layer,position,pick_sequence)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                    """,rows);
        } catch(DuplicateKeyException duplicate) {throw conflict("批量编码和已有库位冲突，本次未新增库位");}
        audit.record(actor,"wms_location.generate","warehouse",warehouse,null,Map.of("prefix",prefix,"count",rows.size()));
        return Map.of("count",rows.size());
    }

    @Transactional
    Map<String,Object> configure(CompanyAccess actor,long warehouse,WarehouseInput input) {
        var before=visibleWarehouse(actor,warehouse,true);
        if(!"active".equals(before.get("status")))throw conflict("请先启用仓库");
        long receiving=requiredId(input.receivingId()), shipping=requiredId(input.shippingId());
        active(actor,warehouse,receiving);
        active(actor,warehouse,shipping);
        if(input.version()!=((Number)before.get("version")).longValue()) throw conflict("仓库设置已变更，请刷新后重试");
        if(!Boolean.TRUE.equals(before.get("location_managed")) && !"1".equals(String.valueOf(before.get("location_managed"))))
            migrate(actor,warehouse,receiving);
        jdbc.update("""
                UPDATE warehouse SET location_managed=1,default_receiving_location_id=?,default_shipping_location_id=?,version=version+1,updated_by=?
                WHERE tenant_id=? AND company_id=? AND id=?
                """,receiving,shipping,actor.userId(),actor.tenantId(),actor.companyId(),warehouse);
        var after=visibleWarehouse(actor,warehouse,false);
        audit.record(actor,"wms_location.configure","warehouse",warehouse,before,after);
        return ApiRows.row(after);
    }

    /** 余额按原锁键顺序保留仓级汇总，生成库位余额和期初移库记录。 */
    private void migrate(CompanyAccess actor,long warehouse,long receiving) {
        var rows=jdbc.queryForList("""
                SELECT id,sku_id,on_hand_qty,reserved_qty,blocked_qty,lot_key FROM inventory_balance
                WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND location_key='' AND (on_hand_qty>0 OR reserved_qty>0)
                ORDER BY sku_id,lot_key FOR UPDATE
                """,actor.tenantId(),actor.companyId(),warehouse);
        if(rows.stream().anyMatch(row->money(row,"reserved_qty").signum()>0))
            throw conflict("请先处理仓库中的未完结预留，再启用库位");
        if(exists("""
                SELECT EXISTS(SELECT 1 FROM inv_block_detail d JOIN inventory_balance b ON b.id=d.balance_id
                    AND b.tenant_id=d.tenant_id AND b.company_id=d.company_id
                    WHERE d.tenant_id=? AND d.company_id=? AND b.warehouse_id=? AND d.reason='quality_hold' AND d.qty>0)
                """,actor.tenantId(),actor.companyId(),warehouse))
            throw conflict("请先处理仓库中的待检冻结，再启用库位");
        var batchSkus=new HashSet<Object>();
        for(var row:rows) if(!row.get("lot_key").equals("")) batchSkus.add(row.get("sku_id"));
        var actual=rows.stream().filter(row->!row.get("lot_key").equals("") || !batchSkus.contains(row.get("sku_id"))).toList();
        validateInitialCapacity(actor,warehouse,receiving,actual);
        for(var row:actual) moveOpening(actor,warehouse,receiving,row);
    }

    private void moveOpening(CompanyAccess actor,long warehouse,long receiving,Map<String,Object> row) {
        String location=Long.toString(receiving), lot=row.get("lot_key").toString();
        Object sku=row.get("sku_id"), before=row.get("id");
        jdbc.update("""
                INSERT INTO inv_location_opening(tenant_id,company_id,warehouse_id,sku_id,location_key,lot_key,on_hand_qty,blocked_qty,operator_id)
                VALUES(?,?,?,?,?,?,?,?,?)
                """,actor.tenantId(),actor.companyId(),warehouse,sku,location,lot,row.get("on_hand_qty"),row.get("blocked_qty"),actor.userId());
        if(lot.isEmpty()) {
            long physical=Ids.next();
            jdbc.update("""
                    INSERT INTO inventory_balance(id,tenant_id,company_id,warehouse_id,sku_id,location_key,lot_key,on_hand_qty,blocked_qty)
                    VALUES(?,?,?,?,?,?, '',?,?)
                    """,physical,actor.tenantId(),actor.companyId(),warehouse,sku,location,row.get("on_hand_qty"),row.get("blocked_qty"));
            jdbc.update("UPDATE inv_block_detail SET balance_id=? WHERE tenant_id=? AND company_id=? AND balance_id=?",
                    physical,actor.tenantId(),actor.companyId(),before);
        } else {
            jdbc.update("UPDATE inventory_balance SET location_key=?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",
                    location,actor.tenantId(),actor.companyId(),before);
        }
        jdbc.update("""
                UPDATE inv_serial SET location_key=?,version=version+1
                WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=? AND location_key='' AND status IN ('in_stock','returned','quality_hold')
                  AND COALESCE(CAST(batch_id AS CHAR),'')=?
                """,location,actor.tenantId(),actor.companyId(),warehouse,sku,lot);
    }

    private void validateInitialCapacity(CompanyAccess actor,long warehouse,long receiving,List<Map<String,Object>> rows) {
        var slot=location(actor,warehouse,receiving);
        validateCapacity(slot.get("capacity_qty"),flag(slot,"mixed_sku_allowed"),flag(slot,"mixed_batch_allowed"),rows);
    }

    private void validateCapacity(Object[] input,List<Map<String,Object>> rows) {
        validateCapacity(input[6],(Boolean)input[7],(Boolean)input[8],rows);
    }

    private void validateCapacity(Object limit,boolean mixedSku,boolean mixedBatch,List<Map<String,Object>> rows) {
        var quantity=rows.stream().map(r->money(r,"on_hand_qty")).reduce(BigDecimal.ZERO,BigDecimal::add);
        if(limit instanceof BigDecimal capacity && quantity.compareTo(capacity)>0)
            throw conflict("现有库存超过库位容量，请调整容量或库存");
        if(!mixedSku && rows.stream().map(r->r.get("sku_id")).distinct().count()>1)
            throw conflict("库位已有多个商品，不能关闭混放商品");
        if(!mixedBatch && rows.stream().map(r->r.get("lot_key")).distinct().count()>1)
            throw conflict("库位已有多个批次，不能关闭混放批次");
    }

    private List<Map<String,Object>> storedStock(CompanyAccess actor,long warehouse,String location) {
        return jdbc.queryForList("""
                SELECT sku_id,lot_key,on_hand_qty FROM inventory_balance WHERE tenant_id=? AND company_id=? AND warehouse_id=?
                  AND location_key=? AND on_hand_qty>0
                """,actor.tenantId(),actor.companyId(),warehouse,location);
    }

    List<Map<String,Object>> balances(CompanyAccess actor,long warehouse,Long location,Long sku,Long cursor,int limit) {
        visibleWarehouse(actor,warehouse,false);
        if(limit<1||limit>200||cursor!=null&&cursor<=0)throw invalid("分页条件无效");
        if(location!=null) location(actor,warehouse,location);
        var rows=jdbc.queryForList("""
                SELECT b.id,b.sku_id,s.code sku_code,s.name sku_name,b.location_key,l.code location_code,b.lot_key,
                       v.batch_no,v.expiry_date,b.on_hand_qty,COALESCE(p.qty,0) reserved_qty,b.blocked_qty,
                       CASE WHEN v.status<>'normal' OR v.expiry_date<? THEN 0
                         ELSE (b.on_hand_qty-COALESCE(p.qty,0)-b.blocked_qty) END available_qty
                FROM inventory_balance b JOIN sku s ON s.tenant_id=b.tenant_id AND s.id=b.sku_id
                 JOIN wms_location l ON l.tenant_id=b.tenant_id AND l.company_id=b.company_id AND l.warehouse_id=b.warehouse_id
                      AND b.location_key=CAST(l.id AS CHAR)
                 LEFT JOIN inv_batch v ON v.tenant_id=b.tenant_id AND v.company_id=b.company_id AND v.sku_id=b.sku_id AND b.lot_key=CAST(v.id AS CHAR)
                 LEFT JOIN (SELECT warehouse_id,sku_id,location_key,lot_key,SUM(qty) qty FROM inv_shipment_pick
                    WHERE tenant_id=? AND company_id=? AND warehouse_id=? GROUP BY warehouse_id,sku_id,location_key,lot_key) p
                    ON p.warehouse_id=b.warehouse_id AND p.sku_id=b.sku_id AND p.location_key=b.location_key AND p.lot_key=b.lot_key
                WHERE b.tenant_id=? AND b.company_id=? AND b.warehouse_id=? AND (? IS NULL OR l.id=?)
                  AND (? IS NULL OR b.sku_id=?) AND b.id<? AND (b.on_hand_qty>0 OR b.reserved_qty>0 OR b.blocked_qty>0)
                ORDER BY b.id DESC LIMIT ?
                """,LocalDate.now(BUSINESS_ZONE),actor.tenantId(),actor.companyId(),warehouse,actor.tenantId(),actor.companyId(),warehouse,
                location,location,sku,sku,cursor==null?Long.MAX_VALUE:cursor,limit);
        return rows.stream().map(ApiRows::row).toList();
    }

    Map<String,Object> label(CompanyAccess actor,long warehouse,long id) {
        var wh=visibleWarehouse(actor,warehouse,false);
        var location=location(actor,warehouse,id);
        return Map.of("id",Long.toString(id),"warehouse",wh.get("name"),"code",location.get("code"),"name",location.get("name"));
    }

    private List<Map<String,Object>> locations(CompanyAccess actor,long warehouse) {
        return jdbc.queryForList("""
                SELECT id,zone_id,code,name,aisle,shelf,layer,position,capacity_qty,mixed_sku_allowed,mixed_batch_allowed,pick_sequence,status,version
                FROM wms_location WHERE tenant_id=? AND company_id=? AND warehouse_id=? ORDER BY zone_id,pick_sequence,code,id LIMIT 3001
                """,actor.tenantId(),actor.companyId(),warehouse).stream().map(ApiRows::row).toList();
    }

    private Map<String,Object> visibleWarehouse(CompanyAccess actor,long warehouse,boolean lock) {
        if(!actor.allowsWarehouse(warehouse)) throw missing();
        return row("SELECT id,code,name,status,location_managed,default_receiving_location_id,default_shipping_location_id,version FROM warehouse "
                +"WHERE tenant_id=? AND company_id=? AND id=?"+(lock?" FOR UPDATE":""),actor.tenantId(),actor.companyId(),warehouse);
    }

    private Map<String,Object> zone(CompanyAccess actor,long warehouse,long id) {
        return row("SELECT id,code,name,type,status,version FROM wms_zone WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND id=?",actor.tenantId(),actor.companyId(),warehouse,id);
    }

    private Map<String,Object> location(CompanyAccess actor,long warehouse,long id) {
        return row("SELECT id,zone_id,code,name,aisle,shelf,layer,position,capacity_qty,mixed_sku_allowed,mixed_batch_allowed,pick_sequence,status,version FROM wms_location "
                +"WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND id=?",actor.tenantId(),actor.companyId(),warehouse,id);
    }

    private void active(CompanyAccess actor,long warehouse,long id) {
        var slot=location(actor,warehouse,id);
        if(!"active".equals(slot.get("status"))||!"active".equals(zone(actor,warehouse,((Number)slot.get("zone_id")).longValue()).get("status")))
            throw conflict("默认库位和所属库区须启用");
    }

    private void checkDisable(CompanyAccess actor,long warehouse,long id,Map<String,Object> wh) {
        if(defaultSlot(wh,id)||exists("""
                SELECT EXISTS(SELECT 1 FROM inventory_balance WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND location_key=? AND (on_hand_qty>0 OR reserved_qty>0))
                """,actor.tenantId(),actor.companyId(),warehouse,Long.toString(id))) throw conflict("默认库位或仍有存量的库位不能停用");
    }

    private boolean defaultSlot(Map<String,Object> warehouse,long id) {
        String value=Long.toString(id);
        return value.equals(Objects.toString(warehouse.get("default_receiving_location_id"),""))
                ||value.equals(Objects.toString(warehouse.get("default_shipping_location_id"),""));
    }

    private Object[] locationData(LocationInput input) {
        BigDecimal capacity=input.capacityQty();
        int order=input.pickSequence()==null?0:input.pickSequence();
        if(capacity!=null&&(capacity.signum()<=0||capacity.precision()-capacity.scale()>18||capacity.scale()>6)||order<0)
            throw invalid("容量必须为正数且最多六位小数，拣货顺序不得为负");
        return new Object[]{code(input.code(),48),text(input.name(),100,"库位名称"),optional(input.aisle()),optional(input.shelf()),
                optional(input.layer()),optional(input.position()),capacity,!Boolean.FALSE.equals(input.mixedSkuAllowed()),
                !Boolean.FALSE.equals(input.mixedBatchAllowed()),order,status(input.enabled())};
    }

    private void insert(CompanyAccess actor,long warehouse,long zone,long id,Object[] data) {
        var args=new ArrayList<Object>(List.of(id,actor.tenantId(),actor.companyId(),warehouse,zone));
        Collections.addAll(args,data);
        jdbc.update("""
                INSERT INTO wms_location(id,tenant_id,company_id,warehouse_id,zone_id,code,name,aisle,shelf,layer,position,
                  capacity_qty,mixed_sku_allowed,mixed_batch_allowed,pick_sequence,status) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,args.toArray());
    }

    private void update(CompanyAccess actor,long warehouse,long zone,long id,Object[] data,Long version) {
        var args=new ArrayList<Object>();args.add(zone);Collections.addAll(args,data);
        Collections.addAll(args,actor.tenantId(),actor.companyId(),warehouse,id,version);
        changed(jdbc.update("""
                UPDATE wms_location SET zone_id=?,code=?,name=?,aisle=?,shelf=?,layer=?,position=?,capacity_qty=?,
                  mixed_sku_allowed=?,mixed_batch_allowed=?,pick_sequence=?,status=?,version=version+1
                WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND id=? AND version=?
                """,args.toArray()));
    }

    private Map<String,Object> row(String sql,Object... args) {
        var rows=jdbc.queryForList(sql,args);
        if(rows.isEmpty()) throw missing();
        return rows.getFirst();
    }
    private boolean exists(String sql,Object... args) {return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args));}
    private static BigDecimal money(Map<String,Object> row,String key) {return (BigDecimal)row.get(key);}
    private static boolean flag(Map<String,Object> row,String key) {return Boolean.TRUE.equals(row.get(key))||"1".equals(String.valueOf(row.get(key)));}
    private static String status(boolean enabled) {return enabled?"active":"disabled";}
    private static void changed(int rows) {if(rows!=1)throw conflict("记录版本已变更，请刷新后重试");}
    private static String code(String value,int limit) {
        String candidate=text(value,limit,"编码");
        if(!candidate.matches("[A-Za-z0-9][A-Za-z0-9_-]*"))throw invalid("编码仅使用英文字母、数字、下划线和连字符");
        return candidate.toUpperCase(Locale.ROOT);
    }
    private static String optional(String value) {return value==null||value.isBlank()?null:text(value,16,"库位分段");}
    private static String text(String value,int max,String label) {
        if(value==null||value.isBlank()||value.strip().length()>max||value.indexOf(0)>=0) throw invalid("请填写有效的"+label);
        return value.strip();
    }
    private static Long id(String value) {
        if(value==null||value.isBlank())return null;
        try {long parsed=Long.parseLong(value);if(parsed>0&&Long.toString(parsed).equals(value))return parsed;}
        catch(NumberFormatException invalidId) {throw invalid("编号无效");}
        throw invalid("编号无效");
    }
    private static long requiredId(String value) {var id=id(value);if(id==null) throw invalid("请选择库区或库位");return id;}
    private static ApiException invalid(String message) {return new ApiException(400,"VALIDATION_FAILED",message);}
    private static ApiException conflict(String message) {return new ApiException(409,"WMS_CONFLICT",message);}
    private static ApiException missing() {return new ApiException(404,"NOT_FOUND","库区、库位或仓库不存在或无权查看");}
}
