package cn.erp.purchasefinance;

import cn.erp.accounting.LedgerService;
import cn.erp.identity.CompanyAccess;
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
public class AccountTransferService {
    private final JdbcTemplate jdbc;
    private final TreasurySupport support;
    private final FinanceSupport finance;
    private final LedgerService ledger;
    private final BusinessCommands commands;
    private final DocumentNumbers numbers;
    private final AuditService audit;
    public AccountTransferService(JdbcTemplate jdbc,TreasurySupport support,FinanceSupport finance,LedgerService ledger,BusinessCommands commands,DocumentNumbers numbers,AuditService audit) {
        this.jdbc=jdbc;this.support=support;this.finance=finance;this.ledger=ledger;this.commands=commands;this.numbers=numbers;this.audit=audit;
    }
    public Map<String,Object> list(CompanyAccess scope,String status,Long cursor,int limit) {
        long before=cursor(cursor,limit);
        if(status!=null&&!Set.of("draft","pending_confirm","confirmed","reversed").contains(status)) {throw invalid("转账状态无效");}
        var rows=jdbc.queryForList("SELECT * FROM account_transfer WHERE tenant_id=? AND company_id=? AND id<? AND (? IS NULL OR status=?) ORDER BY id DESC LIMIT ?",scope.tenantId(),scope.companyId(),before,status,status,limit+1);
        var result=page(rows,limit);
        @SuppressWarnings("unchecked") var items=(List<Map<String,Object>>)result.get("items");
        for(int index=0;index<items.size();index++) {decorate(scope,rows.get(index),items.get(index));}return result;
    }
    public Map<String,Object> get(CompanyAccess scope,long id) {var raw=support.find(scope,"account_transfer",id,false);var result=row(raw);decorate(scope,raw,result);return result;}
    private void decorate(CompanyAccess scope,Map<String,Object> raw,Map<String,Object> result) {
        result.put("documentNo",raw.get("transfer_no"));result.put("fromAccountName",support.accountName(scope,raw.get("from_account_id")));
        result.put("toAccountName",support.accountName(scope,raw.get("to_account_id")));result.put("allowedActions",support.actions(scope,raw,"account_transfer"));
    }
    @Transactional
    public Map<String,Object> create(CompanyAccess scope,String key,TreasuryModels.Transfer input) {
        return execute(scope,"create",key,input,201,()->{
            validate(scope,input);long id=Ids.next();String no=numbers.next(scope,"AT",YearMonth.from(input.businessDate()));
            jdbc.update("""
                INSERT INTO account_transfer(id,tenant_id,company_id,transfer_no,from_account_id,to_account_id,amount,fee_amount,business_date,remark,created_by,updated_by)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,scope.tenantId(),scope.companyId(),no,input.fromAccountId(),input.toAccountId(),amount(input.amount()),TreasurySupport.fee(input.feeAmount()),input.businessDate(),input.remark(),scope.userId(),scope.userId());
            audit.record(scope,"account_transfer.create","account_transfer",id,null,Map.of("documentNo",no));return get(scope,id);
        });
    }
    @Transactional
    public Map<String,Object> update(CompanyAccess scope,long id,TreasuryModels.Transfer input) {
        var before=support.find(scope,"account_transfer",id,true);version(before,input.version());state(before,"draft");validate(scope,input);
        jdbc.update("UPDATE account_transfer SET from_account_id=?,to_account_id=?,amount=?,fee_amount=?,business_date=?,remark=?,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",
            input.fromAccountId(),input.toAccountId(),amount(input.amount()),TreasurySupport.fee(input.feeAmount()),input.businessDate(),input.remark(),scope.userId(),scope.tenantId(),scope.companyId(),id);
        audit.record(scope,"account_transfer.update","account_transfer",id,null,Map.of("version",input.version()+1));return get(scope,id);
    }
    @Transactional
    public Map<String,Object> command(CompanyAccess scope,long id,String action,String key,TreasuryModels.Version input) {
        return execute(scope,action,key,List.of(id,input),200,()->{
            var snapshot=support.find(scope,"account_transfer",id,false);
            LocalDate day=input.businessDate()==null?((java.sql.Date)snapshot.get("business_date")).toLocalDate():input.businessDate();
            Long period=action.equals("confirm")?finance.period(scope,day):null;
            var before=support.find(scope,"account_transfer",id,true);version(before,input.version());
            switch(action) {
                case "submit" -> {state(before,"draft");validateCurrent(scope,before);changeState(scope,id,"pending_confirm");}
                case "return" -> {state(before,"pending_confirm");changeState(scope,id,"draft");}
                case "confirm" -> confirm(scope,id,before,period,day);
                default -> throw invalid("转账操作无效");
            }
            audit.record(scope,"account_transfer."+action,"account_transfer",id,null,Map.of("status",get(scope,id).get("status")));return get(scope,id);
        });
    }
    private void confirm(CompanyAccess scope,long id,Map<String,Object> before,long period,LocalDate day) {
        state(before,"pending_confirm");validateCurrent(scope,before);
        if(support.separate(scope,before)) {throw new ApiException(422,"SAME_OPERATOR_NOT_ALLOWED","转账确认人不能是登记人");}
        BigDecimal amount=decimal(before,"amount"),fee=decimal(before,"fee_amount");
        var lines=new ArrayList<LedgerService.Line>();
        lines.add(new LedgerService.Line("FUNDS","D",amount,null,id(before,"to_account_id"),null,null,null,"账户转入"));
        lines.add(new LedgerService.Line("FUNDS","C",amount.add(fee),null,id(before,"from_account_id"),null,null,null,"账户转出"));
        if(fee.signum()>0) {lines.add(new LedgerService.Line("TRANSFER_FEE","D",fee,null,id(before,"from_account_id"),null,null,null,"转账手续费"));}
        ledger.post(scope,new LedgerService.Posting("account_transfer",id,0,period,day,lines));
        jdbc.update("UPDATE account_transfer SET status='confirmed',confirmed_by=?,confirmed_at=UTC_TIMESTAMP(3),period_id=?,business_date=?,version=version+1,updated_by=? WHERE id=?",scope.userId(),period,day,scope.userId(),id);
    }
    private void changeState(CompanyAccess scope,long id,String state) {jdbc.update("UPDATE account_transfer SET status=?,version=version+1,updated_by=? WHERE id=?",state,scope.userId(),id);}
    private void validate(CompanyAccess scope,TreasuryModels.Transfer input) {
        date(input.businessDate());amount(input.amount());TreasurySupport.fee(input.feeAmount());
        if(input.fromAccountId().equals(input.toAccountId())) {throw invalid("转出和转入账户不能相同");}
        // 顺序访问账户，避免相反方向转账对基础资料锁的顺序不一致。
        for(long id:new TreeSet<>(List.of(input.fromAccountId(),input.toAccountId()))) {support.account(scope,id);}
        if(amount(input.amount()).add(TreasurySupport.fee(input.feeAmount())).precision()>24) {throw invalid("转账金额与手续费合计超出允许范围");}
    }
    private void validateCurrent(CompanyAccess scope,Map<String,Object> raw) {validate(scope,new TreasuryModels.Transfer(id(raw,"from_account_id"),id(raw,"to_account_id"),decimal(raw,"amount").toPlainString(),decimal(raw,"fee_amount").toPlainString(),((java.sql.Date)raw.get("business_date")).toLocalDate(),(String)raw.get("remark"),null));}
    @SuppressWarnings({"unchecked","rawtypes"})
    private Map<String,Object> execute(CompanyAccess scope,String action,String key,Object payload,int status,java.util.function.Supplier<Map<String,Object>> work) {return commands.execute(scope,"account_transfer."+action,key,payload,status,work,(Class)Map.class);}
}
