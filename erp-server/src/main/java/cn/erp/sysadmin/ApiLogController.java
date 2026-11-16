package cn.erp.sysadmin;

import cn.erp.identity.*;
import cn.erp.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static cn.erp.sysadmin.PlatformRows.*;

@RestController
@RequestMapping("/api/v1/sys")
class ApiLogController {
    private final JdbcTemplate jdbc;private final AccessService access;private final BusinessCommands commands;private final AuditService audit;private final GrafanaLinks links;
    ApiLogController(JdbcTemplate jdbc,AccessService access,BusinessCommands commands,AuditService audit,GrafanaLinks links) {this.jdbc=jdbc;this.access=access;this.commands=commands;this.audit=audit;this.links=links;}
    @GetMapping("/api-access-logs") ApiResponse<?> requests(HttpServletRequest request,@RequestParam(defaultValue="") String requestId,@RequestParam(required=false) Integer status,@RequestParam(defaultValue="false") boolean slow,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="50") int limit) {
        var actor=actor(request,"api_log:view");long before=cursor(cursor,limit);if(requestId.length()>64||status!=null&&(status<100||status>599)) throw invalid("查询条件无效");
        return ApiResponse.success(logPage(actor,jdbc.queryForList("SELECT * FROM sys_api_access_log WHERE tenant_id=? AND company_id IN(0,?) AND id<? AND (?='' OR request_id=?) AND (? IS NULL OR status_code=?) AND (?=0 OR slow_request=1) ORDER BY id DESC LIMIT ?",actor.tenantId(),actor.companyId(),before,requestId,requestId,status,status,slow,limit+1),limit));
    }
    @GetMapping("/api-error-logs") ApiResponse<?> errors(HttpServletRequest request,@RequestParam(defaultValue="") String requestId,@RequestParam(required=false) Boolean handled,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="50") int limit) {
        var actor=actor(request,"api_error:view");long before=cursor(cursor,limit);if(requestId.length()>64) throw invalid("查询条件无效");
        return ApiResponse.success(logPage(actor,jdbc.queryForList("SELECT * FROM sys_api_error_log WHERE tenant_id=? AND company_id IN(0,?) AND id<? AND (?='' OR request_id=?) AND (? IS NULL OR handled=?) ORDER BY id DESC LIMIT ?",actor.tenantId(),actor.companyId(),before,requestId,requestId,handled,handled,limit+1),limit));
    }
    record Handle(@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号无效") Long version,boolean handled) {}
    @PutMapping("/api-error-logs/{id}/handled") @Transactional ApiResponse<?> handle(HttpServletRequest request,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Handle input) {
        var actor=actor(request,"api_error:update");
        return ApiResponse.success(commands.execute(actor,"api_error.handle",key,Map.of("id",id,"input",input),200,()->{
            var rows=jdbc.queryForList("SELECT * FROM sys_api_error_log WHERE tenant_id=? AND company_id IN(0,?) AND id=? FOR UPDATE",actor.tenantId(),actor.companyId(),id);if(rows.isEmpty()) throw missing();version(input.version(),rows.getFirst().get("version"));
            jdbc.update("UPDATE sys_api_error_log SET handled=?,handled_by=?,handled_at=UTC_TIMESTAMP(3),version=version+1 WHERE tenant_id=? AND id=?",input.handled(),actor.userId(),actor.tenantId(),id);audit.record(actor,"api_error.handle","sys_api_error_log",id,Map.of("handled",rows.getFirst().get("handled")),Map.of("handled",input.handled()));return Map.of("id",Long.toString(id),"version",input.version()+1,"handled",input.handled());
        },Map.class));
    }
    private Map<String,Object> logPage(CompanyAccess actor,List<Map<String,Object>> rows,int limit) {
        var result=page(rows,limit);
        if(result.get("items") instanceof List<?> values) for(Object value:values) if(value instanceof Map<?,?> fields) {
            @SuppressWarnings("unchecked") var row=(Map<String,Object>)fields;String url=links.request(actor.tenantId(),Objects.toString(row.get("requestId"),null));if(url!=null) row.put("requestLogUrl",url);
        }return result;
    }
    private CompanyAccess actor(HttpServletRequest request,String permission) {var actor=access.requireCompany(request,permission);PlatformAccess.require(jdbc,actor,permission);if(!actor.allWarehouses()) throw new ApiException(403,"FORBIDDEN","接口日志需要当前公司的全部仓库范围");return actor;}
}
