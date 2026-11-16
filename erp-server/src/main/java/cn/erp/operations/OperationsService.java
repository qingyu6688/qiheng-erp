package cn.erp.operations;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 运维查询仅返回当前公司的聚合状态，不暴露文件、任务载荷或其他租户信息。 */
@Service
@Transactional(readOnly=true)
public class OperationsService {
    private final JdbcTemplate jdbc;
    public OperationsService(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    public Map<String,Object> status(CompanyAccess actor) {
        if(!actor.hasPermission("admin:outbox") || !actor.allWarehouses() || !actor.orgScope().allDepartments()) throw new ApiException(403,"FORBIDDEN","运维状态需要后台事件权限和整家公司数据范围");
        var queues=new LinkedHashMap<String,Object>();
        for(String type:List.of("import","export")) {
            String table=type+"_job";
            var row=jdbc.queryForMap("SELECT COUNT(*) total_count,COALESCE(SUM(status='pending'),0) pending_count,COALESCE(SUM(status='running'),0) running_count,COALESCE(SUM(status='failed'),0) failed_count,COALESCE(MAX(IF(status IN ('pending','running'),TIMESTAMPDIFF(SECOND,created_at,UTC_TIMESTAMP(3)),0)),0) oldest_seconds FROM "+table+" WHERE tenant_id=? AND company_id=?",actor.tenantId(),actor.companyId());
            queues.put(type,row);
        }
        var outbox=jdbc.queryForMap("""
                SELECT COUNT(*) total_count,COALESCE(SUM(e.status='pending'),0) pending_count,COALESCE(SUM(e.status='processing'),0) running_count,
                COALESCE(SUM(e.status='dead'),0) failed_count,COALESCE(MAX(IF(e.status IN ('pending','processing'),TIMESTAMPDIFF(SECOND,e.created_at,UTC_TIMESTAMP(3)),0)),0) oldest_seconds
                FROM outbox_event e WHERE e.tenant_id=? AND
                ((e.aggregate_type='journal' AND EXISTS(SELECT 1 FROM journal j WHERE j.tenant_id=e.tenant_id AND j.id=e.aggregate_id AND j.company_id=?))
                OR (e.aggregate_type='reconciliation' AND EXISTS(SELECT 1 FROM reconciliation_run r WHERE r.tenant_id=e.tenant_id AND r.id=e.aggregate_id AND r.company_id=?)))
                """,actor.tenantId(),actor.companyId(),actor.companyId());
        queues.put("outbox",outbox);
        Long unresolved=jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_diff WHERE tenant_id=? AND company_id=? AND status='open'",Long.class,actor.tenantId(),actor.companyId());
        var warnings=new ArrayList<String>();
        if(unresolved!=null&&unresolved>0) warnings.add("存在未处理的对账差异，请立即核查");
        for(var entry:queues.entrySet()) {
            var value=(Map<?,?>)entry.getValue();
            if(((Number)value.get("failed_count")).longValue()>0) warnings.add(entry.getKey()+" 存在失败任务");
            if(((Number)value.get("oldest_seconds")).longValue()>300) warnings.add(entry.getKey()+" 最旧待处理任务已超过五分钟");
        }
        return Map.of("checkedAt",Instant.now().toString(),"companyId",Long.toString(actor.companyId()),"queues",queues,"unresolvedDifferences",unresolved==null?0:unresolved,"warnings",warnings);
    }
}
