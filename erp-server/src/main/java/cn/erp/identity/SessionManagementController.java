package cn.erp.identity;

import cn.erp.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sys")
class SessionManagementController {
    private final AccessService access;private final SessionAuthority sessions;private final JdbcTemplate jdbc;
    SessionManagementController(AccessService access,SessionAuthority sessions,JdbcTemplate jdbc) {this.access=access;this.sessions=sessions;this.jdbc=jdbc;}
    @GetMapping("/online-users") ApiResponse<?> online(HttpServletRequest request,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="50") int limit) {return ApiResponse.success(sessions.list(access.requireTenant(request,"online_user:view"),cursor,limit));}
    @PostMapping("/online-users/{id}/kick") ApiResponse<?> kick(HttpServletRequest request,@PathVariable String id,@RequestHeader(value="Idempotency-Key",required=false) String key) {var result=sessions.kick(access.requireTenant(request,"online_user:kick"),id,key);var current=request.getSession(false);if(current!=null&&SessionAuthority.publicId(current.getId()).equals(id)) current.invalidate();return ApiResponse.success(result);}
    @GetMapping("/login-logs") ApiResponse<?> logs(HttpServletRequest request,@RequestParam(defaultValue="") String result,@RequestParam(defaultValue="") String method,@RequestParam(required=false) Long userId,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="50") int limit) {
        var actor=access.requireTenant(request,"login_log:view");PlatformAccess.require(jdbc,actor,"login_log:view");
        if(limit<1||limit>200||cursor!=null&&cursor<=0||!Set.of("","success","failed","locked","disabled").contains(result)||!Set.of("","password","sms","email","wechat","sso").contains(method)) throw new ApiException(400,"VALIDATION_FAILED","查询条件无效");
        var rows=jdbc.queryForList("SELECT * FROM sys_login_log WHERE tenant_id=? AND id<? AND (?='' OR result=?) AND (?='' OR method=?) AND (? IS NULL OR user_id=?) ORDER BY id DESC LIMIT ?",actor.tenantId(),cursor==null?Long.MAX_VALUE:cursor,result,result,method,method,userId,userId,limit+1);
        var page=new LinkedHashMap<String,Object>();page.put("items",rows.stream().limit(limit).map(ApiRows::row).toList());page.put("nextCursor",rows.size()>limit?rows.get(limit-1).get("id").toString():null);return ApiResponse.success(page);
    }
}
