package cn.erp.pricing;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/pricing")
class PricingController {
    private final AccessService access;
    private final PricingService service;

    PricingController(AccessService access,PricingService service) {this.access=access;this.service=service;}

    @PostMapping("/preview")
    ApiResponse<PricingModels.Preview> preview(HttpServletRequest request,@Valid @RequestBody PricingModels.PreviewRequest input) {
        String permission=input.documentType().equals("sales_order")?"sales_order:create":"purchase_order:create";
        return ApiResponse.success(service.preview(access.requireCompany(request,permission),input));
    }

    @GetMapping("/default-price")
    ApiResponse<PricingModels.DefaultPrice> defaultPrice(HttpServletRequest request,@RequestParam String partnerId,
            @RequestParam String skuId,@RequestParam String unitId,@RequestParam String qty,
            @RequestParam(required=false) LocalDate businessDate,@RequestParam(required=false) Boolean priceTaxInclusive) {
        return ApiResponse.success(service.defaultPrice(access.requireCompany(request,"sales_order:create"),
                partnerId,skuId,unitId,qty,businessDate,priceTaxInclusive));
    }
}
