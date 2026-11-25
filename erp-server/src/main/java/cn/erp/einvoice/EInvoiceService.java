package cn.erp.einvoice;

import cn.erp.identity.CompanyAccess;
import cn.erp.invoicing.InvoiceService;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiRows;
import cn.erp.shared.AuditService;
import cn.erp.shared.BusinessCommands;
import cn.erp.shared.Ids;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import static cn.erp.einvoice.EInvoiceSources.*;

@Service
class EInvoiceService {
    static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final EInvoiceSources sources;
    private final EInvoiceConfig config;
    private final InvoiceService invoices;
    private final BusinessCommands commands;
    private final AuditService audit;
    private final cn.erp.shared.SalesInvoiceLock invoiceLock;

    EInvoiceService(JdbcTemplate jdbc,EInvoiceSources sources,EInvoiceConfig config,InvoiceService invoices,
                    BusinessCommands commands,AuditService audit,cn.erp.shared.SalesInvoiceLock invoiceLock) {
        this.jdbc=jdbc;this.sources=sources;this.config=config;this.invoices=invoices;this.commands=commands;this.audit=audit;
        this.invoiceLock=invoiceLock;
    }

    record Draft(@NotNull(message="请选择客户") @Positive(message="客户编号无效") Long partnerId,
                 @NotBlank(message="请选择发票类型") @Pattern(regexp="normal|special",message="发票类型无效") String invoiceKind,
                 @NotEmpty(message="请选择签收应收") @Size(max=50,message="最多选择五十笔应收") List<Long> receivableIds) {}
    record Revision(@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号无效") Long version,
                    @Size(max=200,message="红字原因最多二百字") String reason) {}

    @Transactional(isolation=Isolation.READ_COMMITTED)
    Map<String,Object> create(CompanyAccess actor,String key,Draft input) {
        var created=command(actor,"einv.create",key,input,()->{
            var selected=sources.selected(actor,input.partnerId(),input.receivableIds());
            var rows=jdbc.queryForList("SELECT name,tax_no FROM business_partner WHERE tenant_id=? AND id=? AND status='active'",actor.tenantId(),input.partnerId());
            if(rows.isEmpty())throw missing();
            var customer=rows.getFirst();
            String buyer=customer.get("name").toString(), taxNo=Objects.toString(customer.get("tax_no"),"");
            if(input.invoiceKind().equals("special")&&!taxNo.matches("[A-Z0-9]{8,32}"))
                throw EInvoiceConfig.invalid("开具数电专票需要客户的有效纳税人识别号");
            var amount=sum(selected,false);var tax=sum(selected,true);
            long id=Ids.next();
            jdbc.update("""
                INSERT INTO einv_issue_request(id,tenant_id,company_id,partner_id,invoice_kind,buyer_name,buyer_tax_no,amount,tax_amount,requested_by)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """,id,actor.tenantId(),actor.companyId(),input.partnerId(),input.invoiceKind(),buyer,taxNo,amount,tax,actor.userId());
            var values=selected.stream().map(row->new Object[]{Ids.next(),actor.tenantId(),actor.companyId(),id,row.receivable(),row.shipment(),
                    row.sku(),row.goods(),row.taxCode(),row.spec(),row.unit(),row.quantity(),row.untaxed(),row.tax(),row.rate()}).toList();
            jdbc.batchUpdate("""
                INSERT INTO einv_issue_line(id,tenant_id,company_id,request_id,receivable_id,shipment_id,sku_id,goods_name,
                  tax_category_code,spec,unit_name,quantity,untaxed_amount,tax_amount,tax_rate) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,values);
            audit.record(actor,"einv.create","einv_issue_request",id,null,Map.of("count",selected.size()));
            return action(id);
        });
        scoped(actor,Long.parseLong(created.get("id").toString()),false);
        return created;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    Map<String,Object> submit(CompanyAccess actor,long id,String key,Revision revision) {
        scoped(actor,id,false);
        return command(actor,"einv.submit",key,List.of(id,revision),()->{
            invoiceLock.lock(actor);
            var row=state(actor,id,revision.version(),"draft");
            config.credentials(actor.tenantId(),actor.companyId());
            if(number(row,"requested_by")==actor.userId())
                throw new ApiException(403,"EINV_SEPARATE_REVIEW","开票申请须由另一位财务人员确认");
            var snapshots=lines(actor.tenantId(),actor.companyId(),id);
            var selected=sources.selected(actor,number(row,"partner_id"),snapshots.stream().map(r->number(r,"receivable_id")).toList());
            validateSnapshot(snapshots,selected);
            var invoiceInput=new InvoiceService.Input("sales",number(row,"partner_id"),"EINV-"+id,LocalDate.now(ZONE),
                    row.get("amount").toString(),row.get("tax_amount").toString(),
                    selected.stream().map(src->new InvoiceService.Line(src.receivable(),src.amount().toPlainString())).toList(),"数电发票待服务商确认");
            long registration=invoices.reserveElectronic(actor,id,invoiceInput);
            jdbc.update("""
                UPDATE einv_issue_request SET invoice_registration_id=?,confirmed_by=?,status='submitted',next_attempt_at=UTC_TIMESTAMP(3),version=version+1
                WHERE tenant_id=? AND company_id=? AND id=?
                """,registration,actor.userId(),actor.tenantId(),actor.companyId(),id);
            audit.record(actor,"einv.submit","einv_issue_request",id,null,Map.of("registrationId",Long.toString(registration)));
            return action(id);
        });
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    Map<String,Object> redIssue(CompanyAccess actor,long id,String key,Revision revision) {
        scoped(actor,id,false);
        return command(actor,"einv.red_issue",key,List.of(id,revision),()->{
            invoiceLock.lock(actor);
            state(actor,id,revision.version(),"issued");
            config.credentials(actor.tenantId(),actor.companyId());
            if(revision.reason()==null||revision.reason().isBlank())throw EInvoiceConfig.invalid("请填写红字原因");
            jdbc.update("""
                UPDATE einv_issue_request SET status='red_submitted',red_reason=?,confirmed_by=?,error=NULL,attempts=0,
                  next_attempt_at=UTC_TIMESTAMP(3),version=version+1 WHERE tenant_id=? AND company_id=? AND id=?
                """,revision.reason().strip(),actor.userId(),actor.tenantId(),actor.companyId(),id);
            audit.record(actor,"einv.red_issue","einv_issue_request",id,null,Map.of("status","red_submitted"));
            return action(id);
        });
    }

    List<Map<String,Object>> list(CompanyAccess actor,Long cursor,int limit) {
        if(limit<1||limit>100||cursor!=null&&cursor<=0)throw EInvoiceConfig.invalid("分页条件无效");
        var args=new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId(),cursor==null?Long.MAX_VALUE:cursor));
        String visibility=InvoiceService.electronicSourceFilter(actor,args,false);args.add(limit);
        return jdbc.queryForList("SELECT "+columns()+" FROM einv_issue_request e WHERE e.tenant_id=? AND e.company_id=? AND e.id<?"+
                visibility+" ORDER BY e.id DESC LIMIT ?",args.toArray()).stream().map(ApiRows::row).toList();
    }

    Map<String,Object> detail(CompanyAccess actor,long id) {
        var row=ApiRows.row(scoped(actor,id,false));
        row.put("lines",lines(actor.tenantId(),actor.companyId(),id).stream().map(ApiRows::row).toList());
        row.put("files",jdbc.queryForList("SELECT variant,file_format FROM einv_file WHERE tenant_id=? AND company_id=? AND request_id=? ORDER BY variant,file_format",
                actor.tenantId(),actor.companyId(),id).stream().map(ApiRows::row).toList());
        return row;
    }

    Map<String,Object> scoped(CompanyAccess actor,long id,boolean locked) {
        var args=new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId(),id));
        String visibility=InvoiceService.electronicSourceFilter(actor,args,false);
        return jdbc.queryForList("SELECT "+columns()+" FROM einv_issue_request e WHERE e.tenant_id=? AND e.company_id=? AND e.id=?"+
                visibility+(locked?" FOR UPDATE":""),args.toArray()).stream().findFirst().orElseThrow(EInvoiceSources::missing);
    }

    List<Map<String,Object>> lines(long tenant,long company,long id) {
        return jdbc.queryForList("""
                SELECT id,receivable_id,shipment_id,sku_id,goods_name,tax_category_code,spec,unit_name,quantity,untaxed_amount,tax_amount,tax_rate
                FROM einv_issue_line WHERE tenant_id=? AND company_id=? AND request_id=? ORDER BY receivable_id
                """,tenant,company,id);
    }

    private String columns() {
        return "e.id,e.partner_id,e.invoice_registration_id,e.invoice_kind,e.buyer_name,e.buyer_tax_no,e.amount,e.tax_amount,e.status,"+
                "e.invoice_no,e.red_invoice_no,e.issued_at,e.error,e.requested_by,e.confirmed_by,e.created_at,e.version";
    }

    private Map<String,Object> state(CompanyAccess actor,long id,long version,String expected) {
        var row=scoped(actor,id,true);
        if(number(row,"version")!=version)throw new ApiException(409,"VERSION_CONFLICT","发票申请已变化，请刷新重试");
        if(!expected.equals(row.get("status")))throw new ApiException(409,"STATE_CONFLICT","申请状态不允许当前操作");
        return row;
    }

    private BigDecimal sum(List<Source> selected,boolean tax) {
        var total=selected.stream().map(source->tax?source.tax():source.amount()).reduce(BigDecimal.ZERO,BigDecimal::add);
        if(total.precision()>24)throw EInvoiceConfig.invalid("开票金额超过上限");
        return total;
    }

    private void validateSnapshot(List<Map<String,Object>> snapshots,List<Source> rows) {
        for(int i=0;i<rows.size();i++) {
            var row=rows.get(i);var saved=snapshots.get(i);
            if(row.untaxed().compareTo(decimal(saved,"untaxed_amount"))!=0||row.tax().compareTo(decimal(saved,"tax_amount"))!=0
                    ||row.quantity().compareTo(decimal(saved,"quantity"))!=0||!row.taxCode().equals(saved.get("tax_category_code")))
                throw new ApiException(409,"EINV_SOURCE_CHANGED","签收应收或税收分类已变化，请重新创建申请");
        }
    }

    private Map<String,Object> action(long id) {return Map.of("id",Long.toString(id));}

    @SuppressWarnings({"unchecked","rawtypes"})
    private Map<String,Object> command(CompanyAccess actor,String operation,String key,Object input,Supplier<Map<String,Object>> perform) {
        return commands.execute(actor,operation,key,input,200,perform,(Class)Map.class);
    }
}
