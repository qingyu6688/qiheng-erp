package cn.erp.period;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/accounting-periods")
class PeriodController {
    private final AccessService access;
    private final PeriodService service;
    PeriodController(AccessService access,PeriodService service) {this.access=access;this.service=service;}
    @PostMapping("/{id}/close-check") ApiResponse<?> check(HttpServletRequest request,@PathVariable long id) {
        return ApiResponse.success(service.closeCheck(access.requireCompany(request,"period:close"),id));
    }
    @PostMapping("/{id}/{action:close|reopen}") ApiResponse<?> command(HttpServletRequest request,@PathVariable long id,@PathVariable String action,
        @RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody PeriodService.Version input) {
        var scope=access.requireCompany(request,"period:"+action);
        return ApiResponse.success(action.equals("close")?service.close(scope,id,key,input):service.reopen(scope,id,key,input));
    }
}
