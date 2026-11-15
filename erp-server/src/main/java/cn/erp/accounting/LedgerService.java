package cn.erp.accounting;

import cn.erp.shared.ActorScope;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 业务用例在同一本地事务内调用，账本和资金余额同时提交。 */
@Service
@Transactional
public class LedgerService {
    private final JdbcTemplate jdbc;
    private final cn.erp.jobs.OutboxService outbox;

    public LedgerService(JdbcTemplate jdbc,cn.erp.jobs.OutboxService outbox) {this.jdbc=jdbc;this.outbox=outbox;}

    public record Line(String accountCode,String direction,BigDecimal amount,Long partnerId,
                       Long cashAccountId,Long warehouseId,Long skuId,Long sourceLineId,String memo) {
        public Line {
            amount=AccountingAmounts.money(amount);
            if (accountCode==null || accountCode.length()>32 || direction==null || !Set.of("D","C").contains(direction)) {
                throw AccountingAmounts.invalid("账本账户或借贷方向无效");
            }
            if (memo!=null && memo.length()>200) {throw AccountingAmounts.invalid("分录说明最多200字");}
        }
    }

    public record Posting(String sourceType,long sourceId,int revision,long periodId,
                          LocalDate businessDate,List<Line> lines) {
        public Posting {
            if(sourceType==null || !sourceType.matches("[a-z][a-z0-9_]{0,31}") || sourceId<=0 || revision<0 || periodId<=0 || businessDate==null || lines==null || lines.size()<2 || lines.size()>1000 || lines.stream().anyMatch(Objects::isNull)) {
                throw AccountingAmounts.invalid("凭证来源、期间或分录有误");
            }
            lines=List.copyOf(lines);
        }
    }

    /** 获取期间共享锁，调用事务完成前关账无法越过该锁。 */
    public void lockOpenPeriod(ActorScope scope,long periodId,LocalDate date) {
        if(scope.tenantId()<=0 || scope.companyId()<=0 || date==null || date.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))) {
            throw AccountingAmounts.invalid("业务日期或公司上下文无效");
        }
        var periods=jdbc.queryForList("SELECT status,start_date,end_date FROM accounting_period WHERE tenant_id=? AND company_id=? AND id=? FOR SHARE",scope.tenantId(),scope.companyId(),periodId);
        if(periods.isEmpty()) {throw AccountingAmounts.missing();}
        var period=periods.getFirst();
        if(!"open".equals(period.get("status"))) {throw new ApiException(422,"PERIOD_CLOSED","会计期间已关闭");}
        LocalDate start=((java.sql.Date)period.get("start_date")).toLocalDate();
        LocalDate end=((java.sql.Date)period.get("end_date")).toLocalDate();
        if(date.isBefore(start) || date.isAfter(end)) {throw AccountingAmounts.invalid("业务日期不属于所选期间");}
    }

    public long post(ActorScope scope,Posting posting) {return postInternal(scope,posting,"post",null);}

    public long reverse(ActorScope scope,long originalJournalId,String sourceType,long sourceId,int revision,
                        long periodId,LocalDate date) {
        lockOpenPeriod(scope,periodId,date);
        var original=jdbc.queryForList("SELECT id FROM journal WHERE tenant_id=? AND company_id=? AND id=? AND posting_action='post' FOR UPDATE",scope.tenantId(),scope.companyId(),originalJournalId);
        if(original.isEmpty()) {throw AccountingAmounts.missing();}
        var reversals=jdbc.queryForList("SELECT source_type,source_id,source_revision FROM journal WHERE tenant_id=? AND company_id=? AND reverses_journal_id=?",scope.tenantId(),scope.companyId(),originalJournalId);
        if(!reversals.isEmpty()) {
            var prior=reversals.getFirst();
            if(!sourceType.equals(prior.get("source_type")) || ((Number)prior.get("source_id")).longValue()!=sourceId || ((Number)prior.get("source_revision")).intValue()!=revision) {throw AccountingAmounts.conflict("原凭证已冲销");}
        }
        var lines=readLines(scope,originalJournalId).stream().map(line -> new Line(line.accountCode(),line.direction().equals("D")?"C":"D",line.amount(),line.partnerId(),line.cashAccountId(),line.warehouseId(),line.skuId(),line.sourceLineId(),line.memo())).toList();
        return postInternal(scope,new Posting(sourceType,sourceId,revision,periodId,date,lines),"reverse",originalJournalId);
    }

    private long postInternal(ActorScope scope,Posting posting,String action,Long reverses) {
        BigDecimal debit=sum(posting.lines(),"D"),credit=sum(posting.lines(),"C");
        if(debit.signum()<=0 || debit.compareTo(credit)!=0 || (long)debit.precision()-debit.scale()>22) {
            throw AccountingAmounts.invalid("分录借贷必须相等且总金额在允许范围内");
        }
        var existing=findSource(scope,posting,action);
        if(!existing.isEmpty()) {return verifyReplay(scope,posting,action,reverses,existing.getFirst());}
        lockOpenPeriod(scope,posting.periodId(),posting.businessDate());
        for(Line line:posting.lines()) {validateDimensions(scope,line);}
        long id=Ids.next();
        jdbc.update("""
                INSERT INTO journal(id,tenant_id,company_id,period_id,business_date,source_type,source_id,
                  posting_action,source_revision,total_debit,total_credit,reverses_journal_id,posted_at,created_by)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3),?) ON DUPLICATE KEY UPDATE id=id
                """,id,scope.tenantId(),scope.companyId(),posting.periodId(),posting.businessDate(),posting.sourceType(),posting.sourceId(),action,posting.revision(),debit,credit,reverses,scope.userId());
        var claimed=jdbc.queryForMap("SELECT * FROM journal WHERE tenant_id=? AND company_id=? AND source_type=? AND source_id=? AND posting_action=? AND source_revision=? FOR UPDATE",
                scope.tenantId(),scope.companyId(),posting.sourceType(),posting.sourceId(),action,posting.revision());
        if(((Number)claimed.get("id")).longValue()!=id) {return verifyReplay(scope,posting,action,reverses,claimed);}
        int number=0;
        for(Line line:posting.lines()) {
            jdbc.update("""
                    INSERT INTO journal_line(id,tenant_id,company_id,journal_id,line_no,account_code,direction,
                      amount,partner_id,cash_account_id,warehouse_id,sku_id,source_line_id,memo)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,Ids.next(),scope.tenantId(),scope.companyId(),id,++number,line.accountCode(),line.direction(),line.amount(),line.partnerId(),line.cashAccountId(),line.warehouseId(),line.skuId(),line.sourceLineId(),line.memo());
        }
        applyCash(scope,posting.lines());
        outbox.publish(scope.tenantId(),"journal",id,0,"journal.posted",Map.of("journalId",Long.toString(id)));
        return id;
    }

    private List<Map<String,Object>> findSource(ActorScope scope,Posting posting,String action) {
        return jdbc.queryForList("SELECT * FROM journal WHERE tenant_id=? AND company_id=? AND source_type=? AND source_id=? AND posting_action=? AND source_revision=?",
                scope.tenantId(),scope.companyId(),posting.sourceType(),posting.sourceId(),action,posting.revision());
    }

    private long verifyReplay(ActorScope scope,Posting posting,String action,Long reverses,Map<String,Object> row) {
        long id=((Number)row.get("id")).longValue();
        if(((Number)row.get("period_id")).longValue()!=posting.periodId() || !((java.sql.Date)row.get("business_date")).toLocalDate().equals(posting.businessDate()) || !Objects.equals(nullableLong(row.get("reverses_journal_id")),reverses) || !readLines(scope,id).equals(posting.lines())) {
            throw new ApiException(409,"IDEMPOTENCY_KEY_REUSED","同一来源过账的内容不一致");
        }
        return id;
    }

    private List<Line> readLines(ActorScope scope,long journalId) {
        return jdbc.query("SELECT * FROM journal_line WHERE tenant_id=? AND company_id=? AND journal_id=? ORDER BY line_no FOR SHARE",
                (r,n)->new Line(r.getString("account_code"),r.getString("direction"),r.getBigDecimal("amount"),nullableLong(r.getObject("partner_id")),nullableLong(r.getObject("cash_account_id")),nullableLong(r.getObject("warehouse_id")),nullableLong(r.getObject("sku_id")),nullableLong(r.getObject("source_line_id")),r.getString("memo")),scope.tenantId(),scope.companyId(),journalId);
    }

    private void validateDimensions(ActorScope scope,Line line) {
        var accounts=jdbc.queryForList("SELECT needs_partner,needs_cash_account,needs_warehouse FROM ledger_account WHERE code=?",line.accountCode());
        if(accounts.isEmpty()) {throw AccountingAmounts.invalid("账本账户不存在");}
        var account=accounts.getFirst();
        if(flag(account.get("needs_partner")) && line.partnerId()==null || flag(account.get("needs_cash_account")) && line.cashAccountId()==null || flag(account.get("needs_warehouse")) && line.warehouseId()==null) {throw AccountingAmounts.invalid("账本分录缺少必要的业务维度");}
        if(line.partnerId()!=null) {exists("SELECT COUNT(*) FROM business_partner WHERE tenant_id=? AND id=?",scope.tenantId(),line.partnerId());}
        if(line.cashAccountId()!=null) {exists("SELECT COUNT(*) FROM cash_account WHERE tenant_id=? AND company_id=? AND id=? AND currency='CNY'",scope.tenantId(),scope.companyId(),line.cashAccountId());}
        if(line.warehouseId()!=null) {exists("SELECT COUNT(*) FROM warehouse WHERE tenant_id=? AND company_id=? AND id=?",scope.tenantId(),scope.companyId(),line.warehouseId());}
        if(line.skuId()!=null) {exists("SELECT COUNT(*) FROM sku WHERE tenant_id=? AND id=?",scope.tenantId(),line.skuId());}
    }

    private void applyCash(ActorScope scope,List<Line> lines) {
        var changes=new TreeMap<Long,BigDecimal>();
        lines.stream().filter(line->line.accountCode().equals("FUNDS")).forEach(line -> changes.merge(line.cashAccountId(),line.direction().equals("D")?line.amount():line.amount().negate(),BigDecimal::add));
        for(var change:changes.entrySet()) {
            int updated=jdbc.update("""
                    UPDATE cash_account_balance SET balance=balance+?,version=version+1
                    WHERE tenant_id=? AND company_id=? AND cash_account_id=? AND balance+?>=0
                    """,change.getValue(),scope.tenantId(),scope.companyId(),change.getKey(),change.getValue());
            if(updated!=1) {throw new ApiException(422,"CASH_BALANCE_INSUFFICIENT","资金账户余额不足或余额行不存在");}
        }
    }

    private void exists(String sql,Object... args) {if(jdbc.queryForObject(sql,Long.class,args)==0) {throw AccountingAmounts.missing();}}
    private static boolean flag(Object value) {return value instanceof Boolean bool?bool:((Number)value).intValue()!=0;}
    private static Long nullableLong(Object value) {return value==null?null:((Number)value).longValue();}
    private static BigDecimal sum(List<Line> lines,String direction) {return lines.stream().filter(line->line.direction().equals(direction)).map(Line::amount).reduce(BigDecimal.ZERO,BigDecimal::add);}
}
