package cn.erp.wms;

import cn.erp.shared.ActorScope;
import cn.erp.shared.Ids;
import cn.erp.shared.StockCountTaskPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static cn.erp.wms.WmsScopes.number;

@Service
@Transactional(propagation = Propagation.MANDATORY)
class StockCountTaskWriter implements StockCountTaskPort {
    private final JdbcTemplate jdbc;

    StockCountTaskWriter(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Override
    public void started(ActorScope actor,long countId) {
        var locations=jdbc.queryForList("""
                SELECT DISTINCT l.location_key,c.warehouse_id,c.owner_user_id,c.owner_department_id
                FROM stock_count c JOIN warehouse w ON w.tenant_id=c.tenant_id AND w.company_id=c.company_id AND w.id=c.warehouse_id
                JOIN stock_count_line l ON l.tenant_id=c.tenant_id AND l.company_id=c.company_id AND l.count_id=c.id
                JOIN wms_location a ON a.tenant_id=c.tenant_id AND a.company_id=c.company_id AND a.warehouse_id=c.warehouse_id
                   AND a.id=CAST(l.location_key AS UNSIGNED)
                WHERE c.tenant_id=? AND c.company_id=? AND c.id=? AND l.location_key<>'' AND w.wms_task_mode<>'off'
                ORDER BY l.location_key
                """,actor.tenantId(),actor.companyId(),countId);
        for(var location:locations) {
            long task=Ids.next(),warehouse=number(location,"warehouse_id");
            jdbc.update("""
                    INSERT INTO wms_task(id,tenant_id,company_id,warehouse_id,no,type,source_type,source_id,owner_user_id,owner_department_id)
                    VALUES(?,?,?,?,?,'count','stock_count_location',?,?,?)
                    """,task,actor.tenantId(),actor.companyId(),warehouse,"CT-"+task,task,
                    location.get("owner_user_id"),location.get("owner_department_id"));
            jdbc.update("""
                    INSERT INTO wms_count_group(task_id,tenant_id,company_id,warehouse_id,count_id,location_id)
                    VALUES(?,?,?,?,?,?)
                    """,task,actor.tenantId(),actor.companyId(),warehouse,countId,Long.parseLong(location.get("location_key").toString()));
        }
    }

    @Override
    public void phase(ActorScope actor,long countId,String status) {
        var scope="""
                WHERE g.tenant_id=? AND g.company_id=? AND g.count_id=?
                """;
        var changes=switch(status) {
            case "recorded" -> "t.updated_at=UTC_TIMESTAMP(3) "+scope+" AND t.status IN ('created','assigned')";
            case "submitted" -> "t.status='done',t.finished_at=COALESCE(t.finished_at,UTC_TIMESTAMP(3)) "+scope+" AND t.status IN ('created','assigned')";
            case "counting" -> "t.status=IF(t.assignee_id IS NULL,'created','assigned'),t.finished_at=NULL "+scope+" AND t.status='done'";
            case "voided" -> "t.status='cancelled',t.finished_at=NULL "+scope+" AND t.status<>'cancelled'";
            default -> throw new IllegalArgumentException("未知盘点作业状态");
        };
        jdbc.update("""
                UPDATE wms_count_group g JOIN wms_task t ON t.tenant_id=g.tenant_id AND t.company_id=g.company_id AND t.id=g.task_id
                SET t.version=t.version+1,
                """+changes,actor.tenantId(),actor.companyId(),countId);
    }
}
