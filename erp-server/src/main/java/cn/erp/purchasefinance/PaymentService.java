package cn.erp.purchasefinance;

import cn.erp.accounting.*;
import cn.erp.identity.CompanyAccess;
import cn.erp.purchasing.PurchaseOrderQueries;
import cn.erp.reference.ReferenceAvailability;
import cn.erp.salesfinance.FinanceSupport;
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
public class PaymentService {
    private final JdbcTemplate jdbc;
    private final TreasurySupport support;
    private final FinanceSupport finance;
    private final ReferenceAvailability references;
    private final LedgerService ledger;
    private final SettlementService settlement;
    private final PurchaseOrderQueries orders;
    private final BusinessCommands commands;
    private final DocumentNumbers numbers;
    private final AuditService audit;
    public PaymentService(JdbcTemplate jdbc,TreasurySupport support,FinanceSupport finance,ReferenceAvailability references,LedgerService ledger,SettlementService settlement,PurchaseOrderQueries orders,BusinessCommands commands,DocumentNumbers numbers,AuditService audit) {
        this.jdbc=jdbc;this.support=support;this.finance=finance;this.references=references;this.ledger=ledger;this.settlement=settlement;this.orders=orders;this.commands=commands;this.numbers=numbers;this.audit=audit;
    }
    public Map<String,Object> list(CompanyAccess scope,Long partner,String status,Long cursor,int limit) {
        long before=cursor(cursor,limit);validateStatus(status);
        var rows=jdbc.queryForList("""
            SELECT p.* FROM payment p WHERE tenant_id=? AND company_id=? AND id<? AND (? IS NULL OR partner_id=?)
              AND (? IS NULL OR status=?) %s ORDER BY id DESC LIMIT ?
            """.formatted(cn.erp.shared.OrgOwnership.filter(scope,"p")),scope.tenantId(),scope.companyId(),before,partner,partner,status,status,limit+1);
        var result=page(rows,limit);
        @SuppressWarnings("unchecked") var items=(List<Map<String,Object>>)result.get("items");
        for(int index=0;index<items.size();index++) {decorate(scope,rows.get(index),items.get(index));}
        return result;
    }
    public Map<String,Object> get(CompanyAccess scope,long id) {var raw=support.find(scope,"payment",id,false);var result=row(raw);decorate(scope,raw,result);return result;}
    private void decorate(CompanyAccess scope,Map<String,Object> raw,Map<String,Object> result) {
        result.put("documentNo",raw.get("payment_no"));result.put("cashAccountName",support.accountName(scope,raw.get("cash_account_id")));
        result.put("partnerName",raw.get("partner_id")==null?null:jdbc.queryForObject("SELECT name FROM business_partner WHERE tenant_id=? AND id=?",String.class,scope.tenantId(),raw.get("partner_id")));
        result.put("allowedActions",support.actions(scope,raw,"payment"));
    }
    @Transactional
    public Map<String,Object> create(CompanyAccess scope,String key,TreasuryModels.Payment input) {
        return execute(scope,"create",key,input,201,()->{
            validate(scope,input);long id=Ids.next();String no=numbers.next(scope,"PY",YearMonth.from(input.businessDate()));
            jdbc.update("""
                INSERT INTO payment(id,tenant_id,company_id,payment_no,payment_type,partner_id,expense_item_id,cash_account_id,amount,business_date,related_order_id,bank_serial_no,remark,created_by,updated_by,owner_user_id,owner_department_id)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,scope.tenantId(),scope.companyId(),no,input.paymentType(),input.partnerId(),input.expenseItemId(),input.cashAccountId(),amount(input.amount()),input.businessDate(),input.relatedOrderId(),TreasurySupport.blank(input.bankSerialNo()),input.remark(),scope.userId(),scope.userId(),scope.userId(),cn.erp.shared.OrgOwnership.department(scope));
            audit.record(scope,"payment.create","payment",id,null,Map.of("documentNo",no));return get(scope,id);
        });
    }
    @Transactional
    public Map<String,Object> update(CompanyAccess scope,long id,TreasuryModels.Payment input) {
        var before=support.find(scope,"payment",id,true);version(before,input.version());state(before,"draft");validate(scope,input);
        jdbc.update("""
            UPDATE payment SET payment_type=?,partner_id=?,expense_item_id=?,cash_account_id=?,amount=?,business_date=?,related_order_id=?,bank_serial_no=?,remark=?,version=version+1,updated_by=?
            WHERE tenant_id=? AND company_id=? AND id=?
            """,input.paymentType(),input.partnerId(),input.expenseItemId(),input.cashAccountId(),amount(input.amount()),input.businessDate(),input.relatedOrderId(),TreasurySupport.blank(input.bankSerialNo()),input.remark(),scope.userId(),scope.tenantId(),scope.companyId(),id);
        audit.record(scope,"payment.update","payment",id,null,Map.of("version",input.version()+1));return get(scope,id);
    }
    @Transactional
    public Map<String,Object> command(CompanyAccess scope,long id,String action,String key,TreasuryModels.Version input) {
        return execute(scope,action,key,List.of(id,input),200,()->{
            var snapshot=support.find(scope,"payment",id,false);
            LocalDate day=input.businessDate()==null?((java.sql.Date)snapshot.get("business_date")).toLocalDate():input.businessDate();
            Long period=null;
            if(action.equals("submit")) {period=finance.period(scope,((java.sql.Date)snapshot.get("business_date")).toLocalDate());}
            else if(action.equals("confirm")) {period=finance.period(scope,day);}
            var before=support.find(scope,"payment",id,true);version(before,input.version());
            switch(action) {
                case "submit" -> {state(before,"draft");validateCurrent(scope,before);changeState(scope,id,"pending_confirm");}
                case "return" -> {state(before,"pending_confirm");changeState(scope,id,"draft");}
                case "confirm" -> confirm(scope,id,before,period,day);
                default -> throw invalid("付款操作无效");
            }
            audit.record(scope,"payment."+action,"payment",id,null,Map.of("status",get(scope,id).get("status")));return get(scope,id);
        });
    }
    private void confirm(CompanyAccess scope,long id,Map<String,Object> before,long period,LocalDate day) {
        state(before,"pending_confirm");validateCurrent(scope,before);
        if(support.separate(scope,before)) {throw new ApiException(422,"SAME_OPERATOR_NOT_ALLOWED","付款确认人不能是登记人");}
        if(before.get("related_order_id")!=null) {orders.header(scope,id(before,"related_order_id"),true);}
        BigDecimal value=decimal(before,"amount");Long partner=null;
        if(before.get("partner_id")!=null) {partner=id(before,"partner_id");}
        ledger.post(scope,new LedgerService.Posting("payment",id,0,period,day,List.of(
            new LedgerService.Line(partner==null?"OTHER_EXPENSE":"AP","D",value,partner,null,null,null,null,"确认付款"),
            new LedgerService.Line("FUNDS","C",value,null,id(before,"cash_account_id"),null,null,null,"确认付款"))));
        jdbc.update("UPDATE payment SET status='confirmed',confirmed_by=?,confirmed_at=UTC_TIMESTAMP(3),period_id=?,business_date=?,version=version+1,updated_by=? WHERE id=?",scope.userId(),period,day,scope.userId(),id);
        if(partner!=null) {settlement.open(scope,new SettlementService.OpenItem("payment",id,partner,"D",value,day));}
        if(before.get("related_order_id")!=null) {orders.refreshProgress(scope,id(before,"related_order_id"));}
    }
    private void changeState(CompanyAccess scope,long id,String state) {jdbc.update("UPDATE payment SET status=?,version=version+1,updated_by=? WHERE id=?",state,scope.userId(),id);}
    private void validate(CompanyAccess scope,TreasuryModels.Payment input) {
        date(input.businessDate());amount(input.amount());support.account(scope,input.cashAccountId());
        if("other_expense".equals(input.paymentType())) {
            if(input.partnerId()!=null||input.relatedOrderId()!=null||input.expenseItemId()==null) {throw invalid("其他支出请选择支出项目，不关联往来或采购订单");}
            if(jdbc.queryForObject("SELECT COUNT(*) FROM income_expense_item WHERE tenant_id=? AND id=? AND item_type='expense' AND status='active'",Long.class,scope.tenantId(),input.expenseItemId())!=1) {throw new ApiException(422,"MASTER_DATA_DISABLED","支出项目不存在或已停用");}
        } else if("supplier".equals(input.paymentType())) {
            if(input.partnerId()==null||input.expenseItemId()!=null) {throw invalid("供应商付款请选择供应商");}
            references.requirePartner(scope,input.partnerId(),"supplier");
            if(input.relatedOrderId()!=null) {
                var order=orders.header(scope,input.relatedOrderId(),false);
                if(id(order,"supplier_id")!=input.partnerId()||"cancelled".equals(order.get("status"))) {throw invalid("关联采购订单须属于当前供应商且未取消");}
            }
        } else {throw invalid("付款类型无效");}
    }
    private void validateCurrent(CompanyAccess scope,Map<String,Object> raw) {
        validate(scope,new TreasuryModels.Payment(raw.get("payment_type").toString(),nullable(raw.get("partner_id")),nullable(raw.get("expense_item_id")),id(raw,"cash_account_id"),decimal(raw,"amount").toPlainString(),((java.sql.Date)raw.get("business_date")).toLocalDate(),nullable(raw.get("related_order_id")),(String)raw.get("bank_serial_no"),(String)raw.get("remark"),null));
    }
    private static Long nullable(Object value) {if(value==null) {return null;}return ((Number)value).longValue();}
    private void validateStatus(String status) {if(status!=null&&!Set.of("draft","pending_confirm","confirmed","reversed").contains(status)) {throw invalid("付款状态无效");}}
    @SuppressWarnings({"unchecked","rawtypes"})
    private Map<String,Object> execute(CompanyAccess scope,String action,String key,Object payload,int status,java.util.function.Supplier<Map<String,Object>> work) {return commands.execute(scope,"payment."+action,key,payload,status,work,(Class)Map.class);}
}
