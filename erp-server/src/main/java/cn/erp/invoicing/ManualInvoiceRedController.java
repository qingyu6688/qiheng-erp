package cn.erp.invoicing;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;

@RestController
@RequestMapping("/api/v1/manual-invoice-reds")
class ManualInvoiceRedController {
    private final AccessService access;
    private final ManualInvoiceRedService service;
    ManualInvoiceRedController(AccessService access, ManualInvoiceRedService service) { this.access = access; this.service = service; }

    @GetMapping
    ApiResponse<?> list(HttpServletRequest request, @RequestParam(required = false) Long invoiceId,
                        @RequestParam(required = false) String status, @RequestParam(required = false) Long cursor,
                        @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(service.list(access.requireCompany(request, "invoice_red:view"), invoiceId, status, cursor, limit));
    }

    @GetMapping("/{id}")
    ApiResponse<?> get(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.success(service.get(access.requireCompany(request, "invoice_red:view"), id));
    }

    @PostMapping
    ResponseEntity<?> create(HttpServletRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                             @Valid @RequestBody ManualInvoiceRedService.Input input) {
        return ResponseEntity.status(201).body(ApiResponse.created(service.create(access.requireCompany(request, "invoice_red:create"), key, input)));
    }

    @PostMapping("/{id}/{action:confirm|cancel}")
    ApiResponse<?> command(HttpServletRequest request, @PathVariable long id, @PathVariable String action,
                           @RequestHeader(value = "Idempotency-Key", required = false) String key,
                           @Valid @RequestBody ManualInvoiceRedService.Revision input) {
        return ApiResponse.success(service.command(access.requireCompany(request, "invoice_red:" + action), id, action, key, input));
    }
}
