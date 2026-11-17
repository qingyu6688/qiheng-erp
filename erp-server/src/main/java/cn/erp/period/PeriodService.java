package cn.erp.period;

import cn.erp.accounting.LedgerClosingProjection;
import cn.erp.identity.CompanyAccess;
import cn.erp.reconciliation.ReconciliationChecks;
import cn.erp.shared.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.salesfinance.FinanceSupport.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
public class PeriodService {
    public record Version(@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号无效") Long version,
        @Size(max=200,message="原因最多200字") String reason) {}
    private static final Set<String> DOCUMENTS=Set.of("shipment","goods_receipt","sales_return","purchase_return","stock_transfer","stock_count","stock_adjustment","stock_misc_doc","document_reversal");
    private static final Map<String,String> DOCUMENT_NUMBERS=Map.ofEntries(Map.entry("shipment","shipment_no"),Map.entry("goods_receipt","receipt_no"),Map.entry("sales_return","return_no"),Map.entry("purchase_return","return_no"),
        Map.entry("stock_transfer","transfer_no"),Map.entry("stock_count","count_no"),Map.entry("stock_adjustment","adjust_no"),Map.entry("stock_misc_doc","document_no"),Map.entry("document_reversal","reversal_no"),Map.entry("receipt","receipt_no"),
        Map.entry("payment","payment_no"),Map.entry("refund_request","refund_no"),Map.entry("account_transfer","transfer_no"),Map.entry("opening_entry","entry_no"));
    private final JdbcTemplate jdbc;
    private final ReconciliationChecks checks;
    private final LedgerClosingProjection projections;
    private final BusinessCommands commands;
    private final AuditService audit;
    public PeriodService(JdbcTemplate jdbc,ReconciliationChecks checks,LedgerClosingProjection projections,BusinessCommands commands,AuditService audit) {
        this.jdbc=jdbc;this.checks=checks;this.projections=projections;this.commands=commands;this.audit=audit;
    }

    private FinancialPeriodPort financial;
    @org.springframework.beans.factory.annotation.Autowired
    void financial(FinancialPeriodPort financial) {this.financial=financial;}

    public Map<String,Object> closeCheck(CompanyAccess scope,long id) {
        companyScope(scope);var period=find(scope,id);return report(scope,id,period);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    @SuppressWarnings({"unchecked","rawtypes"})
    public Map<String,Object> close(CompanyAccess scope,long id,String key,Version input) {
        companyScope(scope);if(financial!=null)financial.authorize(scope,"close");
        return commands.execute(scope,"period.close",key,List.of(id,input),200,()->{
            var before=find(scope,id);version(before,input.version());state(before,"open");
            // 投影在短事务内完成，不能拿着期间排他锁等待后台消费者。
            for(long event:projections.pending(scope)) {projections.project(scope,event);}
            if(financial!=null) financial.prepare(scope,id);
            var initial=report(scope,id,before);requirePassed(initial);
            var periods=lockPeriods(scope);var current=periods.stream().filter(p->id(p,"id")==id).findFirst().orElseThrow(()->missing());
            version(current,input.version());state(current,"open");
            if(periods.stream().anyMatch(p->p.get("period").toString().compareTo(current.get("period").toString())<0&&"open".equals(p.get("status")))) {
                throw new ApiException(422,"PERIOD_ORDER_INVALID","请先关闭之前的开放期间");
            }
            // 排他锁等到在途过账提交后再读最新事实，防止旧快照穿透。
            var latest=report(scope,id,current);requirePassed(latest);
            if(!projections.pending(scope).isEmpty()) {
                throw new ApiException(422,"PERIOD_CLOSE_CHECK_FAILED","有新凭证尚未汇总，请重新执行关账",latest.get("items"));
            }
            if(financial!=null) financial.close(scope,id);
            jdbc.update("UPDATE accounting_period SET status='closed',closed_by=?,closed_at=UTC_TIMESTAMP(3),version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",scope.userId(),scope.tenantId(),scope.companyId(),id);
            var result=view(find(scope,id));audit.record(scope,"period.close","accounting_period",id,view(before),result);return result;
        },(Class)Map.class);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    @SuppressWarnings({"unchecked","rawtypes"})
    public Map<String,Object> reopen(CompanyAccess scope,long id,String key,Version input) {
        companyScope(scope);if(financial!=null)financial.authorize(scope,"reopen");
        if(input.reason()==null||input.reason().isBlank()) {throw invalid("请填写重新打开期间的原因");}
        return commands.execute(scope,"period.reopen",key,List.of(id,input),200,()->{
            var periods=lockPeriods(scope);var before=periods.stream().filter(p->id(p,"id")==id).findFirst().orElseThrow(()->missing());
            version(before,input.version());state(before,"closed");
            if(periods.stream().anyMatch(p->p.get("period").toString().compareTo(before.get("period").toString())>0&&"closed".equals(p.get("status")))) {
                throw new ApiException(422,"PERIOD_ORDER_INVALID","只能重新打开最近一个已关闭期间");
            }
            jdbc.update("UPDATE accounting_period SET status='open',closed_by=NULL,closed_at=NULL,reopen_count=reopen_count+1,version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",scope.tenantId(),scope.companyId(),id);
            if(financial!=null) financial.reopen(scope,id,input.reason());
            var result=view(find(scope,id));audit.record(scope,"period.reopen","accounting_period",id,view(before),Map.of("period",result,"reason",input.reason()));return result;
        },(Class)Map.class);
    }

    private Map<String,Object> report(CompanyAccess scope,long id,Map<String,Object> period) {
        var pending=checks.check(scope,"period_close",id);var items=new ArrayList<Map<String,Object>>();
        add(items,"unposted_documents",pending.stream().filter(d->DOCUMENTS.contains(d.key())).toList(),"已提交未过账的单据已处理",scope,period);
        add(items,"pending_money",pending.stream().filter(d->Set.of("receipt","payment","refund_request","account_transfer","opening_entry").contains(d.key())).toList(),"没有待确认资金及未完成退款",scope,period);
        var inventory=new ArrayList<>(pending.stream().filter(d->Set.of("inventory_balance","inventory_cost","batch_balance","stock_blocks","location_balance").contains(d.objectType())).toList());
        for(String type:List.of("reservation","ledger_inventory")) {inventory.addAll(checks.check(scope,type,null));}
        add(items,"inventory_reconciliation",inventory,"库存、批次、库位、冻结、预留、成本及账本一致",scope,period);
        var money=new ArrayList<>(pending.stream().filter(d->Set.of("cash_balance","open_item").contains(d.objectType())).toList());
        money.addAll(checks.check(scope,"journal_balance",null));
        add(items,"money_reconciliation",money,"资金、往来与凭证一致",scope,period);
        long residues=jdbc.queryForObject("""
            SELECT COUNT(*) FROM (
              SELECT warehouse_id,sku_id,SUM(q) q,SUM(a) a FROM (
                SELECT warehouse_id,sku_id,on_hand_delta q,cost_delta a FROM inventory_movement WHERE tenant_id=? AND company_id=?
                UNION ALL SELECT warehouse_id,sku_id,0,amount FROM cost_adjustment WHERE tenant_id=? AND company_id=?
              ) movements GROUP BY warehouse_id,sku_id HAVING q=0 AND a<>0
            ) residues
            """,Long.class,scope.tenantId(),scope.companyId(),scope.tenantId(),scope.companyId());
        items.add(Map.of("check","cost_residue","passed",residues==0,"count",residues,"message",residues==0?"数量归零的成本尾差已处理":"存在数量归零但成本未归零的流水"));
        if(financial!=null) items.addAll(financial.checks(scope,id));
        return Map.of("id",Long.toString(id),"period",period.get("period"),"version",period.get("version"),"passed",items.stream().allMatch(item->Boolean.TRUE.equals(item.get("passed"))),"items",items);
    }
    private void add(List<Map<String,Object>> items,String type,List<ReconciliationChecks.Difference> differences,String success,CompanyAccess scope,Map<String,Object> period) {
        long count=differences.stream().map(ReconciliationChecks.Difference::actual).mapToLong(n->n.signum()>0&&n.scale()<=0?n.longValue():1).sum();
        var documents=new ArrayList<Map<String,Object>>();
        for(var difference:differences) {
            String table=difference.key();if(!DOCUMENT_NUMBERS.containsKey(table)) {continue;}
            String states=switch(table) {
                case "stock_transfer" -> "'submitted','shipped','received_with_diff'";
                case "stock_count" -> "'counting','submitted','approved'";
                case "stock_adjustment","stock_misc_doc","document_reversal" -> "'pending','approved'";
                case "refund_request" -> "'pending_review','pending_payment'";
                case "opening_entry" -> "'approved'";
                case "receipt","payment","account_transfer" -> "'pending_confirm'";
                default -> "'submitted'";
            };
            String extra=table.equals("stock_misc_doc")?",direction":"";
            for(var raw:jdbc.queryForList("SELECT id,"+DOCUMENT_NUMBERS.get(table)+" document_no,status"+extra+" FROM "+table+" WHERE tenant_id=? AND company_id=? AND business_date BETWEEN ? AND ? AND status IN("+states+") ORDER BY business_date,id LIMIT 50",scope.tenantId(),scope.companyId(),period.get("start_date"),period.get("end_date"))) {
                String documentType=table.equals("stock_misc_doc")?"stock_misc_"+raw.get("direction"):table;
                documents.add(Map.of("documentType",documentType,"documentId",raw.get("id").toString(),"documentNo",raw.get("document_no"),"status",raw.get("status")));
            }
        }
        items.add(Map.of("check",type,"passed",differences.isEmpty(),"count",count,"message",differences.isEmpty()?success:"检查未通过，请处理相关单据或对账差异","documents",documents));
    }
    private void requirePassed(Map<String,Object> result) {if(!Boolean.TRUE.equals(result.get("passed"))) {throw new ApiException(422,"PERIOD_CLOSE_CHECK_FAILED","关账检查未通过",result.get("items"));}}
    private List<Map<String,Object>> lockPeriods(CompanyAccess scope) {return jdbc.queryForList("SELECT * FROM accounting_period WHERE tenant_id=? AND company_id=? ORDER BY period FOR UPDATE",scope.tenantId(),scope.companyId());}
    private Map<String,Object> find(CompanyAccess scope,long id) {var rows=jdbc.queryForList("SELECT * FROM accounting_period WHERE tenant_id=? AND company_id=? AND id=?",scope.tenantId(),scope.companyId(),id);if(rows.isEmpty()) {throw missing();}return rows.getFirst();}
    private Map<String,Object> view(Map<String,Object> row) {return cn.erp.salesfinance.FinanceSupport.row(row);}
    private void companyScope(CompanyAccess scope) {if(!scope.allWarehouses()||!scope.orgScope().allDepartments()) {throw new ApiException(403,"FORBIDDEN","关账和重开期间需要整家公司数据范围");}}
}
