package cn.erp.reversal;

import cn.erp.accounting.LedgerService;
import cn.erp.accounting.SettlementService;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryReversalService;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import static cn.erp.salesfinance.FinanceSupport.*;

@Component
class ReversalPosting {
    private final JdbcTemplate jdbc;
    private final LedgerService ledger;
    private final SettlementService settlement;
    private final InventoryReversalService inventory;
    private final ReversalSources sources;
    private final ReversalProgress progress;
    ReversalPosting(JdbcTemplate jdbc,LedgerService ledger,SettlementService settlement,InventoryReversalService inventory,ReversalSources sources,ReversalProgress progress) {
        this.jdbc=jdbc;this.ledger=ledger;this.settlement=settlement;this.inventory=inventory;this.sources=sources;this.progress=progress;
    }
    void post(CompanyAccess scope,long reversal,String type,long source,Map<String,Object> head,long period,LocalDate day) {
        inventory.reverse(scope,type,source,reversal,period,day);
        for(String fact:List.of("receivable","payable"))for(var row:sources.facts(scope,type,source,fact,true))reverseFact(scope,fact,row,reversal,period,day);
        if(Set.of("receipt","payment").contains(type))reverseMoneyItem(scope,type,source,head,reversal,period,day);
        int revision=0;
        String journals=type.equals("shipment")?"'shipment_post','shipment_sign','shipment_diff'":"'"+type+"'";
        // 原单已被排他锁定，凭证只需读取当前已提交的事实。
        for(long journal:jdbc.queryForList("SELECT id FROM journal WHERE tenant_id=? AND company_id=? AND source_id=? AND source_type IN("+journals+") AND posting_action='post' ORDER BY id FOR SHARE",Long.class,scope.tenantId(),scope.companyId(),source))ledger.reverse(scope,journal,"document_reversal",reversal,revision++,period,day);
        progress.reverse(scope,type,source,head);
        sources.restoreProgress(scope,type,head);
        String table=type.equals("sales_price_adjustment")?"price_adjustment":type;
        jdbc.update("UPDATE "+table+" SET status='reversed',version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",scope.userId(),scope.tenantId(),scope.companyId(),source);
        progress.refresh(scope,type,source,head);
    }
    private void reverseFact(CompanyAccess scope,String fact,Map<String,Object> original,long reversal,long period,LocalDate day) {
        boolean ar=fact.equals("receivable");String party=ar?"customer_id":"supplier_id",reverse=ar?"reverses_receivable_id":"reverses_payable_id";
        String kind=original.get("kind").equals("charge")?"credit":"charge";long id=Ids.next();
        jdbc.update("INSERT INTO "+fact+"(id,tenant_id,company_id,"+party+",kind,source_type,source_id,source_line_id,posting_action,amount,untaxed_amount,tax_amount,business_date,period_id,"+reverse+",created_by,due_date) VALUES(?,?,?,?,?,?,?,?,'reverse',?,?,?,?,?,?,?,?)",
            id,scope.tenantId(),scope.companyId(),original.get(party),kind,original.get("source_type"),original.get("source_id"),original.get("source_line_id"),original.get("amount"),original.get("untaxed_amount"),original.get("tax_amount"),day,period,original.get("id"),scope.userId(),original.get("due_date"));
        String direction=ar?(kind.equals("charge")?"D":"C"):(kind.equals("charge")?"C":"D");
        long counter=settlement.open(scope,new SettlementService.OpenItem(fact,id,id(original,party),direction,decimal(original,"amount"),day));
        long old=jdbc.queryForObject("SELECT id FROM partner_open_item WHERE tenant_id=? AND company_id=? AND item_type=? AND source_id=? FOR UPDATE",Long.class,scope.tenantId(),scope.companyId(),fact,original.get("id"));
        settle(scope,old,counter,direction,decimal(original,"amount"),reversal);
    }
    private void reverseMoneyItem(CompanyAccess scope,String type,long source,Map<String,Object> head,long reversal,long period,LocalDate day) {
        if(head.get("partner_id")==null)return;
        boolean ar=type.equals("receipt")&&head.get("receipt_type").equals("customer");
        String fact=ar?"receivable":"payable",party=ar?"customer_id":"supplier_id";
        String direction=type.equals("receipt")?"D":"C";
        String kind=ar?"charge":type.equals("receipt")?"credit":"charge";
        long id=Ids.next();BigDecimal value=decimal(head,"amount");
        jdbc.update("INSERT INTO "+fact+"(id,tenant_id,company_id,"+party+",kind,source_type,source_id,source_line_id,amount,untaxed_amount,tax_amount,business_date,period_id,created_by) VALUES(?,?,?,?,?,'document_reversal',?,?,?,?,0,?,?,?)",
            id,scope.tenantId(),scope.companyId(),head.get("partner_id"),kind,reversal,source,value,value,day,period,scope.userId());
        long counter=settlement.open(scope,new SettlementService.OpenItem(fact,id,id(head,"partner_id"),direction,value,day));
        long old=jdbc.queryForObject("SELECT id FROM partner_open_item WHERE tenant_id=? AND company_id=? AND item_type=? AND source_id=? FOR UPDATE",Long.class,scope.tenantId(),scope.companyId(),type,source);
        settle(scope,old,counter,direction,value,reversal);
    }
    private void settle(CompanyAccess scope,long old,long counter,String counterDirection,BigDecimal value,long reversal) {
        settlement.allocate(scope,counterDirection.equals("D")?counter:old,counterDirection.equals("C")?counter:old,value,"reversal","document_reversal",reversal);
    }
}
