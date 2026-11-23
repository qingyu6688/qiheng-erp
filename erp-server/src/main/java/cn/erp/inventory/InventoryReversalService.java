package cn.erp.inventory;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import cn.erp.shared.ReceiptQualityPort;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.inventory.InventoryModels.*;

/** 冲销只追加反向流水，原流水、原成本调整及其他单据的库存占用均保留。 */
@Service
@Transactional
public class InventoryReversalService {
    private final JdbcTemplate jdbc;
    private final InventoryService inventory;
    private final InventoryDimensions dimensions;
    private final ReceiptQualityPort quality;
    public InventoryReversalService(JdbcTemplate jdbc,InventoryService inventory,InventoryDimensions dimensions,ReceiptQualityPort quality) {
        this.jdbc=jdbc;this.inventory=inventory;this.dimensions=dimensions;this.quality=quality;
    }

    public void reverse(CompanyAccess scope,String type,long source,long reversal,long period,LocalDate day) {
        String types=switch(type) {
            case "shipment" -> "'shipment_line','shipment_diff'";
            case "sales_return" -> "'sales_return_line'";
            case "goods_receipt" -> "'goods_receipt_line','goods_receipt_fee'";
            case "purchase_return" -> "'purchase_return_line'";
            case "stock_adjustment" -> "'stock_adjustment'";
            case "stock_misc_doc" -> "'stock_misc_line'";
            case "mfg_issue_doc" -> "'mfg_issue_line'";
            case "mfg_receipt_doc" -> "'mfg_receipt_line'";
            case "purchase_fee_doc" -> "'purchase_fee_cost'";
            case "price_adjustment" -> "'price_adjustment_cost'";
            default -> null;
        };
        if(types==null)return;
        if(type.equals("goods_receipt"))quality.beforeReceiptReversal(scope,source);
        // 冲销命令已锁原业务单据，库存流水和成本事实只取当前读。
        var movements=jdbc.queryForList("SELECT * FROM inventory_movement WHERE tenant_id=? AND company_id=? AND source_id=? AND source_type IN("+types+") AND posting_action='post' ORDER BY warehouse_id,sku_id,id FOR SHARE",scope.tenantId(),scope.companyId(),source);
        var adjustments=jdbc.queryForList("SELECT * FROM cost_adjustment WHERE tenant_id=? AND company_id=? AND source_id=? AND source_type IN("+types+") ORDER BY warehouse_id,sku_id,id FOR SHARE",scope.tenantId(),scope.companyId(),source);
        var changes=new TreeMap<Key,BigDecimal[]>();
        for(var row:movements) {
            var amounts=changes.computeIfAbsent(key(row),ignored->zeros());
            amounts[0]=amounts[0].subtract(number(row,"on_hand_delta"));
            amounts[1]=amounts[1].subtract(number(row,"blocked_delta"));
            amounts[2]=amounts[2].subtract(number(row,"cost_delta"));
        }
        for(var row:adjustments) {
            if(Set.of("purchase_price_issued","purchase_fee_issued").contains(row.get("adjust_type")))continue;
            var amounts=changes.computeIfAbsent(key(row),ignored->zeros());amounts[2]=amounts[2].subtract(number(row,"amount"));
        }
        inventory.lockKeys(scope,new ArrayList<>(changes.keySet()));
        for(var entry:changes.entrySet())apply(scope,entry.getKey(),entry.getValue());
        for(var row:movements.reversed()){
            var key=key(row);
            var at=reversalCoordinates(scope,key,row);
            dimensions.requireUnpicked(scope,key,at,number(row,"on_hand_delta").subtract(number(row,"blocked_delta")));
            dimensions.physicalChange(scope,key,at,number(row,"on_hand_delta").negate(),number(row,"blocked_delta").negate(),false);
            long inverse=appendMovement(scope,row,at,reversal,period,day);
            dimensions.reverseHolds(scope,key,at,row,inverse);
            dimensions.reverseSerials(scope,key,row,inverse);
        }
        for(var row:adjustments) {
            jdbc.update("""
                INSERT INTO cost_adjustment(id,tenant_id,company_id,warehouse_id,sku_id,adjust_type,amount,source_type,source_id,source_line_id,business_date,period_id,created_by)
                VALUES(?,?,?,?,?,?,?,'document_reversal',?,?,?,?,?)
                """,Ids.next(),scope.tenantId(),scope.companyId(),row.get("warehouse_id"),row.get("sku_id"),row.get("adjust_type"),number(row,"amount").negate(),reversal,row.get("id"),day,period,scope.userId());
        }
        for(Key key:changes.keySet()) {
            var inbound=jdbc.queryForList("""
                SELECT m.unit_cost FROM inventory_movement m WHERE m.tenant_id=? AND m.company_id=? AND m.warehouse_id=? AND m.sku_id=?
                AND m.posting_action='post' AND m.on_hand_delta>0 AND m.unit_cost IS NOT NULL
                AND NOT EXISTS(SELECT 1 FROM inventory_movement r WHERE r.reverses_movement_id=m.id)
                ORDER BY m.posted_at DESC,m.id DESC LIMIT 1
                """,BigDecimal.class,scope.tenantId(),scope.companyId(),key.warehouseId(),key.skuId());
            jdbc.update("UPDATE inventory_cost SET last_in_unit_cost=? WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=?",inbound.isEmpty()?null:inbound.getFirst(),scope.tenantId(),scope.companyId(),key.warehouseId(),key.skuId());
        }
        inventory.changed(scope,new ArrayList<>(changes.keySet()));
    }
    private void apply(CompanyAccess scope,Key key,BigDecimal[] changes) {
        var before=jdbc.queryForMap("SELECT on_hand_qty,reserved_qty,blocked_qty FROM inventory_balance WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=? AND location_key='' AND lot_key='' FOR UPDATE",scope.tenantId(),scope.companyId(),key.warehouseId(),key.skuId());
        var cost=jdbc.queryForMap("SELECT qty,total_cost FROM inventory_cost WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=? FOR UPDATE",scope.tenantId(),scope.companyId(),key.warehouseId(),key.skuId());
        BigDecimal qty=number(before,"on_hand_qty").add(changes[0]),blocked=number(before,"blocked_qty").add(changes[1]);
        BigDecimal amount=number(cost,"total_cost").add(changes[2]);
        if(qty.signum()<0||blocked.signum()<0||qty.subtract(number(before,"reserved_qty")).subtract(blocked).signum()<0||amount.signum()<0||qty.signum()==0&&amount.signum()!=0) {
            throw new ApiException(422,"INVENTORY_REVERSAL_NOT_ALLOWED","冲销后库存、冻结量或成本不足，请先处理后续库存业务");
        }
        if(number(cost,"qty").compareTo(number(before,"on_hand_qty"))!=0)throw new ApiException(409,"INVENTORY_COST_MISMATCH","库存数量与成本数量不一致，请先对账");
        jdbc.update("UPDATE inventory_balance SET on_hand_qty=?,blocked_qty=?,version=version+1 WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=? AND location_key='' AND lot_key=''",qty,blocked,scope.tenantId(),scope.companyId(),key.warehouseId(),key.skuId());
        jdbc.update("UPDATE inventory_cost SET qty=?,total_cost=?,version=version+1 WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=?",qty,amount,scope.tenantId(),scope.companyId(),key.warehouseId(),key.skuId());
    }
    private InventoryDimensions.Coordinates reversalCoordinates(CompanyAccess scope,Key key,Map<String,Object> row){
        var original=InventoryService.coordinates(row);
        if(!original.location().isEmpty()||!dimensions.config(scope,key).location())return original;
        var opening=jdbc.queryForList("""
                SELECT location_key FROM inv_location_opening
                WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=? AND lot_key=?
                ORDER BY created_at DESC LIMIT 1
                """,String.class,scope.tenantId(),scope.companyId(),key.warehouseId(),key.skuId(),original.lot());
        if(opening.isEmpty())throw new ApiException(422,"INVENTORY_REVERSAL_NOT_ALLOWED","原库位已转换，请先核对期初库位余额");
        row.put("location_key",opening.getFirst());
        return InventoryService.coordinates(row);
    }

    private long appendMovement(CompanyAccess scope,Map<String,Object> row,InventoryDimensions.Coordinates at,long reversal,long period,LocalDate day) {
        long inverse=Ids.next();
        jdbc.update("""
            INSERT INTO inventory_movement(id,tenant_id,company_id,warehouse_id,sku_id,location_key,lot_key,movement_type,posting_action,on_hand_delta,blocked_delta,unit_cost,cost_delta,source_type,source_id,source_line_id,reverses_movement_id,business_date,period_id,posted_at,created_by)
            VALUES(?,?,?,?,?,?,?,?,'reverse',?,?,?,?,'document_reversal',?,?,?,?,?,UTC_TIMESTAMP(3),?)
            """,inverse,scope.tenantId(),scope.companyId(),row.get("warehouse_id"),row.get("sku_id"),at.location(),at.lot(),row.get("movement_type"),number(row,"on_hand_delta").negate(),number(row,"blocked_delta").negate(),row.get("unit_cost"),number(row,"cost_delta").negate(),reversal,row.get("id"),row.get("id"),day,period,scope.userId());
        return inverse;
    }
    private static Key key(Map<String,Object> row) {return new Key(((Number)row.get("warehouse_id")).longValue(),((Number)row.get("sku_id")).longValue());}
    private static BigDecimal number(Map<String,Object> row,String field) {return (BigDecimal)row.get(field);}
    private static BigDecimal[] zeros(){return new BigDecimal[]{BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO};}
}
