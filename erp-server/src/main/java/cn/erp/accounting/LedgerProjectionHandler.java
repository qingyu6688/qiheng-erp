package cn.erp.accounting;

import cn.erp.jobs.*;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class LedgerProjectionHandler implements EventHandler {
    private final JdbcTemplate jdbc;
    LedgerProjectionHandler(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public String eventType() {return "journal.posted";}
    public String consumer() {return "ledger_projection";}

    public void handle(OutboxService.Event event) {
        // 独立协调行按公司串行，避免业务外键共享锁影响已提交事件的投影。
        Long company=jdbc.queryForObject("SELECT company_id FROM journal WHERE tenant_id=? AND id=? FOR SHARE",Long.class,event.tenantId(),event.aggregateId());
        jdbc.update("INSERT INTO ledger_projection_lock(tenant_id,company_id) VALUES(?,?) ON DUPLICATE KEY UPDATE company_id=company_id",event.tenantId(),company);
        jdbc.queryForObject("SELECT company_id FROM ledger_projection_lock WHERE tenant_id=? AND company_id=? FOR UPDATE",Long.class,event.tenantId(),company);
        // 每个维度只累计已消费事件。最大ID仅作展示水位，不能跳过迟到提交。
        var rows=jdbc.queryForList("""
                SELECT j.company_id,j.period_id,l.account_code,COALESCE(l.partner_id,0) partner_key,
                COALESCE(l.cash_account_id,0) cash_key,COALESCE(l.warehouse_id,0) warehouse_key,
                SUM(IF(l.direction='D',l.amount,0)) d,SUM(IF(l.direction='C',l.amount,0)) c
                FROM journal j JOIN journal_line l ON l.tenant_id=j.tenant_id AND l.company_id=j.company_id AND l.journal_id=j.id
                WHERE j.tenant_id=? AND j.id=? GROUP BY j.company_id,j.period_id,l.account_code,l.partner_id,l.cash_account_id,l.warehouse_id
                ORDER BY j.company_id,j.period_id,l.account_code,partner_key,cash_key,warehouse_key
                """,event.tenantId(),event.aggregateId());
        if(rows.isEmpty()) {throw new IllegalStateException("凭证事件没有对应分录");}
        for(var row:rows) {
            BigDecimal opening=jdbc.queryForObject("""
                    SELECT COALESCE(SUM(b.debit_amount-b.credit_amount),0) FROM ledger_balance b
                    JOIN accounting_period previous ON previous.tenant_id=b.tenant_id AND previous.company_id=b.company_id AND previous.id=b.period_id
                    JOIN accounting_period current_period ON current_period.tenant_id=b.tenant_id AND current_period.company_id=b.company_id AND current_period.id=?
                    WHERE b.tenant_id=? AND b.company_id=? AND b.account_code=? AND b.partner_key=?
                      AND b.cash_account_key=? AND b.warehouse_key=? AND previous.start_date<current_period.start_date
                    """,BigDecimal.class,row.get("period_id"),event.tenantId(),company,row.get("account_code"),row.get("partner_key"),row.get("cash_key"),row.get("warehouse_key"));
            jdbc.update("""
                    INSERT INTO ledger_balance(id,tenant_id,company_id,period_id,account_code,partner_key,cash_account_key,warehouse_key,
                    opening_balance,debit_amount,credit_amount,projected_journal_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                    ON DUPLICATE KEY UPDATE opening_balance=VALUES(opening_balance),debit_amount=debit_amount+VALUES(debit_amount),
                    credit_amount=credit_amount+VALUES(credit_amount),projected_journal_id=GREATEST(projected_journal_id,VALUES(projected_journal_id))
                    """,Ids.next(),event.tenantId(),row.get("company_id"),row.get("period_id"),row.get("account_code"),row.get("partner_key"),
                    row.get("cash_key"),row.get("warehouse_key"),opening,row.get("d"),row.get("c"),event.aggregateId());
            BigDecimal movement=((BigDecimal)row.get("d")).subtract((BigDecimal)row.get("c"));
            jdbc.update("""
                    UPDATE ledger_balance b JOIN accounting_period later ON later.tenant_id=b.tenant_id AND later.company_id=b.company_id AND later.id=b.period_id
                    JOIN accounting_period current_period ON current_period.tenant_id=b.tenant_id AND current_period.company_id=b.company_id AND current_period.id=?
                    SET b.opening_balance=b.opening_balance+?
                    WHERE b.tenant_id=? AND b.company_id=? AND b.account_code=? AND b.partner_key=?
                      AND b.cash_account_key=? AND b.warehouse_key=? AND later.start_date>current_period.start_date
                    """,row.get("period_id"),movement,event.tenantId(),company,row.get("account_code"),row.get("partner_key"),row.get("cash_key"),row.get("warehouse_key"));
        }
    }
}
