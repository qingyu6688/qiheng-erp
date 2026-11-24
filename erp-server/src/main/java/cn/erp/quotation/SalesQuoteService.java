package cn.erp.quotation;

import java.time.LocalDate;
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

import cn.erp.identity.CompanyAccess;
import cn.erp.pricing.MinimumSalePrice;
import cn.erp.salesorder.SalesOrderService;
import cn.erp.shared.ActorScope;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiRows;
import cn.erp.shared.AuditService;
import cn.erp.shared.BusinessCommands;
import cn.erp.shared.DocumentNumbers;
import cn.erp.shared.Ids;
import cn.erp.shared.QuotationScopePort;
import cn.erp.shared.WorkflowBusiness;
import cn.erp.shared.WorkflowContext;
import cn.erp.shared.WorkflowPort;

import static cn.erp.quotation.SalesQuoteQueries.*;

@Service
@Transactional(readOnly=true)
public class SalesQuoteService implements WorkflowBusiness,QuotationScopePort {
    private final JdbcTemplate jdbc;
    private final SalesQuoteQueries queries;
    private final SalesQuoteWriter writer;
    private final MinimumSalePrice minimum;
    private final SalesOrderService orders;
    private final BusinessCommands commands;
    private final DocumentNumbers numbers;
    private final AuditService audit;
    private WorkflowPort workflow;
    @Autowired void workflow(WorkflowPort workflow) {this.workflow=workflow;}
    SalesQuoteService(JdbcTemplate jdbc,SalesQuoteQueries queries,SalesQuoteWriter writer,MinimumSalePrice minimum,SalesOrderService orders,
                      BusinessCommands commands,DocumentNumbers numbers,AuditService audit) {
        this.jdbc=jdbc; this.queries=queries; this.writer=writer; this.minimum=minimum; this.orders=orders;
        this.commands=commands; this.numbers=numbers; this.audit=audit;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> create(CompanyAccess actor,String key,SalesQuoteModels.Input input) {
        require(actor,"create"); require(actor,"view");
        return execute(actor,"create",key,input,201,()->{
            var preview=writer.preview(actor,input);
            long id=Ids.next();
            writer.save(actor,id,numbers.next(actor,"SQ",input.order().businessDate()),input,preview,true);
            return audited(actor,id,"create",null,null);
        });
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> update(CompanyAccess actor,long id,String key,SalesQuoteModels.Input input) {
        require(actor,"update"); require(actor,"view"); queries.head(actor,id,false);
        return execute(actor,"update",key,Map.of("id",id,"input",input),200,()->{
            var before=queries.head(actor,id,true);version(before,input.version());state(before,"draft","rejected","issued","expired");
            var old=queries.get(actor,id);
            var preview=writer.preview(actor,input);writer.save(actor,id,before.get("document_no").toString(),input,preview,false);
            return audited(actor,id,"update",old,null);
        });
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> preview(CompanyAccess actor,SalesQuoteModels.Input input) {
        if(!actor.hasPermission(TYPE+":create") && !actor.hasPermission(TYPE+":update"))throw new ApiException(403,"FORBIDDEN","没有报价录入权限");
        require(actor,"view");return Map.of("pricing",writer.preview(actor,input));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> command(CompanyAccess actor,long id,String action,String key,SalesQuoteModels.Command input) {
        if(!Set.of("submit","approve","reject","convert","void").contains(action))throw invalid("报价操作无效");
        require(actor,action);require(actor,"view");queries.head(actor,id,false);
        if(action.equals("convert") && !actor.hasPermission("sales_order:create"))throw new ApiException(403,"FORBIDDEN","报价转单还需要创建销售订单权限");
        if(input==null || input.reason()!=null && input.reason().length()>500)throw invalid("请提供有效操作内容，意见最多500字");
        return execute(actor,action,key,Map.of("id",id,"input",input),200,()->{
            workflow.guard(actor,TYPE,id,action);
            var before=queries.head(actor,id,true);version(before,input.version());
            switch(action) {
                case "submit" -> submit(actor,before,input);
                case "approve","reject" -> review(actor,before,action,input.reason());
                case "convert" -> convert(actor,before,input.businessDate());
                case "void" -> {state(before,"draft","rejected","issued","expired");status(actor,id,"voided");}
                default -> throw invalid("报价操作无效");
            }
            return audited(actor,id,action,before,input.reason());
        });
    }

    private void submit(CompanyAccess actor,Map<String,Object> head,SalesQuoteModels.Command input) {
        state(head,"draft","rejected");valid(head);
        writer.conversionInput(actor,head,date(head,"business_date"));
        var price=minimum.inspectQuotation(actor,number(head,"id"),true);SalesQuoteWriter.blocked(price);
        if(Boolean.TRUE.equals(price.get("belowMinPrice")) && "approval".equals(price.get("belowMinPricePolicy"))) {
            status(actor,number(head,"id"),"pending");
            workflow.start(actor,TYPE,number(head,"id"),input.workflowAssignees()==null?Map.of():input.workflowAssignees());
        } else status(actor,number(head,"id"),"issued");
    }
    private void review(CompanyAccess actor,Map<String,Object> head,String action,String reason) {
        state(head,"pending");
        if(number(head,"created_by")==actor.userId())throw new ApiException(422,"TWO_PERSON_REQUIRED","报价审批人不能是制单人");
        if(action.equals("reject")) {
            if(reason==null || reason.isBlank())throw invalid("请填写驳回原因");
            status(actor,number(head,"id"),"rejected");return;
        }
        valid(head);
        writer.conversionInput(actor,head,date(head,"business_date"));
        var price=minimum.inspectQuotation(actor,number(head,"id"),true);SalesQuoteWriter.blocked(price);
        if(Boolean.TRUE.equals(price.get("belowMinPrice")) && "approval".equals(price.get("belowMinPricePolicy"))
                && !WorkflowContext.priceApproved(actor,TYPE,number(head,"id"),price.get("priceFingerprint").toString()))
            throw new ApiException(422,"PRICE_APPROVAL_REQUIRED","报价须取得与当前最低价快照一致的人工价格审批");
        jdbc.update("UPDATE sales_quote SET status='issued',approved_by=?,approved_at=UTC_TIMESTAMP(3),approved_price_fingerprint=?,version=version+1,updated_by=?"
                +" WHERE tenant_id=? AND company_id=? AND id=?",actor.userId(),price.get("priceFingerprint"),actor.userId(),actor.tenantId(),actor.companyId(),head.get("id"));
    }
    private void convert(CompanyAccess actor,Map<String,Object> head,LocalDate day) {
        state(head,"issued");valid(head);
        var price=minimum.inspectQuotation(actor,number(head,"id"),false);SalesQuoteWriter.blocked(price);
        if(Boolean.TRUE.equals(price.get("belowMinPrice")) && "approval".equals(price.get("belowMinPricePolicy"))
                && !price.get("priceFingerprint").equals(head.get("approved_price_fingerprint")))
            throw new ApiException(422,"PRICE_APPROVAL_REQUIRED","最低售价依据已变化，请修改报价并重新审批后转单");
        var input=writer.conversionInput(actor,head,day==null?today():day);
        var order=orders.createFromQuotation(actor,number(head,"id"),input);
        jdbc.update("UPDATE sales_quote SET status='converted',converted_order_id=?,converted_by=?,converted_at=UTC_TIMESTAMP(3),version=version+1,updated_by=?"
                +" WHERE tenant_id=? AND company_id=? AND id=?",order.get("id"),actor.userId(),actor.userId(),actor.tenantId(),actor.companyId(),head.get("id"));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public void expire(long tenant,long company,long id) {
        var actor=new CompanyAccess(tenant,0,company,true,Set.of(),Set.of(TYPE+":view"));
        // 与人工审批保持同一锁序：流程在先，报价在后。
        workflow.expireQuotation(actor,id);
        var rows=jdbc.queryForList("SELECT * FROM sales_quote WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",tenant,company,id);
        if(rows.isEmpty())return;
        var before=rows.getFirst();
        if(!date(before,"valid_to").isBefore(today()) || Set.of("converted","voided","expired").contains(before.get("status")))return;
        status(actor,id,"expired");
        audit.record(actor,TYPE+".expire",TYPE,id,ApiRows.row(before),Map.of("id",Long.toString(id),"status","expired"));
    }
    private void valid(Map<String,Object> head) {
        if(date(head,"valid_to").isBefore(today()))throw new ApiException(422,"QUOTE_EXPIRED","报价已过期，请修改有效期并重新确认");
    }
    private void version(Map<String,Object> head,Long requested) {
        if(requested==null || requested<0)throw invalid("请提供有效版本号");
        if(requested!=number(head,"version"))throw new ApiException(409,"VERSION_CONFLICT","报价已被修改，请刷新后重试");
    }
    private void state(Map<String,Object> head,String... allowed) {
        if(!Set.of(allowed).contains(head.get("status")))throw new ApiException(409,"STATE_CONFLICT","当前报价状态不允许此操作");
    }
    private void status(CompanyAccess actor,long id,String status) {
        jdbc.update("UPDATE sales_quote SET status=?,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",status,actor.userId(),actor.tenantId(),actor.companyId(),id);
    }
    private Map<String,Object> audited(CompanyAccess actor,long id,String action,Map<String,Object> before,String reason) {
        var result=queries.get(actor,id);var after=new LinkedHashMap<>(result);
        if(reason!=null && !reason.isBlank())after.put("operationReason",reason.trim());
        audit.record(actor,TYPE+"."+action,TYPE,id,before,after);return result;
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    private Map<String,Object> execute(CompanyAccess actor,String action,String key,Object input,int status,Supplier<Map<String,Object>> work) {
        return commands.execute(actor,TYPE+"."+action,key,input,status,work,(Class)Map.class);
    }
    @Override public String businessType() {return TYPE;}
    @Override public void requireVisible(ActorScope scope,long id) {var actor=actor(scope);require(actor,"view");queries.head(actor,id,false);}
    @Override public String condition(ActorScope scope,String alias) {return queries.visible(actor(scope),alias);}
    @Override public Map<String,Object> snapshot(ActorScope scope,long id) {
        var actor=actor(scope);require(actor,"view");var head=queries.head(actor,id,false);
        var result=new LinkedHashMap<String,Object>(minimum.inspectQuotation(actor,id,false));
        result.put("createdBy",number(head,"created_by"));result.put("ownerDeptId",number(head,"owner_department_id"));
        result.put("documentNo",head.get("document_no"));result.put("title","销售报价 "+head.get("document_no"));
        result.put("totalAmount",amount(head,"total_amount").toPlainString());result.put("customerId",head.get("customer_id").toString());
        result.put("warehouseIds",jdbc.queryForList("SELECT DISTINCT warehouse_id FROM sales_quote_line WHERE tenant_id=? AND company_id=? AND quote_id=? AND warehouse_id IS NOT NULL AND is_deleted=0",
                Long.class,actor.tenantId(),actor.companyId(),id).stream().map(String::valueOf).toList());return result;
    }
    @Override @Transactional(isolation=Isolation.READ_COMMITTED)
    public void decide(ActorScope scope,long id,String outcome,String reason,String key) {
        if(!WorkflowContext.allows(scope,TYPE,id) || !Set.of("approved","rejected","cancelled","terminated").contains(outcome))
            throw new ApiException(403,"FORBIDDEN","报价审批必须通过当前有效工作流处理");
        var actor=actor(scope);
        if(Set.of("cancelled","terminated").contains(outcome) || actor.userId()==0 && outcome.equals("rejected")) {
            var rows=jdbc.queryForList("SELECT * FROM sales_quote WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",actor.tenantId(),actor.companyId(),id);
            if(rows.isEmpty())throw missing();var before=rows.getFirst();state(before,"pending");
            String next=date(before,"valid_to").isBefore(today())?"expired":outcome.equals("terminated")?"voided":"rejected";
            status(actor,id,next);audit.record(actor,TYPE+"."+outcome,TYPE,id,ApiRows.row(before),Map.of("id",Long.toString(id),"status",next));return;
        }
        var head=queries.head(actor,id,false);
        command(actor,id,outcome.equals("approved")?"approve":"reject",key,new SalesQuoteModels.Command(number(head,"version"),
                reason==null || reason.isBlank()?"流程未通过":reason,null,null));
    }
    private static CompanyAccess actor(ActorScope actor) {
        if(actor instanceof CompanyAccess access)return access;
        throw new ApiException(403,"FORBIDDEN","缺少当前公司授权");
    }
}
