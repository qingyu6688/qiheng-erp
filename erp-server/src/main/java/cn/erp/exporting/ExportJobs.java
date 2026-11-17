package cn.erp.exporting;

import cn.erp.documents.ObjectFiles;
import cn.erp.documents.ObjectCompensation;
import cn.erp.identity.CompanyAccess;
import cn.erp.reporting.*;
import cn.erp.salesfinance.FinanceSupport;
import cn.erp.shared.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExportJobs {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ExportAccess access;
    private final BusinessCommands commands;
    private final AuditService audit;
    private final ObjectFiles files;
    private final ObjectCompensation compensation;
    public ExportJobs(JdbcTemplate jdbc,ObjectMapper json,ExportAccess access,BusinessCommands commands,AuditService audit,ObjectFiles files,ObjectCompensation compensation) {
        this.jdbc=jdbc;this.json=json;this.access=access;this.commands=commands;this.audit=audit;this.files=files;this.compensation=compensation;
    }
    private FinancialExportPort financial;
    private DocumentPrintExportPort printing;
    @org.springframework.beans.factory.annotation.Autowired
    void financial(FinancialExportPort financial) {this.financial=financial;}
    @org.springframework.beans.factory.annotation.Autowired
    void printing(DocumentPrintExportPort printing) {this.printing=printing;}
    public record Input(String exportType,ReportQuery query,Map<String,Object> financeQuery) {public Input(String exportType,ReportQuery query){this(exportType,query,null);}}
    public record Claimed(long id,long tenant,long company,long user,String owner,ReportType type,ReportQuery query,ExportAccess.Scope initial,String financialType,Map<String,Object> financialQuery) {public Claimed(long id,long tenant,long company,long user,String owner,ReportType type,ReportQuery query,ExportAccess.Scope initial){this(id,tenant,company,user,owner,type,query,initial,null,null);}}
    public record Download(String fileName,byte[] bytes,String contentType) {public Download(String fileName,byte[] bytes){this(fileName,bytes,"text/csv");}}

    @Transactional
    public Map<String,Object> create(CompanyAccess actor,String key,Input input) {
        if(input==null) { throw new ApiException(400,"VALIDATION_FAILED","请填写导出条件"); }
        if(DocumentPrintExportPort.supports(input.exportType())) return createSpecial(actor,key,input,printing.freeze(actor,input.financeQuery()));
        if(FinancialExportPort.supports(input.exportType())) {
            financial.authorize(actor,input.exportType());
            return createSpecial(actor,key,input,financial.freeze(actor,input.exportType(),input.financeQuery()));
        }
        var type=ReportType.parse(input.exportType());type.authorize(actor,true);
        var query=(input.query()==null?new ReportQuery(null,null,null,null,null,null,false):input.query()).normalized();
        if(query.warehouseId()!=null && !actor.allowsWarehouse(query.warehouseId())) { throw missing(); }
        @SuppressWarnings({"unchecked","rawtypes"})
        Map<String,Object> result=commands.execute(actor,"export.create",key,new Input(type.code(),query),202,()->{
            var users=jdbc.queryForList("SELECT id FROM sys_user WHERE tenant_id=? AND id=? AND status='active' FOR UPDATE",actor.tenantId(),actor.userId());
            if(users.isEmpty()) { throw new ApiException(403,"FORBIDDEN","账号不可用"); }
            Long count=jdbc.queryForObject("SELECT COUNT(*) FROM export_job WHERE tenant_id=? AND created_by=? AND status IN ('pending','running') AND expire_at>UTC_TIMESTAMP(3)",Long.class,actor.tenantId(),actor.userId());
            if(count!=null && count>=3) { throw new ApiException(409,"EXPORT_LIMIT","同时最多运行3个导出任务，请稍后重试"); }
            long id=Ids.next();
            jdbc.update("INSERT INTO export_job(id,tenant_id,company_id,export_type,query_params,request_scope,created_by,expire_at) VALUES(?,?,?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 24 HOUR))",
                    id,actor.tenantId(),actor.companyId(),type.code(),encode(query),encode(access.capture(actor)),actor.userId());
            audit.record(actor,"export.create","export_job",id,null,Map.of("exportType",type.code()));
            return Map.<String,Object>of("taskId",Long.toString(id),"taskType","export","statusUrl","/api/v1/export-jobs/"+id);
        },(Class)Map.class);
        return result;
    }
    public Map<String,Object> list(CompanyAccess actor,Long cursor,int limit) {
        if(limit<1 || limit>200 || cursor!=null && cursor<=0) { throw new ApiException(400,"VALIDATION_FAILED","导出任务分页条件无效"); }
        var rows=jdbc.queryForList("SELECT * FROM export_job WHERE tenant_id=? AND company_id=? AND created_by=? AND id<? ORDER BY id DESC LIMIT ?",actor.tenantId(),actor.companyId(),actor.userId(),cursor==null?Long.MAX_VALUE:cursor,limit+1);
        var items=new ArrayList<Map<String,Object>>();
        for(var row:rows.stream().limit(limit).toList()) {
            try { authorize(actor,row,false);items.add(metadata(row)); }
            catch(ApiException error) { if(error.getStatus()!=403) { throw error; } }
        }
        var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("nextCursor",rows.size()>limit?rows.get(limit-1).get("id").toString():null);return result;
    }
    public Map<String,Object> get(CompanyAccess actor,long id) { var row=find(actor,id);authorize(actor,row,false);return metadata(row); }
    public void ready(CompanyAccess actor,long id) { authorize(actor,find(actor,id),true); }
    public Download download(CompanyAccess actor,long id) {
        var row=find(actor,id);authorize(actor,row,true);
        var attachments=jdbc.queryForList("SELECT file_name,storage_key,content_type FROM attachment WHERE tenant_id=? AND company_id=? AND id=? AND owner_type='export_job' AND owner_id=? AND status='active'",actor.tenantId(),actor.companyId(),row.get("attachment_id"),id);
        if(attachments.isEmpty()) { throw missing(); }
        var attachment=attachments.getFirst();
        byte[] bytes=files.read(attachment.get("storage_key").toString());
        // 文件读取之后再次获取即时权限，避免远端对象读取期间撤权仍交付文件。
        authorize(access.current(actor.tenantId(),actor.companyId(),actor.userId()),row,true);
        return new Download(attachment.get("file_name").toString(),bytes,attachment.get("content_type").toString());
    }
    private void authorize(CompanyAccess actor,Map<String,Object> row,boolean download) {
        String exportType=row.get("export_type").toString();
        boolean finance=FinancialExportPort.supports(exportType);
        boolean print=DocumentPrintExportPort.supports(exportType);
        var type=finance||print?null:ReportType.parse(exportType);
        if(print) {
            printing.authorize(actor,options(row.get("query_params")),true);
            if(row.get("result_scope")!=null) access.coversPrint(actor,decode(row.get("result_scope"),ExportAccess.Scope.class));
        }
        else if(finance) {financial.authorize(actor,exportType);financialScope(decode(row.get("request_scope"),ExportAccess.Scope.class));if(row.get("result_scope")!=null)financialScope(decode(row.get("result_scope"),ExportAccess.Scope.class));}else type.authorize(actor,true);
        if(type!=null && row.get("result_scope")!=null) { access.covers(actor,decode(row.get("result_scope"),ExportAccess.Scope.class),type); }
        if(expired(row)) { if(download) { throw new ApiException(410,"EXPORT_EXPIRED","导出文件已过期，请重新导出"); }return; }
        if(!"succeeded".equals(row.get("status")) && download) { throw new ApiException(409,"EXPORT_NOT_READY","导出文件尚未生成成功"); }
    }
    private Map<String,Object> find(CompanyAccess actor,long id) {
        var rows=jdbc.queryForList("SELECT * FROM export_job WHERE tenant_id=? AND company_id=? AND created_by=? AND id=?",actor.tenantId(),actor.companyId(),actor.userId(),id);
        if(rows.isEmpty()) { throw missing(); }return rows.getFirst();
    }
    private Map<String,Object> metadata(Map<String,Object> row) {
        var safe=new LinkedHashMap<String,Object>();
        for(String key:List.of("id","export_type","status","row_count","created_at","finished_at","expire_at","error_message","attempts","as_of")) { safe.put(key,row.get(key)); }
        if(expired(row)) { safe.put("status","expired"); }
        var result=FinanceSupport.row(safe);
        if("succeeded".equals(result.get("status"))) { result.put("downloadUrl","/api/v1/export-jobs/"+result.get("id")+"/download"); }
        return result;
    }
    private boolean expired(Map<String,Object> row) {
        Object value=row.get("expire_at");return value instanceof Timestamp timestamp?timestamp.toInstant().isBefore(Instant.now()):value instanceof java.time.LocalDateTime date && date.toInstant(java.time.ZoneOffset.UTC).isBefore(Instant.now());
    }
    @Transactional
    public Claimed claim() {return claim(null);}
    @Transactional
    public Claimed claim(Long tenant) {
        var rows=jdbc.queryForList("""
            SELECT * FROM export_job WHERE (? IS NULL OR tenant_id=?) AND expire_at>UTC_TIMESTAMP(3) AND attempts<3 AND
              (status='pending' OR status='running' AND lease_until<UTC_TIMESTAMP(3))
            ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
            """,tenant,tenant);
        if(rows.isEmpty()) { return null; }
        var row=rows.getFirst();String owner=UUID.randomUUID().toString();
        jdbc.update("UPDATE export_job SET status='running',attempts=attempts+1,lease_owner=?,lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 2 MINUTE),error_message=NULL WHERE id=?",owner,row.get("id"));
        var type=row.get("export_type").toString();
        if(FinancialExportPort.supports(type) || DocumentPrintExportPort.supports(type)) return new Claimed(number(row,"id"),number(row,"tenant_id"),number(row,"company_id"),number(row,"created_by"),owner,null,null,decode(row.get("request_scope"),ExportAccess.Scope.class),type,options(row.get("query_params")));
        return new Claimed(number(row,"id"),number(row,"tenant_id"),number(row,"company_id"),number(row,"created_by"),owner,ReportType.parse(row.get("export_type").toString()),decode(row.get("query_params"),ReportQuery.class),decode(row.get("request_scope"),ExportAccess.Scope.class));
    }
    @Transactional
    public boolean complete(Claimed job,ExportAccess.Scope scope,String storageKey,String checksum,int size,int rows,String asOf) {
        return completeFile(job,scope,storageKey,checksum,size,rows,asOf,job.type().code()+"-"+job.id()+".csv","text/csv");
    }
    @Transactional
    public boolean completeFile(Claimed job,ExportAccess.Scope scope,String storageKey,String checksum,int size,int rows,String asOf,String fileName,String contentType) {
        var current=access.current(job.tenant(),job.company(),job.user());
        boolean print=DocumentPrintExportPort.supports(job.financialType());
        if(print) {printing.authorize(current,job.financialQuery(),true);access.coversPrint(current,scope);}
        else if(job.financialType()!=null) {financial.authorize(current,job.financialType());financialScope(scope);}else access.covers(current,scope,job.type());
        var owned=jdbc.queryForList("SELECT id FROM export_job WHERE tenant_id=? AND id=? AND lease_owner=? AND status='running' AND lease_until>=UTC_TIMESTAMP(3) AND expire_at>UTC_TIMESTAMP(3) FOR UPDATE",job.tenant(),job.id(),job.owner());
        if(owned.isEmpty()) { return false; }
        long attachment=Ids.next();
        jdbc.update("INSERT INTO attachment(id,tenant_id,company_id,owner_type,owner_id,storage_key,file_name,content_type,size_bytes,sha256,created_by) VALUES(?,?,?,'export_job',?,?,?,?,?,?,?)",attachment,job.tenant(),job.company(),job.id(),storageKey,fileName,contentType,size,checksum,job.user());
        jdbc.update("UPDATE export_job SET status='succeeded',attachment_id=?,row_count=?,result_scope=?,checksum=?,as_of=?,finished_at=UTC_TIMESTAMP(3),expire_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 24 HOUR),lease_owner=NULL,lease_until=NULL WHERE tenant_id=? AND id=? AND lease_owner=?",
                attachment,rows,encode(scope),checksum,Timestamp.from(Instant.parse(asOf)),job.tenant(),job.id(),job.owner());
        if(print) printing.completed(current,job.id(),job.financialQuery());
        compensation.associated(storageKey);
        audit.record(current,"export.succeeded","export_job",job.id(),null,Map.of("rowCount",rows,"checksum",checksum));return true;
    }
    @Transactional
    public void failed(Claimed job,RuntimeException error) {
        String message=error instanceof ApiException?error.getMessage():"导出暂时失败，后台将自动重试";
        boolean permanent=error instanceof ApiException api && api.getStatus()<500;
        jdbc.update("UPDATE export_job SET status=IF(attempts>=3 OR ?,'failed','pending'),error_message=?,finished_at=IF(attempts>=3 OR ?,UTC_TIMESTAMP(3),NULL),lease_owner=NULL,lease_until=NULL WHERE tenant_id=? AND id=? AND lease_owner=? AND status='running'",permanent,message,permanent,job.tenant(),job.id(),job.owner());
    }
    @Transactional
    public List<String> expire() {return expire(null);}
    @Transactional
    public List<String> expire(Long tenant) {
        // 成品的对象删除在事务外执行；状态先过期，下载立即停止。
        var expired=jdbc.queryForList("SELECT j.id,j.attachment_id,a.storage_key FROM export_job j LEFT JOIN attachment a ON a.id=j.attachment_id WHERE (? IS NULL OR j.tenant_id=?) AND j.status<>'expired' AND j.expire_at<UTC_TIMESTAMP(3) ORDER BY j.id LIMIT 100 FOR UPDATE SKIP LOCKED",tenant,tenant);
        var keys=new ArrayList<String>();
        for(var row:expired) {
            jdbc.update("UPDATE export_job SET status='expired',lease_owner=NULL,lease_until=NULL WHERE id=?",row.get("id"));
            if(row.get("attachment_id")!=null) { jdbc.update("UPDATE attachment SET status='deleted' WHERE id=?",row.get("attachment_id")); }
        }
        // 第三次租约耗尽的崩溃任务应落为终态，不长期卡在运行中。
        jdbc.update("UPDATE export_job SET status='failed',error_message='导出任务租约耗尽，请重新提交',finished_at=UTC_TIMESTAMP(3),lease_owner=NULL,lease_until=NULL WHERE (? IS NULL OR tenant_id=?) AND status='running' AND attempts>=3 AND lease_until<UTC_TIMESTAMP(3)",tenant,tenant);
        keys.addAll(jdbc.queryForList("SELECT a.storage_key FROM export_job j JOIN attachment a ON a.id=j.attachment_id WHERE (? IS NULL OR j.tenant_id=?) AND j.status='expired' AND j.object_deleted_at IS NULL ORDER BY j.id LIMIT 100",String.class,tenant,tenant));
        return keys;
    }
    @Transactional
    public void removed(String key) { jdbc.update("UPDATE export_job j JOIN attachment a ON a.id=j.attachment_id SET j.object_deleted_at=UTC_TIMESTAMP(3) WHERE j.status='expired' AND a.storage_key=?",key); }
    @SuppressWarnings({"unchecked","rawtypes"})
    private Map<String,Object> createSpecial(CompanyAccess actor,String key,Input input,Map<String,Object> frozen) {
        return commands.execute(actor,"export.create",key,input,202,()->{
            if(jdbc.queryForList("SELECT id FROM sys_user WHERE tenant_id=? AND id=? AND status='active' FOR UPDATE",actor.tenantId(),actor.userId()).isEmpty())throw new ApiException(403,"FORBIDDEN","账号不可用");
            if(jdbc.queryForObject("SELECT COUNT(*) FROM export_job WHERE tenant_id=? AND created_by=? AND status IN('pending','running') AND expire_at>UTC_TIMESTAMP(3)",Long.class,actor.tenantId(),actor.userId())>=3)throw new ApiException(409,"EXPORT_LIMIT","同时最多运行3个导出任务，请稍后重试");
            long id=Ids.next();jdbc.update("INSERT INTO export_job(id,tenant_id,company_id,export_type,query_params,request_scope,created_by,expire_at) VALUES(?,?,?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 24 HOUR))",id,actor.tenantId(),actor.companyId(),input.exportType(),encode(frozen),encode(access.capture(actor)),actor.userId());audit.record(actor,"export.create","export_job",id,null,Map.of("exportType",input.exportType()));return Map.of("taskId",Long.toString(id),"taskType","export","statusUrl","/api/v1/export-jobs/"+id);
        },(Class)Map.class);
    }
    private Map<String,Object> options(Object value) {return json.readValue(value.toString(),new tools.jackson.core.type.TypeReference<Map<String,Object>>(){});}
    private static void financialScope(ExportAccess.Scope scope) {if(!scope.allWarehouses()||!scope.cost()||!scope.organization().allDepartments())throw new ApiException(403,"FORBIDDEN","财务导出需要可信的整家公司数据范围");}
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch(JacksonException error) { throw new IllegalStateException("导出参数无法保存",error); }
    }
    private <T>T decode(Object value,Class<T> type) {
        if(value==null) { throw new ApiException(403,"FORBIDDEN","旧导出任务没有可信数据范围，请重新导出"); }
        try { return json.readValue(value.toString(),type); }
        catch(JacksonException error) { throw new IllegalStateException("导出参数无法读取",error); }
    }
    private static long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    private static ApiException missing() { return new ApiException(404,"NOT_FOUND","导出任务或文件不存在，或无权查看"); }
}
