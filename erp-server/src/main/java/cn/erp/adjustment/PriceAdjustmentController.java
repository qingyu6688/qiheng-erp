package cn.erp.adjustment;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;

@RestController
@RequestMapping("/api/v1/purchase-price-adjustments")
class PriceAdjustmentController {
    private final AccessService access;
    private final PriceAdjustmentService service;
    private final PriceAdjustmentQueries queries;
    PriceAdjustmentController(AccessService access, PriceAdjustmentService service, PriceAdjustmentQueries queries) {
        this.access = access; this.service = service; this.queries = queries;
    }
    @GetMapping ApiResponse<?> list(HttpServletRequest request, @RequestParam(required = false) String status,
            @RequestParam(required = false) Long partnerId, @RequestParam(required = false) Long cursor, @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(queries.list(access.requireCompany(request, "price_adjustment:view"), status, partnerId, cursor, limit));
    }
    @GetMapping("/{id}") ApiResponse<?> get(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.success(queries.get(access.requireCompany(request, "price_adjustment:view"), id));
    }
    @GetMapping("/metadata") ApiResponse<?> metadata(HttpServletRequest request) {
        return ApiResponse.success(queries.metadata(access.requireCompany(request,"price_adjustment:view")));
    }
    @PostMapping ResponseEntity<?> create(HttpServletRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody PriceAdjustmentModels.Input input) {
        return ResponseEntity.status(201).body(ApiResponse.created(service.create(access.requireCompany(request, "price_adjustment:create"), key, input)));
    }
    @PutMapping("/{id}") ApiResponse<?> update(HttpServletRequest request, @PathVariable long id, @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody PriceAdjustmentModels.Input input) {
        return ApiResponse.success(service.update(access.requireCompany(request, "price_adjustment:update"), id, key, input));
    }
    @PostMapping("/{id}/preview") ApiResponse<?> preview(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.success(service.preview(access.requireCompany(request, "price_adjustment:view"), id));
    }
    @PostMapping("/{id}/{action:submit|approve|reject|post|void}") ApiResponse<?> command(HttpServletRequest request, @PathVariable long id, @PathVariable String action,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @Valid @RequestBody PriceAdjustmentModels.Command input) {
        return ApiResponse.success(service.command(access.requireCompany(request, "price_adjustment:" + (action.equals("reject") ? "approve" : action)), id, action, key, input));
    }
}
