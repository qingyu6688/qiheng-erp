package cn.erp.wms;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiRows;
import cn.erp.shared.OrgOwnership;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class WmsScopes {
    private final JdbcTemplate jdbc;

    WmsScopes(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    record Revision(Long version) {}

    ArrayList<Object> params(CompanyAccess actor) {
        return new ArrayList<>(List.of(actor.tenantId(),actor.companyId()));
    }

    void taskFilter(CompanyAccess actor,String alias,StringBuilder sql,List<Object> params) {
        actor.orgScope().appendOwnerFilter(sql,params,alias);
        if (!actor.allWarehouses()) {
            if (actor.warehouseIds().isEmpty()) {
                sql.append(" AND 1=0");
            } else {
                sql.append(" AND ").append(alias).append(".warehouse_id IN (")
                        .append(String.join(",",Collections.nCopies(actor.warehouseIds().size(),"?"))).append(")");
                params.addAll(actor.warehouseIds());
            }
        }
    }

    Map<String,Object> task(CompanyAccess actor,long id,boolean lock) {
        var sql = new StringBuilder("SELECT t.* FROM wms_task t WHERE t.tenant_id=? AND t.company_id=? AND t.id=?");
        var params = params(actor);
        params.add(id);
        taskFilter(actor,"t",sql,params);
        if (lock) sql.append(" FOR UPDATE");
        return jdbc.queryForList(sql.toString(),params.toArray()).stream().findFirst().orElseThrow(WmsScopes::missing);
    }

    void warehouse(CompanyAccess actor,long warehouse,boolean lock) {
        if (!actor.allowsWarehouse(warehouse) || jdbc.queryForList("""
                SELECT id FROM warehouse WHERE tenant_id=? AND company_id=? AND id=?
                  AND status='active' AND location_managed=1
                """+(lock?" FOR SHARE":""),Long.class,actor.tenantId(),actor.companyId(),warehouse).isEmpty())
            throw missing();
    }

    void fullDepartment(CompanyAccess actor) {
        if (!actor.orgScope().allDepartments()) throw forbidden();
    }

    long newTask(CompanyAccess actor,long warehouse,String type,String sourceType,String prefix) {
        long task = cn.erp.shared.Ids.next();
        insertTask(actor,task,warehouse,type,sourceType,task,prefix,actor.userId(),OrgOwnership.department(actor));
        return task;
    }

    void insertTask(CompanyAccess actor,long task,long warehouse,String type,String sourceType,long sourceId,
                    String prefix,long owner,long department) {
        jdbc.update("""
                INSERT INTO wms_task(id,tenant_id,company_id,warehouse_id,no,type,source_type,source_id,owner_user_id,owner_department_id)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """,task,actor.tenantId(),actor.companyId(),warehouse,prefix+task,type,sourceType,sourceId,owner,department);
    }

    static long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }

    static Map<String,Object> page(List<Map<String,Object>> rows,int limit) {
        var result = new LinkedHashMap<String,Object>();
        result.put("items",rows.stream().limit(limit).map(ApiRows::row).toList());
        result.put("nextCursor",rows.size() > limit ? rows.get(limit-1).get("id").toString() : null);
        return result;
    }

    static void limit(Long cursor,int limit) {
        if (cursor != null && cursor <= 0 || limit <= 0 || limit > 100) throw invalid("作业分页条件无效");
    }

    static void permission(CompanyAccess actor,String permission) {
        if (!actor.hasPermission(permission)) throw forbidden();
    }

    static ApiException missing() { return new ApiException(404,"NOT_FOUND","作业资料不存在或无权查看"); }
    static ApiException forbidden() { return new ApiException(403,"FORBIDDEN","没有执行该仓储作业的权限"); }
    static ApiException conflict(String message) { return new ApiException(409,"STATE_CONFLICT",message); }
    static ApiException invalid(String message) { return new ApiException(422,"VALIDATION_FAILED",message); }
}
