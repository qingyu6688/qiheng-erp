package cn.erp.salesorder;

import cn.erp.identity.AccessService;
import cn.erp.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class SalesOrderController {
    private final AccessService access;
    private final SalesOrderService service;
    private final SalesOrderQueries queries;
    private final SalesInventoryQueries inventory;

    SalesOrderController(AccessService access,SalesOrderService service,SalesOrderQueries queries,SalesInventoryQueries inventory) {
        this.access=access;this.service=service;this.queries=queries;this.inventory=inventory;
    }

    @GetMapping("/sales-orders")
    ApiResponse<?> list(HttpServletRequest req,@RequestParam(defaultValue="") String keyword,@RequestParam(required=false) String status,
            @RequestParam(required=false) String customerId,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit) {
        return ApiResponse.success(queries.list(access.requireCompany(req,"sales_order:view"),keyword,status,customerId,cursor,limit,req.getParameterMap()));
    }
    @GetMapping("/sales-orders/{id}")
    ApiResponse<?> get(HttpServletRequest req,@PathVariable long id) {return ApiResponse.success(queries.get(access.requireCompany(req,"sales_order:view"),id));}
    @PostMapping("/sales-orders")
    ResponseEntity<?> create(HttpServletRequest req,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody SalesOrderModels.Input input) {
        return ResponseEntity.status(201).body(ApiResponse.created(service.create(access.requireCompany(req,"sales_order:create"),key,input)));
    }
    @PutMapping("/sales-orders/{id}")
    ApiResponse<?> edit(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody SalesOrderModels.Input input) {
        return ApiResponse.success(service.edit(access.requireCompany(req,"sales_order:update"),id,input));
    }
    @DeleteMapping("/sales-orders/{id}")
    ApiResponse<?> delete(HttpServletRequest req,@PathVariable long id,@RequestParam long version) {service.delete(access.requireCompany(req,"sales_order:delete"),id,version);return ApiResponse.success(null);}

    @PostMapping("/sales-orders/{id}/{action:submit|approve|reject|withdraw|revoke-approval|cancel|reserve}")
    ApiResponse<?> command(HttpServletRequest req,@PathVariable long id,@PathVariable String action,@RequestHeader(value="Idempotency-Key",required=false) String key,
            @Valid @RequestBody SalesOrderModels.Command input) {
        String operation=action.replace('-','_');
        return ApiResponse.success(service.command(access.requireCompany(req,"sales_order:"+operation),id,operation,key,input));
    }
    @PostMapping("/sales-orders/{id}/close-lines")
    ApiResponse<?> close(HttpServletRequest req,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody SalesOrderModels.Close input) {
        return ApiResponse.success(service.close(access.requireCompany(req,"sales_order:close_lines"),id,key,input));
    }
    @PostMapping("/sales-orders/{id}/changes")
    ApiResponse<?> change(HttpServletRequest req,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody SalesOrderModels.Input input) {
        return ApiResponse.success(service.change(access.requireCompany(req,"sales_order:change"),id,key,input));
    }
    @PostMapping("/sales-orders/changes/{id}/{action:approve|reject}")
    ApiResponse<?> decide(HttpServletRequest req,@PathVariable long id,@PathVariable String action,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody SalesOrderModels.Command input) {
        return ApiResponse.success(service.decideChange(access.requireCompany(req,"sales_order:change_"+action),id,action,key,input));
    }
    @GetMapping("/sales-orders/{id}/reservations")
    ApiResponse<?> reservations(HttpServletRequest req,@PathVariable long id) {return ApiResponse.success(queries.reservations(access.requireCompany(req,"sales_order:view"),id));}

    @GetMapping("/sales-order-lines")
    ApiResponse<?> orderLines(HttpServletRequest req,@RequestParam(required=false) String customerId,@RequestParam(required=false) String warehouseId,
            @RequestParam(defaultValue="true") boolean shippable,@RequestParam(defaultValue="200") int limit) {
        return ApiResponse.success(inventory.orderLines(access.requireCompany(req,"sales_order:view"),customerId,warehouseId,shippable,limit));
    }
    @GetMapping("/inventory-balances")
    ApiResponse<?> balances(HttpServletRequest req,@RequestParam(required=false) String warehouseId,@RequestParam(defaultValue="") String keyword,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit,
            @RequestParam(defaultValue="false") boolean shortageOnly,@RequestParam(defaultValue="false") boolean hideZero,
            @RequestParam(required=false) Long skuId) {
        return ApiResponse.success(inventory.balances(access.requireCompany(req,"inventory:view"),warehouseId,keyword,cursor,limit,shortageOnly,hideZero,skuId));
    }
}
