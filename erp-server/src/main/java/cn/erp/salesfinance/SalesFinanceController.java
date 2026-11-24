package cn.erp.salesfinance;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class SalesFinanceController {
    private final AccessService access;
    private final ReceiptService receipts;
    private final SalesReturnService returns;
    private final RefundApplicationService refunds;
    private final SalesFinanceQueries queries;
    SalesFinanceController(AccessService access,ReceiptService receipts,SalesReturnService returns,RefundApplicationService refunds,SalesFinanceQueries queries) {
        this.access=access;this.receipts=receipts;this.returns=returns;this.refunds=refunds;this.queries=queries;
    }
    @GetMapping("/receipts") ApiResponse<?> receipts(HttpServletRequest req,@RequestParam(required=false) Long partnerId,@RequestParam(required=false) String status,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="20") int limit) {
        return ApiResponse.success(receipts.list(access.requireCompany(req,"receipt:view"),partnerId,status,cursor,limit));
    }
    @GetMapping("/receipts/{id}") ApiResponse<?> receipt(HttpServletRequest req,@PathVariable long id) {return ApiResponse.success(receipts.get(access.requireCompany(req,"receipt:view"),id));}
    @PostMapping("/receipts") ResponseEntity<?> createReceipt(HttpServletRequest req,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody FinanceModels.Receipt input) {
        return ResponseEntity.status(201).body(ApiResponse.created(receipts.create(access.requireCompany(req,"receipt:create"),key,input)));
    }
    @PutMapping("/receipts/{id}") ApiResponse<?> updateReceipt(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody FinanceModels.Receipt input) {return ApiResponse.success(receipts.update(access.requireCompany(req,"receipt:update"),id,input));}
    @PostMapping("/receipts/{id}/{action:submit|return|withdraw|confirm}") ApiResponse<?> receiptCommand(HttpServletRequest req,@PathVariable long id,@PathVariable String action,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody FinanceModels.Version input) {
        return ApiResponse.success(receipts.command(access.requireCompany(req,"receipt:"+(action.equals("withdraw")?"return":action)),id,action,key,input));
    }
    @GetMapping("/sales-returns") ApiResponse<?> returns(HttpServletRequest req,@RequestParam(required=false) Long customerId,@RequestParam(required=false) String status,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="20") int limit) {return ApiResponse.success(returns.list(access.requireCompany(req,"sales_return:view"),customerId,status,cursor,limit));}
    @GetMapping("/sales-returns/{id}") ApiResponse<?> salesReturn(HttpServletRequest req,@PathVariable long id) {return ApiResponse.success(returns.get(access.requireCompany(req,"sales_return:view"),id));}
    @PostMapping("/sales-returns") ResponseEntity<?> createReturn(HttpServletRequest req,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody FinanceModels.ReturnInput input) {return ResponseEntity.status(201).body(ApiResponse.created(returns.create(access.requireCompany(req,"sales_return:create"),key,input)));}
    @PutMapping("/sales-returns/{id}") ApiResponse<?> updateReturn(HttpServletRequest req,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody FinanceModels.ReturnInput input) {return ApiResponse.success(returns.update(access.requireCompany(req,"sales_return:update"),id,key,input));}
    @GetMapping("/sales-returns/{id}/cost-preview") ApiResponse<?> returnCostPreview(HttpServletRequest req,@PathVariable long id) {return ApiResponse.success(returns.costPreview(access.requireCompany(req,"sales_return:view"),id));}
    @PostMapping("/sales-returns/{id}/{action:submit|approve|reject|void|post}") ApiResponse<?> returnCommand(HttpServletRequest req,@PathVariable long id,@PathVariable String action,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody FinanceModels.Version input) {
        return ApiResponse.success(returns.command(access.requireCompany(req,"sales_return:"+(action.equals("reject")?"approve":action)),id,action,key,input));
    }
    @GetMapping("/refund-requests") ApiResponse<?> refunds(HttpServletRequest req,@RequestParam(required=false) Long customerId,@RequestParam(required=false) String status,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="20") int limit) {return ApiResponse.success(refunds.list(access.requireCompany(req,"refund_request:view"),customerId,status,cursor,limit));}
    @GetMapping("/refund-requests/{id}") ApiResponse<?> refund(HttpServletRequest req,@PathVariable long id) {return ApiResponse.success(refunds.get(access.requireCompany(req,"refund_request:view"),id));}
    @PostMapping("/refund-requests") ResponseEntity<?> createRefund(HttpServletRequest req,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody FinanceModels.Refund input) {return ResponseEntity.status(201).body(ApiResponse.created(refunds.create(access.requireCompany(req,"refund_request:create"),key,input)));}
    @PutMapping("/refund-requests/{id}") ApiResponse<?> updateRefund(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody FinanceModels.Refund input) {return ApiResponse.success(refunds.update(access.requireCompany(req,"refund_request:update"),id,input));}
    @PostMapping("/refund-requests/{id}/{action:submit|approve|reject|cancel}") ApiResponse<?> refundCommand(HttpServletRequest req,@PathVariable long id,@PathVariable String action,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody FinanceModels.Version input) {
        return ApiResponse.success(refunds.command(access.requireCompany(req,"refund_request:"+action),id,action,key,input));
    }
    @PostMapping("/refund-requests/{id}/register-paid") ApiResponse<?> paid(HttpServletRequest req,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody FinanceModels.Paid input) {return ApiResponse.success(refunds.paid(access.requireCompany(req,"refund_request:register_paid"),id,key,input));}
    @GetMapping("/receivables") ApiResponse<?> receivables(HttpServletRequest req,@RequestParam(required=false) Long customerId,@RequestParam(defaultValue="false") boolean openOnly,@RequestParam(defaultValue="false") boolean invoiceable,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="20") int limit) {
        return ApiResponse.success(queries.receivables(access.requireCompany(req,"invoice_registration:view"),customerId,openOnly,cursor,limit,invoiceable));
    }
    @GetMapping("/payables") ApiResponse<?> payables(HttpServletRequest req,@RequestParam(required=false) Long supplierId,@RequestParam(defaultValue="false") boolean openOnly,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="20") int limit) {
        return ApiResponse.success(queries.payables(access.requireCompany(req,"settlement:view"),supplierId,openOnly,cursor,limit));
    }
    @GetMapping("/open-items") ApiResponse<?> openItems(HttpServletRequest req,@RequestParam(required=false) Long partnerId,@RequestParam(defaultValue="ar") String side,@RequestParam(defaultValue="true") boolean openOnly,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="20") int limit) {return ApiResponse.success(queries.openItems(access.requireCompany(req,"settlement:view"),partnerId,side,openOnly,cursor,limit));}
    @GetMapping("/settlement-allocations") ApiResponse<?> allocations(HttpServletRequest req,@RequestParam(required=false) Long partnerId,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="20") int limit) {return ApiResponse.success(queries.allocations(access.requireCompany(req,"settlement:view"),partnerId,cursor,limit));}
    @GetMapping("/settlement-allocations/{id}") ApiResponse<?> allocation(HttpServletRequest req,@PathVariable long id) {return ApiResponse.success(queries.allocation(access.requireCompany(req,"settlement:view"),id));}
    @PostMapping("/settlement-allocations") ApiResponse<?> allocate(HttpServletRequest req,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody FinanceModels.Allocations input) {return ApiResponse.success(queries.allocate(access.requireCompany(req,"settlement:allocate"),key,input));}
    @PostMapping("/settlement-allocations/{id}/revoke") ApiResponse<?> revoke(HttpServletRequest req,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody FinanceModels.Version input) {return ApiResponse.success(queries.revoke(access.requireCompany(req,"settlement:revoke"),id,key,input));}
}
