package cn.erp.purchasefees;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;

@RestController
@RequestMapping("/api/v1/purchase-fee-documents")
class PurchaseFeeController {
    private final AccessService access;
    private final PurchaseFeeService service;
    private final PurchaseFeeQueries queries;
    PurchaseFeeController(AccessService access, PurchaseFeeService service, PurchaseFeeQueries queries) {
        this.access = access; this.service = service; this.queries = queries;
    }
    @GetMapping ApiResponse<?> list(HttpServletRequest request, @RequestParam(required = false) String status,
            @RequestParam(required = false) Long partnerId, @RequestParam(required = false) Long cursor, @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(queries.list(access.requireCompany(request, "purchase_fee_doc:view"), status, partnerId, cursor, limit));
    }
    @GetMapping("/{id}") ApiResponse<?> get(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.success(queries.get(access.requireCompany(request, "purchase_fee_doc:view"), id));
    }
    @GetMapping("/metadata") ApiResponse<?> metadata(HttpServletRequest request) {
        return ApiResponse.success(queries.metadata(access.requireCompany(request,"purchase_fee_doc:view")));
    }
    @PostMapping ResponseEntity<?> create(HttpServletRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody PurchaseFeeModels.Input input) {
        return ResponseEntity.status(201).body(ApiResponse.created(service.create(access.requireCompany(request, "purchase_fee_doc:create"), key, input)));
    }
    @PutMapping("/{id}") ApiResponse<?> update(HttpServletRequest request, @PathVariable long id, @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody PurchaseFeeModels.Input input) {
        return ApiResponse.success(service.update(access.requireCompany(request, "purchase_fee_doc:update"), id, key, input));
    }
    @PostMapping("/{id}/preview") ApiResponse<?> preview(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.success(service.preview(access.requireCompany(request, "purchase_fee_doc:view"), id));
    }
    @PostMapping("/{id}/{action:submit|approve|reject|post|void}") ApiResponse<?> command(HttpServletRequest request, @PathVariable long id, @PathVariable String action,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @Valid @RequestBody PurchaseFeeModels.Command input) {
        return ApiResponse.success(service.command(access.requireCompany(request, "purchase_fee_doc:" + (action.equals("reject") ? "approve" : action)), id, action, key, input));
    }
}
