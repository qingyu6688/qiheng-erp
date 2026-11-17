package cn.erp.inventory;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiRows;
import cn.erp.shared.OrgOwnership;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.inventory.InventoryDimensions.today;
import static cn.erp.inventory.InventoryStore.missing;
import static cn.erp.inventory.InventoryDimensions.invalid;

@Service
@Transactional(readOnly=true)
public class InventoryTraceQueries {
    private static final String BATCH_COLUMNS="""
            b.id,b.sku_id,s.code sku_code,s.name sku_name,b.batch_no,b.production_date,b.expiry_date,b.status,
            CASE WHEN b.expiry_date<:today THEN 'expired'
                 WHEN b.expiry_date<=DATE_ADD(:today,INTERVAL s.near_expiry_days DAY) THEN 'near_expiry' ELSE 'valid' END expiry_state
            """;
    private static final String BATCH_FROM=" FROM inv_batch b JOIN sku s ON s.tenant_id=b.tenant_id AND s.id=b.sku_id ";
    private static final String M_SCOPE=" WHERE m.tenant_id=:tenant AND m.company_id=:company ";
    private static final String B_SCOPE=" WHERE b.tenant_id=:tenant AND b.company_id=:company ";
    private final NamedParameterJdbcTemplate jdbc;
    private final InventoryDimensions dimensions;

    InventoryTraceQueries(NamedParameterJdbcTemplate jdbc,InventoryDimensions dimensions){
        this.jdbc=jdbc;
        this.dimensions=dimensions;
    }

    public Map<String,Object> batches(CompanyAccess actor,Long sku,Long warehouse,Integer within,String keyword,Long cursor,int limit){
        var args=pageArgs(actor,cursor,limit).addValue("sku",sku).addValue("warehouse",warehouse).addValue("keyword",keyword);
        if(warehouse!=null&&!actor.allowsWarehouse(warehouse))throw missing();
        if(keyword==null||keyword.length()>100||within!=null&&(within<0||within>3650))throw invalid("批次查询条件无效");
        String sql="SELECT "+BATCH_COLUMNS+BATCH_FROM+B_SCOPE+" AND b.id<:cursor AND "+
                visibleBatch(actor,warehouse!=null);
        if(sku!=null)sql+=" AND b.sku_id=:sku";
        if(!keyword.isBlank())sql+=" AND (LOCATE(:keyword,b.batch_no)>0 OR LOCATE(:keyword,s.code)>0 OR LOCATE(:keyword,s.name)>0)";
        if(within!=null){
            args.addValue("through",today().plusDays(within));
            sql+=" AND b.expiry_date BETWEEN :today AND :through";
        }
        var page=page(jdbc.queryForList(sql+" ORDER BY b.id DESC LIMIT :limit",args),limit);
        @SuppressWarnings("unchecked")
        var items=(List<Map<String,Object>>)page.get("items");
        var balances=balancesByBatch(actor,items.stream().map(item->item.get("id").toString()).toList(),warehouse);
        for(var item:items)item.put("balances",balances.getOrDefault(item.get("id").toString(),List.of()));
        return page;
    }

    public Map<String,Object> batch(CompanyAccess actor,long id,Long cursor,int limit){
        var args=pageArgs(actor,cursor,limit).addValue("id",id);
        var batch=jdbc.queryForList("SELECT "+BATCH_COLUMNS+BATCH_FROM+B_SCOPE+" AND b.id=:id AND "+visibleBatch(actor,false),args)
                .stream().findFirst().map(ApiRows::row).orElseThrow(InventoryStore::missing);
        var result=new LinkedHashMap<>(movementPage(actor,args,"", " AND m.lot_key=CAST(:id AS CHAR)",limit,false));
        result.put("batch",batch);
        String batchId=Long.toString(id);
        result.put("balances",balancesByBatch(actor,List.of(batchId),null).getOrDefault(batchId,List.of()));
        return result;
    }

    public Map<String,Object> serial(CompanyAccess actor,long sku,String number,Long cursor,int limit){
        if(number==null||number.isBlank()||number.length()>100)throw invalid("序列号格式无效");
        var args=pageArgs(actor,cursor,limit).addValue("sku",sku).addValue("code",number.strip());
        var raw=jdbc.queryForList("""
                SELECT a.id,a.sku_id,a.serial_no,a.status,a.warehouse_id,a.location_key,a.batch_id
                FROM inv_serial a WHERE a.tenant_id=:tenant AND a.company_id=:company AND a.sku_id=:sku AND a.serial_no=:code
                AND EXISTS(SELECT 1 FROM inv_movement_serial x JOIN inventory_movement m ON m.id=x.movement_id
                    AND m.tenant_id=x.tenant_id AND m.company_id=x.company_id
                    WHERE x.tenant_id=a.tenant_id AND x.company_id=a.company_id AND x.serial_id=a.id
                """+ warehouse(actor,"m")+sourceFilter(actor,"m")+")",args).stream().findFirst().orElseThrow(InventoryStore::missing);
        args.addValue("serial",raw.get("id"));
        var result=new LinkedHashMap<>(movementPage(actor,args,serialJoin()," AND x.serial_id=:serial",limit,false));
        var identity=ApiRows.row(raw);
        if(!actor.allowsWarehouse(((Number)raw.get("warehouse_id")).longValue())||!actor.orgScope().allDepartments()){
            for(String field:List.of("warehouseId","locationKey","batchId","status"))identity.remove(field);
        }
        result.put("serial",identity);
        return result;
    }

    public Map<String,Object> customer(CompanyAccess actor,long customer,Long sku,Long cursor,int limit){
        var args=pageArgs(actor,cursor,limit).addValue("customer",customer).addValue("sku",sku);
        return movementPage(actor,args,""," AND m.source_type='shipment_line' AND m.movement_type='sales_out' AND sh.customer_id=:customer"
                +" AND sh.status IN('signed','signed_with_diff') AND sl.signed_base_qty>0"
                +" AND (m.lot_key<>'' OR sl.signed_serials IS NOT NULL)"
                +(sku==null?"":" AND m.sku_id=:sku"),limit,true);
    }

    private Map<String,Object> movementPage(CompanyAccess actor,MapSqlParameterSource args,String join,String filter,int limit,boolean customerOnly){
        var rows=jdbc.queryForList("""
                SELECT m.id,m.warehouse_id,w.name warehouse_name,m.sku_id,s.name sku_name,m.location_key,wl.code location_code,
                    b.id batch_id,b.batch_no,m.movement_type,m.posting_action,m.on_hand_delta,m.blocked_delta,m.business_date,m.posted_at,
                    m.source_type,m.source_id,m.source_line_id,gr.receipt_no,pr.return_no purchase_return_no,
                    sh.shipment_no,sh.status shipment_status,sh.sign_date,sh.customer_id,cp.name customer_name,sl.signed_base_qty confirmed_qty,
                    gr.supplier_id,sp.name supplier_name%s
                FROM inventory_movement m
                JOIN sku s ON s.tenant_id=m.tenant_id AND s.id=m.sku_id
                JOIN warehouse w ON w.tenant_id=m.tenant_id AND w.company_id=m.company_id AND w.id=m.warehouse_id
                LEFT JOIN inv_batch b ON b.tenant_id=m.tenant_id AND b.company_id=m.company_id AND b.id=CAST(m.lot_key AS UNSIGNED)
                LEFT JOIN wms_location wl ON wl.tenant_id=m.tenant_id AND wl.company_id=m.company_id
                    AND wl.warehouse_id=m.warehouse_id AND wl.id=CAST(m.location_key AS UNSIGNED)
                LEFT JOIN goods_receipt gr ON m.source_type='goods_receipt_line' AND gr.tenant_id=m.tenant_id AND gr.company_id=m.company_id AND gr.id=m.source_id
                LEFT JOIN business_partner sp ON sp.tenant_id=gr.tenant_id AND sp.id=gr.supplier_id
                LEFT JOIN purchase_return pr ON m.source_type='purchase_return_line' AND pr.tenant_id=m.tenant_id AND pr.company_id=m.company_id AND pr.id=m.source_id
                LEFT JOIN shipment sh ON m.source_type IN('shipment_line','shipment_diff') AND sh.tenant_id=m.tenant_id
                    AND sh.company_id=m.company_id AND sh.id=m.source_id
                LEFT JOIN shipment_line sl ON sl.tenant_id=sh.tenant_id AND sl.company_id=sh.company_id AND sl.shipment_id=sh.id AND sl.id=m.source_line_id
                LEFT JOIN business_partner cp ON cp.tenant_id=sh.tenant_id AND cp.id=sh.customer_id
                """.formatted(join.isEmpty()?"":",x.prior_status,x.current_status")+join+M_SCOPE+" AND m.id<:cursor"+filter+
                warehouse(actor,"m")+sourceFilter(actor,"m")+" ORDER BY m.id DESC LIMIT :limit",args);
        var result=page(rows,limit);
        @SuppressWarnings("unchecked")
        var items=(List<Map<String,Object>>)result.get("items");
        if(items.isEmpty())return result;
        var movementIds=items.stream().map(item->Long.valueOf(item.get("id").toString())).toList();
        args.addValue("movements",movementIds);
        var serials=new HashMap<String,List<String>>();
        if(actor.hasPermission("serial:trace")){
            jdbc.query("""
                    SELECT x.movement_id,s.serial_no FROM inv_movement_serial x JOIN inv_serial s
                    ON s.tenant_id=x.tenant_id AND s.company_id=x.company_id AND s.id=x.serial_id
                    """+(customerOnly?"""
                    JOIN inventory_movement m ON m.id=x.movement_id AND m.tenant_id=x.tenant_id AND m.company_id=x.company_id
                    JOIN shipment_line l ON l.tenant_id=m.tenant_id AND l.company_id=m.company_id AND l.shipment_id=m.source_id AND l.id=m.source_line_id
                    """:"")+"""
                    WHERE x.tenant_id=:tenant AND x.company_id=:company AND x.movement_id IN (:movements)
                    """+(customerOnly?" AND JSON_CONTAINS(l.signed_serials,JSON_QUOTE(s.serial_no),'$.serials') ":"")
                    +(args.hasValue("serial")?" AND x.serial_id=:serial ":"")+"""
                    ORDER BY x.movement_id,s.serial_no
                    """,args,r->{serials.computeIfAbsent(r.getString(1),key->new ArrayList<>()).add(r.getString(2));});
        }
        items.forEach(item->{
            item.put("serials",serials.getOrDefault(item.get("id").toString(),List.of()));
            if ("wms_task_confirmation".equals(item.get("sourceType")) && !actor.hasPermission("wms_task:view")
                    || "wms_move_line".equals(item.get("sourceType")) && !actor.hasPermission("wms_move:view")) {
                item.remove("sourceId");
                item.remove("sourceLineId");
            }
        });
        attachInspections(actor,args,items);
        return result;
    }

    private void attachInspections(CompanyAccess actor,MapSqlParameterSource args,List<Map<String,Object>> items) {
        if(!actor.hasPermission("qc_inspection:view"))return;
        var receiptLines=items.stream().filter(row->"goods_receipt_line".equals(row.get("sourceType")))
                .map(row->Long.valueOf(row.get("sourceLineId").toString())).distinct().toList();
        if(receiptLines.isEmpty())return;
        args.addValue("receiptLines",receiptLines);
        var inspections=new HashMap<String,List<Map<String,Object>>>();
        for(var raw:jdbc.queryForList("""
                SELECT q.id,q.source_line_id,q.inspection_no,q.status,q.result,q.received_qty,q.qualified_qty,q.unqualified_qty,q.concession_qty
                FROM qc_inspection q WHERE q.tenant_id=:tenant AND q.company_id=:company AND q.source_line_id IN (:receiptLines)
                """+warehouse(actor,"q")+OrgOwnership.filter(actor,"q")+" ORDER BY q.id",args)){
            inspections.computeIfAbsent(raw.get("source_line_id").toString(),key->new ArrayList<>()).add(ApiRows.row(raw));
        }
        items.forEach(item->item.put("inspections","goods_receipt_line".equals(item.get("sourceType"))
                ?inspections.getOrDefault(item.get("sourceLineId").toString(),List.of()):List.of()));
    }

    public Map<String,Object> options(CompanyAccess actor,long warehouse,long sku){
        var cfg=dimensions.config(actor,new InventoryModels.Key(warehouse,sku));
        var args=args(actor).addValue("warehouse",warehouse).addValue("sku",sku);
        var result=new LinkedHashMap<String,Object>();
        result.put("batchManaged",cfg.batch());
        result.put("serialManaged",cfg.serial());
        result.put("locationManaged",cfg.location());
        result.put("shelfLifeDays",cfg.shelf());
        result.put("issueStrategy",cfg.strategy());
        result.put("defaultReceivingLocationId",cfg.receiving()==null?null:cfg.receiving().toString());
        result.put("defaultShippingLocationId",cfg.shipping()==null?null:cfg.shipping().toString());
        result.put("locations",cfg.location()?jdbc.queryForList("""
                SELECT l.id,l.code,l.name,z.type FROM wms_location l JOIN wms_zone z
                ON z.tenant_id=l.tenant_id AND z.company_id=l.company_id AND z.warehouse_id=l.warehouse_id AND z.id=l.zone_id
                WHERE l.tenant_id=:tenant AND l.company_id=:company AND l.warehouse_id=:warehouse AND l.status='active' AND z.status='active'
                ORDER BY l.pick_sequence,l.code
                """,args).stream().map(ApiRows::row).toList():List.of());
        result.put("batches",cfg.batch()?jdbc.queryForList("SELECT "+BATCH_COLUMNS+BATCH_FROM+B_SCOPE+
                " AND b.sku_id=:sku AND "+visibleBatch(actor,true)+" ORDER BY b.expiry_date IS NULL,b.expiry_date,b.id LIMIT 200",args)
                .stream().map(ApiRows::row).toList():List.of());
        return result;
    }

    private String visibleBatch(CompanyAccess actor,boolean oneWarehouse){
        String selected=oneWarehouse?" AND m.warehouse_id=:warehouse":"";
        return "EXISTS(SELECT 1 FROM inventory_movement m WHERE m.tenant_id=b.tenant_id AND m.company_id=b.company_id"
                +" AND m.lot_key=CAST(b.id AS CHAR)"+selected+warehouse(actor,"m")+sourceFilter(actor,"m")+")";
    }

    private Map<String,List<Map<String,Object>>> balancesByBatch(CompanyAccess actor,List<String> batches,Long warehouse){
        if(batches.isEmpty())return Map.of();
        var args=args(actor).addValue("batches",batches).addValue("warehouse",warehouse);
        var result=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(var raw:jdbc.queryForList("""
                SELECT x.lot_key,x.warehouse_id,w.name warehouse_name,x.location_key,l.code location_code,x.on_hand_qty,x.blocked_qty,COALESCE(p.qty,0) reserved_qty,
                       IF(b.status='normal' AND (b.expiry_date IS NULL OR b.expiry_date>=:today),GREATEST(x.on_hand_qty-x.blocked_qty-COALESCE(p.qty,0),0),0) available_qty
                FROM inventory_balance x JOIN inv_batch b ON b.tenant_id=x.tenant_id AND b.company_id=x.company_id AND b.id=CAST(x.lot_key AS UNSIGNED)
                JOIN warehouse w ON w.tenant_id=x.tenant_id AND w.company_id=x.company_id AND w.id=x.warehouse_id
                LEFT JOIN wms_location l ON l.tenant_id=x.tenant_id AND l.company_id=x.company_id AND l.warehouse_id=x.warehouse_id AND l.id=CAST(x.location_key AS UNSIGNED)
                LEFT JOIN (
                    SELECT warehouse_id,sku_id,lot_key,location_key,SUM(qty) qty FROM inv_shipment_pick
                    WHERE tenant_id=:tenant AND company_id=:company AND lot_key IN (:batches)
                    GROUP BY warehouse_id,sku_id,lot_key,location_key
                ) p ON p.warehouse_id=x.warehouse_id AND p.sku_id=x.sku_id AND p.lot_key=x.lot_key AND p.location_key=x.location_key
                WHERE x.tenant_id=:tenant AND x.company_id=:company AND x.lot_key IN (:batches) AND x.on_hand_qty>0
                """+warehouse(actor,"x")+(warehouse==null?"":" AND x.warehouse_id=:warehouse")+" ORDER BY x.lot_key,x.warehouse_id,x.location_key",args)){
            result.computeIfAbsent(raw.get("lot_key").toString(),key->new ArrayList<>()).add(ApiRows.row(raw));
        }
        return result;
    }

    private String warehouse(CompanyAccess actor,String alias){
        return actor.allWarehouses()?"":" AND "+alias+".warehouse_id IN (:warehouses)";
    }

    private String sourceFilter(CompanyAccess actor,String alias){
        if(actor.orgScope().allDepartments())return "";
        return " AND ("+OrgOwnership.sourceCondition(actor,alias+".source_type",alias+".source_id")
                +" OR ("+alias+".source_type='stock_misc_line' AND EXISTS(SELECT 1 FROM stock_misc_doc md"
                +" WHERE md.tenant_id=:tenant AND md.company_id=:company AND md.id="+alias+".source_id"+OrgOwnership.filter(actor,"md")+"))"
                +" OR ("+alias+".source_type IN ('wms_task_confirmation','wms_move_line') AND EXISTS(SELECT 1 FROM wms_task wt"
                +" WHERE wt.tenant_id=:tenant AND wt.company_id=:company AND wt.warehouse_id="+alias+".warehouse_id"
                +" AND wt.id="+alias+".source_id"+OrgOwnership.filter(actor,"wt")+")))";
    }

    private MapSqlParameterSource args(CompanyAccess actor){
        return new MapSqlParameterSource().addValue("tenant",actor.tenantId()).addValue("company",actor.companyId())
                .addValue("warehouses",actor.warehouseIds().isEmpty()?List.of(0):actor.warehouseIds()).addValue("today",today());
    }

    private MapSqlParameterSource pageArgs(CompanyAccess actor,Long cursor,int limit){
        if(limit<1||limit>200||cursor!=null&&cursor<=0)throw invalid("追溯分页参数无效");
        return args(actor).addValue("cursor",cursor==null?Long.MAX_VALUE:cursor).addValue("limit",limit+1);
    }

    private Map<String,Object> page(List<Map<String,Object>> rows,int limit){
        var items=rows.stream().limit(limit).map(ApiRows::row).toList();
        var result=new LinkedHashMap<String,Object>();
        result.put("items",items);
        result.put("nextCursor",rows.size()>limit?items.getLast().get("id"):null);
        return result;
    }

    private String serialJoin(){
        return " JOIN inv_movement_serial x ON x.tenant_id=m.tenant_id AND x.company_id=m.company_id AND x.movement_id=m.id ";
    }
}
