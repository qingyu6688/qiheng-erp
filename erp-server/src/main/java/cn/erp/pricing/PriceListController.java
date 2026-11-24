package cn.erp.pricing;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/v1/price-lists","/api/v1/purchase-price-lists"})
class PriceListController {
    private final AccessService access;
    private final PriceListService prices;

    PriceListController(AccessService access, PriceListService prices) {
        this.access = access;
        this.prices = prices;
    }

    @GetMapping
    ApiResponse<PriceListService.Page> page(HttpServletRequest request, @RequestParam(required = false) String cursor,
                                               @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(prices.list(access.requireCompany(request, permission(request,"view")), cursor, limit,purchase(request)));
    }

    @GetMapping("/{id}")
    ApiResponse<PriceListService.View> get(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.success(prices.get(access.requireCompany(request, permission(request,"view")), id,purchase(request)));
    }

    @PostMapping
    ApiResponse<PriceListService.View> create(HttpServletRequest request, @RequestBody PriceListService.Input input) {
        return ApiResponse.success(prices.save(access.requireCompany(request, permission(request,"manage")), null, input,purchase(request)));
    }

    @PutMapping("/{id}")
    ApiResponse<PriceListService.View> update(HttpServletRequest request, @PathVariable long id,
                                                @RequestBody PriceListService.Input input) {
        return ApiResponse.success(prices.save(access.requireCompany(request, permission(request,"manage")), id, input,purchase(request)));
    }
    private static boolean purchase(HttpServletRequest request) { return request.getRequestURI().contains("/purchase-price-lists"); }
    private static String permission(HttpServletRequest request,String action) { return (purchase(request)?"purchase_price_list:":"price_list:")+action; }
}
