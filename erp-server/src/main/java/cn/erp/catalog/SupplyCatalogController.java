package cn.erp.catalog;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class SupplyCatalogController {
    private final AccessService access;
    private final SupplyCatalogService catalog;

    SupplyCatalogController(AccessService access,SupplyCatalogService catalog) {this.access=access;this.catalog=catalog;}

    @GetMapping("/brands")
    ApiResponse<?> brands(HttpServletRequest request,@RequestParam(required=false) String keyword,@RequestParam(defaultValue="false") boolean activeOnly) {
        return ApiResponse.success(catalog.brands(access.requireTenant(request,"brand:view"),keyword,activeOnly));
    }

    @PutMapping("/brands")
    ApiResponse<?> brand(HttpServletRequest request,@RequestBody SupplyCatalogService.BrandInput input) {
        return ApiResponse.success(catalog.saveBrand(access.requireTenant(request,"brand:manage"),input));
    }

    @GetMapping("/skus/{id}/tracking")
    ApiResponse<?> sku(HttpServletRequest request,@PathVariable long id) {
        return ApiResponse.success(catalog.sku(access.requireTenant(request,"sku:view"),id));
    }

    @PutMapping("/skus/{id}/tracking")
    ApiResponse<?> saveSku(HttpServletRequest request,@PathVariable long id,@RequestBody SupplyCatalogService.SkuInput input) {
        var actor=access.requireTenant(request,"sku_tracking:manage");
        if(!actor.hasPermission("sku:update"))throw new ApiException(403,"FORBIDDEN","没有维护商品的权限");
        return ApiResponse.success(catalog.saveSku(actor,id,input));
    }
}
