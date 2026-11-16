package cn.erp.notify;

import cn.erp.identity.*;
import cn.erp.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static cn.erp.notify.NoticeService.*;

@RestController
@RequestMapping("/api/v1")
class NotificationController {
    private final AccessService access;private final MessageService messages;private final NoticeService notices;private final TemplateService templates;private final RobotSettings robots;private final JdbcTemplate jdbc;private final BusinessCommands commands;
    NotificationController(AccessService access,MessageService messages,NoticeService notices,TemplateService templates,RobotSettings robots,JdbcTemplate jdbc,BusinessCommands commands) {this.access=access;this.messages=messages;this.notices=notices;this.templates=templates;this.robots=robots;this.jdbc=jdbc;this.commands=commands;}
    @GetMapping("/notify/messages") ApiResponse<?> messages(HttpServletRequest req,@RequestParam(defaultValue="") String category,@RequestParam(required=false) Boolean unread,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="50") int limit) {return ApiResponse.success(messages.list(access.requireCompany(req,null),category,unread,cursor,limit));}
    @GetMapping("/notify/unread-count") ApiResponse<?> count(HttpServletRequest req) {return ApiResponse.success(messages.counts(access.requireCompany(req,null)));}
    @PutMapping("/notify/messages/{id}/read") ApiResponse<?> read(HttpServletRequest req,@PathVariable long id) {return ApiResponse.success(messages.read(access.requireCompany(req,null),id));}
    @GetMapping("/notify/notices/{id}") ApiResponse<?> notice(HttpServletRequest req,@PathVariable long id) {return ApiResponse.success(messages.notice(access.requireCompany(req,null),id));}
    @PutMapping("/notify/notices/{id}/confirm") ApiResponse<?> confirm(HttpServletRequest req,@PathVariable long id) {return ApiResponse.success(messages.confirmNotice(access.requireCompany(req,null),id));}
    @GetMapping("/notify/important-notices") ApiResponse<?> important(HttpServletRequest req) {return ApiResponse.success(messages.important(access.requireCompany(req,null)));}
    @GetMapping("/notify/subscriptions") ApiResponse<?> subscriptions(HttpServletRequest req) {var actor=access.requireUser(req);return ApiResponse.success(jdbc.queryForList("SELECT category,channel,enabled,version FROM notify_subscription WHERE tenant_id=? AND user_id=? ORDER BY category,channel",actor.tenantId(),actor.userId()).stream().map(ApiRows::row).toList());}
    record Subscription(@NotBlank(message="请选择消息类型") @Pattern(regexp="todo|alert|notice|system",message="订阅类型无效") String category,@NotBlank(message="请选择订阅渠道") @Pattern(regexp="email|sms",message="站内消息必须保留，外部渠道仅支持短信或邮件") String channel,boolean enabled,@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号无效") Long version) {}
    @PutMapping("/notify/subscriptions") @Transactional ApiResponse<?> subscription(HttpServletRequest req,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Subscription input) {
        var actor=access.requireUser(req);return ApiResponse.success(commands.execute(actor,"subscription.save",key,input,200,()->{
            jdbc.queryForObject("SELECT id FROM sys_user WHERE tenant_id=? AND id=? FOR UPDATE",Long.class,actor.tenantId(),actor.userId());
            var rows=jdbc.queryForList("SELECT version FROM notify_subscription WHERE tenant_id=? AND user_id=? AND category=? AND channel=?",actor.tenantId(),actor.userId(),input.category(),input.channel());version(input.version(),rows.isEmpty()?0:rows.getFirst().get("version"));
            jdbc.update("INSERT INTO notify_subscription(tenant_id,user_id,category,channel,enabled,version) VALUES(?,?,?,?,?,1) ON DUPLICATE KEY UPDATE enabled=VALUES(enabled),version=version+1",actor.tenantId(),actor.userId(),input.category(),input.channel(),input.enabled());return Map.of("category",input.category(),"channel",input.channel(),"enabled",input.enabled(),"version",input.version()+1);
        },Map.class));
    }
    @GetMapping("/sys/notices") ApiResponse<?> notices(HttpServletRequest req,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="50") int limit) {return ApiResponse.success(notices.list(access.requireTenant(req,"notice:view"),cursor,limit));}
    @GetMapping("/sys/notices/{id}") ApiResponse<?> adminNotice(HttpServletRequest req,@PathVariable long id) {return ApiResponse.success(notices.get(access.requireTenant(req,"notice:view"),id));}
    @PostMapping("/sys/notices") ApiResponse<?> createNotice(HttpServletRequest req,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody NoticeService.Input input) {return ApiResponse.success(notices.save(access.requireTenant(req,"notice:create"),null,key,input));}
    @PutMapping("/sys/notices/{id}") ApiResponse<?> saveNotice(HttpServletRequest req,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody NoticeService.Input input) {return ApiResponse.success(notices.save(access.requireTenant(req,"notice:update"),id,key,input));}
    record Version(@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号无效") Long version) {}
    @PostMapping("/sys/notices/{id}/{action}") ApiResponse<?> noticeAction(HttpServletRequest req,@PathVariable long id,@PathVariable String action,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Version input) {return ApiResponse.success(notices.change(access.requireTenant(req,"notice:publish"),id,action,input.version(),key));}
    @GetMapping("/sys/notify-templates") ApiResponse<?> templates(HttpServletRequest req,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="50") int limit) {return ApiResponse.success(templates.list(access.requireTenant(req,"notify_template:view"),cursor,limit));}
    @PostMapping("/sys/notify-templates") ApiResponse<?> createTemplate(HttpServletRequest req,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody TemplateService.Input input) {return ApiResponse.success(templates.save(access.requireTenant(req,"notify_template:create"),null,key,input));}
    @PutMapping("/sys/notify-templates/{id}") ApiResponse<?> saveTemplate(HttpServletRequest req,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody TemplateService.Input input) {return ApiResponse.success(templates.save(access.requireTenant(req,"notify_template:update"),id,key,input));}
    @GetMapping("/sys/notify-send-logs") ApiResponse<?> logs(HttpServletRequest req,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="50") int limit) {
        var actor=access.requireCompany(req,"notify_log:view");PlatformAccess.require(jdbc,actor,"notify_log:view");if(!actor.allWarehouses()) throw new ApiException(403,"FORBIDDEN","发送日志需要当前公司的全部仓库范围");return ApiResponse.success(page(jdbc.queryForList("SELECT id,user_id,message_id,channel,template_code,receiver,status,error,attempts,created_at,finished_at FROM notify_send_log WHERE tenant_id=? AND company_id IN(0,?) AND id<? ORDER BY id DESC LIMIT ?",actor.tenantId(),actor.companyId(),before(cursor,limit),limit+1),limit));
    }
    @GetMapping("/sys/notify-robot") ApiResponse<?> robot(HttpServletRequest req) {return ApiResponse.success(robots.view(access.requireTenant(req,"notify_robot:manage")));}
    @PutMapping("/sys/notify-robot") ApiResponse<?> robot(HttpServletRequest req,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody RobotSettings.Input input) {return ApiResponse.success(robots.save(access.requireTenant(req,"notify_robot:manage"),key,input));}
}
