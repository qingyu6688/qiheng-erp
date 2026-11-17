package cn.erp.wms;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiRows;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(readOnly=true)
class WmsCodes {
    private static final int MAX_CODE_LENGTH=160;
    private final JdbcTemplate jdbc;

    WmsCodes(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    Map<String,Object> scan(CompanyAccess actor,Long warehouse,String raw) {
        String code=normalize(raw);
        if (warehouse!=null) checkWarehouse(actor,warehouse);
        List<Map<String,Object>> hits;
        if (code.startsWith("L:")) hits=locations(actor,required(warehouse),null,code.substring(2));
        else if (code.startsWith("B:")) hits=batches(actor,required(warehouse),null,code);
        else if (code.startsWith("X:")) hits=boxes(actor,required(warehouse),null,code);
        else {
            hits=new ArrayList<>(skus(actor,null,code));
            if (warehouse!=null) {
                hits.addAll(locations(actor,warehouse,null,code));
                hits.addAll(serials(actor,warehouse,code));
            }
        }
        if (hits.isEmpty()) throw missing();
        if (hits.size()!=1) throw new ApiException(409,"WMS_BARCODE_AMBIGUOUS","该编码匹配多个对象，请使用带类型前缀的库位标签或扫描商品条码");
        return ApiRows.row(hits.getFirst());
    }

    Map<String,Object> label(CompanyAccess actor,Long warehouse,String type,long id) {
        if (warehouse!=null) checkWarehouse(actor,warehouse);
        var rows=switch (Objects.toString(type,"")) {
            case "sku" -> skus(actor,id,null);
            case "location" -> locations(actor,required(warehouse),id,null);
            case "batch" -> batches(actor,required(warehouse),id,null);
            case "box" -> boxes(actor,required(warehouse),id,null);
            default -> throw invalid("该类型不能打印标签");
        };
        return rows.stream().findFirst().map(ApiRows::row).orElseThrow(WmsCodes::missing);
    }

    private List<Map<String,Object>> skus(CompanyAccess actor,Long id,String code) {
        return jdbc.queryForList("""
                SELECT 'sku' type,s.id,s.id sku_id,s.code sku_code,s.name,
                       COALESCE(b.barcode,s.code) code,b.unit_id
                FROM sku s JOIN product p ON p.tenant_id=s.tenant_id AND p.id=s.product_id AND p.status='active'
                JOIN product_category c ON c.tenant_id=p.tenant_id AND c.id=p.category_id AND c.status='active'
                LEFT JOIN sku_barcode b ON b.tenant_id=s.tenant_id AND b.sku_id=s.id AND b.id=(
                    SELECT x.id FROM sku_barcode x JOIN unit u ON u.tenant_id=x.tenant_id AND u.id=x.unit_id AND u.status='active'
                    WHERE x.tenant_id=s.tenant_id AND x.sku_id=s.id AND (? IS NULL OR x.barcode=?)
                    ORDER BY x.id LIMIT 1)
                WHERE s.tenant_id=? AND s.status='active'
                    AND ((? IS NOT NULL AND s.id=?) OR (? IS NOT NULL AND (b.barcode=? OR s.code=?)))
                ORDER BY s.id LIMIT 2
                """,code,code,actor.tenantId(),id,id,code,code,code);
    }

    private List<Map<String,Object>> locations(CompanyAccess actor,long warehouse,Long id,String code) {
        return jdbc.queryForList("""
                SELECT 'location' type,l.id,l.id location_id,l.warehouse_id,l.name,CONCAT('L:',l.code) code
                FROM wms_location l JOIN wms_zone z ON z.tenant_id=l.tenant_id AND z.company_id=l.company_id
                    AND z.warehouse_id=l.warehouse_id AND z.id=l.zone_id AND z.status='active'
                WHERE l.tenant_id=? AND l.company_id=? AND l.warehouse_id=? AND l.status='active'
                    AND (l.id=? OR l.code=?) ORDER BY l.id LIMIT 2
                """,actor.tenantId(),actor.companyId(),warehouse,id,code);
    }

    private List<Map<String,Object>> batches(CompanyAccess actor,long warehouse,Long id,String code) {
        return jdbc.queryForList("""
                SELECT 'batch' type,b.id,b.id batch_id,s.id sku_id,s.name,b.batch_no,CONCAT('B:',s.code,'|',b.batch_no) code
                FROM inv_batch b JOIN sku s ON s.tenant_id=b.tenant_id AND s.id=b.sku_id
                WHERE b.tenant_id=? AND b.company_id=? AND (b.id=? OR CONCAT('B:',s.code,'|',b.batch_no)=?)
                  AND EXISTS(SELECT 1 FROM inventory_balance x WHERE x.tenant_id=b.tenant_id AND x.company_id=b.company_id
                    AND x.warehouse_id=? AND x.sku_id=s.id AND x.lot_key=CAST(b.id AS CHAR))
                ORDER BY b.id LIMIT 2
                """,actor.tenantId(),actor.companyId(),id,code,warehouse);
    }

    private List<Map<String,Object>> serials(CompanyAccess actor,long warehouse,String code) {
        return jdbc.queryForList("""
                SELECT 'serial' type,r.id,s.id sku_id,r.batch_id,r.serial_no,r.serial_no code,s.name,
                    NULLIF(r.location_key,'') location_id
                FROM inv_serial r JOIN sku s ON s.tenant_id=r.tenant_id AND s.id=r.sku_id
                WHERE r.tenant_id=? AND r.company_id=? AND r.warehouse_id=? AND r.serial_no=?
                  AND r.status IN ('in_stock','returned','reserved','quality_hold')
                ORDER BY r.id LIMIT 2
                """,actor.tenantId(),actor.companyId(),warehouse,code);
    }

    private List<Map<String,Object>> boxes(CompanyAccess actor,long warehouse,Long id,String code) {
        var sql="""
                SELECT 'box' type,b.id,h.id shipment_id,b.box_no name,
                    CONCAT('X:',h.shipment_no,'-',b.box_no) code
                FROM wms_pack b JOIN shipment h ON h.tenant_id=b.tenant_id AND h.company_id=b.company_id AND h.id=b.shipment_id
                WHERE b.tenant_id=? AND b.company_id=? AND h.warehouse_id=? AND b.status='done'
                  AND (b.id=? OR CONCAT('X:',h.shipment_no,'-',b.box_no)=?) AND (%s)
                ORDER BY b.id LIMIT 2
                """.formatted(actor.orgScope().ownerFilter("h"));
        return jdbc.queryForList(sql,actor.tenantId(),actor.companyId(),warehouse,id,code);
    }

    private void checkWarehouse(CompanyAccess actor,long warehouse) {
        if (warehouse<=0 || !actor.allowsWarehouse(warehouse) || !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM warehouse WHERE tenant_id=? AND company_id=? AND id=? AND status='active')
                """,Boolean.class,actor.tenantId(),actor.companyId(),warehouse))) throw missing();
    }

    private static long required(Long warehouse) {
        if (warehouse==null) throw invalid("请先选择仓库");
        return warehouse;
    }

    private static String normalize(String input) {
        String code=Objects.toString(input,"").strip();
        if (code.isEmpty() || code.length()>MAX_CODE_LENGTH || code.codePoints().anyMatch(Character::isISOControl))
            throw invalid("请输入有效的扫码编码");
        return code;
    }

    private static ApiException invalid(String message) { return new ApiException(422,"VALIDATION_FAILED",message); }
    private static ApiException missing() { return new ApiException(404,"NOT_FOUND","编码不存在或不在授权范围内"); }
}
