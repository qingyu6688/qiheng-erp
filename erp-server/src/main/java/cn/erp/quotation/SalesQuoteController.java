package cn.erp.quotation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;

@RestController
@RequestMapping("/api/v1/sales-quotes")
final class SalesQuoteController {
    private final AccessService access;
    private final SalesQuoteService service;
    private final SalesQuoteQueries queries;
    SalesQuoteController(AccessService access,SalesQuoteService service,SalesQuoteQueries queries) {this.access=access;this.service=service;this.queries=queries;}
    @GetMapping ApiResponse<?> list(HttpServletRequest request,@RequestParam(required=false) String status,
            @RequestParam(required=false) Long cursor,@RequestParam(defaultValue="20") int limit) {
        return ApiResponse.success(queries.list(access.requireCompany(request,"sales_quote:view"),status,cursor,limit));
    }
    @GetMapping("/{id}") ApiResponse<?> get(HttpServletRequest request,@PathVariable long id) {
        return ApiResponse.success(queries.get(access.requireCompany(request,"sales_quote:view"),id));
    }
    @GetMapping("/metadata") ApiResponse<?> metadata(HttpServletRequest request,@RequestParam(required=false) String search,@RequestParam(required=false) Long customerId) {
        return ApiResponse.success(queries.metadata(access.requireCompany(request,"sales_quote:view"),search,customerId));
    }
    @GetMapping("/sku-units/{id}") ApiResponse<?> units(HttpServletRequest request,@PathVariable long id) {
        return ApiResponse.success(queries.units(access.requireCompany(request,"sales_quote:view"),id));
    }
    @PostMapping("/preview") ApiResponse<?> preview(HttpServletRequest request,@Valid @RequestBody SalesQuoteModels.Input input) {
        return ApiResponse.success(service.preview(access.requireCompany(request,"sales_quote:view"),input));
    }
    @PostMapping ResponseEntity<?> create(HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody SalesQuoteModels.Input input) {
        return ResponseEntity.status(201).body(ApiResponse.created(service.create(access.requireCompany(request,"sales_quote:create"),key,input)));
    }
    @PutMapping("/{id}") ApiResponse<?> update(HttpServletRequest request,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody SalesQuoteModels.Input input) {
        return ApiResponse.success(service.update(access.requireCompany(request,"sales_quote:update"),id,key,input));
    }
    @PostMapping("/{id}/{action:submit|approve|reject|convert|void}") ApiResponse<?> command(HttpServletRequest request,@PathVariable long id,@PathVariable String action,
            @RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody SalesQuoteModels.Command input) {
        return ApiResponse.success(service.command(access.requireCompany(request,"sales_quote:"+(action.equals("reject")?"approve":action)),id,action,key,input));
    }
}
