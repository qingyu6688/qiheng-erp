package cn.erp.masterdata;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class WarehouseController {
    private final AccessService access;
    private final WarehouseService service;
    private final cn.erp.shared.CommandResults commandResults;

    WarehouseController(AccessService access, WarehouseService service, cn.erp.shared.CommandResults commandResults) {
        this.access = access;
        this.service = service;
        this.commandResults = commandResults;
    }

    @GetMapping("/warehouses")
    ApiResponse<WarehouseModels.Page> list(HttpServletRequest request,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "false") boolean withTotal) {
        return ApiResponse.success(service.list(access.requireCompany(request, "warehouse:view"),
                keyword, status, limit, cursor, withTotal));
    }

    @GetMapping("/warehouses/{id}")
    ApiResponse<WarehouseModels.View> get(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.success(service.get(access.requireCompany(request, "warehouse:view"), id));
    }

    @PostMapping("/warehouses")
    ResponseEntity<ApiResponse<WarehouseModels.View>> create(HttpServletRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody WarehouseModels.Create input) {
        var result = service.create(access.requireCompany(request, "warehouse:create"), key, input);
        return ResponseEntity.created(URI.create("/api/v1/warehouses/" + result.id()))
                .body(ApiResponse.created(result));
    }

    @PutMapping("/warehouses/{id}")
    ApiResponse<WarehouseModels.View> update(HttpServletRequest request, @PathVariable long id,
            @Valid @RequestBody WarehouseModels.Edit input) {
        return ApiResponse.success(service.edit(access.requireCompany(request, "warehouse:update"), id, input));
    }

    @PostMapping("/warehouses/{id}/{action:enable|disable}")
    ApiResponse<WarehouseModels.View> changeStatus(HttpServletRequest request, @PathVariable long id,
            @PathVariable String action, @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody WarehouseModels.Version input) {
        return ApiResponse.success(service.changeStatus(access.requireCompany(request, "warehouse:update"),
                id, action, key, input.version()));
    }

    @GetMapping("/command-results")
    ApiResponse<?> result(HttpServletRequest request,
            @RequestParam String operation, @RequestParam String key) {
        if (!operation.startsWith("warehouse.")) {
            String permission = switch (operation) {
                case "partner.create" -> "partner:create";
                case "partner.enable", "partner.disable" -> "partner:update";
                case "partner_contact.create" -> "partner_contact:create";
                case "cash_account.create" -> "cash_account:create";
                case "cash_account.enable", "cash_account.disable" -> "cash_account:update";
                case "income_expense_item.create" -> "income_expense_item:create";
                case "period.open" -> "period:open";
                case "period.close", "period.reopen" -> operation.replace('.', ':');
                case "stock_transfer.create", "stock_transfer.submit", "stock_transfer.ship", "stock_transfer.receive", "stock_transfer.resolve_diff", "stock_transfer.void",
                     "stock_count.create", "stock_count.start", "stock_count.submit", "stock_count.approve", "stock_count.reject", "stock_count.post", "stock_count.void",
                     "reversal.create", "reversal.approve", "reversal.post" -> operation.replace('.', ':');
                case "reversal.reject" -> "reversal:approve";
                case "reconciliation.run" -> "reconciliation:run";
                case "outbox.replay" -> "admin:outbox";
                case "unit.create" -> "unit:create";
                case "product_category.create" -> "product_category:create";
                case "product.create" -> "product:create";
                case "sku.create" -> "sku:create";
                case "sku.enable", "sku.disable" -> "sku:update";
                case "unit.enable", "unit.disable" -> "unit:update";
                case "product_category.enable", "product_category.disable" -> "product_category:update";
                case "product.enable", "product.disable" -> "product:update";
                case "company.create" -> "company:create";
                case "company.enable", "company.disable" -> "company:update";
                case "user.create" -> "user:create";
                case "user.enable", "user.disable", "user.unlock" -> "user:update";
                case "user.reset_password" -> "user:reset_password";
                case "role.create" -> "role:create";
                case "receipt.withdraw" -> "receipt:return";
                case "purchase_requisition.create", "purchase_requisition.submit", "purchase_requisition.withdraw", "purchase_requisition.approve", "purchase_requisition.reject", "purchase_requisition.close", "purchase_requisition.cancel",
                     "purchase_order.create", "purchase_order.submit", "purchase_order.withdraw", "purchase_order.approve", "purchase_order.reject", "purchase_order.revoke_approval", "purchase_order.close_lines", "purchase_order.cancel", "purchase_order.change", "purchase_order.change_approve", "purchase_order.change_reject",
                     "goods_receipt.create", "goods_receipt.submit", "goods_receipt.void", "goods_receipt.post", "purchase_return.create", "purchase_return.submit", "purchase_return.void", "purchase_return.post",
                     "payment.create", "payment.submit", "payment.return", "payment.confirm", "account_transfer.create", "account_transfer.submit", "account_transfer.return", "account_transfer.confirm",
                     "stock_adjustment.create", "stock_adjustment.submit", "stock_adjustment.approve", "stock_adjustment.reject", "stock_adjustment.post", "stock_adjustment.void",
                     "opening_entry.create", "opening_entry.approve", "opening_entry.post", "opening_entry.void" -> operation.replace('.', ':');
                case "sales_order.create" -> "sales_order:create";
                case "sales_order.submit", "sales_order.approve", "sales_order.reject", "sales_order.withdraw",
                     "sales_order.revoke_approval", "sales_order.close_lines", "sales_order.cancel", "sales_order.reserve",
                     "sales_order.change", "sales_order.change_approve", "sales_order.change_reject" -> operation.replace('.', ':');
                case "shipment.create", "shipment.submit", "shipment.void", "shipment.post", "shipment.sign", "shipment.resolve_diff",
                     "receipt.create", "receipt.submit", "receipt.return", "receipt.confirm",
                     "sales_return.create", "sales_return.submit", "sales_return.void", "sales_return.post",
                     "refund_request.create", "refund_request.submit", "refund_request.approve", "refund_request.reject",
                     "refund_request.cancel", "refund_request.register_paid", "settlement.allocate", "settlement.revoke",
                     "invoice_registration.create", "invoice_registration.void" -> operation.replace('.', ':');
                default -> throw new ApiException(404, "NOT_FOUND", "未找到该操作结果");
            };
            var scope = operation.startsWith("cash_account.") || operation.startsWith("period.") || operation.equals("reconciliation.run")
                    || operation.matches("(?:sales_order|shipment|receipt|sales_return|refund_request|settlement|invoice_registration)\\..+")
                    || operation.matches("(?:purchase_requisition|purchase_order|goods_receipt|purchase_return|payment|account_transfer|stock_adjustment|opening_entry|stock_transfer|stock_count|reversal)\\..+")
                    ? access.requireCompany(request, permission) : access.requireTenant(request, permission);
            return ApiResponse.success(commandResults.get(scope, operation, key));
        }
        String permission = switch (operation) {
            case "warehouse.create" -> "warehouse:create";
            case "warehouse.disable", "warehouse.enable" -> "warehouse:update";
            default -> throw new ApiException(404, "NOT_FOUND", "未找到该操作结果");
        };
        return ApiResponse.success(service.result(access.requireCompany(request, permission), operation, key));
    }
}
