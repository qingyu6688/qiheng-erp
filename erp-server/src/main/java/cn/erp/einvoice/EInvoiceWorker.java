package cn.erp.einvoice;

import cn.erp.identity.CompanyAccess;
import cn.erp.invoicing.InvoiceService;
import cn.erp.shared.ApiException;
import cn.erp.shared.AuditService;
import cn.erp.shared.ManagedJobs;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static cn.erp.einvoice.EInvoiceSources.decimal;
import static cn.erp.einvoice.EInvoiceSources.number;

@Component
class EInvoiceWorker {
    private static final Logger LOG=LoggerFactory.getLogger(EInvoiceWorker.class);
    private static final String PENDING="服务商已接收，等待开票结果";
    private static final String TEMPORARY="服务商结果待确认，系统将继续查询";
    private static final String REJECTED="服务商确认拒绝当前请求，请核对资料";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final EInvoiceService requests;
    private final EInvoiceConfig config;
    private final EInvoiceProvider provider;
    private final InvoiceService invoices;
    private final AuditService audit;
    private final cn.erp.shared.SalesInvoiceLock invoiceLock;

    EInvoiceWorker(JdbcTemplate jdbc,PlatformTransactionManager manager,EInvoiceService requests,EInvoiceConfig config,
                   EInvoiceProvider provider,InvoiceService invoices,AuditService audit,ManagedJobs jobs,cn.erp.shared.SalesInvoiceLock invoiceLock) {
        this.jdbc=jdbc;
        this.transaction=new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.requests=requests;
        this.config=config;
        this.provider=provider;
        this.invoices=invoices;
        this.audit=audit;
        this.invoiceLock=invoiceLock;
        jobs.register("einv.issue",this::runOne);
    }

    boolean runOne(long tenant) {
        if(!provider.available())return false;
        var claim=transaction.execute(ignored->claim(tenant));
        if(claim==null)return false;
        try {
            var result=send(claim);
            transaction.executeWithoutResult(ignored->finish(claim,result));
        } catch(ApiException problem) {
            defer(claim,TEMPORARY);
        } catch(RuntimeException localFailure) {
            defer(claim,"开票结果写回待重试，请联系系统管理员核对");
            LOG.warn("数电发票结果写回失败，租户 {}，公司 {}，申请 {}，类型 {}",
                    claim.tenant(),claim.company(),claim.id(),localFailure.getClass().getSimpleName());
        }
        return true;
    }

    private Claim claim(long tenant) {
        var rows=jdbc.queryForList("""
                SELECT e.* FROM einv_issue_request e
                JOIN einv_provider_config p ON p.tenant_id=e.tenant_id AND p.company_id=e.company_id AND p.enabled=1
                JOIN company c ON c.tenant_id=e.tenant_id AND c.id=e.company_id AND c.status='active'
                JOIN tenant t ON t.id=e.tenant_id AND t.status='active'
                WHERE e.tenant_id=? AND e.status IN ('submitted','red_submitted') AND e.next_attempt_at<=UTC_TIMESTAMP(3)
                AND (e.lease_until IS NULL OR e.lease_until<UTC_TIMESTAMP(3))
                ORDER BY e.next_attempt_at,e.id LIMIT 1 FOR UPDATE OF e SKIP LOCKED
                """,tenant);
        if(rows.isEmpty())return null;
        var row=rows.getFirst();
        long id=number(row,"id"),company=number(row,"company_id");
        String lease=UUID.randomUUID().toString();
        jdbc.update("""
                UPDATE einv_issue_request SET lease_owner=?,lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 45 SECOND)
                WHERE tenant_id=? AND company_id=? AND id=?
                """,lease,tenant,company,id);
        return new Claim(tenant,company,id,number(row,"confirmed_by"),number(row,"invoice_registration_id"),
                "red_submitted".equals(row.get("status")),number(row,"version"),((Number)row.get("attempts")).intValue(),lease,row);
    }

    private EInvoiceProvider.Result send(Claim claim) {
        var credentials=config.credentials(claim.tenant(),claim.company());
        String key=claim.red()?"red-"+claim.id()+"-"+claim.version():"issue-"+claim.id();
        var queried=provider.query(credentials,key);
        if(queried.status()!=EInvoiceProvider.Outcome.NOT_FOUND)return queried;
        if(claim.red())return provider.redIssue(credentials,key,
                new EInvoiceProvider.Red(claim.row().get("invoice_no").toString(),claim.row().get("red_reason").toString()));
        var lines=requests.lines(claim.tenant(),claim.company(),claim.id()).stream().map(row->new EInvoiceProvider.Line(
                (String)row.get("goods_name"),(String)row.get("tax_category_code"),(String)row.get("spec"),
                (String)row.get("unit_name"),decimal(row,"quantity"),decimal(row,"untaxed_amount"),decimal(row,"tax_amount"),
                decimal(row,"tax_rate"))).toList();
        return provider.issue(credentials,key,new EInvoiceProvider.Issue((String)claim.row().get("invoice_kind"),
                (String)claim.row().get("buyer_name"),(String)claim.row().get("buyer_tax_no"),decimal(claim.row(),"amount"),
                decimal(claim.row(),"tax_amount"),lines));
    }

    private void finish(Claim claim,EInvoiceProvider.Result result) {
        invoiceLock.lock(claim.actor());
        var lock=jdbc.queryForList("""
                SELECT id FROM einv_issue_request WHERE tenant_id=? AND company_id=? AND id=? AND version=? AND lease_owner=? FOR UPDATE
                """,claim.tenant(),claim.company(),claim.id(),claim.version(),claim.lease());
        if(lock.isEmpty())return;
        switch(result.status()) {
            case PENDING,NOT_FOUND -> defer(claim,PENDING);
            case FAILED -> reject(claim);
            case ISSUED -> accept(claim,result);
        }
    }

    private void accept(Claim claim,EInvoiceProvider.Result result) {
        String number=result.invoiceNo();
        LocalDate date=result.issuedDate();
        if(number==null||!number.matches("[A-Za-z0-9_-]{1,64}")||number.startsWith("EINV-")||date==null
                ||date.isAfter(LocalDate.now(EInvoiceService.ZONE))||date.isBefore(LocalDate.of(2000,1,1))
                ||claim.red()&&number.equals(claim.row().get("invoice_no"))) {
            defer(claim,"服务商返回的发票号码或日期无效，待确认");
            return;
        }
        var actor=claim.actor();
        if(claim.red()) {
            invoices.voidElectronic(actor,claim.registration(),claim.id());
            terminal(claim,"red_issued","red_invoice_no=?,error=NULL",number);
        } else {
            invoices.completeElectronic(actor,claim.registration(),claim.id(),number,date);
            terminal(claim,"issued","invoice_no=?,issued_at=UTC_TIMESTAMP(3),error=NULL",number);
        }
        String variant=claim.red()?"red":"issue";
        for(String format:EInvoiceFiles.FORMATS)jdbc.update("""
                INSERT INTO einv_file_task(tenant_id,company_id,request_id,variant,file_format,next_attempt_at) VALUES(?,?,?,?,?,UTC_TIMESTAMP(3))
                """,claim.tenant(),claim.company(),claim.id(),variant,format);
        audit.record(actor,claim.red()?"einv.red_issued":"einv.issued","einv_issue_request",claim.id(),null,Map.of("invoiceNo",number));
    }

    private void reject(Claim claim) {
        if(!claim.red())invoices.voidElectronic(claim.actor(),claim.registration(),claim.id());
        terminal(claim,claim.red()?"issued":"failed","error=?",REJECTED);
        audit.record(claim.actor(),claim.red()?"einv.red_failed":"einv.failed","einv_issue_request",claim.id(),null,
                Map.of("reason",REJECTED));
    }

    private void terminal(Claim claim,String status,String assignment,String value) {
        jdbc.update("UPDATE einv_issue_request SET status=?,attempts=0,next_attempt_at=NULL,lease_owner=NULL,"+
                        "lease_until=NULL,version=version+1,"+assignment+" WHERE tenant_id=? AND company_id=? AND id=? AND lease_owner=?",
                status,value,claim.tenant(),claim.company(),claim.id(),claim.lease());
    }

    private void defer(Claim claim,String reason) {
        jdbc.update("""
                UPDATE einv_issue_request SET error=?,attempts=LEAST(attempts+1,30),
                  next_attempt_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(3)),lease_owner=NULL,lease_until=NULL
                WHERE tenant_id=? AND company_id=? AND id=? AND lease_owner=?
                """,reason,delay(claim.attempts()),claim.tenant(),claim.company(),claim.id(),claim.lease());
    }

    static int delay(int attempts) {return Math.min(900,10*(1<<Math.min(attempts,7)));}

    private record Claim(long tenant,long company,long id,long user,long registration,boolean red,long version,int attempts,
                         String lease,Map<String,Object> row) {
        CompanyAccess actor() {return new CompanyAccess(tenant,user,company,true,Set.of(),Set.of());}
    }
}
