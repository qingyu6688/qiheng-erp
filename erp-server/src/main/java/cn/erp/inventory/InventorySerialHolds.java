package cn.erp.inventory;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import static cn.erp.inventory.InventoryModels.*;

/** 序列号冻结须跟随当前入库来源，不能只按仓库总冻结量放行或消费其他来源。 */
final class InventorySerialHolds {
    record Origin(String reason,Source source) {}
    private final JdbcTemplate jdbc;
    InventorySerialHolds(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    boolean managedRejection(CompanyAccess actor,Source source) {
        return "sales_return_line".equals(source.type()) && jdbc.queryForObject(
                "SELECT COUNT(*) FROM sales_return WHERE tenant_id=? AND company_id=? AND id=? AND return_mode='sourceless'",Long.class,
                actor.tenantId(),actor.companyId(),source.id())==1;
    }

    static String available(String alias,String hold) {
        String normal="quality_hold".equals(hold)?"1=0":alias+".status IN('in_stock','returned')";
        String reason="quality_hold".equals(hold)?"quality_hold":"ordinary".equals(hold)?"sales_reject":null;
        if(reason==null) return normal;
        return "("+normal+" OR ("+alias+".status='quality_hold' AND EXISTS(SELECT 1 FROM inv_movement_hold serial_hold WHERE serial_hold.tenant_id="+alias+".tenant_id"
                +" AND serial_hold.company_id="+alias+".company_id AND serial_hold.movement_id="+alias+".last_movement_id AND serial_hold.reason='"+reason+"' AND serial_hold.qty_delta>0)))";
    }

    boolean allowed(CompanyAccess actor,Map<String,Object> serial,String hold) {
        if(!"quality_hold".equals(serial.get("status"))) return true;
        String reason="quality_hold".equals(hold)?"quality_hold":"ordinary".equals(hold)?"sales_reject":null;
        return reason!=null && reason.equals(origin(actor,serial).reason());
    }

    Map<Origin,BigDecimal> selected(CompanyAccess actor,Key key,InventoryDimensions.Coordinates at,List<String> codes,String reason,Source exact) {
        var result=new LinkedHashMap<Origin,BigDecimal>();
        for(String code:codes) {
            var rows=jdbc.queryForList("SELECT * FROM inv_serial WHERE tenant_id=? AND company_id=? AND sku_id=? AND serial_no=? FOR UPDATE",
                    actor.tenantId(),actor.companyId(),key.skuId(),code);
            if(rows.size()!=1) throw unavailable();
            var serial=rows.getFirst();
            if(((Number)serial.get("warehouse_id")).longValue()!=key.warehouseId() || !at.location().equals(serial.get("location_key"))
                    || !at.lot().equals(Objects.toString(serial.get("batch_id"),"")) || !List.of("in_stock","returned","quality_hold").contains(serial.get("status"))) throw unavailable();
            if(!"quality_hold".equals(serial.get("status"))) {
                if(exact!=null && "quality_hold".equals(reason)) throw unavailable();
                continue;
            }
            var origin=origin(actor,serial);
            if(!(reason==null?"sales_reject".equals(origin.reason()):reason.equals(origin.reason())) || exact!=null && !exact.equals(origin.source())) throw unavailable();
            result.merge(origin,BigDecimal.ONE,BigDecimal::add);
        }
        return result;
    }

    private Origin origin(CompanyAccess actor,Map<String,Object> serial) {
        var rows=jdbc.queryForList("SELECT reason,source_type,source_id,source_line_id FROM inv_movement_hold WHERE tenant_id=? AND company_id=? AND movement_id=? AND qty_delta>0",
                actor.tenantId(),actor.companyId(),serial.get("last_movement_id"));
        if(rows.size()!=1) throw unavailable();
        var row=rows.getFirst();return new Origin(row.get("reason").toString(),new Source(row.get("source_type").toString(),
                ((Number)row.get("source_id")).longValue(),((Number)row.get("source_line_id")).longValue()));
    }
    private static ApiException unavailable() {return new ApiException(422,"SERIAL_NOT_AVAILABLE","序列号冻结来源或当前状态不符，请重新选择");}
}
