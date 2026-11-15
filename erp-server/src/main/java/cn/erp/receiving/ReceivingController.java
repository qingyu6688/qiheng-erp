package cn.erp.receiving;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class ReceivingController {
    private final AccessService access;
    private final ReceivingService service;
    private final ReceivingQueries queries;
    ReceivingController(AccessService access,ReceivingService service,ReceivingQueries queries) {this.access=access;this.service=service;this.queries=queries;}
    private boolean returning(String resource) {return resource.equals("purchase-returns");}
    @GetMapping("/{resource:goods-receipts|purchase-returns}") ApiResponse<?> list(HttpServletRequest req,@PathVariable String resource,
        @RequestParam(required=false) Long supplierId,@RequestParam(required=false) String status,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="20") int limit) {
        boolean type=returning(resource);return ApiResponse.success(queries.list(access.requireCompany(req,ReceivingQueries.table(type)+":view"),type,supplierId,status,cursor,limit));
    }
    @GetMapping("/{resource:goods-receipts|purchase-returns}/{id}") ApiResponse<?> get(HttpServletRequest req,@PathVariable String resource,@PathVariable long id) {
        boolean type=returning(resource);return ApiResponse.success(queries.get(access.requireCompany(req,ReceivingQueries.table(type)+":view"),type,id));
    }
    @GetMapping("/goods-receipt-lines") ApiResponse<?> sources(HttpServletRequest req,@RequestParam(required=false) Long supplierId,@RequestParam(required=false) Long warehouseId,
        @RequestParam(defaultValue="true") boolean returnable,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="200") int limit) {
        return ApiResponse.success(queries.returnable(access.requireCompany(req,"goods_receipt:view"),supplierId,warehouseId,returnable,cursor,limit));
    }
    @PostMapping("/goods-receipts") ResponseEntity<?> createReceipt(HttpServletRequest req,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody ReceivingModels.ReceiptInput input) {
        return ResponseEntity.status(201).body(ApiResponse.created(service.createReceipt(access.requireCompany(req,"goods_receipt:create"),key,input)));
    }
    @PutMapping("/goods-receipts/{id}") ApiResponse<?> updateReceipt(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody ReceivingModels.ReceiptInput input) {
        return ApiResponse.success(service.updateReceipt(access.requireCompany(req,"goods_receipt:update"),id,input));
    }
    @PostMapping("/purchase-returns") ResponseEntity<?> createReturn(HttpServletRequest req,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody ReceivingModels.ReturnInput input) {
        return ResponseEntity.status(201).body(ApiResponse.created(service.createReturn(access.requireCompany(req,"purchase_return:create"),key,input)));
    }
    @PutMapping("/purchase-returns/{id}") ApiResponse<?> updateReturn(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody ReceivingModels.ReturnInput input) {
        return ApiResponse.success(service.updateReturn(access.requireCompany(req,"purchase_return:update"),id,input));
    }
    @PostMapping("/{resource:goods-receipts|purchase-returns}/{id}/{action:submit|void|post}") ApiResponse<?> command(HttpServletRequest req,@PathVariable String resource,@PathVariable long id,@PathVariable String action,
        @RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody ReceivingModels.Version input) {
        boolean type=returning(resource);return ApiResponse.success(service.command(access.requireCompany(req,ReceivingQueries.table(type)+":"+action),type,id,action,key,input.version()));
    }
}
