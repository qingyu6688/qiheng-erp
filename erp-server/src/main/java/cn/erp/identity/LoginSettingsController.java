package cn.erp.identity;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.erp.shared.ApiResponse;

@RestController
@RequestMapping("/api/v1/login-settings")
class LoginSettingsController {
    private static final String PERMISSION = "tenant:login_settings";

    private final AccessService access;
    private final LoginSettingsService settings;

    LoginSettingsController(AccessService access, LoginSettingsService settings) {
        this.access = access;
        this.settings = settings;
    }

    @GetMapping
    ApiResponse<Map<String, Map<String, Object>>> read(HttpServletRequest request) {
        return ApiResponse.success(settings.read(access.requireTenant(request, PERMISSION).tenantId()));
    }

    @PutMapping
    ApiResponse<Map<String, Map<String, Object>>> save(@RequestBody LoginSettingsService.Update input,
                                                       HttpServletRequest request) {
        return ApiResponse.success(settings.save(access.requireTenant(request, PERMISSION), input));
    }
}
