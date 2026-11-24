package cn.erp.purchasing;

import cn.erp.catalog.CatalogAvailability;
import cn.erp.identity.CompanyAccess;
import cn.erp.pricing.*;
import cn.erp.reference.ReferenceAvailability;
import cn.erp.shared.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static cn.erp.purchasing.PurchaseOrderQueries.*;

@Service
@Transactional
public class PurchaseOrderService {
    private WorkflowPort workflow;
    @org.springframework.beans.factory.annotation.Autowired
    void workflow(WorkflowPort workflow) {this.workflow=workflow;}
    private final JdbcTemplate jdbc;
    private final PurchaseOrderQueries queries;
    private final PurchaseOrderWriter writer;
    private final BusinessCommands commands;
    private final DocumentNumbers numbers;
    private final RequisitionLinks links;
    private final CatalogAvailability catalog;
    private final ReferenceAvailability partners;
    private final AuditService audit;
    private final ObjectMapper json;
    private PurchasePriceSnapshots priceSources;
    @org.springframework.beans.factory.annotation.Autowired void priceSources(PurchasePriceSnapshots priceSources) {this.priceSources=priceSources;}

    public PurchaseOrderService(JdbcTemplate jdbc,PurchaseOrderQueries queries,PurchaseOrderWriter writer,BusinessCommands commands,
            DocumentNumbers numbers,CatalogAvailability catalog,ReferenceAvailability partners,AuditService audit,ObjectMapper json,RequisitionLinks links) {
        this.jdbc=jdbc;this.queries=queries;this.writer=writer;this.commands=commands;this.numbers=numbers;
        this.catalog=catalog;this.partners=partners;this.audit=audit;this.json=json;this.links=links;
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private Map<String,Object> execute(CompanyAccess scope,String operation,String key,Object payload,int status,java.util.function.Supplier<Map<String,Object>> work) {
        return commands.execute(scope,"purchase_order."+operation,key,payload,status,work,(Class)Map.class);
    }

    public Map<String,Object> create(CompanyAccess scope,String key,PurchaseOrderModels.Input input) {
        return execute(scope,"create",key,input,201,()->{
            var preview=writer.price(scope,input);
            long id=Ids.next();String no=numbers.next(scope,"PO",input.businessDate());
            jdbc.update("INSERT INTO purchase_order(id,tenant_id,company_id,order_no,supplier_id,business_date,price_tax_inclusive,created_by,updated_by,owner_user_id,owner_department_id) VALUES(?,?,?,?,?,?,1,?,?,?,?)",
                    id,scope.tenantId(),scope.companyId(),no,identifier(input.supplierId()),input.businessDate(),scope.userId(),scope.userId(),scope.userId(),cn.erp.shared.OrgOwnership.department(scope));
            writer.writeNew(scope,id,input,preview);
            var result=queries.get(scope,id);
            audit.record(scope,"purchase_order.create","purchase_order",id,null,result);
            return result;
        });
    }

    public Map<String,Object> edit(CompanyAccess scope,long id,PurchaseOrderModels.Input input) {
        var before=queries.header(scope,id,true);version(input.version(),before);state(before,"draft");
        var preview=writer.price(scope,input);
        writer.write(scope,id,input,preview);touch(scope,id);
        var result=queries.get(scope,id);audit.record(scope,"purchase_order.update","purchase_order",id,camel(before),result);return result;
    }

    public void delete(CompanyAccess scope,long id,long expectedVersion) {
        var rows=jdbc.queryForList("SELECT * FROM purchase_order WHERE tenant_id=? AND company_id=?"+cn.erp.shared.OrgOwnership.filter(scope,"purchase_order")+" AND id=? FOR UPDATE",scope.tenantId(),scope.companyId(),id);
        if(rows.isEmpty()) {throw missing();}
        var before=rows.getFirst();
        if(flag(before.get("is_deleted"))) {return;}
        queries.visible(scope,id);version(expectedVersion,before);state(before,"draft");
        links.release(scope,id);
        jdbc.update("UPDATE purchase_order SET is_deleted=1,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",scope.userId(),scope.tenantId(),scope.companyId(),id);
        audit.record(scope,"purchase_order.delete","purchase_order",id,camel(before),null);
    }

    public Map<String,Object> command(CompanyAccess scope,long id,String action,String key,PurchaseOrderModels.Command input) {
        queries.header(scope,id,false);
        return execute(scope,action,key,List.of(id,input),200,()->{
            var before=queries.header(scope,id,true);version(input.version(),before);workflow.guard(scope,"purchase_order",id,action);var rows=queries.lines(scope,id,true);
            switch(action) {
                case "submit" -> {state(before,"draft");active(scope,before,rows,false);priceSources.validate(scope,before,rows,false);changeState(scope,id,"pending");workflow.start(scope,"purchase_order",id,input.workflowAssignees());}
                case "withdraw" -> {state(before,"pending");changeState(scope,id,"draft");}
                case "reject" -> {state(before,"pending");reason(input.comment());changeState(scope,id,"draft");}
                case "approve" -> {
                    state(before,"pending");separateApprover(scope,before);active(scope,before,rows,true);priceSources.validate(scope,before,rows,true);
                    var policy=jdbc.queryForList("SELECT over_receipt_pct FROM company_policy WHERE tenant_id=? AND company_id=?",BigDecimal.class,scope.tenantId(),scope.companyId());
                    freeze(scope,id,policy.isEmpty()?BigDecimal.ZERO:policy.getFirst());
                    PaymentTerms.snapshot(jdbc,scope,"purchase_order",id,number(before,"supplier_id"),"supplier");
                    jdbc.update("UPDATE purchase_order SET status='approved',approved_by=?,approved_at=UTC_TIMESTAMP(3),revision=revision+1,version=version+1,updated_by=? WHERE id=?",scope.userId(),scope.userId(),id);
                }
                case "revoke_approval" -> {state(before,"approved");assertNoReceipt(scope,id);jdbc.update("UPDATE purchase_order SET status='draft',over_receipt_pct=0,version=version+1,updated_by=? WHERE id=?",scope.userId(),id);}
                case "cancel" -> {
                    state(before,"approved");reason(input.reason());
                    if(rows.stream().anyMatch(row->decimal(row,"received_base_qty").signum()>0||decimal(row,"pending_receive_base_qty").signum()>0)) {throw new ApiException(422,"DEPENDENCY_EXISTS","存在已收或待收数量，不能取消订单");}
                    links.release(scope,id);jdbc.update("UPDATE purchase_order SET status='cancelled',cancelled_at=UTC_TIMESTAMP(3),cancel_reason=?,version=version+1,updated_by=? WHERE id=?",input.reason(),scope.userId(),id);
                }
                default -> throw invalid("采购订单操作无效");
            }
            if(Set.of("submit","approve","reject","withdraw","revoke_approval").contains(action)) {approval(scope,id,before,action.equals("revoke_approval")?"revoke":action,input.comment()==null?input.reason():input.comment());}
            var result=summary(scope,id);audit.record(scope,"purchase_order."+action,"purchase_order",id,camel(before),result);return result;
        });
    }

    public Map<String,Object> close(CompanyAccess scope,long id,String key,PurchaseOrderModels.Close input) {
        queries.header(scope,id,false);
        return execute(scope,"close_lines",key,List.of(id,input),200,()->{
            var before=queries.header(scope,id,true);version(input.version(),before);state(before,"approved");reason(input.reason());
            var rows=queries.lines(scope,id,true);var indexed=new HashMap<Long,Map<String,Object>>();rows.forEach(row->indexed.put(number(row,"id"),row));var selected=new HashSet<Long>();
            for(var line:input.lines()) {
                long lineId=identifier(line.lineId());var row=indexed.get(lineId);BigDecimal qty=RequisitionService.quantity(line.closeQty());
                if(row==null||!selected.add(lineId)) {throw invalid("关闭行不属于当前订单或重复");}
                BigDecimal free=decimal(row,"receive_limit_base_qty").subtract(decimal(row,"closed_base_qty")).subtract(decimal(row,"received_base_qty")).subtract(decimal(row,"pending_receive_base_qty"));
                if(qty.compareTo(free)>0) {throw new ApiException(422,"CLOSE_QTY_EXCEEDED","关闭数量超过剩余可收数量");}
                jdbc.update("UPDATE purchase_order_line SET closed_base_qty=closed_base_qty+?,close_reason=?,version=version+1,updated_by=? WHERE id=?",qty,input.reason(),scope.userId(),lineId);
            }
            touch(scope,id);queries.refreshProgress(scope,id);var result=summary(scope,id);audit.record(scope,"purchase_order.close_lines","purchase_order",id,camel(before),result);return result;
        });
    }

    public Map<String,Object> change(CompanyAccess scope,long id,String key,PurchaseOrderModels.Input input) {
        queries.header(scope,id,false);
        return execute(scope,"change",key,List.of(id,input),200,()->{
            var before=queries.header(scope,id,true);version(input.version(),before);state(before,"approved","closed");reason(input.reason());
            BusinessPeople.assign(jdbc,scope,"purchase_order",id,input.buyerUserId());
            var rows=queries.lines(scope,id,true);var preview=writer.price(scope,input);
            if(number(before,"supplier_id")!=identifier(input.supplierId())||flag(before.get("price_tax_inclusive"))!=preview.priceTaxInclusive()) {
                throw new ApiException(422,"CHANGE_NOT_ALLOWED","审批后的订单不能更换供应商或含税价格口径");
            }
            boolean approval=requiresApproval(scope,before,rows,input,preview);
            long changeId=Ids.next();String no=numbers.next(scope,"CH",input.businessDate());
            jdbc.update("""
                    INSERT INTO document_change(id,tenant_id,company_id,change_no,document_type,document_id,from_revision,to_revision,
                      requires_approval,status,before_snapshot,after_snapshot,reason,created_by)
                    VALUES(?,?,?,?,'purchase_order',?,?,?,?,?,?,?,?,?)
                    """,changeId,scope.tenantId(),scope.companyId(),no,id,number(before,"revision"),number(before,"revision")+1,approval,
                    approval?"pending":"applied",json.writeValueAsString(queries.get(scope,id)),json.writeValueAsString(input),input.reason(),scope.userId());
            if(approval) {changeState(scope,id,"change_pending");}
            else {applyChange(scope,id,before,rows,input,preview,changeId);}
            var result=summary(scope,id);
            result.put("change",Map.of("id",Long.toString(changeId),"changeNo",no,"status",approval?"pending":"applied","requiresApproval",approval,"version",0));
            audit.record(scope,"purchase_order.change","purchase_order",id,camel(before),result);return result;
        });
    }

    public Map<String,Object> decideChange(CompanyAccess scope,long changeId,String action,String key,PurchaseOrderModels.Command input) {
        return execute(scope,"change_"+action,key,List.of(changeId,input),200,()->{
            var changes=jdbc.queryForList("SELECT * FROM document_change WHERE tenant_id=? AND company_id=? AND id=? AND document_type='purchase_order'",scope.tenantId(),scope.companyId(),changeId);
            if(changes.isEmpty()) {throw missing();}
            long id=number(changes.getFirst(),"document_id");var before=queries.header(scope,id,true);state(before,"change_pending");
            var change=jdbc.queryForMap("SELECT * FROM document_change WHERE id=? FOR UPDATE",changeId);
            version(input.version(),change);
            if(!change.get("status").equals("pending")) {throw conflict("变更单已处理");}
            if(action.equals("approve")) {
                var applicant=new LinkedHashMap<>(before);applicant.put("created_by",change.get("created_by"));separateApprover(scope,applicant);
                var after=json.readValue(change.get("after_snapshot").toString(),PurchaseOrderModels.Input.class);
                var preview=writer.price(scope,after);var lines=queries.lines(scope,id,true);
                if(priceSources.required(scope,preview) && (!scope.hasPermission("cost:view") || number(change,"created_by")==scope.userId()))
                    throw new ApiException(422,"PURCHASE_PRICE_REVIEW_REQUIRED","超协议价变更须由另一名具有成本查看权限的采购审批人复核");
                requiresApproval(scope,before,lines,after,preview);
                active(scope,before,lines,false);applyChange(scope,id,before,lines,after,preview,changeId);
            } else {reason(input.comment());changeState(scope,id,"approved");queries.refreshProgress(scope,id);}
            jdbc.update("UPDATE document_change SET status=?,decided_by=?,decided_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",action.equals("approve")?"applied":"rejected",scope.userId(),changeId);
            var result=summary(scope,id);result.put("change",Map.of("id",Long.toString(changeId),"status",action.equals("approve")?"applied":"rejected","version",number(change,"version")+1));
            audit.record(scope,"purchase_order.change_"+action,"document_change",changeId,camel(change),result);return result;
        });
    }

    private void applyChange(CompanyAccess scope,long id,Map<String,Object> before,List<Map<String,Object>> rows,
            PurchaseOrderModels.Input input,PricingModels.Preview preview,long changeId) {
        writer.write(scope,id,input,preview);freeze(scope,id,decimal(before,"over_receipt_pct"));
        jdbc.update("UPDATE purchase_order SET status='approved',revision=revision+1,version=version+1,updated_by=? WHERE id=?",scope.userId(),id);
        active(scope,queries.header(scope,id,false),queries.lines(scope,id,true),true);queries.refreshProgress(scope,id);
    }

    private void freeze(CompanyAccess scope,long id,BigDecimal pct) {
        var lines=queries.lines(scope,id,true);
        for(var line:lines) {
            BigDecimal limit=decimal(line,"base_qty").multiply(BigDecimal.ONE.add(pct.movePointLeft(2))).setScale(6,java.math.RoundingMode.DOWN);
            if(limit.compareTo(decimal(line,"received_base_qty").add(decimal(line,"pending_receive_base_qty")).add(decimal(line,"closed_base_qty")))<0) {throw new ApiException(422,"CHANGE_NOT_ALLOWED","变更后的可收上限低于已收、待收及关闭数量");}
            jdbc.update("UPDATE purchase_order_line SET receive_limit_base_qty=?,version=version+1 WHERE id=?",limit,line.get("id"));
        }
        jdbc.update("UPDATE purchase_order SET over_receipt_pct=? WHERE id=?",pct,id);
    }

    private boolean requiresApproval(CompanyAccess scope,Map<String,Object> before,List<Map<String,Object>> rows,PurchaseOrderModels.Input input,PricingModels.Preview preview) {
        boolean approval=priceSources.required(scope,preview) || decimal(before,"order_discount_amount").compareTo(new BigDecimal(preview.orderDiscountAmount()))!=0;
        Set<Long> used=new HashSet<>();
        for(int i=0;i<preview.lines().size();i++) {
            var source=input.lines().get(i);var current=preview.lines().get(i);
            Long id;
            if(source.id()!=null) {id=identifier(source.id());}
            else if(i<rows.size()) {id=number(rows.get(i),"id");}
            else {id=null;}
            Map<String,Object> old=id==null?null:rows.stream().filter(r->number(r,"id")==id).findFirst().orElse(null);
            if(old==null) {if(source.id()!=null) {throw invalid("变更明细不属于当前订单");}approval=true;continue;}
            if(!used.add(id)) {throw invalid("变更订单行不能重复");}
            BigDecimal quantity=new BigDecimal(current.baseQty());
            if(quantity.multiply(BigDecimal.ONE.add(decimal(before,"over_receipt_pct").movePointLeft(2))).compareTo(decimal(old,"received_base_qty").add(decimal(old,"pending_receive_base_qty")).add(decimal(old,"closed_base_qty")))<0) {
                throw new ApiException(422,"CHANGE_NOT_ALLOWED","变更数量不能低于已收、待收与已关闭数量");
            }
            boolean priceChanged=new BigDecimal(current.unitPrice()).compareTo(decimal(old,"unit_price"))!=0 ||
                    new BigDecimal(current.taxRate()).compareTo(decimal(old,"tax_rate"))!=0 ||
                    new BigDecimal(current.lineDiscountAmount()).compareTo(decimal(old,"line_discount"))!=0 ||
                    new BigDecimal(current.unitRate()).compareTo(decimal(old,"unit_rate"))!=0 ||
                    number(old,"sku_id")!=identifier(current.skuId()) || number(old,"unit_id")!=identifier(current.unitId());
            boolean receiving=decimal(old,"received_base_qty").signum()>0||decimal(old,"pending_receive_base_qty").signum()>0;
            if(receiving && priceChanged) {throw new ApiException(422,"CHANGE_NOT_ALLOWED","已收货或待收货的订单行不能修改价格、税率、商品或单位，请先作废待收货单");}
            if(receiving && new BigDecimal(current.receivableNet()).divide(quantity,16,java.math.RoundingMode.HALF_UP)
                    .compareTo(decimal(old,"payable_net").divide(decimal(old,"base_qty"),16,java.math.RoundingMode.HALF_UP))!=0) {
                throw new ApiException(422,"CHANGE_NOT_ALLOWED","变更会改变已收货或待收货行的分摊后单价，请先作废待收货单或关闭剩余数量");
            }
            if(decimal(old,"pending_receive_base_qty").signum()>0 && !Objects.equals(PurchaseOrderWriter.nullableId(current.warehouseId()),old.get("warehouse_id")==null?null:number(old,"warehouse_id"))) {
                throw new ApiException(422,"DEPENDENCY_EXISTS","请先作废该行待收货单，再更换收货仓库");
            }
            if(priceChanged || quantity.compareTo(decimal(old,"base_qty"))>0) {approval=true;}
        }
        for(var old:rows) {if(!used.contains(number(old,"id"))&&(decimal(old,"received_base_qty").signum()>0||decimal(old,"pending_receive_base_qty").signum()>0)) {throw new ApiException(422,"CHANGE_NOT_ALLOWED","已收或待收的订单行不能删除");}}
        var fees=jdbc.queryForList("SELECT fee_type,bearer,amount,tax_amount,tax_rate,price_tax_inclusive,recognized_receipt_id FROM purchase_order_fee WHERE order_id=? ORDER BY id",number(before,"id"));
        boolean feeChanged=fees.size()!=preview.fees().size();
        if(feeChanged) {approval=true;}
        else {for(int i=0;i<fees.size();i++) {var fee=fees.get(i);var current=preview.fees().get(i);
            if(!fee.get("fee_type").equals(current.feeType())||!fee.get("bearer").equals(current.bearer())||
                decimal(fee,"amount").compareTo(new BigDecimal(current.amount()))!=0||decimal(fee,"tax_amount").compareTo(new BigDecimal(current.taxAmount()))!=0||
                decimal(fee,"tax_rate").compareTo(new BigDecimal(current.taxRate()))!=0||flag(fee.get("price_tax_inclusive"))!=current.priceTaxInclusive()) {approval=true;feeChanged=true;}
        }}
        if(feeChanged&&fees.stream().anyMatch(fee->fee.get("recognized_receipt_id")!=null)) {throw new ApiException(422,"CHANGE_NOT_ALLOWED","已经确认的费用不能修改或增减");}
        return approval;
    }

    private void active(CompanyAccess scope,Map<String,Object> order,List<Map<String,Object>> rows,boolean warehouseRequired) {
        partners.requirePartner(scope,number(order,"supplier_id"),"supplier");
        for(var row:rows) {
            catalog.requireActiveSku(scope,number(row,"sku_id"),number(row,"unit_id"));
            if(row.get("warehouse_id")==null) {if(warehouseRequired) {throw invalid("审批前请为每行指定收货仓库");}continue;}
            long warehouse=number(row,"warehouse_id");
            if(!scope.allowsWarehouse(warehouse)) {throw missing();}
            if(jdbc.queryForObject("SELECT COUNT(*) FROM warehouse WHERE tenant_id=? AND company_id=? AND id=? AND status='active'",Long.class,scope.tenantId(),scope.companyId(),warehouse)==0) {throw new ApiException(422,"MASTER_DATA_DISABLED","收货仓库不存在或已停用");}
        }
    }

    private void assertNoReceipt(CompanyAccess scope,long id) {
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM goods_receipt_line g JOIN goods_receipt h ON h.id=g.receipt_id JOIN purchase_order_line l ON l.id=g.order_line_id WHERE l.tenant_id=? AND l.company_id=? AND l.order_id=? AND h.status<>'voided'",Long.class,scope.tenantId(),scope.companyId(),id);
        if(count>0) {throw new ApiException(422,"DEPENDENCY_EXISTS","存在下游收货单，请先处理");}
    }

    private void separateApprover(CompanyAccess scope,Map<String,Object> row) {
        var policies=jdbc.queryForList("SELECT separate_order_approver FROM company_policy WHERE tenant_id=? AND company_id=?",Integer.class,scope.tenantId(),scope.companyId());
        if(row.get("created_by")!=null&&number(row,"created_by")==scope.userId()&&(policies.isEmpty()||policies.getFirst()!=0)) {
            throw new ApiException(422,"SAME_OPERATOR_NOT_ALLOWED","审批人须与制单人分离");
        }
    }
    private void approval(CompanyAccess scope,long id,Map<String,Object> row,String action,String comment) {
        jdbc.update("INSERT INTO approval_record(id,tenant_id,company_id,document_type,document_id,revision,action,operator_id,comment) VALUES(?,?,?,'purchase_order',?,?,?,?,?)",
                Ids.next(),scope.tenantId(),scope.companyId(),id,number(row,"revision"),action,scope.userId(),comment);
    }
    private void changeState(CompanyAccess scope,long id,String state) {
        jdbc.update("UPDATE purchase_order SET status=?,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",state,scope.userId(),scope.tenantId(),scope.companyId(),id);
    }
    private void touch(CompanyAccess scope,long id) {jdbc.update("UPDATE purchase_order SET version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",scope.userId(),scope.tenantId(),scope.companyId(),id);}
    private Map<String,Object> summary(CompanyAccess scope,long id) {var row=queries.header(scope,id,false);return new LinkedHashMap<>(Map.of("id",Long.toString(id),"documentNo",row.get("order_no"),"status",row.get("status"),"version",row.get("version"),"revision",row.get("revision")));}
    private void version(Long requested,Map<String,Object> row) {if(requested==null||requested<0) {throw invalid("请提供有效版本号");}if(requested!=number(row,"version")) {throw new ApiException(409,"VERSION_CONFLICT","订单已被他人修改，请刷新",Map.of("currentVersion",row.get("version")));}}
    private void state(Map<String,Object> row,String... states) {if(!Arrays.asList(states).contains(row.get("status"))) {throw conflict("当前订单状态不允许执行此操作");}}
    private void reason(String text) {if(text==null||text.isBlank()||text.length()>500) {throw invalid("请填写原因或审批意见");}}
    private boolean flag(Object value) {return value instanceof Boolean b?b:((Number)value).intValue()!=0;}
    private BigDecimal quantity(String value) {if(value==null||!value.matches("[0-9]+(?:\\.[0-9]{1,6})?")||value.length()>26) {throw invalid("数量格式或精度无效");}var number=new BigDecimal(value);if(number.signum()<=0||number.precision()-number.scale()>18) {throw invalid("数量必须大于零且在允许范围内");}return number.setScale(6);}
}
