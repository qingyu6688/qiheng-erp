package cn.erp.dashboard;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
class DashboardController {
    private static final String VIEW="dashboard:view";
    private final AccessService access;
    private final DashboardService service;

    DashboardController(AccessService access,DashboardService service) {this.access=access;this.service=service;}

    @GetMapping("/metrics")
    ApiResponse<?> metrics(HttpServletRequest request) {
        return ApiResponse.success(Map.of("items",service.available(access.requireCompany(request,VIEW))));
    }

    @GetMapping("/data")
    ApiResponse<?> data(HttpServletRequest request,@RequestParam(required=false) String period,
                        @RequestParam(required=false) String metrics,@RequestParam(defaultValue="false") boolean refresh) {
        return ApiResponse.success(service.data(access.requireCompany(request,VIEW),period,metrics,refresh));
    }

    @GetMapping("/layout")
    ApiResponse<?> layout(HttpServletRequest request) {return ApiResponse.success(service.layout(access.requireCompany(request,VIEW)));}

    @PutMapping("/layout")
    ApiResponse<?> save(HttpServletRequest request,@RequestBody DashboardService.LayoutInput input) {
        return ApiResponse.success(service.save(access.requireCompany(request,VIEW),input));
    }
}
