package cn.erp.wms;

import cn.erp.identity.AccessService;
import cn.erp.identity.CompanyAccess;
import cn.erp.salesfinance.FinanceSupport;
import cn.erp.shared.ApiRows;
import cn.erp.shared.AuditService;
import cn.erp.shared.BusinessCommands;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static cn.erp.wms.WmsScopes.conflict;
import static cn.erp.wms.WmsScopes.invalid;
import static cn.erp.wms.WmsScopes.missing;
import static cn.erp.wms.WmsScopes.number;

@Service
class TaskAdministration {
    private static final ZoneId BUSINESS_ZONE=ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final WmsScopes scopes;
    private final WmsMoves moves;
    private final BusinessCommands commands;
    private final AuditService audit;

    TaskAdministration(JdbcTemplate jdbc,AccessService access,WmsScopes scopes,WmsMoves moves,BusinessCommands commands,AuditService audit) {
        this.jdbc=jdbc;
        this.access=access;
        this.scopes=scopes;
        this.moves=moves;
        this.commands=commands;
        this.audit=audit;
    }

    record Assignment(Long assigneeId,Long version) {}

    @Transactional(readOnly=true)
    Map<String,Object> page(CompanyAccess actor,Long warehouse,String type,String status,String assignee,Long cursor,int limit) {
        WmsScopes.limit(cursor,limit);
        if(type!=null&&!allowedTypes(actor).contains(type)) throw missing();
        if(status!=null && !Set.of("created","assigned","in_progress","done","cancelled").contains(status))
            throw invalid("仓储作业状态无效");
        if(assignee!=null && !"me".equals(assignee)) throw invalid("领取人筛选值无效");
        var sql=new StringBuilder("""
                SELECT t.id,t.no,t.type,t.source_type,t.source_id,t.warehouse_id,t.wave_id,t.assignee_id,u.display_name assignee_name,t.status,t.version,
                       t.created_at,t.started_at,t.finished_at
                FROM wms_task t LEFT JOIN sys_user u ON u.tenant_id=t.tenant_id AND u.id=t.assignee_id
                WHERE t.tenant_id=? AND t.company_id=?
                """);
        var params=scopes.params(actor);
        commonFilters(actor,warehouse,sql,params);
        if(type!=null){sql.append(" AND t.type=?");params.add(type);}
        if(status!=null){sql.append(" AND t.status=?");params.add(status);}
        if(assignee!=null){sql.append(" AND t.assignee_id=?");params.add(actor.userId());}
        if(cursor!=null){sql.append(" AND t.id<?");params.add(cursor);}
        params.add(limit+1);
        return WmsScopes.page(jdbc.queryForList(sql+" ORDER BY t.id DESC LIMIT ?",params.toArray()),limit);
    }

    @Transactional(readOnly=true)
    List<Map<String,Object>> statistics(CompanyAccess actor,Long warehouse,LocalDate from,LocalDate to) {
        var end=to==null?LocalDate.now(BUSINESS_ZONE):to;
        var start=from==null?end.minusDays(29):from;
        if(start.isAfter(end) || ChronoUnit.DAYS.between(start,end)>365 || end.getYear()>9998 || start.getYear()<1000)
            throw invalid("统计起止日期无效，最多查询 366 天");
        var sql=new StringBuilder("""
                SELECT t.assignee_id,u.display_name assignee_name,t.warehouse_id,t.type,COUNT(*) task_count,
                       CAST(AVG(TIMESTAMPDIFF(MICROSECOND,t.created_at,t.finished_at)/1000000) AS DECIMAL(16,2)) average_seconds
                FROM wms_task t LEFT JOIN sys_user u ON u.tenant_id=t.tenant_id AND u.id=t.assignee_id
                WHERE t.tenant_id=? AND t.company_id=? AND t.status='done' AND t.finished_at>=? AND t.finished_at<?
                """);
        var params=scopes.params(actor);
        params.add(Timestamp.from(start.atStartOfDay(BUSINESS_ZONE).toInstant()));
        params.add(Timestamp.from(end.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant()));
        commonFilters(actor,warehouse,sql,params);
        sql.append(" GROUP BY t.assignee_id,u.display_name,t.warehouse_id,t.type ORDER BY t.warehouse_id,t.assignee_id,t.type LIMIT 500");
        return jdbc.queryForList(sql.toString(),params.toArray()).stream().map(ApiRows::row).toList();
    }

    @Transactional(readOnly=true)
    long todayPendingCount(CompanyAccess actor,Long warehouse) {
        var today=LocalDate.now(BUSINESS_ZONE);
        var sql=new StringBuilder("""
                SELECT COUNT(*) FROM wms_task t WHERE t.tenant_id=? AND t.company_id=? AND t.assignee_id=?
                    AND t.status IN ('assigned','in_progress') AND t.created_at>=? AND t.created_at<?
                """);
        var params=scopes.params(actor);
        params.add(actor.userId());
        params.add(Timestamp.from(today.atStartOfDay(BUSINESS_ZONE).toInstant()));
        params.add(Timestamp.from(today.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant()));
        commonFilters(actor,warehouse,sql,params);
        return jdbc.queryForObject(sql.toString(),Long.class,params.toArray());
    }

    @Transactional(readOnly=true)
    List<Map<String,Object>> assignees(CompanyAccess actor,long id,String keyword) {
        var row=managed(actor,id,false);
        String search=keyword==null?"":keyword.strip();
        if(search.length()>60) throw invalid("人员查询词过长");
        var candidates=jdbc.queryForList("""
                SELECT id,display_name FROM sys_user
                WHERE tenant_id=? AND status='active' AND LOWER(display_name) LIKE CONCAT('%',LOWER(?),'%')
                ORDER BY display_name,id LIMIT 100
                """,actor.tenantId(),search);
        return candidates.stream().filter(person->mayAssign(actor,row,number(person,"id"))).limit(30).map(ApiRows::row).toList();
    }

    @Transactional
    String assign(CompanyAccess actor,long id,String key,Assignment input) {
        managed(actor,id,false);
        if(input.assigneeId()==null || input.assigneeId()<=0) throw invalid("请选择作业领取人");
        return commands.execute(actor,"wms_task.assign",key,List.of(id,input),200,()->{
            var before=managed(actor,id,true);
            notStarted(before,input.version());
            if(before.get("wave_id")!=null) throw conflict("拣货任务已加入波次，请按波次领取");
            if(!mayAssign(actor,before,input.assigneeId())) throw missing();
            jdbc.update("""
                    UPDATE wms_task SET assignee_id=?,status='assigned',version=version+1
                    WHERE tenant_id=? AND company_id=? AND id=?
                    """,input.assigneeId(),actor.tenantId(),actor.companyId(),id);
            audit.record(actor,"wms_task.assign","wms_task",id,ApiRows.row(before),Map.of("assigneeId",input.assigneeId().toString()));
            return Long.toString(id);
        },String.class);
    }

    @Transactional
    String cancel(CompanyAccess actor,long id,String key,WmsScopes.Revision input) {
        managed(actor,id,false);
        return commands.execute(actor,"wms_task.cancel",key,List.of(id,input),200,()->{
            var before=managed(actor,id,true);
            notStarted(before,input.version());
            String type=before.get("type").toString();
            if("pick".equals(type)) throw conflict("请在来源发货单上作废拣货，释放发货占用");
            if(Set.of("move","replenish").contains(type)) moves.cancelLocked(actor,id);
            else jdbc.update("""
                    UPDATE wms_task SET status='cancelled',version=version+1
                    WHERE tenant_id=? AND company_id=? AND id=?
                    """,actor.tenantId(),actor.companyId(),id);
            audit.record(actor,"wms_task.cancel","wms_task",id,ApiRows.row(before),Map.of("status","cancelled"));
            return Long.toString(id);
        },String.class);
    }

    private void commonFilters(CompanyAccess actor,Long warehouse,StringBuilder sql,List<Object> params) {
        scopes.taskFilter(actor,"t",sql,params);
        var types=allowedTypes(actor);
        sql.append(" AND t.type IN (").append(String.join(",",Collections.nCopies(types.size(),"?"))).append(")");
        params.addAll(types);
        if(warehouse!=null){
            if(!actor.allowsWarehouse(warehouse)) throw missing();
            sql.append(" AND t.warehouse_id=?");params.add(warehouse);
        }
    }

    private Map<String,Object> managed(CompanyAccess actor,long id,boolean lock) {
        var task=scopes.task(actor,id,lock);
        if(!allowedTypes(actor).contains(task.get("type").toString())) throw missing();
        return task;
    }

    private boolean mayAssign(CompanyAccess actor,Map<String,Object> task,long user) {
        var candidate=access.forUser(actor.tenantId(),user,actor.companyId());
        String type=task.get("type").toString();
        if(candidate==null || !candidate.allowsWarehouse(number(task,"warehouse_id"))
                || !candidate.orgScope().allows(number(task,"owner_department_id"),number(task,"owner_user_id"))) return false;
        return switch(type) {
            case "move","replenish" -> candidate.hasPermission("wms_move:view") && candidate.hasPermission("wms_move:manage");
            case "count" -> candidate.hasPermission("wms_task:view") && candidate.hasPermission("wms_task:execute")
                    && candidate.hasPermission("stock_count:view") && candidate.hasPermission("stock_count:record_counts");
            default -> candidate.hasPermission("wms_task:view") && candidate.hasPermission("wms_task:execute");
        };
    }

    private List<String> allowedTypes(CompanyAccess actor) {
        var types=new ArrayList<>(List.of("putaway","pick"));
        if(actor.hasPermission("stock_count:view")) types.add("count");
        if(actor.hasPermission("wms_move:view")) {types.add("move");types.add("replenish");}
        return types;
    }

    private void notStarted(Map<String,Object> row,Long revision) {
        FinanceSupport.version(row,revision);
        if(!Set.of("created","assigned").contains(row.get("status")) || row.get("started_at")!=null)
            throw conflict("作业已开始或结束，不能重新分配或直接取消");
    }
}
