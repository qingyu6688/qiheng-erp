package cn.erp.purchasing;

import cn.erp.identity.AccessService;
import cn.erp.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class PurchaseOrderController {
    private final AccessService access;
    private final PurchaseOrderService service;
    private final PurchaseOrderQueries queries;

    PurchaseOrderController(AccessService access,PurchaseOrderService service,PurchaseOrderQueries queries) {
        this.access=access;this.service=service;this.queries=queries;
    }

    @GetMapping("/purchase-orders")
    ApiResponse<?> list(HttpServletRequest req,@RequestParam(defaultValue="") String keyword,@RequestParam(required=false) String status,
            @RequestParam(required=false) String supplierId,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit) {
        return ApiResponse.success(queries.list(access.requireCompany(req,"purchase_order:view"),keyword,status,supplierId,cursor,limit,req.getParameterMap()));
    }
    @GetMapping("/purchase-orders/{id}")
    ApiResponse<?> get(HttpServletRequest req,@PathVariable long id) {return ApiResponse.success(queries.get(access.requireCompany(req,"purchase_order:view"),id));}
    @PostMapping("/purchase-orders")
    ResponseEntity<?> create(HttpServletRequest req,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody PurchaseOrderModels.Input input) {
        return ResponseEntity.status(201).body(ApiResponse.created(service.create(access.requireCompany(req,"purchase_order:create"),key,input)));
    }
    @PutMapping("/purchase-orders/{id}")
    ApiResponse<?> edit(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody PurchaseOrderModels.Input input) {
        return ApiResponse.success(service.edit(access.requireCompany(req,"purchase_order:update"),id,input));
    }
    @DeleteMapping("/purchase-orders/{id}")
    ApiResponse<?> delete(HttpServletRequest req,@PathVariable long id,@RequestParam long version) {service.delete(access.requireCompany(req,"purchase_order:delete"),id,version);return ApiResponse.success(null);}

    @PostMapping("/purchase-orders/{id}/{action:submit|approve|reject|withdraw|revoke-approval|cancel}")
    ApiResponse<?> command(HttpServletRequest req,@PathVariable long id,@PathVariable String action,@RequestHeader(value="Idempotency-Key",required=false) String key,
            @Valid @RequestBody PurchaseOrderModels.Command input) {
        String operation=action.replace('-','_');
        return ApiResponse.success(service.command(access.requireCompany(req,"purchase_order:"+operation),id,operation,key,input));
    }
    @PostMapping("/purchase-orders/{id}/close-lines")
    ApiResponse<?> close(HttpServletRequest req,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody PurchaseOrderModels.Close input) {
        return ApiResponse.success(service.close(access.requireCompany(req,"purchase_order:close_lines"),id,key,input));
    }
    @PostMapping("/purchase-orders/{id}/changes")
    ApiResponse<?> change(HttpServletRequest req,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody PurchaseOrderModels.Input input) {
        return ApiResponse.success(service.change(access.requireCompany(req,"purchase_order:change"),id,key,input));
    }
    @PostMapping("/purchase-orders/changes/{id}/{action:approve|reject}")
    ApiResponse<?> decide(HttpServletRequest req,@PathVariable long id,@PathVariable String action,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody PurchaseOrderModels.Command input) {
        return ApiResponse.success(service.decideChange(access.requireCompany(req,"purchase_order:change_"+action),id,action,key,input));
    }
    @GetMapping("/purchase-order-lines")
    ApiResponse<?> orderLines(HttpServletRequest req,@RequestParam(required=false) String supplierId,@RequestParam(required=false) String warehouseId,
            @RequestParam(defaultValue="true") boolean receivable,@RequestParam(defaultValue="200") int limit) {
        return ApiResponse.success(queries.candidates(access.requireCompany(req,"purchase_order:view"),supplierId,warehouseId,receivable,limit));
    }
}
