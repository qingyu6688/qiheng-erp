package cn.erp.mfg;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiRows;
import cn.erp.shared.OrgOwnership;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import static cn.erp.mfg.MfgPlanning.*;

@Component
class MfgOrders {
    private static final Set<String> STATUSES=Set.of("draft","released","in_progress","completed","closed","cancelled");
    private static final TypeReference<List<String>> SKU_PATH=new TypeReference<>() {};
    private static final String HEAD="""
            SELECT o.*,s.code sku_code,s.name sku_name,u.name unit_name,b.version_no bom_version_no,
                iw.code issue_warehouse_code,rw.code receive_warehouse_code
            FROM mfg_work_order o JOIN sku s ON s.tenant_id=o.tenant_id AND s.id=o.sku_id
            JOIN unit u ON u.tenant_id=o.tenant_id AND u.id=o.unit_id
            JOIN mfg_bom b ON b.tenant_id=o.tenant_id AND b.company_id=o.company_id AND b.id=o.bom_id
            JOIN warehouse iw ON iw.tenant_id=o.tenant_id AND iw.company_id=o.company_id AND iw.id=o.issue_warehouse_id
            JOIN warehouse rw ON rw.tenant_id=o.tenant_id AND rw.company_id=o.company_id AND rw.id=o.receive_warehouse_id
            WHERE o.tenant_id=? AND o.company_id=?
            """;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    MfgOrders(JdbcTemplate jdbc,ObjectMapper json) {this.jdbc=jdbc; this.json=json;}

    Map<String,Object> header(CompanyAccess actor,long id,boolean lock) {
        var sql=new StringBuilder(HEAD).append(" AND o.id=?");
        var args=new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId(),id));
        appendScope(actor,"o",sql,args);
        if(lock) sql.append(" FOR UPDATE OF o");
        var order=jdbc.queryForList(sql.toString(),args.toArray()).stream().findFirst().orElseThrow(MfgPlanning::missing);
        if(lock && !actor.allWarehouses()) {
            var warehouses=jdbc.queryForList("""
                    SELECT issue_warehouse_id FROM mfg_work_order_material
                    WHERE tenant_id=? AND company_id=? AND work_order_id=? FOR SHARE
                    """,Long.class,actor.tenantId(),actor.companyId(),id);
            if(warehouses.stream().anyMatch(warehouse->!actor.allowsWarehouse(warehouse))) throw missing();
        }
        return order;
    }

    static void appendScope(CompanyAccess actor,String alias,StringBuilder sql,List<Object> params) {
        sql.append(OrgOwnership.filter(actor,alias));
        if(actor.allWarehouses()) return;
        if(actor.warehouseIds().isEmpty()) {sql.append(" AND 1=0");return;}
        String marks=String.join(",",Collections.nCopies(actor.warehouseIds().size(),"?"));
        sql.append(" AND ").append(alias).append(".issue_warehouse_id IN (").append(marks).append(") AND ")
                .append(alias).append(".receive_warehouse_id IN (").append(marks).append(")");
        params.addAll(actor.warehouseIds()); params.addAll(actor.warehouseIds());
        sql.append(" AND NOT EXISTS (SELECT 1 FROM mfg_work_order_material mm WHERE mm.tenant_id=").append(alias).append(".tenant_id")
                .append(" AND mm.company_id=").append(alias).append(".company_id AND mm.work_order_id=").append(alias)
                .append(".id AND mm.issue_warehouse_id NOT IN (").append(marks).append("))");
        params.addAll(actor.warehouseIds());
    }

    Map<String,Object> list(CompanyAccess actor,Long warehouse,String status,String keyword,Long cursor,int limit) {
        page(cursor,limit);
        if(status!=null && !STATUSES.contains(status)) throw invalid("生产工单状态无效");
        var sql=new StringBuilder(HEAD);
        var args=new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId()));
        appendScope(actor,"o",sql,args);
        if(warehouse!=null) {
            if(!actor.allowsWarehouse(id(warehouse,"仓库"))) throw missing();
            sql.append(" AND (o.issue_warehouse_id=? OR o.receive_warehouse_id=? OR EXISTS (SELECT 1 FROM mfg_work_order_material m")
                    .append(" WHERE m.tenant_id=o.tenant_id AND m.company_id=o.company_id AND m.work_order_id=o.id AND m.issue_warehouse_id=?))");
            args.addAll(List.of(warehouse,warehouse,warehouse));
        }
        if(status!=null) {sql.append(" AND o.status=?");args.add(status);}
        if(cursor!=null) {sql.append(" AND o.id<?");args.add(cursor);}
        String search=text(keyword,100,"查询词",false);
        if(search!=null) {
            sql.append(" AND (LOCATE(?,o.document_no)>0 OR LOCATE(?,s.code)>0 OR LOCATE(?,s.name)>0)");
            args.addAll(List.of(search,search,search));
        }
        args.add(limit+1);
        return page(jdbc.queryForList(sql+" ORDER BY o.id DESC LIMIT ?",args.toArray()),limit);
    }

    List<Map<String,Object>> lines(CompanyAccess actor,long id) {return lines(actor,id,false);}

    List<Map<String,Object>> lines(CompanyAccess actor,long id,boolean lock) {
        return jdbc.queryForList("""
                SELECT m.*,s.name sku_name,s.code sku_code,u.name unit_name,w.code issue_warehouse_code
                FROM mfg_work_order_material m JOIN sku s ON s.tenant_id=m.tenant_id AND s.id=m.component_sku_id
                JOIN unit u ON u.tenant_id=m.tenant_id AND u.id=m.unit_id
                JOIN warehouse w ON w.tenant_id=m.tenant_id AND w.company_id=m.company_id AND w.id=m.issue_warehouse_id
                WHERE m.tenant_id=? AND m.company_id=? AND m.work_order_id=? ORDER BY m.line_no
                """+(lock?" FOR UPDATE OF m":""),actor.tenantId(),actor.companyId(),id);
    }

    Map<String,Object> detail(CompanyAccess actor,long id) {
        var result=ApiRows.row(header(actor,id,false));
        var alternatives=jdbc.queryForList("""
                SELECT a.*,s.code sku_code,s.name sku_name FROM mfg_work_order_substitute a
                JOIN mfg_work_order_material m ON m.tenant_id=a.tenant_id AND m.company_id=a.company_id AND m.id=a.work_order_material_id
                JOIN sku s ON s.tenant_id=a.tenant_id AND s.id=a.substitute_sku_id
                WHERE m.tenant_id=? AND m.company_id=? AND m.work_order_id=? ORDER BY a.priority,a.id
                """,actor.tenantId(),actor.companyId(),id).stream().collect(Collectors.groupingBy(
                        item->number(item,"work_order_material_id"),Collectors.mapping(ApiRows::row,Collectors.toList())));
        result.put("materials",lines(actor,id).stream().map(material->{
            var row=ApiRows.row(material);
            row.remove("pathJson");
            row.put("path",json.readValue(material.get("path_json").toString(),SKU_PATH));
            row.put("componentSkuCode",material.get("sku_code")); row.put("componentSkuName",material.get("sku_name"));
            row.put("remainingQty",decimal(material,"required_qty").subtract(decimal(material,"issued_qty")).add(decimal(material,"returned_qty")).toPlainString());
            row.put("substitutes",alternatives.getOrDefault(number(material,"id"),List.of()));
            return row;
        }).toList());
        if(actor.hasPermission("mfg_issue:view")) result.put("issues",activity(actor,id,true));
        if(actor.hasPermission("mfg_receipt:view")) result.put("receipts",activity(actor,id,false));
        return result;
    }

    private List<Map<String,Object>> activity(CompanyAccess actor,long id,boolean issue) {
        String documentTable=issue?"mfg_issue_doc":"mfg_receipt_doc";
        var sql=new StringBuilder("SELECT d.id,d.document_no,d.warehouse_id,d.business_date,d.status,d.version,d.posted_at"
                +(issue?",d.direction,d.over_issue":"")+" FROM "+documentTable+" d WHERE d.tenant_id=? AND d.company_id=? AND d.work_order_id=?")
                .append(OrgOwnership.filter(actor,"d"));
        var args=new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId(),id));
        if(!actor.allWarehouses()) {
            if(actor.warehouseIds().isEmpty()) return List.of();
            sql.append(" AND d.warehouse_id IN (").append(String.join(",",Collections.nCopies(actor.warehouseIds().size(),"?"))).append(")");
            args.addAll(actor.warehouseIds());
        }
        return jdbc.queryForList(sql+" ORDER BY d.id DESC LIMIT 100",args.toArray()).stream().map(ApiRows::row).toList();
    }
}
