package cn.erp.mfg;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static cn.erp.mfg.MfgStockModels.*;

@RestController
@RequestMapping("/api/v1/mfg/{resource:issues|receipts}")
class MfgStockController {
    private final AccessService access;
    private final MfgStockDocuments documents;

    MfgStockController(AccessService access, MfgStockDocuments documents) {
        this.access = access;
        this.documents = documents;
    }

    @GetMapping
    ApiResponse<?> list(HttpServletRequest request, @PathVariable String resource,
                        @RequestParam(required = false) Long workOrderId, @RequestParam(required = false) Long warehouseId,
                        @RequestParam(required = false) String direction, @RequestParam(required = false) String status,
                        @RequestParam(required = false) Long cursor, @RequestParam(defaultValue = "30") int limit) {
        var type = resource(resource);
        return ApiResponse.success(documents.list(access.requireCompany(request, type.permission("view")), type,
                new Filter(workOrderId, warehouseId, direction, status, cursor, limit)));
    }

    @GetMapping("/{id}")
    ApiResponse<?> detail(HttpServletRequest request, @PathVariable String resource, @PathVariable long id) {
        var type = resource(resource);
        return ApiResponse.success(documents.detail(access.requireCompany(request, type.permission("view")), type, id));
    }

    @PostMapping
    ResponseEntity<?> create(HttpServletRequest request, @PathVariable String resource,
                             @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody Draft input) {
        var type = resource(resource);
        return ResponseEntity.status(201).body(ApiResponse.created(documents.create(
                access.requireCompany(request, type.permission("create")), type, key, input)));
    }

    @PutMapping("/{id}")
    ApiResponse<?> update(HttpServletRequest request, @PathVariable String resource, @PathVariable long id,
                          @RequestBody Draft input) {
        var type = resource(resource);
        return ApiResponse.success(documents.update(access.requireCompany(request, type.permission("update")), type, id, input));
    }

    @PostMapping("/{id}/{action:submit|approve|reject|post|cancel}")
    ApiResponse<?> command(HttpServletRequest request, @PathVariable String resource, @PathVariable long id,
                           @PathVariable String action, @RequestHeader(name = "Idempotency-Key", required = false) String key,
                           @RequestBody Revision input) {
        var type = resource(resource);
        return ApiResponse.success(documents.command(access.requireCompany(request, type.permission(action)), type, id, action, key, input));
    }

    private Type resource(String resource) { return "issues".equals(resource) ? Type.ISSUE : Type.RECEIPT; }
}
