package cn.erp.purchasefinance;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class TreasuryController {
    private final AccessService access;
    private final PaymentService payments;
    private final AccountTransferService transfers;
    TreasuryController(AccessService access,PaymentService payments,AccountTransferService transfers) {this.access=access;this.payments=payments;this.transfers=transfers;}
    @GetMapping("/payments") ApiResponse<?> payments(HttpServletRequest request,@RequestParam(required=false) Long partnerId,@RequestParam(required=false) String status,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="20") int limit) {return ApiResponse.success(payments.list(access.requireCompany(request,"payment:view"),partnerId,status,cursor,limit));}
    @GetMapping("/payments/{id}") ApiResponse<?> payment(HttpServletRequest request,@PathVariable long id) {return ApiResponse.success(payments.get(access.requireCompany(request,"payment:view"),id));}
    @PostMapping("/payments") ResponseEntity<?> createPayment(HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody TreasuryModels.Payment input) {return ResponseEntity.status(201).body(ApiResponse.created(payments.create(access.requireCompany(request,"payment:create"),key,input)));}
    @PutMapping("/payments/{id}") ApiResponse<?> updatePayment(HttpServletRequest request,@PathVariable long id,@Valid @RequestBody TreasuryModels.Payment input) {return ApiResponse.success(payments.update(access.requireCompany(request,"payment:update"),id,input));}
    @PostMapping("/payments/{id}/{action:submit|return|confirm}") ApiResponse<?> paymentCommand(HttpServletRequest request,@PathVariable long id,@PathVariable String action,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody TreasuryModels.Version input) {return ApiResponse.success(payments.command(access.requireCompany(request,"payment:"+action),id,action,key,input));}
    @GetMapping("/account-transfers") ApiResponse<?> transfers(HttpServletRequest request,@RequestParam(required=false) String status,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="20") int limit) {return ApiResponse.success(transfers.list(access.requireCompany(request,"account_transfer:view"),status,cursor,limit));}
    @GetMapping("/account-transfers/{id}") ApiResponse<?> transfer(HttpServletRequest request,@PathVariable long id) {return ApiResponse.success(transfers.get(access.requireCompany(request,"account_transfer:view"),id));}
    @PostMapping("/account-transfers") ResponseEntity<?> createTransfer(HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody TreasuryModels.Transfer input) {return ResponseEntity.status(201).body(ApiResponse.created(transfers.create(access.requireCompany(request,"account_transfer:create"),key,input)));}
    @PutMapping("/account-transfers/{id}") ApiResponse<?> updateTransfer(HttpServletRequest request,@PathVariable long id,@Valid @RequestBody TreasuryModels.Transfer input) {return ApiResponse.success(transfers.update(access.requireCompany(request,"account_transfer:update"),id,input));}
    @PostMapping("/account-transfers/{id}/{action:submit|return|confirm}") ApiResponse<?> transferCommand(HttpServletRequest request,@PathVariable long id,@PathVariable String action,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody TreasuryModels.Version input) {return ApiResponse.success(transfers.command(access.requireCompany(request,"account_transfer:"+action),id,action,key,input));}
}
