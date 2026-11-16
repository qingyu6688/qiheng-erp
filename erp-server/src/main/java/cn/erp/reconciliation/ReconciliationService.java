package cn.erp.reconciliation;

import cn.erp.identity.CompanyAccess;
import cn.erp.jobs.OutboxService;
import cn.erp.shared.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Service
public class ReconciliationService {
    private static boolean financialAllowed(CompanyAccess actor) {
        try {FinancialScope.require(actor,"gl_ledger:view");return true;}
        catch(ApiException denied) {if(denied.getStatus()==403)return false;throw denied;}
    }
    private final JdbcTemplate jdbc;
    private final ReconciliationChecks checks;
    private final OutboxService events;

    public ReconciliationService(JdbcTemplate jdbc,ReconciliationChecks checks,OutboxService events) {
        this.jdbc=jdbc;this.checks=checks;this.events=events;
    }

    private cn.erp.identity.AccessService access;
    @org.springframework.beans.factory.annotation.Autowired
    void access(cn.erp.identity.AccessService access) {this.access=access;}

    @Transactional
    public Map<String,Object> request(CompanyAccess scope,String type,Long period) {
        if(!scope.allWarehouses()||!scope.orgScope().allDepartments()) {throw new ApiException(403,"FORBIDDEN","公司级对账需要整家公司数据范围");}
        if(type!=null&&type.startsWith("gl_"))FinancialScope.require(scope,"gl_ledger:view");
        if(!ReconciliationChecks.TYPES.contains(type)) {throw new ApiException(400,"VALIDATION_FAILED","对账类型无效");}
        if(period!=null && jdbc.queryForObject("SELECT COUNT(*) FROM accounting_period WHERE tenant_id=? AND company_id=? AND id=?",Long.class,scope.tenantId(),scope.companyId(),period)==0) {
            throw new ApiException(404,"NOT_FOUND","会计期间不存在或无权查看");
        }
        if(type.equals("period_close") && period==null) {throw new ApiException(400,"VALIDATION_FAILED","关账检查须选择期间");}
        long id=Ids.next();
        jdbc.update("INSERT INTO reconciliation_run(id,tenant_id,company_id,check_type,period_id,triggered_by) VALUES(?,?,?,?,?,?)",
                id,scope.tenantId(),scope.companyId(),type,period,scope.userId());
        events.publish(scope.tenantId(),"reconciliation",id,0,"reconciliation.requested",Map.of("runId",Long.toString(id)));
        return Map.of("taskId",Long.toString(id),"id",Long.toString(id),"taskType","reconciliation","statusUrl","/api/v1/reconciliation-runs/"+id);
    }

    @Transactional
    public void perform(long tenant,long id,boolean enforceAccess) {
        var runs=jdbc.queryForList("SELECT * FROM reconciliation_run WHERE tenant_id=? AND id=? AND status IN ('running','error') FOR UPDATE",tenant,id);
        if(runs.isEmpty()) {return;}
        var run=runs.getFirst();long company=((Number)run.get("company_id")).longValue();
        long user=run.get("triggered_by")==null?0:((Number)run.get("triggered_by")).longValue();
        if(enforceAccess) {
            Long authorized=jdbc.queryForObject("""
                    SELECT COUNT(*) FROM sys_user u JOIN tenant t ON t.id=u.tenant_id
                    JOIN company c ON c.tenant_id=u.tenant_id AND c.id=?
                    WHERE u.tenant_id=? AND u.id=? AND u.status='active' AND t.status='active' AND c.status='active'
                    AND EXISTS(SELECT 1 FROM sys_user_data_scope s WHERE s.tenant_id=u.tenant_id AND s.user_id=u.id AND s.company_id=c.id AND s.scope_type='company')
                    AND EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.tenant_id=ur.tenant_id AND r.id=ur.role_id AND r.status='active'
                        JOIN sys_role_permission rp ON rp.tenant_id=r.tenant_id AND rp.role_id=r.id JOIN sys_permission p ON p.id=rp.permission_id
                        WHERE ur.tenant_id=u.tenant_id AND ur.user_id=u.id AND p.code='reconciliation:run')
                    """,Long.class,company,tenant,user);
            if(authorized==null || authorized==0 || !new cn.erp.identity.OrgScopeResolver(jdbc).resolve(tenant,company,user).allDepartments()) {
                markError(tenant,id,"发起人的权限或公司范围已变更");return;
            }
        }
        var scope=new CompanyAccess(tenant,user,company,true,Set.of(),Set.of());
        if(run.get("check_type").toString().startsWith("gl_")) {scope=access==null?null:access.forUser(tenant,user,company);if(scope==null||!financialAllowed(scope)) {markError(tenant,id,"发起人的财务权限或完整数据范围已变更");return;}}

        Long period=run.get("period_id")==null?null:((Number)run.get("period_id")).longValue();
        var diffs=checks.check(scope,run.get("check_type").toString(),period);
        for(var diff:diffs) {
            jdbc.update("""
                    INSERT INTO reconciliation_diff(id,tenant_id,company_id,run_id,object_type,object_key,expected_value,actual_value)
                    VALUES(?,?,?,?,?,?,?,?)
                    """,Ids.next(),tenant,company,id,diff.objectType(),diff.key(),diff.expected(),diff.actual());
        }
        jdbc.update("UPDATE reconciliation_run SET status=?,diff_count=?,error_message=NULL,finished_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND id=?",
                diffs.isEmpty()?"passed":"failed",diffs.size(),tenant,id);
    }

    @Transactional
    public void markError(long tenant,long id,String message) {
        jdbc.update("UPDATE reconciliation_run SET status='error',error_message=?,finished_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND id=? AND status='running'",message,tenant,id);
    }

    public Map<String,Object> get(CompanyAccess scope,long id) {
        requireCompanyScope(scope);
        var rows=jdbc.queryForList("SELECT id,check_type,status,diff_count,started_at,finished_at,error_message FROM reconciliation_run WHERE tenant_id=? AND company_id=? AND id=?",
                scope.tenantId(),scope.companyId(),id);
        if(rows.isEmpty()) {throw new ApiException(404,"NOT_FOUND","任务不存在或无权查看");}
        if(rows.getFirst().get("check_type").toString().startsWith("gl_"))FinancialScope.require(scope,"gl_ledger:view");
        return row(rows.getFirst());
    }

    public Map<String,Object> list(CompanyAccess scope,Long cursor,int limit) {
        requireCompanyScope(scope);
        if(limit<1 || limit>200 || cursor!=null && cursor<=0) {throw new ApiException(400,"VALIDATION_FAILED","分页条件无效");}
        var rows=jdbc.queryForList("SELECT id,check_type,status,diff_count,started_at,finished_at,error_message FROM reconciliation_run WHERE tenant_id=? AND company_id=? AND id<? "+(financialAllowed(scope)?"":"AND check_type NOT IN('gl_trial','gl_operational') ")+" ORDER BY id DESC LIMIT ?",
                scope.tenantId(),scope.companyId(),cursor==null?Long.MAX_VALUE:cursor,limit+1);
        var items=rows.stream().limit(limit).map(ReconciliationService::row).toList();
        var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("nextCursor",rows.size()>limit?items.getLast().get("id"):null);return result;
    }

    public Map<String,Object> differences(CompanyAccess scope,long id,Long cursor,int limit) {
        get(scope,id);
        if(limit<1 || limit>200 || cursor!=null && cursor<=0) {throw new ApiException(400,"VALIDATION_FAILED","分页条件无效");}
        var rows=jdbc.queryForList("SELECT id,object_type,object_key,expected_value,actual_value,status,resolution FROM reconciliation_diff WHERE tenant_id=? AND company_id=? AND run_id=? AND id<? ORDER BY id DESC LIMIT ?",
                scope.tenantId(),scope.companyId(),id,cursor==null?Long.MAX_VALUE:cursor,limit+1);
        var items=rows.stream().limit(limit).map(ReconciliationService::row).toList();
        var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("nextCursor",rows.size()>limit?items.getLast().get("id"):null);return result;
    }

    private static void requireCompanyScope(CompanyAccess scope) {
        if(!scope.allWarehouses()||!scope.orgScope().allDepartments()) {throw new ApiException(403,"FORBIDDEN","公司级对账需要整家公司数据范围");}
    }

    private static Map<String,Object> row(Map<String,Object> input) {
        var result=new LinkedHashMap<String,Object>();
        input.forEach((key,value)->{
            StringBuilder field=new StringBuilder();boolean upper=false;
            for(char c:key.toCharArray()) {if(c=='_') {upper=true;} else {field.append(upper?Character.toUpperCase(c):c);upper=false;}}
            Object output=value;
            if(key.equals("id") && value!=null) {output=value.toString();}
            if(value instanceof java.math.BigDecimal decimal) {output=decimal.toPlainString();}
            if(value instanceof java.sql.Timestamp time) {output=time.toInstant().toString();}
            if(value instanceof java.time.LocalDateTime time) {output=time.toInstant(java.time.ZoneOffset.UTC).toString();}
            result.put(field.toString(),output);
        });
        return result;
    }
}
