package cn.erp.invoicing;

import cn.erp.identity.AccessService;
import cn.erp.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/invoice-registrations")
class InvoiceController {
    private final AccessService access;
    private final InvoiceService service;
    InvoiceController(AccessService access,InvoiceService service){this.access=access;this.service=service;}
    @GetMapping ApiResponse<?> list(HttpServletRequest req,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="20") int limit){return ApiResponse.success(service.list(access.requireCompany(req,"invoice_registration:view"),cursor,limit));}
    @GetMapping("/{id}") ApiResponse<?> get(HttpServletRequest req,@PathVariable long id){return ApiResponse.success(service.get(access.requireCompany(req,"invoice_registration:view"),id));}
    @PostMapping ResponseEntity<?> create(HttpServletRequest req,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody InvoiceService.Input input){return ResponseEntity.status(201).body(ApiResponse.created(service.create(access.requireCompany(req,"invoice_registration:create"),key,input)));}
    @PostMapping("/{id}/void") ApiResponse<?> voidInvoice(HttpServletRequest req,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Version input){return ApiResponse.success(service.voidInvoice(access.requireCompany(req,"invoice_registration:void"),id,input.version(),key));}
    record Version(@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号不能为负") Long version){}
}
