package cn.erp.scheduler;

import cn.erp.identity.*;
import cn.erp.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sys/jobs")
class JobController {
    private final JobEngine engine;private final JdbcTemplate jdbc;private final AccessService access;private final BusinessCommands commands;private final AuditService audit;
    JobController(JobEngine engine,JdbcTemplate jdbc,AccessService access,BusinessCommands commands,AuditService audit) {this.engine=engine;this.jdbc=jdbc;this.access=access;this.commands=commands;this.audit=audit;}
    @GetMapping ApiResponse<?> list(HttpServletRequest request) {var actor=actor(request,"job:view");engine.ensure(actor.tenantId());return ApiResponse.success(jdbc.queryForList("SELECT code,name,schedule,enabled,last_run_at,next_run_at,last_result,version FROM job_definition WHERE tenant_id=? ORDER BY code",actor.tenantId()).stream().map(ApiRows::row).toList());}
    record Input(@NotBlank(message="请填写任务计划") @Size(max=128,message="计划过长") String schedule,boolean enabled,@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号无效") Long version) {}
    @PutMapping("/{code}") @Transactional ApiResponse<?> update(HttpServletRequest request,@PathVariable String code,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Input input) {
        var actor=actor(request,"job:update");PlatformAccess.lock(jdbc,actor,"job:update");engine.ensure(actor.tenantId());var next=JobEngine.next(input.schedule(),java.time.Instant.now());
        return ApiResponse.success(commands.execute(actor,"job.update",key,Map.of("code",code,"input",input),200,()->{
            var rows=jdbc.queryForList("SELECT * FROM job_definition WHERE tenant_id=? AND code=? FOR UPDATE",actor.tenantId(),code);if(rows.isEmpty()) throw missing();if(((Number)rows.getFirst().get("version")).longValue()!=input.version()) throw new ApiException(409,"VERSION_CONFLICT","任务配置已变化，请刷新后重试");
            jdbc.update("UPDATE job_definition SET schedule=?,enabled=?,next_run_at=?,version=version+1 WHERE tenant_id=? AND code=?",input.schedule(),input.enabled(),java.sql.Timestamp.from(next),actor.tenantId(),code);audit.record(actor,"job.update","job_definition",Integer.toUnsignedLong(code.hashCode()),null,Map.of("schedule",input.schedule(),"enabled",input.enabled()));return Map.of("code",code,"version",input.version()+1);
        },Map.class));
    }
    @PostMapping("/{code}/run") @Transactional ApiResponse<?> run(HttpServletRequest request,@PathVariable String code,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        var actor=actor(request,"job:run");PlatformAccess.lock(jdbc,actor,"job:run");engine.ensure(actor.tenantId());
        // API与Worker分离部署时，管理入口只排队预置动作，不能要求API本机注册执行器。
        if(jdbc.queryForList("SELECT code FROM job_definition WHERE tenant_id=0 AND code=?",String.class,code).isEmpty()) throw missing();
        return ApiResponse.success(commands.execute(actor,"job.run",key,Map.of("code",code),202,()->{if(jdbc.queryForList("SELECT code FROM job_definition WHERE tenant_id=? AND code=?",String.class,actor.tenantId(),code).isEmpty()) throw missing();long id=Ids.next();jdbc.update("INSERT INTO job_run_log(id,tenant_id,job_code,trigger_kind,operator_id) VALUES(?,?,?,'manual',?)",id,actor.tenantId(),code,actor.userId());audit.record(actor,"job.run","job_run_log",id,null,Map.of("code",code));return Map.of("id",Long.toString(id),"status","pending");},Map.class));
    }
    @GetMapping("/{code}/logs") ApiResponse<?> logs(HttpServletRequest request,@PathVariable String code,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="50") int limit) {
        var actor=actor(request,"job:view");if(limit<1||limit>200||cursor!=null&&cursor<=0) throw new ApiException(400,"VALIDATION_FAILED","分页条件无效");var rows=jdbc.queryForList("SELECT id,job_code,trigger_kind,operator_id,node_id,status,started_at,finished_at,message,created_at FROM job_run_log WHERE tenant_id=? AND job_code=? AND id<? ORDER BY id DESC LIMIT ?",actor.tenantId(),code,cursor==null?Long.MAX_VALUE:cursor,limit+1);var page=new LinkedHashMap<String,Object>();page.put("items",rows.stream().limit(limit).map(ApiRows::row).toList());page.put("nextCursor",rows.size()>limit?rows.get(limit-1).get("id").toString():null);return ApiResponse.success(page);
    }
    private CompanyAccess actor(HttpServletRequest request,String permission) {var actor=access.requireTenant(request,permission);PlatformAccess.require(jdbc,actor,permission);return actor;}
    private ApiException missing() {return new ApiException(404,"NOT_FOUND","任务不存在或未登记");}
}
