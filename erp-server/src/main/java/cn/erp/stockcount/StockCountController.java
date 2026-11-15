package cn.erp.stockcount;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/stock-counts")
class StockCountController {
    private final AccessService access;
    private final StockCountService service;
    StockCountController(AccessService access,StockCountService service){this.access=access;this.service=service;}
    @GetMapping ApiResponse<?> list(HttpServletRequest request,@RequestParam(required=false) String status,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="20") int limit){return ApiResponse.success(service.list(access.requireCompany(request,"stock_count:view"),status,cursor,limit));}
    @GetMapping("/{id}") ApiResponse<?> get(HttpServletRequest request,@PathVariable long id){return ApiResponse.success(service.get(access.requireCompany(request,"stock_count:view"),id));}
    @PostMapping ResponseEntity<?> create(HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody StockCountModels.Document input){return ResponseEntity.status(201).body(ApiResponse.created(service.create(access.requireCompany(request,"stock_count:create"),key,input)));}
    @PutMapping("/{id}") ApiResponse<?> update(HttpServletRequest request,@PathVariable long id,@Valid @RequestBody StockCountModels.Document input){return ApiResponse.success(service.update(access.requireCompany(request,"stock_count:update"),id,input));}
    @PutMapping("/{id}/counts") ApiResponse<?> counts(HttpServletRequest request,@PathVariable long id,@Valid @RequestBody StockCountModels.Counts input){return ApiResponse.success(service.counts(access.requireCompany(request,"stock_count:record_counts"),id,input));}
    @PostMapping("/{id}/{action:start|submit|approve|reject|post|void}") ApiResponse<?> command(HttpServletRequest request,@PathVariable long id,@PathVariable String action,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody StockCountModels.Version input){return ApiResponse.success(service.command(access.requireCompany(request,"stock_count:"+action),id,action,key,input));}
}
