package cn.erp.einvoice;

import cn.erp.documents.ObjectCompensation;
import cn.erp.documents.ObjectFiles;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import cn.erp.shared.ManagedJobs;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static cn.erp.einvoice.EInvoiceSources.number;

@Component
class EInvoiceFiles {
    static final List<String> FORMATS=List.of("pdf","ofd","xml");
    private static final Map<String,String> TYPES=Map.of("pdf","application/pdf","ofd","application/ofd","xml","application/xml");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final EInvoiceService requests;
    private final EInvoiceConfig config;
    private final EInvoiceProvider provider;
    private final ObjectFiles objects;
    private final ObjectCompensation compensation;

    EInvoiceFiles(JdbcTemplate jdbc,PlatformTransactionManager manager,EInvoiceService requests,EInvoiceConfig config,
                  EInvoiceProvider provider,ObjectFiles objects,ObjectCompensation compensation,ManagedJobs jobs) {
        this.jdbc=jdbc;
        this.transaction=new TransactionTemplate(manager);
        this.requests=requests;
        this.config=config;
        this.provider=provider;
        this.objects=objects;
        this.compensation=compensation;
        jobs.register("einv.files",this::runOne);
    }

    record Download(String name,byte[] content) {}

    Download get(CompanyAccess actor,long request,String variant,String format) {
        if(!List.of("issue","red").contains(variant)||!FORMATS.contains(format))throw EInvoiceSources.missing();
        requests.scoped(actor,request,false);
        var row=jdbc.queryForList("""
                SELECT a.file_name,a.storage_key FROM einv_file f JOIN attachment a
                ON a.tenant_id=f.tenant_id AND a.company_id=f.company_id AND a.id=f.attachment_id
                WHERE f.tenant_id=? AND f.company_id=? AND f.request_id=? AND f.variant=? AND f.file_format=? AND a.status='active'
                """,actor.tenantId(),actor.companyId(),request,variant,format).stream().findFirst().orElseThrow(EInvoiceSources::missing);
        return new Download(row.get("file_name").toString(),objects.read(row.get("storage_key").toString()));
    }

    boolean runOne(long tenant) {
        if(!provider.available())return false;
        var claim=transaction.execute(ignored->claim(tenant));
        if(claim==null)return false;
        try {
            byte[] bytes=provider.download(config.credentials(tenant,claim.company()),claim.invoice(),claim.format());
            if(bytes==null)remove(claim);
            else store(claim,bytes);
        } catch(ApiException failed) {
            jdbc.update("""
                    UPDATE einv_file_task SET attempts=LEAST(attempts+1,30),next_attempt_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(3)),
                      lease_owner=NULL,lease_until=NULL
                    WHERE tenant_id=? AND company_id=? AND request_id=? AND variant=? AND file_format=? AND lease_owner=?
                    """,delayArgs(claim));
        }
        return true;
    }

    private Object[] delayArgs(Claim claim) {
        return new Object[]{EInvoiceWorker.delay(claim.attempts()),claim.tenant(),claim.company(),claim.request(),
                claim.variant(),claim.format(),claim.lease()};
    }

    private Claim claim(long tenant) {
        var rows=jdbc.queryForList("""
                SELECT f.*,e.invoice_no,e.red_invoice_no,e.confirmed_by FROM einv_file_task f JOIN einv_issue_request e
                ON e.tenant_id=f.tenant_id AND e.company_id=f.company_id AND e.id=f.request_id
                JOIN einv_provider_config p ON p.tenant_id=f.tenant_id AND p.company_id=f.company_id AND p.enabled=1
                JOIN company c ON c.tenant_id=f.tenant_id AND c.id=f.company_id AND c.status='active'
                JOIN tenant t ON t.id=f.tenant_id AND t.status='active'
                WHERE f.tenant_id=? AND f.next_attempt_at<=UTC_TIMESTAMP(3)
                  AND (f.lease_until IS NULL OR f.lease_until<UTC_TIMESTAMP(3))
                ORDER BY f.next_attempt_at,f.request_id,f.variant,f.file_format LIMIT 1 FOR UPDATE OF f SKIP LOCKED
                """,tenant);
        if(rows.isEmpty())return null;
        var row=rows.getFirst();
        long company=number(row,"company_id"),request=number(row,"request_id");
        String variant=(String)row.get("variant"),format=(String)row.get("file_format"),lease=UUID.randomUUID().toString();
        jdbc.update("""
                UPDATE einv_file_task SET lease_owner=?,lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 45 SECOND)
                WHERE tenant_id=? AND company_id=? AND request_id=? AND variant=? AND file_format=?
                """,lease,tenant,company,request,variant,format);
        String invoice=(String)row.get(variant.equals("red")?"red_invoice_no":"invoice_no");
        return new Claim(tenant,company,request,number(row,"confirmed_by"),variant,format,invoice,lease,((Number)row.get("attempts")).intValue());
    }

    private void store(Claim claim,byte[] bytes) {
        validate(claim.format(),bytes);
        String key=claim.tenant()+"/"+claim.company()+"/einvoice_"+UUID.randomUUID();
        compensation.stage(key);
        boolean linked=false;
        try {
            objects.put(key,bytes,TYPES.get(claim.format()));
            linked=Boolean.TRUE.equals(transaction.execute(ignored->associate(claim,bytes,key)));
        } finally {
            if(!linked)compensation.discard(key);
        }
    }

    private boolean associate(Claim claim,byte[] bytes,String key) {
        if(remove(claim)!=1)return false;
        long attachment=Ids.next();
        jdbc.update("""
                INSERT INTO attachment(id,tenant_id,company_id,owner_type,owner_id,storage_key,file_name,content_type,size_bytes,sha256,created_by)
                VALUES(?,?,?,'einv_issue_request',?,?,?,?,?,?,?)
                """,attachment,claim.tenant(),claim.company(),claim.request(),key,claim.invoice()+"."+claim.format(),TYPES.get(claim.format()),
                bytes.length,digest(bytes),claim.user());
        jdbc.update("""
                INSERT INTO einv_file(tenant_id,company_id,request_id,variant,file_format,attachment_id) VALUES(?,?,?,?,?,?)
                """,claim.tenant(),claim.company(),claim.request(),claim.variant(),claim.format(),attachment);
        compensation.associated(key);
        return true;
    }

    private int remove(Claim claim) {
        return jdbc.update("DELETE FROM einv_file_task WHERE tenant_id=? AND company_id=? AND request_id=? AND variant=? AND file_format=? AND lease_owner=?",
                claim.tenant(),claim.company(),claim.request(),claim.variant(),claim.format(),claim.lease());
    }

    private void validate(String format,byte[] bytes) {
        String start=new String(bytes,0,Math.min(bytes.length,8),StandardCharsets.US_ASCII);
        boolean valid=switch(format) {
            case "pdf" -> start.startsWith("%PDF-")&&new String(bytes,Math.max(0,bytes.length-1024),Math.min(1024,bytes.length),
                    StandardCharsets.US_ASCII).contains("%%EOF");
            case "ofd" -> start.startsWith("PK\u0003\u0004");
            case "xml" -> validXml(bytes);
            default -> false;
        };
        if(!valid)throw new ApiException(503,"EINV_INVALID_FILE","服务商返回的版式文件无效");
    }

    private boolean validXml(byte[] bytes) {
        var factory=XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES,false);
        try {
            var xml=factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
            try {
                boolean root=false;
                while(xml.hasNext()) {
                    int event=xml.next();
                    if(event==XMLStreamConstants.DTD)return false;
                    if(!root&&event==XMLStreamConstants.START_ELEMENT) {
                        if(xml.getLocalName().equalsIgnoreCase("html"))return false;
                        root=true;
                    }
                }
                return root;
            } finally {xml.close();}
        } catch(XMLStreamException invalid) {return false;}
    }

    private String digest(byte[] bytes) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException e) {throw new IllegalStateException("运行环境缺少摘要算法",e);}
    }
    private record Claim(long tenant,long company,long request,long user,String variant,String format,String invoice,String lease,int attempts) {}
}
