package cn.erp.customfield;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class CustomFieldController {
    private static final String MANAGE = "custom_field:manage";
    private final AccessService access;
    private final CustomFields fields;

    CustomFieldController(AccessService access, CustomFields fields) {
        this.access = access;
        this.fields = fields;
    }

    @GetMapping("/custom-fields/{entity}")
    ApiResponse<?> schema(HttpServletRequest request, @PathVariable String entity) {
        CustomFields.entity(entity);
        var permission = CustomFieldTypes.VIEW.get(entity);
        var actor = entity.equals("sku") || entity.equals("business_partner") ? access.requireTenant(request, permission)
                : access.requireCompany(request, permission);
        return ApiResponse.success(fields.fields(actor.tenantId(), entity));
    }

    @GetMapping("/sys/custom-fields/{entity}")
    ApiResponse<?> list(HttpServletRequest request, @PathVariable String entity) {
        return ApiResponse.success(fields.fields(access.requireCompany(request, MANAGE).tenantId(), entity, true));
    }

    @PutMapping("/sys/custom-fields/{entity}")
    ApiResponse<?> save(HttpServletRequest request, @PathVariable String entity, @RequestBody CustomFields.Input input) {
        return ApiResponse.success(fields.save(access.requireCompany(request, MANAGE), entity, input));
    }
}
