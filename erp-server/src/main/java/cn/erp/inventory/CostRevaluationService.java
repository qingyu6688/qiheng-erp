package cn.erp.inventory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import cn.erp.accounting.LedgerService;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ActorScope;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiRows;
import cn.erp.shared.AuditService;
import cn.erp.shared.BusinessCommands;
import cn.erp.shared.DocumentNumbers;
import cn.erp.shared.FinancialScope;
import cn.erp.shared.Ids;
import cn.erp.shared.OrgOwnership;
import cn.erp.shared.WorkflowBusiness;
import cn.erp.shared.WorkflowContext;
import cn.erp.shared.WorkflowPort;

import static cn.erp.inventory.CostRevaluationPlanner.*;

@Service
@Transactional(readOnly=true)
public class CostRevaluationService implements WorkflowBusiness {
    static final String TYPE="cost_revaluation";
    private final JdbcTemplate jdbc;
    private final CostRevaluationPlanner planner;
    private final InventoryService inventory;
    private final LedgerService ledger;
    private final BusinessCommands commands;
    private final DocumentNumbers numbers;
    private final AuditService audit;
    private WorkflowPort workflow;
    @Autowired void workflow(WorkflowPort workflow) { this.workflow=workflow; }

    CostRevaluationService(JdbcTemplate jdbc,CostRevaluationPlanner planner,InventoryService inventory,
                           LedgerService ledger,BusinessCommands commands,DocumentNumbers numbers,AuditService audit) {
        this.jdbc=jdbc; this.planner=planner; this.inventory=inventory; this.ledger=ledger;
        this.commands=commands; this.numbers=numbers; this.audit=audit;
    }

    public Map<String,Object> get(CompanyAccess actor,long id) {
        require(actor,"view");
        var head=head(actor,id,false);
        var result=ApiRows.row(head);
        result.put("warehouseName",jdbc.queryForObject("SELECT name FROM warehouse WHERE tenant_id=? AND company_id=? AND id=?",
                String.class,actor.tenantId(),actor.companyId(),head.get("warehouse_id")));
        result.put("skuName",jdbc.queryForObject("SELECT name FROM sku WHERE tenant_id=? AND id=?",String.class,actor.tenantId(),head.get("sku_id")));
        result.put("lines",jdbc.queryForList("SELECT l.*,m.source_type,m.source_id,m.source_line_id,m.movement_type,m.business_date,m.posted_at,m.on_hand_delta"
                +" FROM cost_revaluation_line l JOIN inventory_movement m ON m.id=l.movement_id AND m.tenant_id=l.tenant_id AND m.company_id=l.company_id"
                +" WHERE l.tenant_id=? AND l.company_id=? AND l.revaluation_id=? ORDER BY m.posted_at,m.id",actor.tenantId(),actor.companyId(),id)
                .stream().map(ApiRows::row).toList());
        result.put("issuedDelta",amount(head,"stock_delta").negate().toPlainString());
        var available=switch(head.get("status").toString()) {
            case "draft","rejected" -> List.of("refresh","submit","void");
            case "approved" -> List.of("refresh","post","void");
            default -> List.<String>of();
        };
        result.put("allowedActions",available.stream().filter(action->actor.hasPermission(permission(action))).toList());
        return result;
    }

    public Map<String,Object> list(CompanyAccess actor,String status,Long cursor,int limit) {
        require(actor,"view");
        if (limit<1 || limit>100 || cursor!=null && cursor<=0) throw invalid("分页参数无效");
        if (status!=null && !Set.of("draft","pending","approved","rejected","posted","voided").contains(status)) throw invalid("任务状态无效");
        var args=new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId(),cursor==null?Long.MAX_VALUE:cursor));
        String filter="h.tenant_id=? AND h.company_id=? AND h.id<?";
        if (status!=null) { filter+=" AND h.status=?"; args.add(status); }
        args.add(limit+1);
        var rows=jdbc.queryForList("SELECT h.*,w.name warehouse_name,s.name sku_name FROM cost_revaluation h"
                +" JOIN warehouse w ON w.tenant_id=h.tenant_id AND w.company_id=h.company_id AND w.id=h.warehouse_id"
                +" JOIN sku s ON s.tenant_id=h.tenant_id AND s.id=h.sku_id WHERE "+filter+" ORDER BY h.id DESC LIMIT ?",args.toArray());
        var result=new LinkedHashMap<String,Object>();
        result.put("items",rows.stream().limit(limit).map(ApiRows::row).toList());
        result.put("nextCursor",rows.size()>limit?rows.get(limit-1).get("id").toString():null);
        return result;
    }

    public Map<String,Object> metadata(CompanyAccess actor,String search) {
        require(actor,"view");
        if (search!=null && search.length()>100) throw invalid("商品搜索最多100字");
        var warehouses=jdbc.queryForList("SELECT id,name FROM warehouse WHERE tenant_id=? AND company_id=? AND status='active' ORDER BY id",
                actor.tenantId(),actor.companyId()).stream().map(ApiRows::row).toList();
        var skus=jdbc.queryForList("SELECT id,name FROM sku WHERE tenant_id=? AND status='active' AND name LIKE ? ORDER BY id LIMIT 200",
                actor.tenantId(),"%"+(search==null?"":search)+"%").stream().map(ApiRows::row).toList();
        return Map.of("warehouses",warehouses,"skus",skus,"skuLimit",200);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> create(CompanyAccess actor,String key,CostRevaluationModels.Input input) {
        require(actor,"create"); require(actor,"view");
        if (input==null || input.reason()==null || input.reason().isBlank() || input.reason().length()>500) throw invalid("请填写重估原因，最多500字");
        return execute(actor,"create",key,input,201,()->{
            var plan=planner.calculate(actor,input.warehouseId(),input.skuId(),input.startDate(),input.businessDate());
            long id=Ids.next();
            jdbc.update("""
                    INSERT INTO cost_revaluation(id,tenant_id,company_id,document_no,warehouse_id,sku_id,start_date,business_date,reason,
                      preview_hash,inventory_version,quantity,before_cost,after_cost,stock_delta,event_count,owner_user_id,owner_department_id,created_by,updated_by)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,id,actor.tenantId(),actor.companyId(),numbers.next(actor,"CR",input.businessDate()),input.warehouseId(),input.skuId(),
                    input.startDate(),input.businessDate(),input.reason().trim(),plan.hash(),plan.inventoryVersion(),plan.quantity(),plan.before(),plan.after(),
                    plan.delta(),plan.eventCount(),actor.userId(),OrgOwnership.department(actor),actor.userId(),actor.userId());
            saveLines(actor,id,plan);
            return audited(actor,id,"create",null,null);
        });
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> command(CompanyAccess actor,long id,String action,String key,CostRevaluationModels.Command input) {
        require(actor,"view"); FinancialScope.require(actor,permission(action));
        if (input==null || input.version()==null || input.version()<0 || input.reason()!=null && input.reason().length()>500)
            throw invalid("请提供有效版本号，操作意见最多500字");
        head(actor,id,false);
        return execute(actor,action,key,Map.of("id",id,"input",input),200,()->{
            workflow.guard(actor,TYPE,id,action);
            var before=head(actor,id,true);
            if (input.version()!=number(before,"version")) throw new ApiException(409,"VERSION_CONFLICT","成本重估任务已变化，请刷新后重试");
            switch(action) {
                case "refresh" -> refresh(actor,before);
                case "submit" -> {
                    state(before,"draft","rejected");
                    unchanged(actor,before);
                    if (lineCount(actor,id)==0) throw invalid("当前没有成本差异，无需提交重估");
                    status(actor,id,"pending");
                    workflow.start(actor,TYPE,id,input.workflowAssignees()==null?Map.of():input.workflowAssignees());
                }
                case "approve","reject" -> review(actor,before,action,input.reason());
                case "post" -> post(actor,before);
                case "void" -> { state(before,"draft","rejected","approved"); status(actor,id,"voided"); }
                default -> throw invalid("成本重估操作无效");
            }
            return audited(actor,id,action,before,input.reason());
        });
    }

    private void refresh(CompanyAccess actor,Map<String,Object> head) {
        state(head,"draft","rejected","approved");
        var plan=calculate(actor,head);
        long id=number(head,"id");
        jdbc.update("UPDATE cost_revaluation SET status='draft',preview_hash=?,inventory_version=?,quantity=?,before_cost=?,after_cost=?,stock_delta=?,"
                +"event_count=?,approved_by=NULL,approved_at=NULL,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",
                plan.hash(),plan.inventoryVersion(),plan.quantity(),plan.before(),plan.after(),plan.delta(),plan.eventCount(),actor.userId(),actor.tenantId(),actor.companyId(),id);
        jdbc.update("DELETE FROM cost_revaluation_line WHERE tenant_id=? AND company_id=? AND revaluation_id=?",actor.tenantId(),actor.companyId(),id);
        saveLines(actor,id,plan);
    }

    private void review(CompanyAccess actor,Map<String,Object> head,String action,String reason) {
        state(head,"pending");
        if (actor.userId()==number(head,"created_by")) throw new ApiException(422,"TWO_PERSON_REQUIRED","成本重估审批人不能是制单人");
        if (action.equals("reject")) {
            if (reason==null || reason.isBlank()) throw invalid("请填写驳回原因");
            status(actor,number(head,"id"),"rejected"); return;
        }
        unchanged(actor,head);
        jdbc.update("UPDATE cost_revaluation SET status='approved',approved_by=?,approved_at=UTC_TIMESTAMP(3),version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",
                actor.userId(),actor.userId(),actor.tenantId(),actor.companyId(),head.get("id"));
    }

    private void post(CompanyAccess actor,Map<String,Object> head) {
        state(head,"approved");
        var plan=unchanged(actor,head);
        long id=number(head,"id"),warehouse=number(head,"warehouse_id"),sku=number(head,"sku_id");
        var key=new InventoryModels.Key(warehouse,sku);
        var changes=jdbc.queryForList("SELECT id,cost_delta FROM cost_revaluation_line WHERE tenant_id=? AND company_id=? AND revaluation_id=? ORDER BY id",
                actor.tenantId(),actor.companyId(),id).stream().map(line->new InventoryService.RevaluationChange(number(line,"id"),amount(line,"cost_delta"))).toList();
        inventory.revalueCost(actor,new InventoryModels.Posting(key,new InventoryModels.Source(TYPE,id,id),
                date(head,"business_date"),plan.period(),"revaluation"),changes);
        BigDecimal delta=plan.delta();
        if (delta.signum()!=0) ledger.post(actor,new LedgerService.Posting(TYPE,id,0,plan.period(),date(head,"business_date"),List.of(
                new LedgerService.Line("INVENTORY",delta.signum()>0?"D":"C",delta.abs(),null,null,warehouse,sku,id,"受控成本重估"),
                new LedgerService.Line("COST_VARIANCE",delta.signum()>0?"C":"D",delta.abs(),null,null,warehouse,sku,id,"受控成本重估"))));
        jdbc.update("UPDATE cost_revaluation SET status='posted',period_id=?,posted_by=?,posted_at=UTC_TIMESTAMP(3),version=version+1,updated_by=?"
                +" WHERE tenant_id=? AND company_id=? AND id=?",plan.period(),actor.userId(),actor.userId(),actor.tenantId(),actor.companyId(),id);
    }

    private CostRevaluationPlanner.Plan unchanged(CompanyAccess actor,Map<String,Object> head) {
        var plan=calculate(actor,head);
        if (!plan.hash().equals(head.get("preview_hash"))) throw new ApiException(409,"COST_PREVIEW_CHANGED","库存或成本流水已变化，请重新生成预览并提交审批");
        return plan;
    }
    private CostRevaluationPlanner.Plan calculate(CompanyAccess actor,Map<String,Object> head) {
        return planner.calculate(actor,number(head,"warehouse_id"),number(head,"sku_id"),date(head,"start_date"),date(head,"business_date"));
    }
    private void saveLines(CompanyAccess actor,long id,CostRevaluationPlanner.Plan plan) {
        var rows=plan.lines().stream().map(line->new Object[]{Ids.next(),actor.tenantId(),actor.companyId(),id,line.movement(),line.before(),line.after(),line.delta()}).toList();
        if (!rows.isEmpty()) jdbc.batchUpdate("INSERT INTO cost_revaluation_line(id,tenant_id,company_id,revaluation_id,movement_id,before_cost,after_cost,cost_delta) VALUES(?,?,?,?,?,?,?,?)",rows);
    }
    private long lineCount(CompanyAccess actor,long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM cost_revaluation_line WHERE tenant_id=? AND company_id=? AND revaluation_id=?",Long.class,actor.tenantId(),actor.companyId(),id);
    }
    private Map<String,Object> head(CompanyAccess actor,long id,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM cost_revaluation WHERE tenant_id=? AND company_id=? AND id=?"+(lock?" FOR UPDATE":""),actor.tenantId(),actor.companyId(),id);
        if (rows.isEmpty()) throw new ApiException(404,"NOT_FOUND","成本重估任务不存在或不在当前公司");
        return rows.getFirst();
    }
    private void status(CompanyAccess actor,long id,String status) {
        jdbc.update("UPDATE cost_revaluation SET status=?,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",
                status,actor.userId(),actor.tenantId(),actor.companyId(),id);
    }
    private void state(Map<String,Object> head,String... states) {
        if (!Set.of(states).contains(head.get("status"))) throw new ApiException(409,"STATE_CONFLICT","当前任务状态不允许此操作");
    }
    private Map<String,Object> audited(CompanyAccess actor,long id,String action,Map<String,Object> before,String reason) {
        var result=get(actor,id); var after=new LinkedHashMap<>(result);
        if (reason!=null && !reason.isBlank()) after.put("operationReason",reason.trim());
        audit.record(actor,TYPE+"."+action,TYPE,id,before,after); return result;
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    private Map<String,Object> execute(CompanyAccess actor,String action,String key,Object input,int status,Supplier<Map<String,Object>> work) {
        return commands.execute(actor,TYPE+"."+action,key,input,status,work,(Class)Map.class);
    }
    private static String permission(String action) { return TYPE+":"+(action.equals("refresh")?"submit":action.equals("reject")?"approve":action); }
    private static void require(CompanyAccess actor,String action) { FinancialScope.require(actor,permission(action)); }

    @Override public String businessType() { return TYPE; }
    @Override public Map<String,Object> snapshot(ActorScope scope,long id) {
        var actor=actor(scope); require(actor,"view"); var head=head(actor,id,false);
        var result=new LinkedHashMap<String,Object>();
        result.put("createdBy",number(head,"created_by")); result.put("ownerDeptId",number(head,"owner_department_id"));
        result.put("documentNo",head.get("document_no")); result.put("totalAmount",amount(head,"stock_delta").abs().toPlainString());
        result.put("title","成本重估 "+head.get("document_no")); result.put("warehouseIds",List.of(head.get("warehouse_id").toString()));
        result.put("previewHash",head.get("preview_hash")); result.put("beforeCost",amount(head,"before_cost").toPlainString());
        result.put("afterCost",amount(head,"after_cost").toPlainString()); return result;
    }
    @Override @Transactional(isolation=Isolation.READ_COMMITTED)
    public void decide(ActorScope scope,long id,String outcome,String comment,String key) {
        if (!WorkflowContext.allows(scope,TYPE,id) || !Set.of("approved","rejected","cancelled","terminated").contains(outcome))
            throw new ApiException(403,"FORBIDDEN","成本重估必须通过当前有效工作流审批");
        var actor=actor(scope); var head=head(actor,id,false);
        if (Set.of("cancelled","terminated").contains(outcome)) {
            require(actor,"view"); state(head,"pending"); status(actor,id,outcome.equals("cancelled")?"rejected":"voided");
            audited(actor,id,outcome,head,comment); return;
        }
        command(actor,id,outcome.equals("approved")?"approve":"reject",key,new CostRevaluationModels.Command(number(head,"version"),
                comment==null || comment.isBlank()?"流程未通过":comment,null));
    }
    private CompanyAccess actor(ActorScope actor) {
        if (actor instanceof CompanyAccess access) return access;
        throw new ApiException(403,"FORBIDDEN","缺少当前公司授权");
    }
}
