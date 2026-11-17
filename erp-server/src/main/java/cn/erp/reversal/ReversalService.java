package cn.erp.reversal;

import cn.erp.identity.CompanyAccess;
import cn.erp.salesfinance.FinanceSupport;
import cn.erp.shared.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.salesfinance.FinanceSupport.*;

@Service
@Transactional
public class ReversalService {
    private cn.erp.shared.WorkflowPort workflow;
    @org.springframework.beans.factory.annotation.Autowired
    void workflow(cn.erp.shared.WorkflowPort workflow) {this.workflow=workflow;}
    private final JdbcTemplate jdbc;
    private final FinanceSupport finance;
    private final ReversalSources sources;
    private final ReversalProgress progress;
    private final ReversalPosting posting;
    private final BusinessCommands commands;
    private final DocumentNumbers numbers;
    private final AuditService audit;
    public ReversalService(JdbcTemplate jdbc,FinanceSupport finance,ReversalSources sources,ReversalProgress progress,ReversalPosting posting,BusinessCommands commands,DocumentNumbers numbers,AuditService audit) {
        this.jdbc=jdbc;this.finance=finance;this.sources=sources;this.progress=progress;this.posting=posting;this.commands=commands;this.numbers=numbers;this.audit=audit;
    }
    public Map<String,Object> create(CompanyAccess scope,String key,ReversalModels.Input input) {
        if(input==null||input.sourceType()==null||input.sourceId()==null||input.sourceId()<=0||input.reason()==null||input.reason().isBlank()||input.reason().length()>200)throw invalid("请填写有效的来源和冲销原因");
        date(input.businessDate());
        sources.head(scope,input.sourceType(),input.sourceId(),false);
        return execute(scope,"create",key,input,201,()->{
            finance.period(scope,input.businessDate());
            var head=sources.head(scope,input.sourceType(),input.sourceId(),true);sources.posted(head,input.sourceType());
            progress.lock(scope,input.sourceType(),input.sourceId());sources.dependencies(scope,input.sourceType(),input.sourceId());
            if(!jdbc.queryForList("SELECT id FROM document_reversal WHERE tenant_id=? AND company_id=?"+cn.erp.shared.OrgOwnership.filter(scope,"document_reversal")+" AND source_type=? AND source_id=? AND status<>'rejected' FOR UPDATE",Long.class,scope.tenantId(),scope.companyId(),input.sourceType(),input.sourceId()).isEmpty())throw new ApiException(409,"STATE_CONFLICT","原单已有待处理或已完成的冲销单");
            long id=Ids.next();
            jdbc.update("INSERT INTO document_reversal(id,tenant_id,company_id,reversal_no,source_type,source_id,business_date,reason,created_by,owner_user_id,owner_department_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",id,scope.tenantId(),scope.companyId(),numbers.next(scope,"RV",input.businessDate()),input.sourceType(),input.sourceId(),input.businessDate(),input.reason().trim(),scope.userId(),head.get("owner_user_id"),head.get("owner_department_id"));
            workflow.start(scope,"document_reversal",id,input.workflowAssignees());return changed(scope,id,"create");
        });
    }
    @Transactional(readOnly=true)
    public Map<String,Object> workflowPreview(CompanyAccess scope,String type,long id,LocalDate businessDate) {
        if(!scope.hasPermission("reversal:view")) throw new ApiException(403,"FORBIDDEN","没有发起冲销的权限");
        date(businessDate);var source=sources.head(scope,type,id,false);sources.posted(source,type);
        var result=new LinkedHashMap<String,Object>();result.put("createdBy",scope.userId());result.put("ownerDeptId",Objects.toString(source.get("owner_department_id"),"0"));result.put("sourceType",type);result.put("crossPeriod",!businessDate.toString().substring(0,7).equals(source.get("business_date").toString().substring(0,7)));
        result.put("totalAmount","0");for(String key:List.of("total_amount","receivable_amount","payable_amount","amount","total_cost"))if(source.get(key)!=null){result.put("totalAmount",source.get(key));break;}return result;
    }
    public Map<String,Object> command(CompanyAccess scope,long id,String action,String key,ReversalModels.Version input) {
        var initial=find(scope,id,false);sources.head(scope,initial.get("source_type").toString(),id(initial,"source_id"),false);
        return execute(scope,action,key,List.of(id,input),200,()->{
            workflow.guard(scope,"document_reversal",id,action);
            var snapshot=find(scope,id,false);String type=snapshot.get("source_type").toString();long source=id(snapshot,"source_id");
            long period=action.equals("post")?finance.period(scope,((java.sql.Date)snapshot.get("business_date")).toLocalDate()):0;
            var head=sources.head(scope,type,source,true);progress.lock(scope,type,source);
            var before=find(scope,id,true);version(before,input.version());
            if(action.equals("approve")||action.equals("reject")) {
                state(before,"pending");
                if(id(before,"created_by")==scope.userId())throw new ApiException(422,"SAME_OPERATOR_NOT_ALLOWED","冲销审核人不能是申请人");
                if(action.equals("reject")&&(input.reason()==null||input.reason().isBlank()))throw invalid("请填写驳回原因");
                jdbc.update("UPDATE document_reversal SET status=?,approved_by=?,approved_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",action.equals("approve")?"approved":"rejected",scope.userId(),id);
            } else if(action.equals("post")) {
                state(before,"approved");sources.posted(head,type);sources.dependencies(scope,type,source);
                posting.post(scope,id,type,source,head,period,((java.sql.Date)before.get("business_date")).toLocalDate());
                jdbc.update("UPDATE document_reversal SET status='posted',period_id=?,posted_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",period,id);
            } else throw invalid("冲销操作无效");
            audit.record(scope,"reversal."+action,"document_reversal",id,null,Map.of("reason",input.reason()==null?"":input.reason(),"sourceType",type,"sourceId",Long.toString(source)));
            return get(scope,id);
        });
    }
    @Transactional(readOnly=true)
    public Map<String,Object> get(CompanyAccess scope,long id) {
        var before=find(scope,id,false);String type=before.get("source_type").toString();var source=sources.head(scope,type,id(before,"source_id"),false);
        var result=row(before);result.put("documentNo",before.get("reversal_no"));result.put("sourceNo",sources.number(type,source));result.put("warehouseIds",sources.warehouses(source));
        var actions=new ArrayList<String>();
        if(before.get("status").equals("pending")&&id(before,"created_by")!=scope.userId()&&scope.hasPermission("reversal:approve")){actions.add("approve");actions.add("reject");}
        if(before.get("status").equals("approved")&&scope.hasPermission("reversal:post"))actions.add("post");result.put("allowedActions",cn.erp.shared.WorkflowViews.actions(before,actions));cn.erp.shared.WorkflowViews.decorate(jdbc,scope,"document_reversal",id,result);return result;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> list(CompanyAccess scope,String status,Long cursor,int limit) {
        long before=cursor(cursor,limit);if(status!=null&&!Set.of("pending","approved","posted","rejected").contains(status))throw invalid("冲销状态无效");
        var allowed=ReversalSources.TYPES.stream().filter(type->scope.hasPermission(type+":view")).sorted().toList();
        var parts=new ArrayList<String>();var args=new ArrayList<Object>(List.of(scope.tenantId(),scope.companyId(),before));
        if(status!=null){args.add(status);}
        for(String type:allowed) {
            if(scope.allWarehouses()||Set.of("receipt","payment","account_transfer").contains(type))parts.add("r.source_type='"+type+"'");
            else if(!scope.warehouseIds().isEmpty()) {
                parts.add("(r.source_type='"+type+"' AND EXISTS(SELECT 1 FROM "+type+" h WHERE h.tenant_id=r.tenant_id AND h.company_id=r.company_id AND h.id=r.source_id AND h.warehouse_id IN("+String.join(",",Collections.nCopies(scope.warehouseIds().size(),"?"))+")))");
                args.addAll(scope.warehouseIds());
            }
        }
        if(parts.isEmpty())return page(List.of(),limit);
        args.add(limit+1);
        var rows=jdbc.queryForList("SELECT r.id FROM document_reversal r WHERE r.tenant_id=? AND r.company_id=? AND r.id<?"+(status==null?"":" AND r.status=?")+" AND ("+String.join(" OR ",parts)+") ORDER BY r.id DESC LIMIT ?",args.toArray());
        var items=rows.stream().limit(limit).map(candidate->get(scope,id(candidate,"id"))).toList();
        var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("nextCursor",rows.size()>limit?items.getLast().get("id"):null);return result;
    }
    private Map<String,Object> find(CompanyAccess scope,long id,boolean lock) {
        cn.erp.shared.OrgOwnership.require(jdbc,scope,"document_reversal",id);
        var rows=jdbc.queryForList("SELECT * FROM document_reversal WHERE tenant_id=? AND company_id=?"+cn.erp.shared.OrgOwnership.filter(scope,"document_reversal")+" AND id=?"+(lock?" FOR UPDATE":""),scope.tenantId(),scope.companyId(),id);
        if(rows.isEmpty())throw missing();return rows.getFirst();
    }
    private Map<String,Object> changed(CompanyAccess scope,long id,String action){var result=get(scope,id);audit.record(scope,"reversal."+action,"document_reversal",id,null,result);return result;}
    @SuppressWarnings({"unchecked","rawtypes"}) private Map<String,Object> execute(CompanyAccess scope,String action,String key,Object input,int status,java.util.function.Supplier<Map<String,Object>> work){return commands.execute(scope,"reversal."+action,key,input,status,work,(Class)Map.class);}
}
