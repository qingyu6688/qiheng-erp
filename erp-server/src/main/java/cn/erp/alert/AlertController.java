package cn.erp.alert;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/alerts")
class AlertController {
    private final AccessService access;
    private final AlertRules rules;
    private final AlertEvents events;
    private final AlertSafetyStock safety;

    AlertController(AccessService access,AlertRules rules,AlertEvents events,AlertSafetyStock safety) {
        this.access=access;this.rules=rules;this.events=events;this.safety=safety;
    }

    @GetMapping
    ApiResponse<?> list(HttpServletRequest request,@RequestParam(required=false) String rule,@RequestParam(required=false) String level,
                        @RequestParam(required=false) String status,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="30") int limit) {
        return ApiResponse.success(events.list(access.requireCompany(request,"alert:view"),rule,level,status,cursor,limit));
    }

    @GetMapping("/unhandled-count")
    ApiResponse<?> count(HttpServletRequest request) {
        return ApiResponse.success(Map.of("total",events.unhandled(access.requireCompany(request,"alert:view"))));
    }

    @GetMapping("/{id:[0-9]+}")
    ApiResponse<?> detail(HttpServletRequest request,@PathVariable long id) {
        return ApiResponse.success(events.detail(access.requireCompany(request,"alert:view"),id));
    }

    @PostMapping("/{id:[0-9]+}/{action:acknowledge|ignore|resolve}")
    ApiResponse<?> handle(HttpServletRequest request,@PathVariable long id,@PathVariable String action,
                          @RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody AlertEvents.Handle body) {
        return ApiResponse.success(events.handle(access.requireCompany(request,"alert:handle"),id,action,key,body));
    }

    @GetMapping("/rules")
    ApiResponse<?> rules(HttpServletRequest request) {
        return ApiResponse.success(Map.of("items",rules.list(access.requireCompany(request,"alert:configure"))));
    }

    @GetMapping("/receiver-options")
    ApiResponse<?> receivers(HttpServletRequest request,@RequestParam String type,@RequestParam(required=false) String keyword) {
        return ApiResponse.success(Map.of("items",rules.receiverOptions(access.requireCompany(request,"alert:configure"),type,keyword)));
    }

    @PutMapping("/rules/{code}")
    ApiResponse<?> update(HttpServletRequest request,@PathVariable String code,@RequestBody AlertRules.Change body) {
        return ApiResponse.success(rules.update(access.requireCompany(request,"alert:configure"),code,body));
    }

    @GetMapping("/safety-stock")
    ApiResponse<?> supplies(HttpServletRequest request,@RequestParam(required=false) String keyword,@RequestParam(required=false) Long cursor,
                            @RequestParam(defaultValue="30") int limit) {
        return ApiResponse.success(safety.list(access.requireCompany(request,"alert:configure"),keyword,cursor,limit));
    }

    @PutMapping("/safety-stock/{sku}")
    ApiResponse<?> saveSupply(HttpServletRequest request,@PathVariable long sku,@RequestBody AlertSafetyStock.Input body) {
        return ApiResponse.success(safety.update(access.requireCompany(request,"alert:configure"),sku,body));
    }
}
