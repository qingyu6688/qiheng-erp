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
        var actor=access.requireTenant(req,"admin:outbox");
        if(!Set.of("dead","pending","processing","done").contains(status) || limit<1 || limit>200 || cursor!=null&&cursor<=0) {
            throw new ApiException(400,"VALIDATION_FAILED","任务筛选条件无效");
        }
        var rows=jdbc.queryForList("SELECT id,event_type,status,attempts,last_error,created_at FROM outbox_event WHERE tenant_id=? AND status=? AND id<? ORDER BY id DESC LIMIT ?",
                actor.tenantId(),status,cursor==null?Long.MAX_VALUE:cursor,limit+1);
        var items=rows.stream().limit(limit).map(row->Map.of("id",row.get("id").toString(),"eventType",row.get("event_type"),"status",row.get("status"),
                "attempts",row.get("attempts"),"lastError",row.get("last_error")==null?"":row.get("last_error"))).toList();
        var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("nextCursor",rows.size()>limit?items.getLast().get("id"):null);
        return ApiResponse.success(result);
    }

    @PostMapping("/{id}/replay")
    ApiResponse<?> replay(HttpServletRequest req,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        var actor=access.requireTenant(req,"admin:outbox");
        return ApiResponse.success(commands.execute(actor,"outbox.replay",key,id,200,()->{
            events.replay(actor,id);audit.record(actor,"outbox.replay","outbox_event",id,null,Map.of("status","pending"));
            return new Result(Long.toString(id),"pending");
        },Result.class));
    }
    record Result(String id,String status) {}
}
