package cn.erp.einvoice;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.AuditService;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static cn.erp.einvoice.EInvoiceConfig.requireFull;

@Service
class EInvoiceInbound {
    private static final int SYNC_DAYS=30;
    private final EInvoiceConfig config;
    private final EInvoiceProvider provider;
    private final AuditService audit;
    private final TransactionTemplate transaction;
    private final InboundInvoiceQueries queries;
    private final InboundInvoiceMatching matching;
    private final InboundInvoiceImport importing;

    EInvoiceInbound(EInvoiceConfig config,EInvoiceProvider provider,AuditService audit,
                    PlatformTransactionManager manager,ManagedJobs jobs,
                    InboundInvoiceQueries queries,InboundInvoiceMatching matching,InboundInvoiceImport importing) {
        this.config=config;
        this.provider=provider;
        this.audit=audit;
        this.transaction=new TransactionTemplate(manager);
        this.queries=queries;this.matching=matching;this.importing=importing;
        jobs.register("einv.inbound",this::syncTenant);
    }

    record Range(@NotNull(message="请选择开始日期") LocalDate from,@NotNull(message="请选择结束日期") LocalDate to) {}
    record Match(@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号无效") Long version,
                 @Positive(message="应付编号无效") Long payableId) {}

    List<Map<String,Object>> list(CompanyAccess actor,Long cursor,int limit) {
        return queries.list(actor,cursor,limit,null,null,null);
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
        var invoice=queries.head(actor,id,false);
        if(!"normal".equals(invoice.get("status")))return List.of();
        Object items=matching.candidates(actor,id,null,50).get("items");
        if(!(items instanceof List<?> values))throw new IllegalStateException("候选应付结果格式无效");
        return values.stream().map(value->{
            if(!(value instanceof Map<?,?> fields))throw new IllegalStateException("候选应付结果格式无效");
            var result=new java.util.LinkedHashMap<String,Object>();fields.forEach((key,field)->result.put(key.toString(),field));return (Map<String,Object>)result;
        }).toList();
    }

    Map<String,Object> match(CompanyAccess actor,long id,Match input,String key) {
        return matching.legacy(actor,id,input.version(),input.payableId(),key,List.of(id,input));
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
        if(rows==null||rows.size()>2000||rows.stream().anyMatch(row->!valid(row,range))||rows.stream().map(EInvoiceProvider.Inbound::invoiceNo).distinct().count()!=rows.size())
            throw new ApiException(503,"EINV_PROVIDER_BAD_RESPONSE","服务商返回的进项发票数据无效或超出单次同步上限");
        transaction.executeWithoutResult(ignored->{
            importing.save(actor,rows);
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
