package cn.erp.purchasing;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class RequisitionController {
    private final AccessService access;private final RequisitionService service;
    RequisitionController(AccessService access,RequisitionService service) {this.access=access;this.service=service;}
    @GetMapping("/purchase-requisitions") ApiResponse<?> list(HttpServletRequest req,@RequestParam(required=false) String status,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="20") int limit) {return ApiResponse.success(service.list(access.requireCompany(req,"purchase_requisition:view"),status,cursor,limit));}
    @GetMapping("/purchase-requisitions/{id}") ApiResponse<?> get(HttpServletRequest req,@PathVariable long id) {return ApiResponse.success(service.get(access.requireCompany(req,"purchase_requisition:view"),id));}
    @PostMapping("/purchase-requisitions") ResponseEntity<?> create(HttpServletRequest req,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody RequisitionModels.Input input) {return ResponseEntity.status(201).body(ApiResponse.created(service.create(access.requireCompany(req,"purchase_requisition:create"),key,input)));}
    @PutMapping("/purchase-requisitions/{id}") ApiResponse<?> edit(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody RequisitionModels.Input input) {return ApiResponse.success(service.edit(access.requireCompany(req,"purchase_requisition:update"),id,input));}
    @DeleteMapping("/purchase-requisitions/{id}") ApiResponse<?> delete(HttpServletRequest req,@PathVariable long id,@RequestParam long version) {service.delete(access.requireCompany(req,"purchase_requisition:delete"),id,version);return ApiResponse.success(null);}
    @PostMapping("/purchase-requisitions/{id}/{action:submit|withdraw|approve|reject|close|cancel}") ApiResponse<?> command(HttpServletRequest req,@PathVariable long id,@PathVariable String action,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody PurchaseOrderModels.Command input) {return ApiResponse.success(service.command(access.requireCompany(req,"purchase_requisition:"+action),id,action,key,input));}
    @GetMapping("/purchase-requisition-lines") ApiResponse<?> candidates(HttpServletRequest req,@RequestParam(defaultValue="true") boolean orderable,@RequestParam(defaultValue="200") int limit) {return ApiResponse.success(service.candidates(access.requireCompany(req,"purchase_requisition:view"),orderable,limit));}
}
