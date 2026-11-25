package cn.erp.inventory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;

@RestController
@RequestMapping("/api/v1/cost-revaluations")
final class CostRevaluationController {
    private final AccessService access;
    private final CostRevaluationService service;
    CostRevaluationController(AccessService access,CostRevaluationService service) { this.access=access; this.service=service; }
    @GetMapping ApiResponse<?> list(HttpServletRequest request,@RequestParam(required=false) String status,
            @RequestParam(required=false) Long cursor,@RequestParam(defaultValue="20") int limit) {
        return ApiResponse.success(service.list(access.requireCompany(request,"cost_revaluation:view"),status,cursor,limit));
    }
    @GetMapping("/{id}") ApiResponse<?> get(HttpServletRequest request,@PathVariable long id) {
        return ApiResponse.success(service.get(access.requireCompany(request,"cost_revaluation:view"),id));
    }
    @GetMapping("/metadata") ApiResponse<?> metadata(HttpServletRequest request,@RequestParam(required=false) String search) {
        return ApiResponse.success(service.metadata(access.requireCompany(request,"cost_revaluation:view"),search));
    }
    @PostMapping ResponseEntity<?> create(HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false) String key,
            @Valid @RequestBody CostRevaluationModels.Input input) {
        return ResponseEntity.status(201).body(ApiResponse.created(service.create(access.requireCompany(request,"cost_revaluation:create"),key,input)));
    }
    @PostMapping("/{id}/{action:refresh|submit|approve|reject|post|void}") ApiResponse<?> command(HttpServletRequest request,@PathVariable long id,
            @PathVariable String action,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody CostRevaluationModels.Command input) {
        String permission="cost_revaluation:"+(action.equals("refresh")?"submit":action.equals("reject")?"approve":action);
        return ApiResponse.success(service.command(access.requireCompany(request,permission),id,action,key,input));
    }
}
