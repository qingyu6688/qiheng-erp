package cn.erp.wms;

import cn.erp.identity.AccessService;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wms/count-tasks")
class CountWorkController {
    private final AccessService access;
    private final CountTasks tasks;

    CountWorkController(AccessService access,CountTasks tasks) {
        this.access=access;
        this.tasks=tasks;
    }

    @GetMapping
    ApiResponse<?> page(HttpServletRequest request,@RequestParam(required=false) Long warehouseId,
                         @RequestParam(required=false) String status,@RequestParam(required=false) Long cursor,
                         @RequestParam(defaultValue="30") int limit) {
        return ApiResponse.success(tasks.page(actor(request,false),warehouseId,status,cursor,limit));
    }

    @GetMapping("/{id}")
    ApiResponse<?> detail(HttpServletRequest request,@PathVariable long id) {
        return ApiResponse.success(tasks.detail(actor(request,false),id));
    }

    @PostMapping("/{id}/claim")
    ApiResponse<?> claim(HttpServletRequest request,@PathVariable long id,@RequestHeader(name="Idempotency-Key",required=false) String key,
                         @RequestBody WmsScopes.Revision input) {
        return ApiResponse.success(Map.of("id",tasks.claim(actor(request,true),id,key,input)));
    }

    @PostMapping("/{id}/confirm")
    ApiResponse<?> confirm(HttpServletRequest request,@PathVariable long id,@RequestHeader(name="Idempotency-Key",required=false) String key,
                           @RequestBody CountTasks.Confirmation input) {
        return ApiResponse.success(Map.of("id",tasks.confirm(actor(request,true),id,key,input)));
    }

    private CompanyAccess actor(HttpServletRequest request,boolean write) {
        var actor=access.requireCompany(request,"wms_task:view");
        WmsScopes.permission(actor,"stock_count:view");
        if(write) {
            WmsScopes.permission(actor,"wms_task:execute");
            WmsScopes.permission(actor,"stock_count:record_counts");
        }
        return actor;
    }
}
