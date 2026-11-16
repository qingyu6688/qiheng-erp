package cn.erp.importing;

import cn.erp.catalog.CatalogImportFacade;
import cn.erp.documents.ObjectFiles;
import cn.erp.identity.CompanyAccess;
import cn.erp.opening.OpeningService;
import cn.erp.reference.PartnerImportFacade;
import cn.erp.shared.ApiException;
import cn.erp.shared.AuditService;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** 文件解析在事务外，单行领域写入与结果、断点在同一事务内提交。 */
@Service
public class ImportProcessor {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ObjectFiles files;
    private final ImportAccess access;
    private final ImportJobs jobs;
    private final CatalogImportFacade catalog;
    private final PartnerImportFacade partners;
    private final OpeningService opening;
    private final AuditService audit;
    private final TransactionTemplate tx;
    ImportProcessor(JdbcTemplate jdbc,ObjectMapper json,ObjectFiles files,ImportAccess access,ImportJobs jobs,
            CatalogImportFacade catalog,PartnerImportFacade partners,OpeningService opening,AuditService audit,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.json=json;this.files=files;this.access=access;this.jobs=jobs;this.catalog=catalog;
        this.partners=partners;this.opening=opening;this.audit=audit;this.tx=new TransactionTemplate(manager);
    }
    public boolean processNext() {
        return processNext(Integer.MAX_VALUE);
    }
    public boolean processTenant(long tenant) {return processNext(Integer.MAX_VALUE,tenant);}
    boolean processNext(int rowBudget) {return processNext(rowBudget,null);}
    private boolean processNext(int rowBudget,Long tenant) {
        String owner=UUID.randomUUID().toString();
        Map<String,Object> job=tx.execute(status->{
            jdbc.update("UPDATE import_job SET status='failed',error_message='导入任务租约连续失效，请恢复后重试任务',finished_at=UTC_TIMESTAMP(3),lease_owner=NULL,lease_until=NULL WHERE (? IS NULL OR tenant_id=?) AND status='running' AND attempts>=3 AND lease_until<UTC_TIMESTAMP(3)",tenant,tenant);
            var candidates=jdbc.queryForList("""
                    SELECT j.*,a.storage_key,a.sha256 FROM import_job j JOIN attachment a ON a.id=j.attachment_id
                    WHERE (? IS NULL OR j.tenant_id=?) AND j.attempts<3 AND ((j.status='pending' AND (j.next_attempt_at IS NULL OR j.next_attempt_at<=UTC_TIMESTAMP(3))) OR (j.status='running' AND j.lease_until<UTC_TIMESTAMP(3)))
                    ORDER BY j.created_at,j.id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """,tenant,tenant);
            if(candidates.isEmpty())return null;var row=candidates.getFirst();
            jdbc.update("UPDATE import_job SET status='running',attempts=attempts+1,next_attempt_at=NULL,lease_owner=?,lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 5 MINUTE),started_at=COALESCE(started_at,UTC_TIMESTAMP(3)),error_message=NULL WHERE id=?",owner,row.get("id"));return row;
        });
        if(job==null)return false;
        long id=number(job,"id");String type=job.get("job_type").toString();
        try {
            tx.executeWithoutResult(status->authorize(job));
            byte[] source=files.read(job.get("storage_key").toString());
            if(!ImportJobs.digest(source).equals(job.get("sha256")))throw ImportCsv.invalid("源文件校验失败，请联系管理员核对文件仓");
            var rows=ImportCsv.parse(type,source);
            int processed=0;
            for(var row:rows) {
                if(row.rowNo()-1<=((Number)job.get("checkpoint_row")).intValue())continue;
                if(processed++>=rowBudget)return true;
                try {tx.executeWithoutResult(status->writeRow(job,owner,type,row));}
                catch(ApiException error) {
                    if(error.getErrorCode().equals("IMPORT_LEASE_LOST"))return true;
                    if(error.getStatus()==403||error.getStatus()>=500)throw error;
                    tx.executeWithoutResult(status->rowError(job,owner,row,error));
                } catch(DataIntegrityViolationException error) {
                    tx.executeWithoutResult(status->rowError(job,owner,row,new ApiException(400,"IMPORT_ROW_ERROR","编码重复或资料不符合约束，请检查本行数据")));
                }
            }
            tx.executeWithoutResult(status->{
                lock(id,owner);var scope=authorize(job);
                jdbc.update("UPDATE import_job SET status=CASE WHEN failed_rows=0 THEN 'succeeded' WHEN success_rows=0 THEN 'failed' ELSE 'partial' END,finished_at=UTC_TIMESTAMP(3),lease_owner=NULL,lease_until=NULL WHERE id=?",id);
                var counts=jdbc.queryForMap("SELECT status,success_rows,failed_rows FROM import_job WHERE id=?",id);
                audit.record(scope,"import_job.finish","import_job",id,null,counts);
            });
        } catch(RuntimeException error) {
            if(error instanceof ApiException api&&api.getErrorCode().equals("IMPORT_LEASE_LOST"))return true;
            String message=error instanceof ApiException api?api.getMessage():"导入处理中断，请联系管理员检查后重试";
            boolean retryable=error instanceof ApiException api&&api.getStatus()>=500||error instanceof TransientDataAccessException||error instanceof DataAccessResourceFailureException;
            tx.executeWithoutResult(status->jdbc.update("""
                    UPDATE import_job SET status=IF(? AND attempts<3,'pending','failed'),error_message=?,
                    next_attempt_at=IF(? AND attempts<3,TIMESTAMPADD(SECOND,2*POW(2,attempts-1),UTC_TIMESTAMP(3)),NULL),
                    finished_at=IF(? AND attempts<3,NULL,UTC_TIMESTAMP(3)),lease_owner=NULL,lease_until=NULL
                    WHERE id=? AND lease_owner=? AND status='running'
                    """,retryable,message,retryable,retryable,id,owner));
        }
        return true;
    }
    private void writeRow(Map<String,Object> job,String owner,String type,ImportCsv.Row row) {
        long id=number(job,"id");var locked=lock(id,owner);
        if(((Number)locked.get("checkpoint_row")).intValue()>=row.rowNo()-1)return;
        var scope=authorize(job);var values=row.cells();
        if(values.size()!=ImportCsv.headers(type).size())throw rowError("表头","本行列数与模板不一致");
        String target;
        switch(type) {
            case "sku" -> target=catalog.create(scope,"imp_"+id+"_"+row.rowNo(),new CatalogImportFacade.Input(
                    required(values,0,"productCode"),required(values,1,"code"),required(values,2,"name"),optional(values,3),
                    optional(values,4),optional(values,5),optional(values,6),optional(values,7)));
            case "partner" -> {
                var roles=new LinkedHashSet<>(Arrays.asList(required(values,4,"roles").split("\\|",-1)));
                target=partners.create(scope,new PartnerImportFacade.Input(required(values,0,"code"),required(values,1,"name"),optional(values,2),optional(values,3),roles));
            }
            case "opening_stock" -> target=createOpening(scope,job,row);
            default -> throw ImportCsv.invalid("导入类型无效");
        }
        String targetType=type.equals("opening_stock")?"stock_adjustment":type;
        jdbc.update("INSERT INTO import_job_target(id,tenant_id,company_id,job_id,row_no,target_type,target_id) VALUES(?,?,?,?,?,?,?)",Ids.next(),scope.tenantId(),scope.companyId(),id,row.rowNo(),targetType,Long.parseLong(target));
        jdbc.update("UPDATE import_job SET checkpoint_row=?,success_rows=success_rows+1,lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 5 MINUTE) WHERE id=?",row.rowNo()-1,id);
    }
    private String createOpening(CompanyAccess scope,Map<String,Object> job,ImportCsv.Row row) {
        var values=row.cells();var options=json.readValue(job.get("options_json").toString(),ImportJobs.Options.class);
        var skus=jdbc.queryForList("SELECT id FROM sku WHERE tenant_id=? AND code=?",Long.class,scope.tenantId(),required(values,0,"skuCode"));
        if(skus.isEmpty())throw rowError("skuCode","SKU 编码不存在");
        var units=jdbc.queryForList("SELECT id FROM unit WHERE tenant_id=? AND name=?",Long.class,scope.tenantId(),required(values,1,"unitName"));
        if(units.isEmpty())throw rowError("unitName","单位名称不存在");
        String qty=required(values,2,"qty"),cost=required(values,3,"totalCost"),reason=required(values,4,"reason");
        if(!qty.matches("[0-9]{1,12}(\\.[0-9]{1,6})?")||new BigDecimal(qty).signum()<=0)throw rowError("qty","数量须大于零，整数最多 12 位、小数最多 6 位");
        if(!cost.matches("[0-9]{1,16}(\\.[0-9]{1,2})?"))throw rowError("totalCost","期初总成本须非负，整数最多 16 位、小数最多 2 位");
        if(reason.length()>200)throw rowError("reason","期初原因最多 200 个字符");
        var input=new OpeningService.Stock("opening",options.warehouseId(),options.businessDate(),reason,List.of(new OpeningService.StockLine(skus.getFirst(),units.getFirst(),qty,cost)),null);
        return opening.createStock(scope,"imp_"+number(job,"id")+"_"+row.rowNo(),input).get("id").toString();
    }
    private void rowError(Map<String,Object> job,String owner,ImportCsv.Row row,ApiException error) {
        long id=number(job,"id");var locked=lock(id,owner);var scope=authorize(job);
        if(((Number)locked.get("checkpoint_row")).intValue()>=row.rowNo()-1)return;
        String column=error.getDetails() instanceof Map<?,?> detail&&detail.get("columnName")!=null?detail.get("columnName").toString():"整行";
        jdbc.update("INSERT INTO import_job_error(id,tenant_id,company_id,job_id,row_no,column_name,message) VALUES(?,?,?,?,?,?,?)",Ids.next(),scope.tenantId(),scope.companyId(),id,row.rowNo(),column,error.getMessage());
        jdbc.update("UPDATE import_job SET checkpoint_row=?,failed_rows=failed_rows+1,lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 5 MINUTE) WHERE id=?",row.rowNo()-1,id);
    }
    private CompanyAccess authorize(Map<String,Object> job) {
        var scope=access.current(number(job,"tenant_id"),number(job,"company_id"),number(job,"created_by"),jobs.snapshot(job.get("warehouse_snapshot")),job.get("job_type").toString(),ImportJobs.cost(job));
        scope=jobs.organization(scope,job.get("org_scope_snapshot"));
        var options=json.readValue(job.get("options_json").toString(),ImportJobs.Options.class);
        if(options.warehouseId()!=null&&!scope.allowsWarehouse(options.warehouseId()))throw ImportAccess.forbidden();return scope;
    }
    private Map<String,Object> lock(long id,String owner) {
        var rows=jdbc.queryForList("SELECT checkpoint_row FROM import_job WHERE id=? AND lease_owner=? AND status='running' FOR UPDATE",id,owner);
        if(rows.isEmpty())throw new ApiException(409,"IMPORT_LEASE_LOST","任务已由其他工作进程接管");return rows.getFirst();
    }
    private static long number(Map<String,Object> row,String field){return ((Number)row.get(field)).longValue();}
    private static String optional(List<String> row,int index){String value=row.get(index).trim();return value.isEmpty()?null:value;}
    private static String required(List<String> row,int index,String name){String value=optional(row,index);if(value==null)throw rowError(name,"必填项不能为空");return value;}
    private static ApiException rowError(String column,String message){return new ApiException(400,"IMPORT_ROW_ERROR",message,Map.of("columnName",column));}
}
