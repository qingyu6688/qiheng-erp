package cn.erp.pricing;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/price-lists")
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
        return ApiResponse.success(prices.list(access.requireCompany(request, "price_list:view"), cursor, limit));
    }

    @GetMapping("/{id}")
    ApiResponse<PriceListService.View> get(HttpServletRequest request, @PathVariable long id) {
        return ApiResponse.success(prices.get(access.requireCompany(request, "price_list:view"), id));
    }

    @PostMapping
    ApiResponse<PriceListService.View> create(HttpServletRequest request, @RequestBody PriceListService.Input input) {
        return ApiResponse.success(prices.save(access.requireCompany(request, "price_list:manage"), null, input));
    }

    @PutMapping("/{id}")
    ApiResponse<PriceListService.View> update(HttpServletRequest request, @PathVariable long id,
                                                @RequestBody PriceListService.Input input) {
        return ApiResponse.success(prices.save(access.requireCompany(request, "price_list:manage"), id, input));
    }
}
