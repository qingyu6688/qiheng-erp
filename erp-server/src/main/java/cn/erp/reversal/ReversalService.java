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
    private InboundInvoiceChangePort invoices;
    @org.springframework.beans.factory.annotation.Autowired
    void invoices(InboundInvoiceChangePort invoices) {this.invoices=invoices;}
    private SalesInvoiceLock salesInvoiceLock;
    @org.springframework.beans.factory.annotation.Autowired void salesInvoiceLock(SalesInvoiceLock salesInvoiceLock) {this.salesInvoiceLock=salesInvoiceLock;}
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
    private final PutawayPort putaway;
    public ReversalService(JdbcTemplate jdbc,FinanceSupport finance,ReversalSources sources,ReversalProgress progress,ReversalPosting posting,BusinessCommands commands,DocumentNumbers numbers,AuditService audit,PutawayPort putaway) {
        this.jdbc=jdbc;this.finance=finance;this.sources=sources;this.progress=progress;this.posting=posting;this.commands=commands;this.numbers=numbers;this.audit=audit;
        this.putaway=putaway;
    }
    public Map<String,Object> create(CompanyAccess scope,String key,ReversalModels.Input input) {
        if(input==null||input.sourceType()==null||input.sourceId()==null||input.sourceId()<=0||input.reason()==null||input.reason().isBlank()||input.reason().length()>200)throw invalid("请填写有效的来源和冲销原因");
        date(input.businessDate());
        sources.head(scope,input.sourceType(),input.sourceId(),false);
        return execute(scope,"create",key,input,201,()->{
            long period=finance.period(scope,input.businessDate());
            sources.prepare(scope,input.sourceType(),input.sourceId(),period,input.businessDate());
            var head=sources.head(scope,input.sourceType(),input.sourceId(),true);sources.posted(head,input.sourceType());
            putaway.requireReversible(scope,input.sourceType(),input.sourceId());
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
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String,Object> command(CompanyAccess scope,long id,String action,String key,ReversalModels.Version input) {
        var initial=find(scope,id,false);sources.head(scope,initial.get("source_type").toString(),id(initial,"source_id"),false);
        return execute(scope,action,key,List.of(id,input),200,()->{
            workflow.guard(scope,"document_reversal",id,action);
            var snapshot=find(scope,id,false);String type=snapshot.get("source_type").toString();long source=id(snapshot,"source_id");
            if(action.equals("post")&&Set.of("shipment","sales_return","sales_price_adjustment").contains(type))salesInvoiceLock.lock(scope);
            if(action.equals("post")&&Set.of("goods_receipt","purchase_return","price_adjustment","purchase_fee_doc").contains(type))invoices.lock(scope);
            var day=((java.sql.Date)snapshot.get("business_date")).toLocalDate();
            long period=action.equals("post")?finance.period(scope,day):0;
            if(action.equals("post"))sources.prepare(scope,type,source,period,day);
            var head=sources.head(scope,type,source,true);progress.lock(scope,type,source);
            var before=find(scope,id,true);version(before,input.version());
            if(action.equals("approve")||action.equals("reject")) {
                state(before,"pending");
                if(id(before,"created_by")==scope.userId())throw new ApiException(422,"SAME_OPERATOR_NOT_ALLOWED","冲销审核人不能是申请人");
                if(action.equals("reject")&&(input.reason()==null||input.reason().isBlank()))throw invalid("请填写驳回原因");
                if(action.equals("approve")) putaway.requireReversible(scope,type,source);
                jdbc.update("UPDATE document_reversal SET status=?,approved_by=?,approved_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",action.equals("approve")?"approved":"rejected",scope.userId(),id);
            } else if(action.equals("cancel")) {
                return cancelBlockedReversal(scope,id,before,type,source,input.reason());
            } else if(action.equals("post")) {
                state(before,"approved");sources.posted(head,type);sources.dependencies(scope,type,source);
                putaway.requireReversible(scope,type,source);
                posting.post(scope,id,type,source,head,period,((java.sql.Date)before.get("business_date")).toLocalDate());
                invoices.changed(scope,type,source,"采购来源已冲销，请重新核对");
                putaway.cancelUntouched(scope,type,source);
                jdbc.update("UPDATE document_reversal SET status='posted',period_id=?,posted_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",period,id);
            } else throw invalid("冲销操作无效");
            audit.record(scope,"reversal."+action,"document_reversal",id,null,Map.of("reason",input.reason()==null?"":input.reason(),"sourceType",type,"sourceId",Long.toString(source)));
            return get(scope,id);
        });
    }
    private Map<String,Object> cancelBlockedReversal(CompanyAccess scope,long id,Map<String,Object> before,String type,long source,String reason) {
        if(!scope.hasPermission("reversal:approve")) throw new ApiException(403,"FORBIDDEN","没有撤销冲销的权限");
        state(before,"approved");
        if(isApplicant(scope,before)) throw new ApiException(422,"SAME_OPERATOR_NOT_ALLOWED","撤销人不能是冲销申请人");
        if(reason==null||reason.isBlank()||reason.length()>200) throw invalid("请填写撤销原因，最多200字");
        if(!putaway.hasConfirmedMovement(scope,type,source,true))
            throw new ApiException(409,"STATE_CONFLICT","只有因原单已上架而无法过账的冲销才能撤销");
        // 保留原审批人、审批时间和申请原因，撤销人及原因单独记入审计。
        jdbc.update("UPDATE document_reversal SET status='rejected',version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",
                scope.tenantId(),scope.companyId(),id);
        audit.record(scope,"reversal.cancel","document_reversal",id,Map.of("status","approved"),
                Map.of("status","rejected","reason",reason.trim(),"sourceType",type,"sourceId",Long.toString(source)));
        return get(scope,id);
    }

    private static boolean isApplicant(CompanyAccess scope,Map<String,Object> reversal) {
        Object creator=reversal.get("created_by");
        if(creator==null) creator=reversal.get("owner_user_id");
        return Long.toString(scope.userId()).equals(Objects.toString(creator,""));
    }

    @Transactional(readOnly=true)
    public Map<String,Object> get(CompanyAccess scope,long id) {
        var before=find(scope,id,false);String type=before.get("source_type").toString();var source=sources.head(scope,type,id(before,"source_id"),false);
        var result=row(before);result.put("documentNo",before.get("reversal_no"));result.put("sourceNo",sources.number(type,source));result.put("warehouseIds",sources.warehouses(source));
        var actions=new ArrayList<String>();
        if(before.get("status").equals("pending")&&id(before,"created_by")!=scope.userId()&&scope.hasPermission("reversal:approve")){actions.add("approve");actions.add("reject");}
        if(before.get("status").equals("approved")) {
            if(putaway.hasConfirmedMovement(scope,type,id(before,"source_id"),false)) {
                if(!isApplicant(scope,before)&&scope.hasPermission("reversal:approve")) actions.add("cancel");
            } else if(scope.hasPermission("reversal:post")) actions.add("post");
        }
        result.put("allowedActions",cn.erp.shared.WorkflowViews.actions(before,actions));cn.erp.shared.WorkflowViews.decorate(jdbc,scope,"document_reversal",id,result);return result;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> list(CompanyAccess scope,String status,Long cursor,int limit) {
        long before=cursor(cursor,limit);if(status!=null&&!Set.of("pending","approved","posted","rejected").contains(status))throw invalid("冲销状态无效");
        var allowed=ReversalSources.TYPES.stream().filter(type->scope.hasPermission(ManufacturingReversalPort.viewPermission(type))).sorted().toList();
        var parts=new ArrayList<String>();var args=new ArrayList<Object>(List.of(scope.tenantId(),scope.companyId(),before));
        if(status!=null){args.add(status);}
        for(String type:allowed) {
            if(type.equals("purchase_fee_doc")) {
                parts.add("(r.source_type='purchase_fee_doc' AND EXISTS(SELECT 1 FROM purchase_fee_doc h WHERE h.tenant_id=r.tenant_id AND h.company_id=r.company_id AND h.id=r.source_id AND "+PurchaseFeeScope.condition(scope,"h")+"))");
            } else if(type.equals("price_adjustment")) {
                parts.add("(r.source_type='price_adjustment' AND EXISTS(SELECT 1 FROM price_adjustment h WHERE h.tenant_id=r.tenant_id AND h.company_id=r.company_id AND h.id=r.source_id AND "+PriceAdjustmentScope.condition(scope,"h")+"))");
            } else if(ManufacturingReversalPort.supports(type)) {
                parts.add("(r.source_type='"+type+"' AND EXISTS(SELECT 1 FROM "+type+" h WHERE h.tenant_id=r.tenant_id AND h.company_id=r.company_id AND h.id=r.source_id AND "+ManufacturingScope.stockCondition(scope,"h")+"))");
            } else if(scope.allWarehouses()||Set.of("receipt","payment","account_transfer").contains(type))parts.add("r.source_type='"+type+"'");
            else if(!scope.warehouseIds().isEmpty()) {
                parts.add("(r.source_type='"+type+"' AND EXISTS(SELECT 1 FROM "+type+" h WHERE h.tenant_id=r.tenant_id AND h.company_id=r.company_id AND h.id=r.source_id AND h.warehouse_id IN("+String.join(",",Collections.nCopies(scope.warehouseIds().size(),"?"))+")))");
                args.addAll(scope.warehouseIds());
            }
        }
        if(parts.isEmpty())return page(List.of(),limit);
        args.add(limit+1);
        var rows=jdbc.queryForList("SELECT r.id FROM document_reversal r WHERE r.tenant_id=? AND r.company_id=? AND r.id<?"+(status==null?"":" AND r.status=?")+OrgOwnership.filter(scope,"r")+" AND ("+String.join(" OR ",parts)+") ORDER BY r.id DESC LIMIT ?",args.toArray());
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
