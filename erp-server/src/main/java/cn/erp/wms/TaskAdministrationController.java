package cn.erp.wms;

import cn.erp.identity.AccessService;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
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
@RequestMapping("/api/v1/wms/task-admin")
class TaskAdministrationController {
    private final AccessService access;
    private final TaskAdministration service;

    TaskAdministrationController(AccessService access,TaskAdministration service) {
        this.access=access;
        this.service=service;
    }

    @GetMapping
    ApiResponse<?> page(HttpServletRequest request,@RequestParam(required=false) Long warehouseId,
                         @RequestParam(required=false) String type,@RequestParam(required=false) String status,
                         @RequestParam(required=false) String assignee,@RequestParam(required=false) Long cursor,
                         @RequestParam(defaultValue="30") int limit) {
        return ApiResponse.success(service.page(access.requireCompany(request,"wms_task:view"),warehouseId,type,status,assignee,cursor,limit));
    }

    @GetMapping("/statistics")
    ApiResponse<?> statistics(HttpServletRequest request,@RequestParam(required=false) Long warehouseId,
                               @RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to) {
        var actor=access.requireCompany(request,"wms_task:view");
        return ApiResponse.success(Map.of("items",service.statistics(actor,warehouseId,from,to),
                "myPendingToday",service.todayPendingCount(actor,warehouseId)));
    }

    @GetMapping("/{id}/assignees")
    ApiResponse<?> assignees(HttpServletRequest request,@PathVariable long id,@RequestParam(required=false) String keyword) {
        return ApiResponse.success(Map.of("items",service.assignees(write(request),id,keyword)));
    }

    @PostMapping("/{id}/assign")
    ApiResponse<?> assign(HttpServletRequest request,@PathVariable long id,@RequestHeader(name="Idempotency-Key",required=false) String key,
                          @RequestBody TaskAdministration.Assignment input) {
        return ApiResponse.success(Map.of("id",service.assign(write(request),id,key,input)));
    }

    @PostMapping("/{id}/cancel")
    ApiResponse<?> cancel(HttpServletRequest request,@PathVariable long id,@RequestHeader(name="Idempotency-Key",required=false) String key,
                          @RequestBody WmsScopes.Revision input) {
        return ApiResponse.success(Map.of("id",service.cancel(write(request),id,key,input)));
    }

    private CompanyAccess write(HttpServletRequest request) {
        var actor=access.requireCompany(request,"wms_task:assign");
        WmsScopes.permission(actor,"wms_task:view");
        return actor;
    }
}
