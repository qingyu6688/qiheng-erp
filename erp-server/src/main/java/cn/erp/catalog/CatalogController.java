package cn.erp.catalog;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class CatalogController {
    private final AccessService access;
    private final CatalogService catalog;
    private final SkuDetailsService details;

    CatalogController(AccessService access,CatalogService catalog,SkuDetailsService details) {
        this.access=access; this.catalog=catalog; this.details=details;
    }

    @GetMapping("/units")
    ApiResponse<CatalogModels.Page> listUnitInput(HttpServletRequest request,
            @RequestParam(required=false) String keyword,@RequestParam(required=false) String status,
            @RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor) {
        return ApiResponse.success(catalog.list(access.requireTenant(request,"unit:view"),"unit",keyword,status,limit,cursor));
    }

    @GetMapping("/units/{id}")
    ApiResponse<CatalogModels.Detail> detailUnitInput(HttpServletRequest request,@PathVariable long id) {
        return ApiResponse.success(catalog.detail(access.requireTenant(request,"unit:view"),"unit",id));
    }

    @PostMapping("/units")
    ResponseEntity<ApiResponse<CatalogModels.Detail>> createUnitInput(HttpServletRequest request,
            @RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody CatalogModels.UnitInput body) {
        var result=catalog.create(access.requireTenant(request,"unit:create"),"unit",key,body);
        return ResponseEntity.created(URI.create("/api/v1/units/"+result.id())).body(ApiResponse.created(result));
    }

    @PutMapping("/units/{id}")
    ApiResponse<CatalogModels.Detail> editUnitInput(HttpServletRequest request,@PathVariable long id,
            @Valid @RequestBody CatalogModels.UnitInput body) {
        return ApiResponse.success(catalog.edit(access.requireTenant(request,"unit:update"),"unit",id,body,body.version()));
    }

    @PostMapping("/units/{id}/{action:enable|disable}")
    ApiResponse<CatalogModels.Detail> statusUnitInput(HttpServletRequest request,@PathVariable long id,
            @PathVariable String action,@RequestHeader(value="Idempotency-Key",required=false) String key,
            @Valid @RequestBody CatalogModels.Version body) {
        return ApiResponse.success(catalog.changeStatus(access.requireTenant(request,"unit:update"),
                "unit",id,action,key,body.version()));
    }

    @GetMapping("/product-categories")
    ApiResponse<CatalogModels.Page> listCategoryInput(HttpServletRequest request,
            @RequestParam(required=false) String keyword,@RequestParam(required=false) String status,
            @RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor) {
        return ApiResponse.success(catalog.list(access.requireTenant(request,"product_category:view"),"product_category",keyword,status,limit,cursor));
    }

    @GetMapping("/product-categories/{id}")
    ApiResponse<CatalogModels.Detail> detailCategoryInput(HttpServletRequest request,@PathVariable long id) {
        return ApiResponse.success(catalog.detail(access.requireTenant(request,"product_category:view"),"product_category",id));
    }

    @PostMapping("/product-categories")
    ResponseEntity<ApiResponse<CatalogModels.Detail>> createCategoryInput(HttpServletRequest request,
            @RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody CatalogModels.CategoryInput body) {
        var result=catalog.create(access.requireTenant(request,"product_category:create"),"product_category",key,body);
        return ResponseEntity.created(URI.create("/api/v1/product-categories/"+result.id())).body(ApiResponse.created(result));
    }

    @PutMapping("/product-categories/{id}")
    ApiResponse<CatalogModels.Detail> editCategoryInput(HttpServletRequest request,@PathVariable long id,
            @Valid @RequestBody CatalogModels.CategoryInput body) {
        return ApiResponse.success(catalog.edit(access.requireTenant(request,"product_category:update"),"product_category",id,body,body.version()));
    }

    @PostMapping("/product-categories/{id}/{action:enable|disable}")
    ApiResponse<CatalogModels.Detail> statusCategoryInput(HttpServletRequest request,@PathVariable long id,
            @PathVariable String action,@RequestHeader(value="Idempotency-Key",required=false) String key,
            @Valid @RequestBody CatalogModels.Version body) {
        return ApiResponse.success(catalog.changeStatus(access.requireTenant(request,"product_category:update"),
                "product_category",id,action,key,body.version()));
    }

    @GetMapping("/products")
    ApiResponse<CatalogModels.Page> listProductInput(HttpServletRequest request,
            @RequestParam(required=false) String keyword,@RequestParam(required=false) String status,
            @RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor) {
        return ApiResponse.success(catalog.list(access.requireTenant(request,"product:view"),"product",keyword,status,limit,cursor));
    }

    @GetMapping("/products/{id}")
    ApiResponse<CatalogModels.Detail> detailProductInput(HttpServletRequest request,@PathVariable long id) {
        return ApiResponse.success(catalog.detail(access.requireTenant(request,"product:view"),"product",id));
    }

    @PostMapping("/products")
    ResponseEntity<ApiResponse<CatalogModels.Detail>> createProductInput(HttpServletRequest request,
            @RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody CatalogModels.ProductInput body) {
        var result=catalog.create(access.requireTenant(request,"product:create"),"product",key,body);
        return ResponseEntity.created(URI.create("/api/v1/products/"+result.id())).body(ApiResponse.created(result));
    }

    @PutMapping("/products/{id}")
    ApiResponse<CatalogModels.Detail> editProductInput(HttpServletRequest request,@PathVariable long id,
            @Valid @RequestBody CatalogModels.ProductInput body) {
        return ApiResponse.success(catalog.edit(access.requireTenant(request,"product:update"),"product",id,body,body.version()));
    }

    @PostMapping("/products/{id}/{action:enable|disable}")
    ApiResponse<CatalogModels.Detail> statusProductInput(HttpServletRequest request,@PathVariable long id,
            @PathVariable String action,@RequestHeader(value="Idempotency-Key",required=false) String key,
            @Valid @RequestBody CatalogModels.Version body) {
        return ApiResponse.success(catalog.changeStatus(access.requireTenant(request,"product:update"),
                "product",id,action,key,body.version()));
    }

    @GetMapping("/skus")
    ApiResponse<CatalogModels.Page> listSkuInput(HttpServletRequest request,
            @RequestParam(required=false) String keyword,@RequestParam(required=false) String status,
            @RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor) {
        return ApiResponse.success(catalog.list(access.requireTenant(request,"sku:view"),"sku",keyword,status,limit,cursor));
    }

    @GetMapping("/skus/{id}")
    ApiResponse<CatalogModels.Detail> detailSkuInput(HttpServletRequest request,@PathVariable long id) {
        return ApiResponse.success(catalog.detail(access.requireTenant(request,"sku:view"),"sku",id));
    }

    @PostMapping("/skus")
    ResponseEntity<ApiResponse<CatalogModels.Detail>> createSkuInput(HttpServletRequest request,
            @RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody CatalogModels.SkuInput body) {
        var result=catalog.create(access.requireTenant(request,"sku:create"),"sku",key,body);
        return ResponseEntity.created(URI.create("/api/v1/skus/"+result.id())).body(ApiResponse.created(result));
    }

    @PutMapping("/skus/{id}")
    ApiResponse<CatalogModels.Detail> editSkuInput(HttpServletRequest request,@PathVariable long id,
            @Valid @RequestBody CatalogModels.SkuInput body) {
        return ApiResponse.success(catalog.edit(access.requireTenant(request,"sku:update"),"sku",id,body,body.version()));
    }

    @PostMapping("/skus/{id}/{action:enable|disable}")
    ApiResponse<CatalogModels.Detail> statusSkuInput(HttpServletRequest request,@PathVariable long id,
            @PathVariable String action,@RequestHeader(value="Idempotency-Key",required=false) String key,
            @Valid @RequestBody CatalogModels.Version body) {
        return ApiResponse.success(catalog.changeStatus(access.requireTenant(request,"sku:update"),
                "sku",id,action,key,body.version()));
    }

    @GetMapping("/skus/by-barcode/{barcode}")
    ApiResponse<CatalogModels.Scan> scan(HttpServletRequest request,@PathVariable String barcode) {
        return ApiResponse.success(catalog.scan(access.requireTenant(request,"sku:view"),barcode));
    }

    @PutMapping("/skus/{id}/units")
    ApiResponse<CatalogModels.Detail> units(HttpServletRequest request,@PathVariable long id,
            @Valid @RequestBody CatalogModels.UnitsInput body) {
        return ApiResponse.success(details.units(access.requireTenant(request,"sku:update"),id,body));
    }

    @PutMapping("/skus/{id}/barcodes")
    ApiResponse<CatalogModels.Detail> barcodes(HttpServletRequest request,@PathVariable long id,
            @Valid @RequestBody CatalogModels.BarcodesInput body) {
        return ApiResponse.success(details.barcodes(access.requireTenant(request,"sku:update"),id,body));
    }
}
