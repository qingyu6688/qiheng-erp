package cn.erp.salesfinance;

import cn.erp.accounting.*;
import cn.erp.identity.CompanyAccess;
import cn.erp.reference.ReferenceAvailability;
import cn.erp.salesorder.SalesOrderQueries;
import cn.erp.shared.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.salesfinance.FinanceSupport.*;

@Service
@Transactional(readOnly=true)
public class ReceiptService {
    private final JdbcTemplate jdbc;
    private final FinanceSupport support;
    private final DocumentNumbers numbers;
    private final BusinessCommands commands;
    private final AuditService audit;
    private final ReferenceAvailability references;
    private final LedgerService ledger;
    private final SettlementService settlement;
    private final SalesOrderQueries orders;
    public ReceiptService(JdbcTemplate jdbc,FinanceSupport support,DocumentNumbers numbers,BusinessCommands commands,
            AuditService audit,ReferenceAvailability references,LedgerService ledger,SettlementService settlement,SalesOrderQueries orders) {
        this.jdbc=jdbc;this.support=support;this.numbers=numbers;this.commands=commands;this.audit=audit;
        this.references=references;this.ledger=ledger;this.settlement=settlement;this.orders=orders;
    }

    public Map<String,Object> list(CompanyAccess scope,Long partnerId,String status,Long cursor,int limit) {
        long before=cursor(cursor,limit);
        if(status!=null&&!Set.of("draft","pending_confirm","confirmed","reversed").contains(status)) {throw invalid("收款状态无效");}
        var rows=jdbc.queryForList("""
                SELECT r.id,r.receipt_no document_no,r.receipt_type,r.partner_id,p.name partner_name,r.cash_account_id,a.name cash_account_name,
                r.amount,r.business_date,r.status,r.version,r.created_by FROM receipt r
                LEFT JOIN business_partner p ON p.tenant_id=r.tenant_id AND p.id=r.partner_id
                JOIN cash_account a ON a.tenant_id=r.tenant_id AND a.company_id=r.company_id AND a.id=r.cash_account_id
                WHERE r.tenant_id=? AND r.company_id=? AND r.id<? AND (? IS NULL OR r.partner_id=?)
                AND (? IS NULL OR r.status=?) %s ORDER BY r.id DESC LIMIT ?
                """.formatted(cn.erp.shared.OrgOwnership.filter(scope,"r")),scope.tenantId(),scope.companyId(),before,partnerId,partnerId,status,status,limit+1);
        var result=page(rows,limit);
        @SuppressWarnings("unchecked") var items=(List<Map<String,Object>>)result.get("items");
        for(int i=0;i<items.size();i++) {items.get(i).put("allowedActions",actions(scope,rows.get(i)));}
        return result;
    }

    public Map<String,Object> get(CompanyAccess scope,long id) {
        var raw=support.find(scope,"receipt",id,false);
        var result=row(raw);result.put("documentNo",raw.get("receipt_no"));
        result.put("partnerName",raw.get("partner_id")==null?null:jdbc.queryForObject("SELECT name FROM business_partner WHERE tenant_id=? AND id=?",String.class,scope.tenantId(),raw.get("partner_id")));
        result.put("cashAccountName",jdbc.queryForObject("SELECT name FROM cash_account WHERE tenant_id=? AND company_id=? AND id=?",String.class,scope.tenantId(),scope.companyId(),raw.get("cash_account_id")));
        result.put("allowedActions",actions(scope,raw));
        return result;
    }

    private List<String> actions(CompanyAccess scope,Map<String,Object> raw) {
        var actions=new ArrayList<String>();
        if("draft".equals(raw.get("status"))) {
            if(scope.hasPermission("receipt:update")) {actions.add("update");}
            if(scope.hasPermission("receipt:submit")) {actions.add("submit");}
        }
        if("pending_confirm".equals(raw.get("status"))) {
            if(scope.hasPermission("receipt:return")) {actions.add("return");}
            if(scope.hasPermission("receipt:confirm")&&!separateConfirmer(scope,raw)) {actions.add("confirm");}
        }
        return actions;
    }

    @Transactional
    public Map<String,Object> create(CompanyAccess scope,String key,FinanceModels.Receipt input) {
        return execute(scope,"receipt.create",key,input,201,()->{
            validate(scope,input);
            long id=Ids.next();String no=numbers.next(scope,"RC",YearMonth.from(input.businessDate()));
            jdbc.update("""
                    INSERT INTO receipt(id,tenant_id,company_id,receipt_no,receipt_type,partner_id,income_item_id,cash_account_id,
                    amount,business_date,related_order_id,bank_serial_no,remark,created_by,updated_by,owner_user_id,owner_department_id)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,id,scope.tenantId(),scope.companyId(),no,input.receiptType(),input.partnerId(),input.incomeItemId(),input.cashAccountId(),
                    amount(input.amount()),input.businessDate(),input.relatedOrderId(),blank(input.bankSerialNo()),input.remark(),scope.userId(),scope.userId(),scope.userId(),cn.erp.shared.OrgOwnership.department(scope));
            audit.record(scope,"receipt.create","receipt",id,null,Map.of("documentNo",no));
            return get(scope,id);
        });
    }

    @Transactional
    public Map<String,Object> update(CompanyAccess scope,long id,FinanceModels.Receipt input) {
        var before=support.find(scope,"receipt",id,true);version(before,input.version());state(before,"draft");validate(scope,input);
        jdbc.update("""
                UPDATE receipt SET receipt_type=?,partner_id=?,income_item_id=?,cash_account_id=?,amount=?,business_date=?,
                related_order_id=?,bank_serial_no=?,remark=?,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?
                """,input.receiptType(),input.partnerId(),input.incomeItemId(),input.cashAccountId(),amount(input.amount()),input.businessDate(),
                input.relatedOrderId(),blank(input.bankSerialNo()),input.remark(),scope.userId(),scope.tenantId(),scope.companyId(),id);
        audit.record(scope,"receipt.update","receipt",id,null,Map.of("version",input.version()+1));return get(scope,id);
    }

    @Transactional
    public Map<String,Object> command(CompanyAccess scope,long id,String action,String key,FinanceModels.Version input) {
        return execute(scope,"receipt."+action,key,List.of(id,input),200,()->{
            // 提交和确认均先取期间共享锁，避免关账完成后新增待确认单据。
            Map<String,Object> snapshot=support.find(scope,"receipt",id,false);
            LocalDate businessDate=input.businessDate()==null?((java.sql.Date)snapshot.get("business_date")).toLocalDate():input.businessDate();
            Long period=null;
            if(action.equals("submit")) {period=support.period(scope,((java.sql.Date)snapshot.get("business_date")).toLocalDate());}
            else if(action.equals("confirm")) {period=support.period(scope,businessDate);}
            var before=support.find(scope,"receipt",id,true);version(before,input.version());
            if(action.equals("submit")) {
                state(before,"draft");validateCurrent(scope,before);
                jdbc.update("UPDATE receipt SET status='pending_confirm',version=version+1,updated_by=? WHERE id=?",scope.userId(),id);
            } else if(Set.of("return","withdraw").contains(action)) {
                state(before,"pending_confirm");
                jdbc.update("UPDATE receipt SET status='draft',version=version+1,updated_by=? WHERE id=?",scope.userId(),id);
            } else if(action.equals("confirm")) {confirm(scope,id,before,period,businessDate);}
            else {throw invalid("收款操作无效");}
            audit.record(scope,"receipt."+action,"receipt",id,null,Map.of("version",input.version()+1));return get(scope,id);
        });
    }

    private void confirm(CompanyAccess scope,long id,Map<String,Object> before,long period,LocalDate date) {
        state(before,"pending_confirm");validateCurrent(scope,before);
        if(separateConfirmer(scope,before)) {throw new ApiException(422,"SAME_OPERATOR_NOT_ALLOWED","收款确认人不能是登记人");}
        if(before.get("related_order_id")!=null) {
            if(jdbc.queryForList("SELECT id FROM sales_order WHERE tenant_id=? AND company_id=?"+cn.erp.shared.OrgOwnership.filter(scope,"sales_order")+" AND id=? FOR UPDATE",Long.class,scope.tenantId(),scope.companyId(),before.get("related_order_id")).isEmpty()) throw missing();
        }
        BigDecimal value=decimal(before,"amount");Long partner=before.get("partner_id")==null?null:id(before,"partner_id");
        String type=before.get("receipt_type").toString();
        String counterpart=type.equals("customer")?"AR":type.equals("supplier_refund")?"AP":"OTHER_INCOME";
        ledger.post(scope,new LedgerService.Posting("receipt",id,0,period,date,List.of(
                new LedgerService.Line("FUNDS","D",value,null,id(before,"cash_account_id"),null,null,null,"确认收款"),
                new LedgerService.Line(counterpart,"C",value,partner,null,null,null,null,"确认收款"))));
        jdbc.update("UPDATE receipt SET status='confirmed',confirmed_by=?,confirmed_at=UTC_TIMESTAMP(3),period_id=?,business_date=?,version=version+1,updated_by=? WHERE id=?",
                scope.userId(),period,date,scope.userId(),id);
        if(partner!=null) {settlement.open(scope,new SettlementService.OpenItem("receipt",id,partner,"C",value,date));}
        if(before.get("related_order_id")!=null) {orders.refreshProgress(scope,id(before,"related_order_id"));}
    }

    private boolean separateConfirmer(CompanyAccess scope,Map<String,Object> receipt) {
        var values=jdbc.queryForList("SELECT separate_receipt_confirmer FROM company_policy WHERE tenant_id=? AND company_id=?",Integer.class,scope.tenantId(),scope.companyId());
        boolean separate=values.isEmpty()||values.getFirst()!=0;
        return separate&&receipt.get("created_by")!=null&&id(receipt,"created_by")==scope.userId();
    }

    private void validate(CompanyAccess scope,FinanceModels.Receipt input) {
        date(input.businessDate());amount(input.amount());references.requireCashAccount(scope,input.cashAccountId());
        if(input.receiptType().equals("other_income")) {
            if(input.partnerId()!=null||input.relatedOrderId()!=null||input.incomeItemId()==null) {throw invalid("其他收入请选择收入项目");}
            if(jdbc.queryForObject("SELECT COUNT(*) FROM income_expense_item WHERE tenant_id=? AND id=? AND item_type='income' AND status='active'",Long.class,scope.tenantId(),input.incomeItemId())!=1) {throw missing();}
        } else {
            if(input.partnerId()==null||input.incomeItemId()!=null) {throw invalid("请选择对应往来单位");}
            references.requirePartner(scope,input.partnerId(),input.receiptType().equals("customer")?"customer":"supplier");
            if(input.receiptType().equals("supplier_refund")&&input.relatedOrderId()!=null) {throw invalid("供应商退款不能关联销售订单");}
        }
        if(input.relatedOrderId()!=null) {
            var order=jdbc.queryForList("SELECT customer_id FROM sales_order WHERE tenant_id=? AND company_id=? AND id=?",scope.tenantId(),scope.companyId(),input.relatedOrderId());
            if(order.isEmpty()||id(order.getFirst(),"customer_id")!=input.partnerId()) {throw missing();}
            orders.get(scope,input.relatedOrderId());
        }
    }

    private void validateCurrent(CompanyAccess scope,Map<String,Object> before) {
        references.requireCashAccount(scope,id(before,"cash_account_id"));
        String type=before.get("receipt_type").toString();
        if(!type.equals("other_income")) {references.requirePartner(scope,id(before,"partner_id"),type.equals("customer")?"customer":"supplier");}
        else if(jdbc.queryForObject("SELECT COUNT(*) FROM income_expense_item WHERE tenant_id=? AND id=? AND status='active' AND item_type='income'",Long.class,scope.tenantId(),before.get("income_item_id"))!=1) {throw missing();}
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private Map<String,Object> execute(CompanyAccess scope,String operation,String key,Object payload,int status,java.util.function.Supplier<Map<String,Object>> work) {
        return commands.execute(scope,operation,key,payload,status,work,(Class)Map.class);
    }
    private static String blank(String value) {return value==null||value.isBlank()?null:value.trim();}
}
