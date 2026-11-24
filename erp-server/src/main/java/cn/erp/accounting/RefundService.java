package cn.erp.accounting;

import cn.erp.shared.ActorScope;
import cn.erp.shared.ApiException;
import cn.erp.shared.SourcelessRefundCredits;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 只处理运营账本内的退款额度和登记结果，银行操作由业务经办完成。 */
@Service
@Transactional
public class RefundService {
    private final JdbcTemplate jdbc;
    private final SettlementService settlement;
    private final LedgerService ledger;

    public RefundService(JdbcTemplate jdbc,SettlementService settlement,LedgerService ledger) {
        this.jdbc=jdbc;this.settlement=settlement;this.ledger=ledger;
    }

    /** 草稿退款先锁原收款或所选退货，再预占客户贷方额度。 */
    public long reserve(ActorScope scope,long refundId,List<Long> creditItemIds) {
        var refund=request(scope,refundId);
        if(!"draft".equals(refund.get("status"))) {throw AccountingAmounts.conflict("退款申请当前状态不能提交");}
        if(creditItemIds==null || creditItemIds.isEmpty() || creditItemIds.size()>200 || new HashSet<>(creditItemIds).size()!=creditItemIds.size()) {throw AccountingAmounts.invalid("请选择不重复的退款可用额度");}
        BigDecimal amount=AccountingAmounts.money((BigDecimal)refund.get("amount"));
        if(SourcelessRefundCredits.usesCredit(refund)) {
            if(!creditItemIds.equals(SourcelessRefundCredits.selected(jdbc,scope,refundId))) throw AccountingAmounts.conflict("额度来源与已保存申请不一致");
            SourcelessRefundCredits.requireRequest(jdbc,scope,refund,true,true);
        } else reserveReceipt(scope,refund,amount);
        LocalDate date=((java.sql.Date)refund.get("business_date")).toLocalDate();
        long debit=settlement.open(scope,new SettlementService.OpenItem("refund_request",refundId,((Number)refund.get("customer_id")).longValue(),"D",amount,date));
        var ids=new ArrayList<>(creditItemIds);ids.add(debit);
        var items=settlement.lockItems(scope,ids.stream().mapToLong(Long::longValue).toArray());
        BigDecimal remaining=amount;
        // lockItems 已按 ID 排序加锁，实际占用仍按调用方给出的账龄顺序分配。
        for(long credit:creditItemIds) {
            var item=items.get(credit);
            if(!"C".equals(item.get("direction")) || !item.get("partner_id").equals(refund.get("customer_id"))) {throw new ApiException(422,"SETTLEMENT_NOT_ALLOWED","退款额度须属于同一客户的贷方未清项");}
            BigDecimal used=remaining.min((BigDecimal)item.get("open_amount"));
            if(used.signum()>0) {settlement.reserve(scope,debit,credit,used,refundId);remaining=remaining.subtract(used);}
        }
        if(remaining.signum()>0) {throw new ApiException(422,"REFUND_AMOUNT_EXCEEDED","客户贷方可用额度不足");}
        jdbc.update("UPDATE refund_request SET status='pending_review',version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",scope.userId(),scope.tenantId(),scope.companyId(),refundId);
        return debit;
    }

    private void reserveReceipt(ActorScope scope,Map<String,Object> refund,BigDecimal amount) {
        var receipts=jdbc.queryForList("SELECT * FROM receipt WHERE tenant_id=? AND company_id=?"+cn.erp.shared.OrgOwnership.filter(scope,"receipt")+" AND id=? AND partner_id=? AND receipt_type='customer' AND status='confirmed' FOR UPDATE",
                scope.tenantId(),scope.companyId(),refund.get("original_receipt_id"),refund.get("customer_id"));
        if(receipts.isEmpty()) {throw AccountingAmounts.missing();}
        if(jdbc.update("UPDATE receipt SET refund_pending_amount=refund_pending_amount+?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=? AND amount-refunded_amount-refund_pending_amount>=?",
                amount,scope.tenantId(),scope.companyId(),refund.get("original_receipt_id"),amount)!=1) {throw new ApiException(422,"REFUND_AMOUNT_EXCEEDED","退款超过原收款的可退金额");}
    }

    public void approve(ActorScope scope,long refundId) {
        var refund=request(scope,refundId);
        if(!"pending_review".equals(refund.get("status"))) {throw AccountingAmounts.conflict("退款申请当前状态不能审核");}
        if(refund.get("created_by")!=null && ((Number)refund.get("created_by")).longValue()==scope.userId()) {throw new ApiException(422,"SAME_OPERATOR_NOT_ALLOWED","经办人不能审核自己的退款申请");}
        SourcelessRefundCredits.requireRequest(jdbc,scope,refund,true,true);
        jdbc.update("UPDATE refund_request SET status='pending_payment',reviewed_by=?,reviewed_at=UTC_TIMESTAMP(3),version=version+1,updated_by=? WHERE id=?",
                scope.userId(),scope.userId(),refundId);
    }

    public void release(ActorScope scope,long refundId) {
        var refund=request(scope,refundId);
        if("cancelled".equals(refund.get("status"))) {return;}
        if(!Set.of("pending_review","pending_payment").contains(refund.get("status"))) {throw AccountingAmounts.conflict("退款申请当前状态不能释放占用");}
        if(SourcelessRefundCredits.usesCredit(refund)) SourcelessRefundCredits.requireRequest(jdbc,scope,refund,true,true);
        else lockReceipt(scope,refund.get("original_receipt_id"));
        lockAllocationItems(scope,refundId);
        var allocations=allocations(scope,refundId);
        for(long allocation:allocations) {settlement.release(scope,allocation);}
        if(!SourcelessRefundCredits.usesCredit(refund)) {
            int updated=jdbc.update("UPDATE receipt SET refund_pending_amount=refund_pending_amount-?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=? AND refund_pending_amount>=?",
                refund.get("amount"),scope.tenantId(),scope.companyId(),refund.get("original_receipt_id"),refund.get("amount"));
            if(updated!=1) {throw AccountingAmounts.conflict("原收款的退款占用不一致，请先对账");}
        }
        jdbc.update("UPDATE refund_request SET status='cancelled',version=version+1,updated_by=? WHERE id=?",scope.userId(),refundId);
    }

    /** 财务在银行完成退款后登记，同一事务更新凭证、账户余额和占用状态。 */
    public long confirm(ActorScope scope,long refundId,long periodId,LocalDate date,String bankSerial) {
        ledger.lockOpenPeriod(scope,periodId,date);
        if(bankSerial==null || bankSerial.isBlank() || bankSerial.length()>64) {throw AccountingAmounts.invalid("请填写64字以内的银行流水号");}
        var refund=request(scope,refundId);
        SourcelessRefundCredits.requireRequest(jdbc,scope,refund,true,true);
        if("refunded".equals(refund.get("status"))) {
            if(!bankSerial.equals(refund.get("bank_serial_no")) || ((Number)refund.get("period_id")).longValue()!=periodId || !((java.sql.Date)refund.get("business_date")).toLocalDate().equals(date)) {throw new ApiException(409,"IDEMPOTENCY_KEY_REUSED","已登记退款的内容不一致");}
            return jdbc.queryForObject("SELECT id FROM journal WHERE tenant_id=? AND company_id=? AND source_type='refund_request' AND source_id=? AND posting_action='post' AND source_revision=0",Long.class,scope.tenantId(),scope.companyId(),refundId);
        }
        if(!"pending_payment".equals(refund.get("status"))) {throw AccountingAmounts.conflict("退款须先审核通过");}
        if(!SourcelessRefundCredits.usesCredit(refund)) lockReceipt(scope,refund.get("original_receipt_id"));
        lockAllocationItems(scope,refundId);
        var allocationIds=allocations(scope,refundId);
        BigDecimal reserved=jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM settlement_allocation WHERE tenant_id=? AND company_id=? AND trigger_type='refund_request' AND trigger_id=? AND status='reserved'",BigDecimal.class,scope.tenantId(),scope.companyId(),refundId);
        if(reserved.compareTo((BigDecimal)refund.get("amount"))!=0) {throw AccountingAmounts.conflict("退款占用金额不完整，请先对账");}
        long partner=((Number)refund.get("customer_id")).longValue(),account=((Number)refund.get("cash_account_id")).longValue();
        BigDecimal amount=(BigDecimal)refund.get("amount");
        long journal=ledger.post(scope,new LedgerService.Posting("refund_request",refundId,0,periodId,date,List.of(
                new LedgerService.Line("AR","D",amount,partner,null,null,null,null,"客户退款"),
                new LedgerService.Line("FUNDS","C",amount,null,account,null,null,null,"客户退款"))));
        if(!SourcelessRefundCredits.usesCredit(refund)) {
            int updated=jdbc.update("UPDATE receipt SET refund_pending_amount=refund_pending_amount-?,refunded_amount=refunded_amount+?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=? AND refund_pending_amount>=?",
                amount,amount,scope.tenantId(),scope.companyId(),refund.get("original_receipt_id"),amount);
            if(updated!=1) {throw AccountingAmounts.conflict("原收款的退款占用不一致，请先对账");}
        }
        for(long allocation:allocationIds) {jdbc.update("UPDATE settlement_allocation SET status='active',version=version+1 WHERE id=? AND status='reserved'",allocation);}
        jdbc.update("""
                UPDATE refund_request SET status='refunded',paid_registered_by=?,paid_at=UTC_TIMESTAMP(3),period_id=?,
                  business_date=?,bank_serial_no=?,version=version+1,updated_by=? WHERE id=?
                """,scope.userId(),periodId,date,bankSerial,scope.userId(),refundId);
        return journal;
    }

    private void lockAllocationItems(ActorScope scope,long refundId) {
        var itemIds=jdbc.queryForList("""
                SELECT debit_item_id AS item_id FROM settlement_allocation WHERE tenant_id=? AND company_id=? AND trigger_type='refund_request' AND trigger_id=? AND status='reserved'
                UNION SELECT credit_item_id FROM settlement_allocation WHERE tenant_id=? AND company_id=? AND trigger_type='refund_request' AND trigger_id=? AND status='reserved'
                """,Long.class,scope.tenantId(),scope.companyId(),refundId,scope.tenantId(),scope.companyId(),refundId);
        settlement.lockItems(scope,itemIds.stream().mapToLong(Long::longValue).toArray());
    }

    private void lockReceipt(ActorScope scope,Object id) {
        var rows=jdbc.queryForList("SELECT id FROM receipt WHERE tenant_id=? AND company_id=?"+cn.erp.shared.OrgOwnership.filter(scope,"receipt")+" AND id=? FOR UPDATE",Long.class,scope.tenantId(),scope.companyId(),id);
        if(rows.isEmpty()) throw AccountingAmounts.missing();
    }

    private Map<String,Object> request(ActorScope scope,long id) {
        var rows=jdbc.queryForList("SELECT * FROM refund_request WHERE tenant_id=? AND company_id=?"+cn.erp.shared.OrgOwnership.filter(scope,"refund_request")+" AND id=? FOR UPDATE",scope.tenantId(),scope.companyId(),id);
        if(rows.isEmpty()) {throw AccountingAmounts.missing();}
        return rows.getFirst();
    }

    private List<Long> allocations(ActorScope scope,long refundId) {
        return jdbc.queryForList("SELECT id FROM settlement_allocation WHERE tenant_id=? AND company_id=? AND trigger_type='refund_request' AND trigger_id=? AND status='reserved' ORDER BY id",Long.class,scope.tenantId(),scope.companyId(),refundId);
    }
}
