package cn.erp.wms;

import cn.erp.identity.AccessService;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wms")
class PutawayTaskController {
    private final AccessService access;
    private final PutawayTasks tasks;

    PutawayTaskController(AccessService access,PutawayTasks tasks) {
        this.access = access;
        this.tasks = tasks;
    }

    @GetMapping("/tasks")
    ApiResponse<?> page(HttpServletRequest request,@RequestParam(required = false) Long warehouseId,
                        @RequestParam(required = false) String status,@RequestParam(required = false) Long cursor,
                        @RequestParam(defaultValue = "30") int limit) {
        return ApiResponse.success(tasks.page(access.requireCompany(request,"wms_task:view"),warehouseId,status,cursor,limit));
    }

    @GetMapping("/tasks/{id}")
    ApiResponse<?> detail(HttpServletRequest request,@PathVariable long id) {
        return ApiResponse.success(tasks.detail(access.requireCompany(request,"wms_task:view"),id));
    }

    @PostMapping("/tasks/{id}/claim")
    ApiResponse<?> claim(HttpServletRequest request,@PathVariable long id,
                         @RequestHeader(name = "Idempotency-Key",required = false) String key,@RequestBody PutawayTasks.Revision input) {
        tasks.claim(operator(request),id,key,input);
        return ApiResponse.success(Map.of("id",Long.toString(id)));
    }

    @PostMapping("/tasks/{id}/lines/{lineId}/confirm")
    ApiResponse<?> confirm(HttpServletRequest request,@PathVariable long id,@PathVariable long lineId,
                           @RequestHeader(name = "Idempotency-Key",required = false) String key,@RequestBody PutawayTasks.Confirmation input) {
        tasks.confirm(operator(request),id,lineId,key,input);
        return ApiResponse.success(Map.of("id",Long.toString(id)));
    }

    @PostMapping("/tasks/{id}/finish")
    ApiResponse<?> finish(HttpServletRequest request,@PathVariable long id,
                          @RequestHeader(name = "Idempotency-Key",required = false) String key,@RequestBody PutawayTasks.Revision input) {
        tasks.finish(operator(request),id,key,input);
        return ApiResponse.success(Map.of("id",Long.toString(id)));
    }

    @GetMapping("/warehouses")
    ApiResponse<?> warehouses(HttpServletRequest request) {
        var actor = access.requireCompany(request,null);
        if (!actor.hasPermission("wms_task:view") && !actor.hasPermission("wms_task:configure"))
            throw new ApiException(403,"FORBIDDEN","没有查看仓储作业仓库的权限");
        return ApiResponse.success(Map.of("items",tasks.warehouses(actor)));
    }

    @PutMapping("/warehouses/{id}/task-mode")
    ApiResponse<?> mode(HttpServletRequest request,@PathVariable long id,@RequestBody PutawayTasks.Mode input) {
        var actor = access.requireCompany(request,"wms_task:configure");
        if (!actor.hasPermission("warehouse:update")) throw new ApiException(403,"FORBIDDEN","没有修改仓库的权限");
        tasks.mode(actor,id,input);
        return ApiResponse.success(Map.of("id",Long.toString(id),"taskMode",input.taskMode()));
    }

    private CompanyAccess operator(HttpServletRequest request) {
        var actor = access.requireCompany(request,"wms_task:execute");
        if (!actor.hasPermission("wms_task:view")) throw new ApiException(403,"FORBIDDEN","没有查看仓储作业任务的权限");
        return actor;
    }
}
