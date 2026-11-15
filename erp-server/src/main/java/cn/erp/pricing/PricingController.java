package cn.erp.pricing;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
}
