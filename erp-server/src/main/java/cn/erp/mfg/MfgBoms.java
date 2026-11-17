package cn.erp.mfg;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiRows;
import cn.erp.shared.OrgOwnership;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import static cn.erp.mfg.MfgPlanning.*;

@Component
class MfgBoms {
    private static final String HEAD = """
            SELECT b.*,s.code sku_code,s.name sku_name,u.name unit_name FROM mfg_bom b
            JOIN sku s ON s.tenant_id=b.tenant_id AND s.id=b.sku_id
            JOIN unit u ON u.tenant_id=b.tenant_id AND u.id=b.unit_id
            WHERE b.tenant_id=? AND b.company_id=?
            """;
    private static final Set<String> STATUSES=Set.of("draft","pending","approved","disabled");
    private final JdbcTemplate jdbc;

    MfgBoms(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    void lockGraph(CompanyAccess actor) {
        jdbc.update("""
                INSERT INTO mfg_bom_guard(tenant_id,company_id) VALUES(?,?)
                ON DUPLICATE KEY UPDATE company_id=company_id
                """,actor.tenantId(),actor.companyId());
    }

    Map<String,Object> header(CompanyAccess actor,long id,boolean lock) {
        return heads(actor," AND b.id=?",List.of(id),lock).stream().findFirst().orElseThrow(MfgPlanning::missing);
    }

    Map<String,Object> effectiveDefault(CompanyAccess actor,long sku,LocalDate day,boolean lock) {
        var result=heads(actor," AND b.sku_id=? AND b.is_default=TRUE AND b.status='approved'"
                +" AND b.valid_from<=? AND (b.valid_to IS NULL OR b.valid_to>=?)",List.of(sku,day,day),lock);
        if(result.size()>1) throw conflict("同一日期存在多个默认物料清单，请检查版本");
        return result.isEmpty() ? null : result.getFirst();
    }

    Map<String,Object> effective(CompanyAccess actor,long bom,long sku,LocalDate day,boolean lock) {
        var row=header(actor,bom,lock);
        if(number(row,"sku_id")!=sku || !"approved".equals(row.get("status")) || !activeOn(row,day))
            throw invalid("请选择计划开工日有效并已审核的物料清单版本");
        return row;
    }

    static boolean activeOn(Map<String,Object> row,LocalDate date) {
        return !date.isBefore(date(row,"valid_from")) && !date.isAfter(end(date(row,"valid_to")));
    }

    private List<Map<String,Object>> heads(CompanyAccess actor,String where,List<?> values,boolean lock) {
        var sql=new StringBuilder(HEAD).append(where);
        var args=new ArrayList<Object>();
        args.add(actor.tenantId()); args.add(actor.companyId()); args.addAll(values);
        appendScope(actor,sql,args);
        if(lock) sql.append(" FOR UPDATE OF b");
        return jdbc.queryForList(sql.toString(),args.toArray());
    }

    Map<String,Object> list(CompanyAccess actor,Long sku,String status,String keyword,Long cursor,int limit) {
        page(cursor,limit);
        if(status!=null && !STATUSES.contains(status)) throw invalid("物料清单状态无效");
        if(sku!=null) id(sku,"父项商品");
        var sql=new StringBuilder(HEAD);
        var args=new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId()));
        if(sku!=null){sql.append(" AND b.sku_id=?");args.add(sku);}
        if(status!=null){sql.append(" AND b.status=?");args.add(status);}
        if(cursor!=null){sql.append(" AND b.id<?");args.add(cursor);}
        String search=text(keyword,100,"查询词",false);
        if(search!=null) {
            sql.append(" AND (LOCATE(?,s.code)>0 OR LOCATE(?,s.name)>0 OR LOCATE(?,b.version_no)>0)");
            args.addAll(List.of(search,search,search));
        }
        appendScope(actor,sql,args);
        args.add(limit+1);
        return page(jdbc.queryForList(sql+" ORDER BY b.id DESC LIMIT ?",args.toArray()),limit);
    }

    private void appendScope(CompanyAccess actor,StringBuilder sql,List<Object> args) {
        sql.append(OrgOwnership.filter(actor,"b"));
        if(actor.allWarehouses()) return;
        String selectable=actor.warehouseIds().isEmpty() ? "1=1"
                : "l.issue_warehouse_id NOT IN ("+String.join(",",Collections.nCopies(actor.warehouseIds().size(),"?"))+")";
        sql.append("""
                 AND NOT EXISTS (SELECT 1 FROM mfg_bom_line l WHERE l.tenant_id=b.tenant_id
                    AND l.company_id=b.company_id AND l.bom_id=b.id AND l.issue_warehouse_id IS NOT NULL AND
                """).append(selectable).append(")");
        args.addAll(actor.warehouseIds());
    }

    List<Map<String,Object>> lines(CompanyAccess actor,long bom,boolean lock) {
        return jdbc.queryForList("""
                SELECT l.*,s.name sku_name,s.code sku_code,u.name unit_name FROM mfg_bom_line l
                JOIN sku s ON s.tenant_id=l.tenant_id AND s.id=l.component_sku_id
                JOIN unit u ON u.tenant_id=l.tenant_id AND u.id=l.unit_id
                WHERE l.tenant_id=? AND l.company_id=? AND l.bom_id=? ORDER BY l.line_no
                """+(lock?" FOR SHARE":""),actor.tenantId(),actor.companyId(),bom);
    }

    List<Map<String,Object>> substitutes(CompanyAccess actor,long line,boolean lock) {
        return jdbc.queryForList("""
                SELECT x.*,s.code sku_code,s.name sku_name,u.name unit_name FROM mfg_bom_substitute x
                JOIN sku s ON s.tenant_id=x.tenant_id AND s.id=x.substitute_sku_id
                JOIN unit u ON u.tenant_id=x.tenant_id AND u.id=x.unit_id
                WHERE x.tenant_id=? AND x.company_id=? AND x.bom_line_id=? ORDER BY x.priority,x.id
                """+(lock?" FOR SHARE":""),actor.tenantId(),actor.companyId(),line);
    }

    Map<String,Object> detail(CompanyAccess actor,long bom) {
        var result=ApiRows.row(header(actor,bom,false));
        result.put("lines",lines(actor,bom,false).stream().map(line->{
            var row=ApiRows.row(line);
            row.put("componentSkuName",line.get("sku_name")); row.put("componentSkuCode",line.get("sku_code"));
            row.put("phantom",line.get("is_phantom"));
            row.put("substitutes",substitutes(actor,number(line,"id"),false).stream().map(ApiRows::row).toList());
            return row;
        }).toList());
        return result;
    }

    boolean overlapsDefault(CompanyAccess actor,long bom,long sku,LocalDate start,LocalDate end) {
        return !jdbc.queryForList("""
                SELECT id FROM mfg_bom WHERE tenant_id=? AND company_id=? AND sku_id=? AND id<>?
                    AND status='approved' AND is_default=TRUE AND valid_from<=? AND (valid_to IS NULL OR valid_to>=?)
                LIMIT 1 FOR SHARE
                """,Long.class,actor.tenantId(),actor.companyId(),sku,bom,end,start).isEmpty();
    }

    List<Map<String,Object>> graph(CompanyAccess actor,long candidate) {
        return jdbc.queryForList("""
                SELECT b.id,b.sku_id,b.valid_from,b.valid_to,b.is_default,l.component_sku_id FROM mfg_bom b
                JOIN mfg_bom_line l ON l.tenant_id=b.tenant_id AND l.company_id=b.company_id AND l.bom_id=b.id
                WHERE b.tenant_id=? AND b.company_id=? AND (b.id=? OR (b.status='approved' AND b.is_default=TRUE))
                ORDER BY b.id,l.line_no FOR SHARE
                """,actor.tenantId(),actor.companyId(),candidate);
    }
}
