package cn.erp.einvoice;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiRows;
import cn.erp.shared.AuditService;
import cn.erp.shared.BusinessCommands;
import cn.erp.shared.Ids;
import cn.erp.shared.ManagedJobs;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static cn.erp.einvoice.EInvoiceConfig.requireFull;
import static cn.erp.einvoice.EInvoiceSources.missing;
import static cn.erp.einvoice.EInvoiceSources.number;

@Service
class EInvoiceInbound {
    private static final int SYNC_DAYS=30;
    private static final String PAYABLES="""
            FROM payable a JOIN goods_receipt r ON r.tenant_id=a.tenant_id AND r.company_id=a.company_id AND r.id=a.source_id
            JOIN business_partner p ON p.tenant_id=a.tenant_id AND p.id=a.supplier_id
            WHERE a.tenant_id=? AND a.company_id=? AND p.tax_no=? AND a.amount=? AND a.tax_amount=?
              AND a.source_type='goods_receipt_line' AND a.kind='charge' AND a.posting_action='post' AND r.status='posted'
              AND NOT EXISTS(SELECT 1 FROM payable reversal WHERE reversal.tenant_id=a.tenant_id AND reversal.company_id=a.company_id
                                AND reversal.reverses_payable_id=a.id)
            """;
    private final JdbcTemplate jdbc;
    private final EInvoiceConfig config;
    private final EInvoiceProvider provider;
    private final AuditService audit;
    private final BusinessCommands commands;
    private final TransactionTemplate transaction;

    EInvoiceInbound(JdbcTemplate jdbc,EInvoiceConfig config,EInvoiceProvider provider,AuditService audit,
                    BusinessCommands commands,PlatformTransactionManager manager,ManagedJobs jobs) {
        this.jdbc=jdbc;
        this.config=config;
        this.provider=provider;
        this.audit=audit;
        this.commands=commands;
        this.transaction=new TransactionTemplate(manager);
        jobs.register("einv.inbound",this::syncTenant);
    }

    record Range(@NotNull(message="请选择开始日期") LocalDate from,@NotNull(message="请选择结束日期") LocalDate to) {}
    record Match(@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号无效") Long version,
                 @Positive(message="应付编号无效") Long payableId) {}

    List<Map<String,Object>> list(CompanyAccess actor,Long cursor,int limit) {
        requireFull(actor);
        if(limit<1||limit>100||cursor!=null&&cursor<=0)throw EInvoiceConfig.invalid("分页条件无效");
        return jdbc.queryForList("""
                SELECT id,invoice_no,seller_name,seller_tax_no,invoice_date,amount,tax_amount,status,checked,payable_id,version
                FROM einv_inbound WHERE tenant_id=? AND company_id=? AND id<? ORDER BY id DESC LIMIT ?
                """,actor.tenantId(),actor.companyId(),cursor==null?Long.MAX_VALUE:cursor,limit).stream().map(ApiRows::row).toList();
    }

    Map<String,Object> sync(CompanyAccess actor,Range range) {
        requireFull(actor);
        var today=LocalDate.now(EInvoiceService.ZONE);
        long between=ChronoUnit.DAYS.between(range.from(),range.to());
        if(between<0||between>=SYNC_DAYS||range.to().isAfter(today))throw EInvoiceConfig.invalid("每次可同步截至今天的连续三十天内的进项发票");
        return Map.of("count",pull(actor,range));
    }

    List<Map<String,Object>> candidates(CompanyAccess actor,long id) {
        requireFull(actor);
        var row=row(actor,id,false);
        if(!"normal".equals(row.get("status")))return List.of();
        return jdbc.queryForList("SELECT a.id,a.source_id,r.receipt_no,a.business_date,a.amount,a.tax_amount "+PAYABLES+"""
                AND NOT EXISTS(SELECT 1 FROM einv_inbound i WHERE i.tenant_id=a.tenant_id AND i.company_id=a.company_id
                    AND i.payable_id=a.id AND i.id<>?) ORDER BY a.id DESC LIMIT 50
                """,actor.tenantId(),actor.companyId(),row.get("seller_tax_no"),row.get("amount"),row.get("tax_amount"),id)
                .stream().map(ApiRows::row).toList();
    }

    @Transactional
    @SuppressWarnings({"unchecked","rawtypes"})
    Map<String,Object> match(CompanyAccess actor,long id,Match input,String key) {
        requireFull(actor);
        row(actor,id,false);
        return commands.execute(actor,"einv.inbound_match",key,List.of(id,input),200,()->{
            var old=row(actor,id,true);
            if(number(old,"version")!=input.version())throw new ApiException(409,"VERSION_CONFLICT","进项发票已变化，请刷新后重试");
            if(input.payableId()!=null)validateMatch(actor,id,old,input.payableId());
            jdbc.update("UPDATE einv_inbound SET payable_id=?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",
                    input.payableId(),actor.tenantId(),actor.companyId(),id);
            var current=ApiRows.row(row(actor,id,false));
            audit.record(actor,"einv.inbound_match","einv_inbound",id,Map.of("linked",old.get("payable_id")!=null),current);
            return current;
        },(Class)Map.class);
    }

    private void validateMatch(CompanyAccess actor,long id,Map<String,Object> invoice,long payable) {
        if(!"normal".equals(invoice.get("status")))throw new ApiException(409,"STATE_CONFLICT","已作废或已红冲的发票不能匹配应付");
        var locked=jdbc.queryForList("SELECT a.id "+PAYABLES+" AND a.id=? FOR UPDATE OF a",
                actor.tenantId(),actor.companyId(),invoice.get("seller_tax_no"),invoice.get("amount"),invoice.get("tax_amount"),payable);
        if(locked.isEmpty())throw missing();
        if(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM einv_inbound WHERE tenant_id=? AND company_id=? AND payable_id=? AND id<>? FOR SHARE)
                """,Boolean.class,actor.tenantId(),actor.companyId(),payable,id)))
            throw new ApiException(409,"STATE_CONFLICT","该应付已关联另一张进项发票");
    }

    private Map<String,Object> row(CompanyAccess actor,long id,boolean lock) {
        return jdbc.queryForList("""
                SELECT id,invoice_no,seller_name,seller_tax_no,invoice_date,amount,tax_amount,status,checked,payable_id,version
                FROM einv_inbound WHERE tenant_id=? AND company_id=? AND id=?
                """+(lock?" FOR UPDATE":""),actor.tenantId(),actor.companyId(),id).stream().findFirst().orElseThrow(EInvoiceSources::missing);
    }

    private void syncTenant(long tenant) {
        var today=LocalDate.now(EInvoiceService.ZONE);
        boolean failed=false;
        for(long company:config.enabled(tenant)) {
            try {pull(new CompanyAccess(tenant,0,company,true,Set.of(),Set.of()),new Range(today.minusDays(SYNC_DAYS-1),today));}
            catch(ApiException unavailable) {failed=true;}
        }
        if(failed)throw new ApiException(503,"EINV_PROVIDER_UNAVAILABLE","有公司未能完成进项发票同步，可在台账重试");
    }

    private int pull(CompanyAccess actor,Range range) {
        var rows=provider.downloadInbound(config.credentials(actor.tenantId(),actor.companyId()),range.from(),range.to());
        if(rows==null||rows.size()>2000||rows.stream().anyMatch(row->!valid(row,range)))
            throw new ApiException(503,"EINV_PROVIDER_BAD_RESPONSE","服务商返回的进项发票数据无效或超出单次同步上限");
        transaction.executeWithoutResult(ignored->{
            var values=rows.stream().sorted(java.util.Comparator.comparing(EInvoiceProvider.Inbound::invoiceNo)).map(row->new Object[]{
                    Ids.next(),actor.tenantId(),actor.companyId(),row.invoiceNo(),row.sellerName(),row.sellerTaxNo(),row.invoiceDate(),row.amount(),
                    row.taxAmount(),row.status(),row.checked()}).toList();
            jdbc.batchUpdate("""
                INSERT INTO einv_inbound(id,tenant_id,company_id,invoice_no,seller_name,seller_tax_no,invoice_date,amount,tax_amount,status,checked)
                VALUES(?,?,?,?,?,?,?,?,?,?,?) AS incoming ON DUPLICATE KEY UPDATE
                version=einv_inbound.version+IF(NOT (incoming.seller_name=einv_inbound.seller_name
                    AND incoming.seller_tax_no=einv_inbound.seller_tax_no AND incoming.invoice_date=einv_inbound.invoice_date
                    AND incoming.amount=einv_inbound.amount AND incoming.tax_amount=einv_inbound.tax_amount
                    AND incoming.status=einv_inbound.status AND incoming.checked=einv_inbound.checked),1,0),
                payable_id=IF(incoming.status<>'normal' OR incoming.amount<>einv_inbound.amount OR incoming.tax_amount<>einv_inbound.tax_amount
                    OR incoming.seller_tax_no<>einv_inbound.seller_tax_no,NULL,einv_inbound.payable_id),
                seller_name=incoming.seller_name,seller_tax_no=incoming.seller_tax_no,invoice_date=incoming.invoice_date,
                amount=incoming.amount,tax_amount=incoming.tax_amount,
                status=IF(einv_inbound.status<>'normal' AND incoming.status='normal',einv_inbound.status,incoming.status),checked=incoming.checked
                """,values);
            if(!rows.isEmpty())audit.record(actor,"einv.inbound_sync","company",actor.companyId(),null,
                    Map.of("from",range.from(),"to",range.to(),"count",rows.size()));
        });
        return rows.size();
    }

    private boolean valid(EInvoiceProvider.Inbound row,Range range) {
        return row!=null&&row.invoiceNo()!=null&&row.invoiceNo().matches("[A-Za-z0-9_-]{1,64}")&&row.sellerName()!=null
                &&!row.sellerName().isBlank()&&row.sellerName().length()<=100&&row.sellerTaxNo()!=null
                &&row.sellerTaxNo().toUpperCase(Locale.ROOT).matches("[0-9A-Z]{8,32}")&&row.invoiceDate()!=null
                &&!row.invoiceDate().isBefore(range.from())&&!row.invoiceDate().isAfter(range.to())
                &&row.status()!=null&&List.of("normal","voided","red_issued").contains(row.status())&&money(row.amount())&&money(row.taxAmount())
                &&row.amount().signum()>0&&row.taxAmount().signum()>=0&&row.taxAmount().compareTo(row.amount())<=0;
    }

    private boolean money(BigDecimal amount) {
        return amount!=null&&amount.scale()<=2&&(long)amount.precision()-amount.scale()<=22;
    }
}
