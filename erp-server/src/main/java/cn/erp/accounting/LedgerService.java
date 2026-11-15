package cn.erp.accounting;

import cn.erp.shared.ActorScope;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
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
        // 独占锁放在可更新的公司协调行，凭证本身保持只追加。
        jdbc.update("""
                INSERT INTO ledger_projection_lock(tenant_id,company_id) VALUES(?,?)
                ON DUPLICATE KEY UPDATE company_id=company_id
                """,scope.tenantId(),scope.companyId());
        var original=jdbc.queryForList("SELECT id FROM journal WHERE tenant_id=? AND company_id=? AND id=? AND posting_action='post' FOR SHARE",scope.tenantId(),scope.companyId(),originalJournalId);
        if(original.isEmpty()) {throw AccountingAmounts.missing();}
        var reversals=jdbc.queryForList("SELECT source_type,source_id,source_revision FROM journal WHERE tenant_id=? AND company_id=? AND reverses_journal_id=? FOR SHARE",scope.tenantId(),scope.companyId(),originalJournalId);
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
        var existing=findSource(scope,posting,action,false);
        if(!existing.isEmpty()) {return verifyReplay(scope,posting,reverses,existing.getFirst());}
        lockOpenPeriod(scope,posting.periodId(),posting.businessDate());
        validateDimensions(scope,posting.lines());
        long id=Ids.next();
        try {
            jdbc.update("""
                    INSERT INTO journal(id,tenant_id,company_id,period_id,business_date,source_type,source_id,
                      posting_action,source_revision,total_debit,total_credit,reverses_journal_id,posted_at,created_by)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3),?)
                    """,id,scope.tenantId(),scope.companyId(),posting.periodId(),posting.businessDate(),posting.sourceType(),posting.sourceId(),action,posting.revision(),debit,credit,reverses,scope.userId());
        } catch(DuplicateKeyException conflict) {
            // 凭证只追加。共享锁读取最新已提交凭证，避免沿用本事务的旧快照。
            var claimed=findSource(scope,posting,action,true);
            if(claimed.isEmpty()) {throw conflict;}
            return verifyReplay(scope,posting,reverses,claimed.getFirst());
        }
        int number=0;var inserts=new ArrayList<Object[]>();
        for(Line line:posting.lines()) {
            inserts.add(new Object[]{Ids.next(),scope.tenantId(),scope.companyId(),id,++number,line.accountCode(),line.direction(),line.amount(),line.partnerId(),line.cashAccountId(),line.warehouseId(),line.skuId(),line.sourceLineId(),line.memo()});
        }
        jdbc.batchUpdate("""
                    INSERT INTO journal_line(id,tenant_id,company_id,journal_id,line_no,account_code,direction,
                      amount,partner_id,cash_account_id,warehouse_id,sku_id,source_line_id,memo)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,inserts);
        applyCash(scope,posting.lines());
        outbox.publish(scope.tenantId(),"journal",id,0,"journal.posted",Map.of("journalId",Long.toString(id)));
        return id;
    }

    private List<Map<String,Object>> findSource(ActorScope scope,Posting posting,String action,boolean current) {
        return jdbc.queryForList("SELECT * FROM journal WHERE tenant_id=? AND company_id=? AND source_type=? AND source_id=? AND posting_action=? AND source_revision=?"
                        +(current?" FOR SHARE":""),
                scope.tenantId(),scope.companyId(),posting.sourceType(),posting.sourceId(),action,posting.revision());
    }

    private long verifyReplay(ActorScope scope,Posting posting,Long reverses,Map<String,Object> row) {
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

    private void validateDimensions(ActorScope scope,List<Line> lines) {
        var accounts=new HashMap<String,Map<String,Object>>();
        for(var account:jdbc.queryForList("SELECT code,needs_partner,needs_cash_account,needs_warehouse FROM ledger_account")) {accounts.put(account.get("code").toString(),account);}
        var partners=new HashSet<Long>();var cash=new HashSet<Long>();var warehouses=new HashSet<Long>();var skus=new HashSet<Long>();
        for(var line:lines) {
        var account=accounts.get(line.accountCode());
        if(account==null) {throw AccountingAmounts.invalid("账本账户不存在");}
        if(flag(account.get("needs_partner")) && line.partnerId()==null || flag(account.get("needs_cash_account")) && line.cashAccountId()==null || flag(account.get("needs_warehouse")) && line.warehouseId()==null) {throw AccountingAmounts.invalid("账本分录缺少必要的业务维度");}
        if(line.partnerId()!=null) {partners.add(line.partnerId());}
        if(line.cashAccountId()!=null) {cash.add(line.cashAccountId());}
        if(line.warehouseId()!=null) {warehouses.add(line.warehouseId());}
        if(line.skuId()!=null) {skus.add(line.skuId());}
        }
        dimensions(scope,"business_partner",partners,false,"");dimensions(scope,"cash_account",cash,true," AND currency='CNY'");
        dimensions(scope,"warehouse",warehouses,true,"");dimensions(scope,"sku",skus,false,"");
    }

    private void dimensions(ActorScope scope,String table,Set<Long> ids,boolean company,String extra) {
        if(ids.isEmpty()) {return;}
        var args=new ArrayList<Object>();args.add(scope.tenantId());if(company) {args.add(scope.companyId());}args.addAll(ids);
        String filter="tenant_id=?"+(company?" AND company_id=?":"")+" AND id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")"+extra;
        if(jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+filter,Long.class,args.toArray())!=ids.size()) {throw AccountingAmounts.missing();}
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
