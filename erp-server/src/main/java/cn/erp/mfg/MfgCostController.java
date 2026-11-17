package cn.erp.mfg;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/mfg")
class MfgCostController {
    private final AccessService access;
    private final MfgCostService costs;
    private final MfgReports reports;

    MfgCostController(AccessService access,MfgCostService costs,MfgReports reports) {
        this.access=access;this.costs=costs;this.reports=reports;
    }

    @GetMapping("/cost-periods")
    ApiResponse<?> periods(HttpServletRequest request,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="30") int limit) {
        return ApiResponse.success(costs.list(access.requireCompany(request,"mfg_cost:view"),cursor,limit));
    }

    @GetMapping("/cost-periods/{id}")
    ApiResponse<?> period(HttpServletRequest request,@PathVariable long id) {
        return ApiResponse.success(costs.detail(access.requireCompany(request,"mfg_cost:view"),id));
    }

    @PostMapping("/cost-periods/{id}/collect")
    ApiResponse<?> collect(HttpServletRequest request,@PathVariable long id,@RequestHeader(name="Idempotency-Key",required=false) String key,
                           @RequestBody MfgCostService.Collect input) {
        return ApiResponse.success(costs.collect(access.requireCompany(request,"mfg_cost:manage"),id,key,input));
    }

    @PostMapping("/cost-periods/{id}/allocate")
    ApiResponse<?> allocate(HttpServletRequest request,@PathVariable long id,@RequestHeader(name="Idempotency-Key",required=false) String key,
                            @RequestBody MfgCostService.Allocate input) {
        return ApiResponse.success(costs.allocate(access.requireCompany(request,"mfg_cost:manage"),id,key,input));
    }

    @PostMapping("/cost-periods/{id}/{action:calculate|recalculate}")
    ApiResponse<?> calculate(HttpServletRequest request,@PathVariable long id,@PathVariable String action,
                            @RequestHeader(name="Idempotency-Key",required=false) String key,@RequestBody MfgCostService.Revision input) {
        return ApiResponse.success(costs.calculate(access.requireCompany(request,"mfg_cost:manage"),id,action,key,input));
    }

    @PostMapping("/cost-periods/{id}/post")
    ApiResponse<?> post(HttpServletRequest request,@PathVariable long id,@RequestHeader(name="Idempotency-Key",required=false) String key,
                        @RequestBody MfgCostService.Revision input) {
        return ApiResponse.success(costs.post(access.requireCompany(request,"mfg_cost:post"),id,key,input));
    }

    @PostMapping("/cost-periods/{id}/reopen")
    ApiResponse<?> reopen(HttpServletRequest request,@PathVariable long id,@RequestHeader(name="Idempotency-Key",required=false) String key,
                          @RequestBody MfgCostService.Revision input) {
        return ApiResponse.success(costs.reopen(access.requireCompany(request,"mfg_cost:post"),id,key,input));
    }

    @GetMapping("/reports/{report:progress|material-variance|cost-detail}")
    ApiResponse<?> report(HttpServletRequest request,@PathVariable String report,@RequestParam(required=false) Long warehouseId,
                          @RequestParam(required=false) Long workOrderId,@RequestParam(required=false) Long periodId,
                          @RequestParam(required=false) Long cursor,@RequestParam(defaultValue="30") int limit) {
        return ApiResponse.success(reports.page(access.requireCompany(request,"mfg_report:view"),report,warehouseId,workOrderId,periodId,cursor,limit));
    }
}
