package cn.erp.jobs;

import cn.erp.identity.AccessService;
import cn.erp.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/outbox-events")
class OutboxController {
    private final AccessService access;
    private final OutboxService events;
    private final JdbcTemplate jdbc;
    private final BusinessCommands commands;
    private final AuditService audit;

    OutboxController(AccessService access,OutboxService events,JdbcTemplate jdbc,BusinessCommands commands,AuditService audit) {
        this.access=access;this.events=events;this.jdbc=jdbc;this.commands=commands;this.audit=audit;
    }

    @GetMapping
    ApiResponse<?> list(HttpServletRequest req,@RequestParam(defaultValue="dead") String status,
            @RequestParam(required=false) Long cursor,@RequestParam(defaultValue="20") int limit) {
        var actor=company(req);
        if(!Set.of("dead","pending","processing","done").contains(status) || limit<1 || limit>200 || cursor!=null&&cursor<=0) {
            throw new ApiException(400,"VALIDATION_FAILED","任务筛选条件无效");
        }
        var rows=jdbc.queryForList("SELECT e.id,e.event_type,e.status,e.attempts,e.last_error,e.created_at FROM outbox_event e WHERE e.tenant_id=? AND "+visible()+" AND e.status=? AND e.id<? ORDER BY e.id DESC LIMIT ?",
                actor.tenantId(),actor.companyId(),actor.companyId(),status,cursor==null?Long.MAX_VALUE:cursor,limit+1);
        var items=rows.stream().limit(limit).map(row->Map.of("id",row.get("id").toString(),"eventType",row.get("event_type"),"status",row.get("status"),
                "attempts",row.get("attempts"),"lastError",row.get("last_error")==null?"":row.get("last_error"))).toList();
        var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("nextCursor",rows.size()>limit?items.getLast().get("id"):null);
        return ApiResponse.success(result);
    }

    @PostMapping("/{id}/replay")
    ApiResponse<?> replay(HttpServletRequest req,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        var actor=company(req);
        Long found=jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event e WHERE e.tenant_id=? AND e.id=? AND "+visible(),Long.class,
                actor.tenantId(),id,actor.companyId(),actor.companyId());
        if(found==null||found==0) throw new ApiException(404,"NOT_FOUND","事件不存在或无权查看");
        return ApiResponse.success(commands.execute(actor,"outbox.replay",key,id,200,()->{
            events.replay(actor,id);audit.record(actor,"outbox.replay","outbox_event",id,null,Map.of("status","pending"));
            return new Result(Long.toString(id),"pending");
        },Result.class));
    }
    private cn.erp.identity.CompanyAccess company(HttpServletRequest req) {
        var actor=access.requireCompany(req,"admin:outbox");
        if(!actor.allWarehouses()||!actor.orgScope().allDepartments()) throw new ApiException(403,"FORBIDDEN","后台事件管理需要整家公司数据范围");
        return actor;
    }
    private String visible() {return "((e.aggregate_type='journal' AND EXISTS(SELECT 1 FROM journal j WHERE j.tenant_id=e.tenant_id AND j.id=e.aggregate_id AND j.company_id=?)) OR (e.aggregate_type='reconciliation' AND EXISTS(SELECT 1 FROM reconciliation_run r WHERE r.tenant_id=e.tenant_id AND r.id=e.aggregate_id AND r.company_id=?)))";}
    record Result(String id,String status) {}
}
