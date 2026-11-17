package cn.erp.exporting;

import cn.erp.documents.ObjectFiles;
import cn.erp.documents.ObjectCompensation;
import cn.erp.reporting.ReportService;
import tools.jackson.databind.ObjectMapper;
import java.security.*;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="erp.worker.enabled",havingValue="true",matchIfMissing=true)
public class ExportWorker {
    private static final Logger LOG=LoggerFactory.getLogger(ExportWorker.class);
    private final ExportJobs jobs;
    private final ExportAccess access;
    private final ReportService reports;
    private final ObjectFiles files;
    private final ObjectMapper json;
    private final ObjectCompensation compensation;
    public ExportWorker(ExportJobs jobs,ExportAccess access,ReportService reports,ObjectFiles files,ObjectMapper json,ObjectCompensation compensation) {
        this.jobs=jobs;this.access=access;this.reports=reports;this.files=files;this.json=json;this.compensation=compensation;
    }
    @org.springframework.beans.factory.annotation.Autowired
    void managed(cn.erp.shared.ManagedJobs jobs) {jobs.register("export.poll",tenant->runOne(tenant));}

    private cn.erp.shared.FinancialExportPort financial;
    private cn.erp.shared.DocumentPrintExportPort printing;
    @org.springframework.beans.factory.annotation.Autowired
    void financial(cn.erp.shared.FinancialExportPort financial) {this.financial=financial;}
    @org.springframework.beans.factory.annotation.Autowired
    void printing(cn.erp.shared.DocumentPrintExportPort printing) {this.printing=printing;}

    public void poll() {
        try { runOne(); }
        catch(RuntimeException error) { LOG.warn("导出队列暂不可用，将在下轮重试"); }
    }
    public boolean runOne() {return runOne(null);}
    public boolean runOne(Long tenant) {
        var job=jobs.claim(tenant);if(job==null) { return false; }
        String key=null;
        try {
            if(job.financialType()!=null) {
                var actor=access.current(job.tenant(),job.company(),job.user());
                cn.erp.shared.FinancialExportPort.File file;
                if(cn.erp.shared.DocumentPrintExportPort.supports(job.financialType())) {
                    actor=access.intersectPrint(actor,job.initial());
                    file=printing.render(actor,job.financialQuery());
                } else {
                    financial.authorize(actor,job.financialType());
                    if(!job.initial().allWarehouses()||!job.initial().cost()||!job.initial().organization().allDepartments())throw new cn.erp.shared.ApiException(403,"FORBIDDEN","财务导出初始范围无效");
                    file=financial.render(actor,job.financialType(),job.financialQuery());
                }
                key=job.tenant()+"/"+job.company()+"/exports/"+UUID.randomUUID();compensation.stage(key);files.put(key,file.bytes(),file.contentType());
                if(!jobs.completeFile(job,access.capture(actor),key,checksum(file.bytes()),file.bytes().length,file.rowCount(),file.asOf(),file.fileName(),file.contentType()))compensation.discard(key);return true;
            }
            var actor=access.intersect(access.current(job.tenant(),job.company(),job.user()),job.initial(),job.type());
            // 查询快照的只读事务在这里结束，生成CSV及对象I/O均不占用业务事务。
            var snapshot=reports.snapshot(actor,job.type(),job.query());
            byte[] bytes=ExportCsv.writeReport(job.type(),Boolean.TRUE.equals(job.query().detail()),actor.hasPermission("cost:view"),
                    job.query().groupBy(),snapshot.items(),json);
            key=job.tenant()+"/"+job.company()+"/exports/"+UUID.randomUUID();
            compensation.stage(key);
            files.put(key,bytes,"text/csv");
            var warehouses=new java.util.HashSet<>(actor.warehouseIds());
            if(job.query().warehouseId()!=null) { warehouses.retainAll(java.util.Set.of(job.query().warehouseId())); }
            var resultScope=new ExportAccess.Scope(actor.allWarehouses(),warehouses,job.type().costBearing() && actor.hasPermission("cost:view"),actor.orgScope());
            if(!jobs.complete(job,resultScope,key,checksum(bytes),bytes.length,snapshot.items().size(),snapshot.asOf())) { compensation.discard(key); }
        } catch(RuntimeException error) {
            if(key!=null) { compensation.discard(key); }
            jobs.failed(job,error);
        }
        return true;
    }

    public void clean() {clean(null);}
    public void clean(Long tenant) {
        try { for(String key:jobs.expire(tenant)) { try { files.delete(key);jobs.removed(key); }catch(RuntimeException error) { LOG.warn("过期导出对象清理失败，将在下轮重试"); } } }
        catch(RuntimeException error) { LOG.warn("过期导出任务清理失败，将在下轮重试"); }
    }
    public static String checksum(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(NoSuchAlgorithmException error) { throw new IllegalStateException("当前运行环境不支持SHA-256",error); }
    }
}
