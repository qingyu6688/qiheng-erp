package cn.erp.identity;

import cn.erp.shared.ApiException;
import cn.erp.shared.AuditService;
import cn.erp.shared.BusinessCommands;
import cn.erp.shared.Ids;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.identity.AdminModels.*;

@Service
@Transactional(readOnly=true)
class OrganizationService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final BusinessCommands commands;

    OrganizationService(JdbcTemplate jdbc,AuditService audit,BusinessCommands commands) {
        this.jdbc=jdbc;
        this.audit=audit;
        this.commands=commands;
    }

    Page<CompanyView> companies(CompanyAccess actor) {
        var ids=jdbc.queryForList("SELECT DISTINCT company_id FROM sys_user_data_scope WHERE tenant_id=? AND user_id=?",Long.class,actor.tenantId(),actor.userId());
        return new Page<>(ids.stream().map(id -> company(actor,id,false)).toList(),null);
    }

    CompanyView company(CompanyAccess actor,long id,boolean lock) {
        visible(actor,id);
        var rows=jdbc.query("SELECT * FROM company WHERE tenant_id=? AND id=?"+(lock?" FOR UPDATE":""),
                (r,n)->new CompanyView(r.getString("id"),r.getString("code"),r.getString("name"),r.getString("tax_no"),
                        r.getString("address"),r.getString("phone"),r.getString("status"),r.getLong("version"),
                        actor.hasPermission("company:update")?List.of("update",r.getString("status").equals("active")?"disable":"enable"):List.of()),
                actor.tenantId(),id);
        if(rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    @Transactional
    CompanyView create(CompanyAccess actor,String key,CompanyInput input) {
        return commands.execute(actor,"company.create",key,input,201,()->{
            long id=Ids.next();
            jdbc.update("INSERT INTO company(id,tenant_id,code,name,tax_no,address,phone,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?)",
                    id,actor.tenantId(),input.code(),input.name(),input.taxNo(),input.address(),input.phone(),actor.userId(),actor.userId());
            jdbc.update("INSERT INTO company_policy(id,tenant_id,company_id,created_by,updated_by) VALUES(?,?,?,?,?)",
                    Ids.next(),actor.tenantId(),id,actor.userId(),actor.userId());
            jdbc.update("INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id,created_by) VALUES(?,?,?,'company',?,?)",
                    Ids.next(),actor.tenantId(),actor.userId(),id,actor.userId());
            var after=company(actor,id,false);
            audit.record(new CompanyAccess(actor.tenantId(),actor.userId(),id,true,java.util.Set.of(),actor.permissions()),"company.create","company",id,null,after);
            return after;
        },CompanyView.class);
    }

    @Transactional
    CompanyView edit(CompanyAccess actor,long id,CompanyInput input) {
        var before=company(actor,id,true);
        version(input.version(),before.version());
        jdbc.update("UPDATE company SET code=?,name=?,tax_no=?,address=?,phone=?,version=version+1,updated_by=? WHERE tenant_id=? AND id=?",
                input.code(),input.name(),input.taxNo(),input.address(),input.phone(),actor.userId(),actor.tenantId(),id);
        var after=company(actor,id,false);
        audit.record(companyScope(actor,id),"company.update","company",id,before,after);
        return after;
    }

    @Transactional
    CompanyView status(CompanyAccess actor,long id,String action,String key,Version input) {
        return commands.execute(actor,"company."+action,key,List.of(id,input),200,()->{
            jdbc.queryForObject("SELECT id FROM tenant WHERE id=? FOR UPDATE",Long.class,actor.tenantId());
            var before=company(actor,id,true);
            version(input.version(),before.version());
            jdbc.update("UPDATE company SET status=?,version=version+1,updated_by=? WHERE tenant_id=? AND id=?",
                    action.equals("enable")?"active":"disabled",actor.userId(),actor.tenantId(),id);
            var after=company(actor,id,false);
            audit.record(companyScope(actor,id),"company."+action,"company",id,before,after);
            return after;
        },CompanyView.class);
    }

    PolicyView policy(CompanyAccess actor,long id) {
        visible(actor,id);
        var policies=jdbc.query("SELECT * FROM company_policy WHERE tenant_id=? AND company_id=?",(r,n)->
                new PolicyView(r.getString("taxpayer_type"),r.getInt("reservation_valid_days"),r.getInt("reservation_grace_days"),
                        r.getBigDecimal("over_receipt_pct").toPlainString(),r.getBoolean("separate_order_approver"),
                        r.getBoolean("separate_receipt_confirmer"),r.getLong("version"),r.getString("below_min_price_policy")),actor.tenantId(),id);
        return policies.isEmpty()?new PolicyView("general",30,7,"0.00",true,true,0):policies.getFirst();
    }

    @Transactional
    PolicyView policy(CompanyAccess actor,long id,PolicyInput input) {
        company(actor,id,true);
        var before=policy(actor,id);
        version(input.version(),before.version());
        jdbc.update("INSERT IGNORE INTO company_policy(id,tenant_id,company_id,created_by) VALUES(?,?,?,?)",Ids.next(),actor.tenantId(),id,actor.userId());
        jdbc.update("""
                UPDATE company_policy SET taxpayer_type=?,reservation_valid_days=?,reservation_grace_days=?,
                over_receipt_pct=?,separate_order_approver=?,separate_receipt_confirmer=?,below_min_price_policy=COALESCE(?,below_min_price_policy),version=version+1,updated_by=?
                WHERE tenant_id=? AND company_id=?
                """,input.taxpayerType(),input.reservationValidDays(),input.reservationGraceDays(),new java.math.BigDecimal(input.overReceiptPct()),
                input.separateOrderApprover(),input.separateReceiptConfirmer(),input.belowMinPricePolicy(),actor.userId(),actor.tenantId(),id);
        var after=policy(actor,id);
        audit.record(companyScope(actor,id),"company_policy.update","company_policy",id,before,after);
        return after;
    }

    Page<Map<String,Object>> auditLogs(CompanyAccess actor,String cursor,int limit) {
        if(limit<1||limit>200) throw new ApiException(400,"VALIDATION_FAILED","查询条数为 1 到 200");
        long beforeId=Long.MAX_VALUE;
        if(cursor!=null) {
            try {
                beforeId=Long.parseLong(cursor);
                if(beforeId<=0) throw new NumberFormatException();
            } catch(NumberFormatException error) {
                throw new ApiException(400,"VALIDATION_FAILED","分页游标有误");
            }
        }
        var rows=jdbc.query("""
                SELECT id,action,object_type,object_id,operator_id,created_at,request_id FROM audit_log a
                WHERE tenant_id=? AND id<? AND (company_id IS NULL OR EXISTS(SELECT 1 FROM sys_user_data_scope s
                WHERE s.tenant_id=a.tenant_id AND s.company_id=a.company_id AND s.user_id=?)) ORDER BY id DESC LIMIT ?
                """,(r,n)->{
            var item=new java.util.LinkedHashMap<String,Object>();
            item.put("id",r.getString("id"));item.put("action",r.getString("action"));
            item.put("entityType",r.getString("object_type"));item.put("entityId",r.getString("object_id"));
            item.put("operatorId",r.getString("operator_id"));item.put("createdAt",r.getTimestamp("created_at").toInstant().toString());
            item.put("requestId",r.getString("request_id"));return (Map<String,Object>)item;
        },actor.tenantId(),beforeId,actor.userId(),limit+1);
        boolean hasMore=rows.size()>limit;
        var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
        return new Page<>(items,hasMore?items.getLast().get("id").toString():null);
    }

    void visible(CompanyAccess actor,long id) {
        Long found=jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_data_scope WHERE tenant_id=? AND user_id=? AND company_id=?",
                Long.class,actor.tenantId(),actor.userId(),id);
        if(found==null||found==0) throw missing();
    }

    private CompanyAccess companyScope(CompanyAccess actor,long id) {
        return new CompanyAccess(actor.tenantId(),actor.userId(),id,true,java.util.Set.of(),actor.permissions());
    }

    static void version(Long supplied,long actual) {
        if(supplied==null) throw new ApiException(400,"VALIDATION_FAILED","请提供版本号");
        if(supplied!=actual) throw new ApiException(409,"VERSION_CONFLICT","资料已被他人修改，请刷新后重试",Map.of("currentVersion",actual));
    }

    static ApiException missing() {return new ApiException(404,"NOT_FOUND","资料不存在或无权查看");}
}
