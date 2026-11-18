package cn.erp.workbench;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class OrderRelatedDocumentsController {
    private final AccessService access;
    private final DocumentBrowser browser;
    private final OrderRelatedDocuments documents;

    OrderRelatedDocumentsController(AccessService access, DocumentBrowser browser, OrderRelatedDocuments documents) {
        this.access = access;
        this.browser = browser;
        this.documents = documents;
    }

    @GetMapping("/{kind:sales-orders|purchase-orders}/{orderId}/related/{resource}")
    ApiResponse<OrderRelatedDocuments.Page> page(HttpServletRequest request, @PathVariable String kind,
            @PathVariable long orderId, @PathVariable String resource, @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(documents.page(access.requireCompany(request, browser.permission(kind)), kind,
                orderId, resource, cursor, limit));
    }

    @GetMapping("/{kind:sales-orders|purchase-orders}/{orderId}/settlement-summary")
    ApiResponse<OrderRelatedDocuments.SettlementSummary> settlementSummary(HttpServletRequest request,
            @PathVariable String kind, @PathVariable long orderId) {
        return ApiResponse.success(documents.settlementSummary(access.requireCompany(request, browser.permission(kind)),
                kind, orderId));
    }
}
