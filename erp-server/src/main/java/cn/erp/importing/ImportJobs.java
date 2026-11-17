package cn.erp.importing;

import cn.erp.documents.ObjectFiles;
import cn.erp.documents.ObjectCompensation;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.AuditService;
import cn.erp.shared.BusinessCommands;
import cn.erp.shared.Ids;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class ImportJobs {
    record Options(Long warehouseId,LocalDate businessDate) {}
    private static final String COLUMNS="id,job_type,status,total_rows,success_rows,failed_rows,checkpoint_row,created_at,started_at,finished_at,error_message,attempts,next_attempt_at";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ObjectFiles files;
    private final ObjectCompensation objects;
    private final ImportAccess access;
    private final AuditService audit;
    private final BusinessCommands commands;
    private final TransactionTemplate tx;
    private final cn.erp.customfield.CustomFields customFields;
    ImportJobs(JdbcTemplate jdbc,ObjectMapper json,ObjectFiles files,ObjectCompensation objects,ImportAccess access,AuditService audit,
            BusinessCommands commands,PlatformTransactionManager manager,cn.erp.customfield.CustomFields customFields) {
        this.jdbc=jdbc;this.json=json;this.files=files;this.objects=objects;this.access=access;this.audit=audit;this.commands=commands;this.tx=new TransactionTemplate(manager);this.customFields=customFields;
    }
    byte[] template(CompanyAccess scope,String type) {return ImportCsv.template(type,customFields.importKeys(scope.tenantId(),type));}
    public Map<String,Object> create(CompanyAccess scope,String key,String type,Long warehouse,LocalDate date,String filename,byte[] bytes) {
        ImportCsv.headers(type);
        if(filename==null||!filename.toLowerCase(Locale.ROOT).endsWith(".csv"))throw ImportCsv.invalid("请选择 .csv 格式文件");
        var rows=ImportCsv.parse(type,bytes);
        boolean cost=type.equals("opening_stock")||(type.equals("sku")&&rows.stream().anyMatch(r->r.cells().size()>5&&!r.cells().get(5).isBlank()));
        ImportAccess.permissions(scope,type,cost);
        if(!customFields.importKeys(scope.tenantId(),type).containsAll(rows.getFirst().keys()))
            throw ImportCsv.invalid("文件含当前未启用或未授权导入的字段，请重新下载模板");
        var snapshot=access.snapshot(scope);
        var current=access.current(scope.tenantId(),scope.companyId(),scope.userId(),snapshot,type,cost);
        if(type.equals("opening_stock")) {
            if(warehouse==null||date==null)throw ImportCsv.invalid("库存期初导入须选择仓库和业务日期");
            if(!current.allowsWarehouse(warehouse))throw ImportAccess.forbidden();
            if(date.isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))))throw ImportCsv.invalid("业务日期不能晚于今天");
        } else if(warehouse!=null||date!=null)throw ImportCsv.invalid("此导入类型不接受仓库和业务日期");
        var options=new Options(warehouse,date);
        String sha=digest(bytes);
        String fingerprint=digest((type+"|"+sha+"|"+json.writeValueAsString(options)).getBytes(StandardCharsets.UTF_8));
        String storageKey=scope.tenantId()+"/"+scope.companyId()+"/import_"+UUID.randomUUID().toString().replace("-","");
        long jobId=Ids.next(),attachment=Ids.next();
        boolean retained=false;
        objects.stage(storageKey);
        try {
            files.put(storageKey,bytes,"text/csv; charset=UTF-8");
            Map<String,Object> result=tx.execute(status->commands.execute(scope,"import_job.create",key,
                    Map.of("type",type,"fingerprint",fingerprint),202,()->{
                        jdbc.queryForObject("SELECT id FROM company WHERE tenant_id=? AND id=? FOR UPDATE",Long.class,scope.tenantId(),scope.companyId());
                        var duplicate=jdbc.queryForList("SELECT id,created_by FROM import_job WHERE tenant_id=? AND company_id=? AND file_fingerprint=? FOR UPDATE",scope.tenantId(),scope.companyId(),fingerprint);
                        if(!duplicate.isEmpty())return existing(scope,duplicate.getFirst());
                        long pending=jdbc.queryForObject("SELECT COUNT(*) FROM import_job WHERE tenant_id=? AND company_id=? AND created_by=? AND status IN('pending','running')",Long.class,scope.tenantId(),scope.companyId(),scope.userId());
                        if(pending>=3)throw new ApiException(429,"IMPORT_LIMIT","每人每家公司最多同时执行 3 个导入任务");
                        jdbc.update("INSERT INTO attachment(id,tenant_id,company_id,owner_type,owner_id,storage_key,file_name,content_type,size_bytes,sha256,created_by) VALUES(?,?,?,'import_job',?,?,?,?,?,?,?)",
                                attachment,scope.tenantId(),scope.companyId(),jobId,storageKey,"import.csv","text/csv",bytes.length,sha,scope.userId());
                        jdbc.update("""
                                INSERT INTO import_job(id,tenant_id,company_id,job_type,attachment_id,total_rows,created_by,file_fingerprint,options_json,warehouse_snapshot,requires_cost,org_scope_snapshot)
                                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                                """,jobId,scope.tenantId(),scope.companyId(),type,attachment,rows.size(),scope.userId(),fingerprint,json.writeValueAsString(options),json.writeValueAsString(snapshot.stream().sorted().map(Object::toString).toList()),cost,json.writeValueAsString(scope.orgScope()));
                        audit.record(scope,"import_job.create","import_job",jobId,null,Map.of("jobType",type,"totalRows",rows.size()));
                        objects.associated(storageKey);
                        return Map.of("id",Long.toString(jobId),"jobType",type,"status","pending","totalRows",rows.size(),"successRows",0,"failedRows",0);
                    },Map.class));
            // 幂等缓存和相同指纹均可能返回旧任务，只有本次创建的文件需要保留。
            retained=result!=null&&Long.toString(jobId).equals(result.get("id"));
            if(result!=null)authorize(scope,Long.parseLong(result.get("id").toString()));
            return result;
        } catch(DuplicateKeyException conflict) {
            // 并发提交相同文件时，唯一指纹是最终防线，不能创建第二批期初草稿。
            var duplicate=jdbc.queryForList("SELECT id,created_by FROM import_job WHERE tenant_id=? AND company_id=? AND file_fingerprint=?",scope.tenantId(),scope.companyId(),fingerprint);
            if(duplicate.isEmpty())throw new ApiException(409,"IMPORT_CONFLICT","导入任务创建冲突，请重试");
            return existing(scope,duplicate.getFirst());
        } finally {if(!retained)objects.discard(storageKey);}
    }
    private Map<String,Object> existing(CompanyAccess scope,Map<String,Object> entry) {
        if(((Number)entry.get("created_by")).longValue()!=scope.userId())throw new ApiException(409,"IMPORT_DUPLICATE","这份文件在当前公司已创建导入任务");
        long id=((Number)entry.get("id")).longValue();authorize(scope,id);return publicRow(jdbc.queryForMap("SELECT "+COLUMNS+" FROM import_job WHERE id=?",id));
    }
    public Map<String,Object> list(CompanyAccess scope,String type,Long cursor,int limit) {
        if(limit<1||limit>200||cursor!=null&&cursor<=0)throw ImportCsv.invalid("分页游标须为正整数，每页数量为 1 至 200");
        if(type!=null)ImportAccess.permissions(scope,type,false);
        var rows=jdbc.queryForList("SELECT "+COLUMNS+" FROM import_job WHERE tenant_id=? AND company_id=? AND created_by=? AND id<? AND (? IS NULL OR job_type=?) ORDER BY id DESC LIMIT ?",
                scope.tenantId(),scope.companyId(),scope.userId(),cursor==null?Long.MAX_VALUE:cursor,type,type,limit+1);
        var visible=new ArrayList<Map<String,Object>>();
        for(var row:rows.stream().limit(limit).toList()) {
            try{authorize(scope,((Number)row.get("id")).longValue());visible.add(metadata(row));}
            catch(ApiException denied){if(denied.getStatus()!=403&&denied.getStatus()!=404)throw denied;}
        }
        boolean more=rows.size()>limit;
        var result=new LinkedHashMap<String,Object>();result.put("items",visible);
        result.put("nextCursor",more?rows.get(limit-1).get("id").toString():null);return result;
    }
    public Map<String,Object> get(CompanyAccess scope,long id) {
        authorize(scope,id);var result=metadata(jdbc.queryForMap("SELECT "+COLUMNS+" FROM import_job WHERE id=?",id));
        result.put("targets",jdbc.queryForList("SELECT row_no,target_type,target_id FROM import_job_target WHERE job_id=? ORDER BY row_no LIMIT 200",id).stream().map(ImportJobs::publicRow).toList());return result;
    }
    public Map<String,Object> errors(CompanyAccess scope,long id,Long cursor,int limit) {
        authorize(scope,id);if(limit<1||limit>200||cursor!=null&&cursor<=0)throw ImportCsv.invalid("分页游标须为正整数，每页数量为 1 至 200");
        var rows=jdbc.queryForList("SELECT id,row_no,column_name,message FROM import_job_error WHERE job_id=? AND id>? ORDER BY id LIMIT ?",id,cursor==null?0:cursor,limit+1);
        boolean more=rows.size()>limit;if(more)rows.removeLast();
        var result=new LinkedHashMap<String,Object>();result.put("items",rows.stream().map(ImportJobs::publicRow).toList());result.put("nextCursor",more?rows.getLast().get("id").toString():null);return result;
    }
    /** 只导出已判错误的原始行，仍以当前授权检查源文件中的采购价和仓库。 */
    public byte[] errorRows(CompanyAccess scope,long id) {
        authorize(scope,id);
        var job=jdbc.queryForMap("SELECT j.job_type,a.storage_key,a.sha256 FROM import_job j JOIN attachment a ON a.id=j.attachment_id WHERE j.tenant_id=? AND j.company_id=? AND j.id=?",scope.tenantId(),scope.companyId(),id);
        byte[] source=files.read(job.get("storage_key").toString());
        if(!digest(source).equals(job.get("sha256")))throw ImportCsv.invalid("源文件校验失败，请联系管理员");
        String type=job.get("job_type").toString();var rows=ImportCsv.parse(type,source);
        var errors=jdbc.queryForList("SELECT row_no,column_name,message FROM import_job_error WHERE tenant_id=? AND company_id=? AND job_id=? ORDER BY row_no,id",scope.tenantId(),scope.companyId(),id);
        var byRow=new LinkedHashMap<Integer,List<String>>();
        for(var error:errors)byRow.computeIfAbsent(((Number)error.get("row_no")).intValue(),key->new ArrayList<>()).add(error.get("column_name")+"："+error.get("message"));
        var output=new StringBuilder("\uFEFF");var header=new ArrayList<>(ImportCsv.headers(type));header.addAll(rows.getFirst().keys());header.add("错误原因");csvLine(output,header);
        for(var row:rows)if(byRow.containsKey(row.rowNo())) {var cells=new ArrayList<>(row.cells());cells.add(String.join("；",byRow.get(row.rowNo())));csvLine(output,cells);}
        authorize(scope,id);return output.toString().getBytes(StandardCharsets.UTF_8);
    }
    private void csvLine(StringBuilder output,List<String> cells) {
        var safe=new ArrayList<String>();for(String value:cells) {
            String stripped=value.stripLeading();boolean formula=!stripped.isEmpty()&&"=+-@".indexOf(stripped.charAt(0))>=0;
            safe.add("\""+(formula?"'":"")+value.replace("\"","\"\"")+"\"");
        }
        output.append(String.join(",",safe)).append("\r\n");
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    public Map<String,Object> retry(CompanyAccess scope,long id,String key) {
        authorize(scope,id);
        Map<String,Object> result=tx.execute(status->commands.execute(scope,"import_job.retry",key,Map.of("id",Long.toString(id)),202,()->{
            var row=jdbc.queryForMap("SELECT "+COLUMNS+" FROM import_job WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",scope.tenantId(),scope.companyId(),id);
            authorize(scope,id);
            if(!canRetry(row))throw new ApiException(409,"IMPORT_RETRY_NOT_ALLOWED","仅中断且仍有未处理行的失败任务可以重试");
            jdbc.update("UPDATE import_job SET status='pending',attempts=0,next_attempt_at=NULL,error_message=NULL,finished_at=NULL,lease_owner=NULL,lease_until=NULL WHERE id=?",id);
            audit.record(scope,"import_job.retry","import_job",id,null,Map.of("checkpointRow",row.get("checkpoint_row")));
            return metadata(jdbc.queryForMap("SELECT "+COLUMNS+" FROM import_job WHERE id=?",id));
        },(Class)Map.class));
        authorize(scope,id);return result;
    }
    private static boolean canRetry(Map<String,Object> row) {
        return "failed".equals(row.get("status"))&&((Number)row.get("checkpoint_row")).intValue()<((Number)row.get("total_rows")).intValue();
    }
    private static Map<String,Object> metadata(Map<String,Object> row) {var result=publicRow(row);result.put("canRetry",canRetry(row));return result;}
    private void authorize(CompanyAccess scope,long id) {
        var rows=jdbc.queryForList("SELECT created_by,job_type,requires_cost,warehouse_snapshot,options_json FROM import_job WHERE tenant_id=? AND company_id=? AND id=?",scope.tenantId(),scope.companyId(),id);
        if(rows.isEmpty()||((Number)rows.getFirst().get("created_by")).longValue()!=scope.userId())throw new ApiException(404,"NOT_FOUND","导入任务不存在或无权查看");
        var row=rows.getFirst();var current=access.current(scope.tenantId(),scope.companyId(),scope.userId(),snapshot(row.get("warehouse_snapshot")),row.get("job_type").toString(),cost(row));
        var options=json.readValue(row.get("options_json").toString(),Options.class);
        if(options.warehouseId()!=null&&!current.allowsWarehouse(options.warehouseId()))throw ImportAccess.forbidden();
    }
    CompanyAccess organization(CompanyAccess actor,Object snapshot) {
        if(snapshot==null) return actor;
        var initial=json.readValue(snapshot.toString(),cn.erp.shared.OrgScope.class);
        return new CompanyAccess(actor.tenantId(),actor.userId(),actor.companyId(),actor.allWarehouses(),actor.warehouseIds(),actor.permissions(),actor.orgScope().intersect(initial));
    }
    Set<Long> snapshot(Object value) {
        if(value==null)return Set.of();var result=new HashSet<Long>();
        for(Object id:json.readValue(value.toString(),List.class))result.add(Long.parseLong(id.toString()));return Set.copyOf(result);
    }
    static boolean cost(Map<String,Object> row){Object value=row.get("requires_cost");return Boolean.TRUE.equals(value)||(value instanceof Number n&&n.intValue()==1);}
    static Map<String,Object> publicRow(Map<String,Object> row) {
        var result=new LinkedHashMap<String,Object>();row.forEach((key,value)->{
            StringBuilder camel=new StringBuilder();boolean upper=false;for(char c:key.toCharArray()){if(c=='_')upper=true;else{camel.append(upper?Character.toUpperCase(c):c);upper=false;}}
            Object exposed=value;
            if(value!=null) {
                if(key.equals("id")||key.endsWith("_id"))exposed=value.toString();
                else if(value instanceof java.sql.Timestamp timestamp)exposed=timestamp.toInstant().toString();
                else if(value instanceof Number number&&Set.of("total_rows","success_rows","failed_rows","checkpoint_row","row_no","attempts").contains(key))exposed=number.intValue();
            }
            result.put(camel.toString(),exposed);
        });return result;
    }
    static String digest(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException error){throw new IllegalStateException("运行环境缺少摘要算法",error);}
    }
}
