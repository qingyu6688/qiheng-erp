package cn.erp.fulfillment;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class ShipmentController {
    private final AccessService access;
    private final ShipmentService service;
    private final ShipmentQueries queries;

    ShipmentController(AccessService access,ShipmentService service,ShipmentQueries queries) {
        this.access=access;this.service=service;this.queries=queries;
    }

    @GetMapping("/shipments")
    ApiResponse<?> list(HttpServletRequest request,@RequestParam(required=false) String status,
            @RequestParam(defaultValue="false") boolean overdue,@RequestParam(defaultValue="false") boolean signOverdue,@RequestParam(required=false) Long customerId,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit) {
        return ApiResponse.success(queries.list(access.requireCompany(request,"shipment:view"),status,overdue||signOverdue,customerId,cursor,limit));
    }

    @GetMapping("/shipments/{id}")
    ApiResponse<?> get(HttpServletRequest request,@PathVariable long id) {
        return ApiResponse.success(queries.get(access.requireCompany(request,"shipment:view"),id));
    }

    @GetMapping("/shipment-lines")
    ApiResponse<?> returnable(HttpServletRequest request,@RequestParam(required=false) Long customerId,
            @RequestParam(defaultValue="false") boolean returnable,@RequestParam(required=false) String cursor,
            @RequestParam(defaultValue="20") int limit) {
        return ApiResponse.success(queries.returnable(access.requireCompany(request,"shipment:view"),customerId,returnable,cursor,limit));
    }

    @PostMapping("/shipments")
    ResponseEntity<?> create(HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false) String key,
            @Valid @RequestBody ShipmentModels.Input input) {
        return ResponseEntity.status(201).body(ApiResponse.created(service.create(access.requireCompany(request,"shipment:create"),key,input)));
    }

    @PutMapping("/shipments/{id}")
    ApiResponse<?> update(HttpServletRequest request,@PathVariable long id,@Valid @RequestBody ShipmentModels.Input input) {
        return ApiResponse.success(service.update(access.requireCompany(request,"shipment:update"),id,input));
    }

    @PutMapping("/shipments/{id}/logistics")
    ApiResponse<?> logistics(HttpServletRequest request,@PathVariable long id,@Valid @RequestBody ShipmentModels.Logistics input) {
        return ApiResponse.success(service.logistics(access.requireCompany(request,"shipment:update"),id,input));
    }

    @PostMapping("/shipments/{id}/submit")
    ApiResponse<?> submit(HttpServletRequest request,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,
            @Valid @RequestBody ShipmentModels.Version input) {
        return ApiResponse.success(service.submit(access.requireCompany(request,"shipment:submit"),id,key,input.version()));
    }

    @PostMapping("/shipments/{id}/void")
    ApiResponse<?> voidSubmitted(HttpServletRequest request,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,
            @Valid @RequestBody ShipmentModels.Version input) {
        return ApiResponse.success(service.voidSubmitted(access.requireCompany(request,"shipment:void"),id,key,input.version()));
    }

    @PostMapping("/shipments/{id}/post")
    ApiResponse<?> post(HttpServletRequest request,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,
            @Valid @RequestBody ShipmentModels.Version input) {
        return ApiResponse.success(service.post(access.requireCompany(request,"shipment:post"),id,key,input.version()));
    }

    @PostMapping("/shipments/{id}/sign")
    ApiResponse<?> sign(HttpServletRequest request,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,
            @Valid @RequestBody ShipmentModels.Signed input) {
        return ApiResponse.success(service.sign(access.requireCompany(request,"shipment:sign"),id,key,input));
    }

    @PostMapping("/shipments/{id}/resolve-diff")
    ApiResponse<?> resolve(HttpServletRequest request,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,
            @Valid @RequestBody ShipmentModels.Resolution input) {
        return ApiResponse.success(service.resolve(access.requireCompany(request,"shipment:resolve_diff"),id,key,input));
    }
}
