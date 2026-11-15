package cn.erp.salesfinance;

import cn.erp.accounting.RefundService;
import cn.erp.identity.CompanyAccess;
import cn.erp.reference.ReferenceAvailability;
import cn.erp.salesorder.SalesOrderQueries;
import cn.erp.shared.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static cn.erp.salesfinance.FinanceSupport.*;

@Service
@Transactional(readOnly=true)
public class RefundApplicationService {
    private final JdbcTemplate jdbc;
    private final FinanceSupport support;
    private final DocumentNumbers numbers;
    private final BusinessCommands commands;
    private final AuditService audit;
    private final ReferenceAvailability references;
    private final RefundService refunds;
    private final ObjectMapper json;
    private final SalesOrderQueries orders;
    public RefundApplicationService(JdbcTemplate jdbc,FinanceSupport support,DocumentNumbers numbers,BusinessCommands commands,
            AuditService audit,ReferenceAvailability references,RefundService refunds,ObjectMapper json,SalesOrderQueries orders) {
        this.jdbc=jdbc;this.support=support;this.numbers=numbers;this.commands=commands;this.audit=audit;this.references=references;this.refunds=refunds;this.json=json;this.orders=orders;
    }

    public Map<String,Object> list(CompanyAccess scope,Long customerId,String status,Long cursor,int limit) {
        long before=cursor(cursor,limit);
        if(status!=null&&!Set.of("draft","pending_review","pending_payment","refunded","cancelled").contains(status)) {throw invalid("退款状态无效");}
        var rows=jdbc.queryForList("""
                SELECT r.id,r.refund_no document_no,r.customer_id,p.name customer_name,r.original_receipt_id,
                r.cash_account_id,a.name cash_account_name,r.amount,r.business_date,r.status,r.version,r.created_by FROM refund_request r
                JOIN business_partner p ON p.tenant_id=r.tenant_id AND p.id=r.customer_id
                JOIN cash_account a ON a.tenant_id=r.tenant_id AND a.company_id=r.company_id AND a.id=r.cash_account_id
                WHERE r.tenant_id=? AND r.company_id=? AND r.id<? AND (? IS NULL OR r.customer_id=?) AND (? IS NULL OR r.status=?)
                ORDER BY r.id DESC LIMIT ?
                """,scope.tenantId(),scope.companyId(),before,customerId,customerId,status,status,limit+1);
        var result=page(rows,limit);
        @SuppressWarnings("unchecked") var items=(List<Map<String,Object>>)result.get("items");
        for(int i=0;i<items.size();i++) {items.get(i).put("allowedActions",actions(scope,rows.get(i)));}
        return result;
    }

    public Map<String,Object> get(CompanyAccess scope,long id) {
        var raw=support.find(scope,"refund_request",id,false);
        var result=row(raw);result.remove("creditItemIds");
        result.put("creditItemIds",readIds(raw.get("credit_item_ids")).stream().map(String::valueOf).toList());
        result.put("documentNo",raw.get("refund_no"));
        result.put("customerName",jdbc.queryForObject("SELECT name FROM business_partner WHERE tenant_id=? AND id=?",String.class,scope.tenantId(),raw.get("customer_id")));
        result.put("cashAccountName",jdbc.queryForObject("SELECT name FROM cash_account WHERE tenant_id=? AND company_id=? AND id=?",String.class,scope.tenantId(),scope.companyId(),raw.get("cash_account_id")));
        result.put("allowedActions",actions(scope,raw));return result;
    }

    private List<String> actions(CompanyAccess scope,Map<String,Object> raw) {
        var actions=new ArrayList<String>();
        String state=raw.get("status").toString();
        if(state.equals("draft")) {
            if(scope.hasPermission("refund_request:update")) {actions.add("update");}
            if(scope.hasPermission("refund_request:submit")) {actions.add("submit");}
        }
        if(state.equals("pending_review")) {
            if(raw.get("created_by")==null||id(raw,"created_by")!=scope.userId()) {
                if(scope.hasPermission("refund_request:approve")) {actions.add("approve");}
                if(scope.hasPermission("refund_request:reject")) {actions.add("reject");}
            }
        }
        if(state.equals("pending_payment")) {
            if(scope.hasPermission("refund_request:register_paid")) {actions.add("register-paid");}
            if(scope.hasPermission("refund_request:cancel")) {actions.add("cancel");}
        }
        return actions;
    }

    @Transactional
    public Map<String,Object> create(CompanyAccess scope,String key,FinanceModels.Refund input) {
        return execute(scope,"refund_request.create",key,input,201,()->{
            validate(scope,input);
            long id=Ids.next();String no=numbers.next(scope,"RF",YearMonth.from(input.businessDate()));
            jdbc.update("""
                    INSERT INTO refund_request(id,tenant_id,company_id,refund_no,customer_id,original_receipt_id,cash_account_id,amount,business_date,
                    request_key,reason,credit_item_ids,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,id,scope.tenantId(),scope.companyId(),no,input.customerId(),input.originalReceiptId(),input.cashAccountId(),amount(input.amount()),
                    input.businessDate(),UUID.randomUUID().toString(),input.reason(),json.writeValueAsString(input.creditItemIds()),scope.userId(),scope.userId());
            audit.record(scope,"refund_request.create","refund_request",id,null,Map.of("documentNo",no));return get(scope,id);
        });
    }

    @Transactional
    public Map<String,Object> update(CompanyAccess scope,long id,FinanceModels.Refund input) {
        var before=support.find(scope,"refund_request",id,true);version(before,input.version());state(before,"draft");validate(scope,input);
        // 驳回保留占用历史，调整金额和日期时同步恢复后的借方未清项。
        var history=jdbc.queryForList("SELECT id,partner_id FROM partner_open_item WHERE tenant_id=? AND company_id=? AND item_type='refund_request' AND source_id=? FOR UPDATE",scope.tenantId(),scope.companyId(),id);
        if(!history.isEmpty()) {
            if(id(history.getFirst(),"partner_id")!=input.customerId()) {throw new ApiException(422,"REFUND_CUSTOMER_IMMUTABLE","已提交过的退款申请不能更换客户，请新建申请");}
            long itemId=id(history.getFirst(),"id");
            if(jdbc.queryForObject("SELECT COUNT(*) FROM settlement_allocation WHERE tenant_id=? AND company_id=? AND debit_item_id=? AND status IN('reserved','active')",Long.class,scope.tenantId(),scope.companyId(),itemId)!=0) {throw new ApiException(409,"STATE_CONFLICT","退款仍有有效额度占用");}
            jdbc.update("UPDATE partner_open_item SET amount=?,open_amount=?,aging_date=?,version=version+1 WHERE id=?",amount(input.amount()),amount(input.amount()),input.businessDate(),itemId);
        }
        jdbc.update("""
                UPDATE refund_request SET customer_id=?,original_receipt_id=?,cash_account_id=?,amount=?,business_date=?,reason=?,credit_item_ids=?,
                version=version+1,updated_by=? WHERE id=?
                """,input.customerId(),input.originalReceiptId(),input.cashAccountId(),amount(input.amount()),input.businessDate(),input.reason(),
                json.writeValueAsString(input.creditItemIds()),scope.userId(),id);
        audit.record(scope,"refund_request.update","refund_request",id,null,Map.of("version",input.version()+1));return get(scope,id);
    }

    @Transactional
    public Map<String,Object> command(CompanyAccess scope,long id,String action,String key,FinanceModels.Version input) {
        return execute(scope,"refund_request."+action,key,List.of(id,input),200,()->{
            var snapshot=support.find(scope,"refund_request",id,false);
            if(Set.of("submit","approve").contains(action)) {support.period(scope,((java.sql.Date)snapshot.get("business_date")).toLocalDate());}
            var before=support.find(scope,"refund_request",id,true);version(before,input.version());
            var affected=affectedOrders(scope,before);lockOrders(scope,affected);
            switch(action) {
                case "submit" -> {
                    state(before,"draft");references.requireCashAccount(scope,id(before,"cash_account_id"));
                    references.requirePartner(scope,id(before,"customer_id"),"customer");
                    var ids=readIds(before.get("credit_item_ids"));
                    if(ids.isEmpty()) {ids=availableCredits(scope,id(before,"customer_id"));}
                    if(ids.isEmpty()) {throw new ApiException(422,"REFUND_AMOUNT_EXCEEDED","客户贷方可用额度不足");}
                    refunds.reserve(scope,id,ids);
                }
                case "approve" -> {state(before,"pending_review");refunds.approve(scope,id);}
                case "reject" -> {
                    state(before,"pending_review");
                    if(before.get("created_by")!=null&&id(before,"created_by")==scope.userId()) {throw new ApiException(422,"SAME_OPERATOR_NOT_ALLOWED","经办人不能审核自己的退款申请");}
                    if(input.reason()==null||input.reason().isBlank()) {throw invalid("请填写驳回原因");}
                    refunds.release(scope,id);
                    jdbc.update("UPDATE refund_request SET status='draft',reviewed_by=NULL,reviewed_at=NULL,version=version+1,updated_by=? WHERE id=?",scope.userId(),id);
                }
                case "cancel" -> {state(before,"pending_payment");refunds.release(scope,id);}
                default -> throw invalid("退款操作无效");
            }
            for(long order:affected) {orders.refreshProgress(scope,order);}
            audit.record(scope,"refund_request."+action,"refund_request",id,null,Map.of("status",get(scope,id).get("status")));return get(scope,id);
        });
    }

    @Transactional
    public Map<String,Object> paid(CompanyAccess scope,long id,String key,FinanceModels.Paid input) {
        return execute(scope,"refund_request.register_paid",key,List.of(id,input),200,()->{
            long period=support.period(scope,input.paidDate());
            var before=support.find(scope,"refund_request",id,true);version(before,input.version());state(before,"pending_payment","refunded");
            var affected=affectedOrders(scope,before);lockOrders(scope,affected);
            references.requireCashAccount(scope,id(before,"cash_account_id"));
            refunds.confirm(scope,id,period,input.paidDate(),input.bankSerialNo());
            for(long order:affected) {orders.refreshProgress(scope,order);}
            audit.record(scope,"refund_request.register_paid","refund_request",id,null,Map.of("status","refunded"));return get(scope,id);
        });
    }

    private void validate(CompanyAccess scope,FinanceModels.Refund input) {
        date(input.businessDate());amount(input.amount());references.requirePartner(scope,input.customerId(),"customer");references.requireCashAccount(scope,input.cashAccountId());
        if(jdbc.queryForObject("SELECT COUNT(*) FROM receipt WHERE tenant_id=? AND company_id=? AND id=? AND partner_id=? AND receipt_type='customer' AND status='confirmed'",
                Long.class,scope.tenantId(),scope.companyId(),input.originalReceiptId(),input.customerId())!=1) {throw missing();}
        if(input.creditItemIds()!=null&&(input.creditItemIds().stream().anyMatch(Objects::isNull)||new HashSet<>(input.creditItemIds()).size()!=input.creditItemIds().size())) {throw invalid("退款额度来源不能重复或为空");}
    }

    private List<Long> availableCredits(CompanyAccess scope,long customer) {
        return jdbc.queryForList("""
                SELECT i.id FROM partner_open_item i LEFT JOIN receipt r ON r.tenant_id=i.tenant_id AND r.company_id=i.company_id
                  AND i.item_type='receipt' AND r.id=i.source_id
                WHERE i.tenant_id=? AND i.company_id=? AND i.partner_id=? AND i.direction='C' AND i.open_amount>0
                  AND (i.item_type='receivable' OR (i.item_type='receipt' AND r.receipt_type='customer' AND r.status='confirmed'))
                ORDER BY i.aging_date,i.id LIMIT 200
                """,Long.class,scope.tenantId(),scope.companyId(),customer);
    }

    private Set<Long> affectedOrders(CompanyAccess scope,Map<String,Object> refund) {
        var ids=new TreeSet<Long>();
        // 自动选择可能跨多张订单，预先锁定客户所有退货来源的订单头，保持与签收相同的锁序。
        ids.addAll(jdbc.queryForList("""
                SELECT DISTINCT o.order_id FROM sales_return h JOIN sales_return_line r ON r.tenant_id=h.tenant_id AND r.company_id=h.company_id AND r.return_id=h.id
                JOIN shipment_line l ON l.tenant_id=r.tenant_id AND l.company_id=r.company_id AND l.id=r.shipment_line_id
                JOIN sales_order_line o ON o.tenant_id=l.tenant_id AND o.company_id=l.company_id AND o.id=l.order_line_id
                WHERE h.tenant_id=? AND h.company_id=? AND h.customer_id=?
                """,Long.class,scope.tenantId(),scope.companyId(),refund.get("customer_id")));
        ids.addAll(jdbc.queryForList("SELECT related_order_id FROM receipt WHERE tenant_id=? AND company_id=? AND id=? AND related_order_id IS NOT NULL",Long.class,scope.tenantId(),scope.companyId(),refund.get("original_receipt_id")));
        return ids;
    }
    private void lockOrders(CompanyAccess scope,Set<Long> ids) {
        for(long id:ids) {jdbc.queryForObject("SELECT id FROM sales_order WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",Long.class,scope.tenantId(),scope.companyId(),id);}
    }

    private List<Long> readIds(Object data) {
        if(data==null||data.toString().equals("null")) {return List.of();}
        var array=json.readTree(data.toString());
        if(!array.isArray()) {return List.of();}
        var result=new ArrayList<Long>();array.forEach(node->result.add(node.asLong()));return result;
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    private Map<String,Object> execute(CompanyAccess scope,String operation,String key,Object payload,int status,java.util.function.Supplier<Map<String,Object>> work) {
        return commands.execute(scope,operation,key,payload,status,work,(Class)Map.class);
    }
}
