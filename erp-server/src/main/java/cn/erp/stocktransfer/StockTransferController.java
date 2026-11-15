package cn.erp.stocktransfer;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/stock-transfers")
class StockTransferController {
    private final AccessService access;
    private final StockTransferService service;
    StockTransferController(AccessService access,StockTransferService service){this.access=access;this.service=service;}
    @GetMapping ApiResponse<?> list(HttpServletRequest request,@RequestParam(required=false) String status,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="20") int limit){return ApiResponse.success(service.list(access.requireCompany(request,"stock_transfer:view"),status,cursor,limit));}
    @GetMapping("/{id}") ApiResponse<?> get(HttpServletRequest request,@PathVariable long id){return ApiResponse.success(service.get(access.requireCompany(request,"stock_transfer:view"),id));}
    @PostMapping ResponseEntity<?> create(HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody StockTransferModels.Input input){return ResponseEntity.status(201).body(ApiResponse.created(service.create(access.requireCompany(request,"stock_transfer:create"),key,input)));}
    @PutMapping("/{id}") ApiResponse<?> edit(HttpServletRequest request,@PathVariable long id,@Valid @RequestBody StockTransferModels.Input input){return ApiResponse.success(service.update(access.requireCompany(request,"stock_transfer:update"),id,input));}
    @PostMapping("/{id}/{action:submit|ship|void}") ApiResponse<?> command(HttpServletRequest request,@PathVariable long id,@PathVariable String action,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody StockTransferModels.Version input){return ApiResponse.success(service.command(access.requireCompany(request,"stock_transfer:"+action),id,action,key,input));}
    @PostMapping("/{id}/receive") ApiResponse<?> receive(HttpServletRequest request,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody StockTransferModels.Receive input){return ApiResponse.success(service.receive(access.requireCompany(request,"stock_transfer:receive"),id,key,input));}
    @PostMapping("/{id}/resolve-diff") ApiResponse<?> difference(HttpServletRequest request,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody StockTransferModels.Difference input){return ApiResponse.success(service.difference(access.requireCompany(request,"stock_transfer:resolve_diff"),id,key,input));}
}
