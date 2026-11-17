package cn.erp.quality;

import cn.erp.identity.CompanyAccess;
import cn.erp.opening.OpeningService;
import cn.erp.receiving.ReceivingModels;
import cn.erp.receiving.ReceivingService;
import cn.erp.shared.ApiRows;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static cn.erp.quality.QualityModels.*;

@Service
class QualityDispositions {
    private static final ZoneId BUSINESS_ZONE=ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final ReceivingService receiving;
    private final OpeningService opening;

    QualityDispositions(JdbcTemplate jdbc,@Lazy ReceivingService receiving,@Lazy OpeningService opening) {
        this.jdbc=jdbc;
        this.receiving=receiving;
        this.opening=opening;
    }

    List<Map<String,Object>> list(CompanyAccess actor,long inspection){
        return jdbc.queryForList("""
                SELECT d.id,d.round_no,d.action,d.qty,d.target_type,d.target_id,d.reason,d.created_by,d.created_at,
                  COALESCE(r.status,s.status) target_status,COALESCE(r.warehouse_id,s.warehouse_id) warehouse_id,
                  COALESCE(r.owner_user_id,s.owner_user_id) owner_user_id,COALESCE(r.owner_department_id,s.owner_department_id) owner_department_id
                FROM qc_disposition d
                LEFT JOIN purchase_return r ON r.tenant_id=d.tenant_id AND r.company_id=d.company_id AND r.id=d.target_id AND d.target_type='purchase_return'
                LEFT JOIN stock_adjustment s ON s.tenant_id=d.tenant_id AND s.company_id=d.company_id AND s.id=d.target_id AND d.target_type='stock_adjustment'
                WHERE d.tenant_id=? AND d.company_id=? AND d.inspection_id=? ORDER BY d.id
                """,actor.tenantId(),actor.companyId(),inspection).stream().map(row->visibleTarget(actor,row)).toList();
    }

    private Map<String,Object> visibleTarget(CompanyAccess actor,Map<String,Object> row){
        var result=ApiRows.row(row);
        if(row.get("target_id")!=null&&(row.get("warehouse_id")==null||!actor.hasPermission(row.get("target_type")+":view")||
                !actor.allowsWarehouse(id(row,"warehouse_id"))||
                !actor.orgScope().allows(id(row,"owner_department_id"),id(row,"owner_user_id")))){
            result.remove("targetId");
            result.remove("targetStatus");
        }
        result.remove("warehouseId");
        result.remove("ownerUserId");
        result.remove("ownerDepartmentId");
        return result;
    }
    BigDecimal allocated(CompanyAccess actor,Map<String,Object> inspection){
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(d.qty),0) FROM qc_disposition d
                LEFT JOIN purchase_return r ON r.tenant_id=d.tenant_id AND r.company_id=d.company_id AND r.id=d.target_id AND d.target_type='purchase_return'
                LEFT JOIN stock_adjustment s ON s.tenant_id=d.tenant_id AND s.company_id=d.company_id AND s.id=d.target_id AND d.target_type='stock_adjustment'
                WHERE d.tenant_id=? AND d.company_id=? AND d.inspection_id=? AND d.round_no=?
                  AND (d.action='reinspect' OR COALESCE(r.status,s.status) NOT IN('voided','reversed'))
                """,BigDecimal.class,actor.tenantId(),actor.companyId(),inspection.get("id"),inspection.get("round_no"));
    }

    Map<String,Object> linked(CompanyAccess actor,String type,long target){
        return jdbc.queryForList("""
                SELECT q.*,d.id disposition_id,d.qty disposition_qty FROM qc_disposition d
                JOIN qc_inspection q ON q.tenant_id=d.tenant_id AND q.company_id=d.company_id AND q.id=d.inspection_id
                WHERE d.tenant_id=? AND d.company_id=? AND d.target_type=? AND d.target_id=? FOR SHARE
                """,actor.tenantId(),actor.companyId(),type,target).stream().findFirst().orElse(Map.of());
    }

    void create(CompanyAccess actor,Map<String,Object> inspection,String action,BigDecimal qty,String reason){
        long id=Ids.next();
        String type=switch(action){
            case "return" -> "purchase_return";
            case "scrap" -> "stock_adjustment";
            default -> null;
        };
        Long target=type==null?null:draft(actor,inspection,action,qty,reason,"iqc-"+id);
        jdbc.update("""
                INSERT INTO qc_disposition(id,tenant_id,company_id,inspection_id,round_no,action,qty,target_type,target_id,reason,created_by)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """,id,actor.tenantId(),actor.companyId(),inspection.get("id"),inspection.get("round_no"),action,qty,type,target,reason,actor.userId());
    }

    private long draft(CompanyAccess actor,Map<String,Object> inspection,String action,BigDecimal qty,String reason,String key){
        long sku=id(inspection,"sku_id"),warehouse=id(inspection,"warehouse_id");
        var unit=jdbc.queryForObject("SELECT p.base_unit_id FROM sku s JOIN product p ON p.tenant_id=s.tenant_id AND p.id=s.product_id WHERE s.tenant_id=? AND s.id=?",Long.class,actor.tenantId(),sku);
        var day=LocalDate.now(BUSINESS_ZONE);
        if(inspection.get("business_date") instanceof Date original && day.isBefore(original.toLocalDate()))day=original.toLocalDate();
        var selection=QualityInspections.selection(inspection);
        if(action.equals("return")){
            var line=new ReceivingModels.ReturnLine(id(inspection,"source_line_id"),unit,qty.toPlainString(),selection);
            var input=new ReceivingModels.ReturnInput(id(inspection,"supplier_id"),warehouse,day,reason,List.of(line),null);
            return id(receiving.createReturn(actor,key,input).get("id").toString());
        }
        var line=new OpeningService.StockLine(sku,unit,qty.toPlainString(),null,selection);
        var input=new OpeningService.Stock("scrap",warehouse,day,reason,List.of(line),null);
        return id(opening.createStock(actor,key,input).get("id").toString());
    }
}
