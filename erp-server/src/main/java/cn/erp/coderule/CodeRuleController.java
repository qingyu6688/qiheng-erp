package cn.erp.coderule;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sys/code-rules")
class CodeRuleController {
    private static final String MANAGE = "code_rule:manage";
    private final AccessService access;
    private final CodeRules rules;

    CodeRuleController(AccessService access, CodeRules rules) {
        this.access = access;
        this.rules = rules;
    }

    @GetMapping
    ApiResponse<?> list(HttpServletRequest request) { return ApiResponse.success(rules.list(access.requireCompany(request, MANAGE))); }

    @PutMapping
    ApiResponse<?> save(HttpServletRequest request, @RequestBody CodeRules.Input input) {
        return ApiResponse.success(rules.save(access.requireCompany(request, MANAGE), input));
    }

    @PostMapping("/preview")
    ApiResponse<?> preview(HttpServletRequest request, @RequestBody CodeRules.Preview input) {
        return ApiResponse.success(Map.of("code", rules.preview(access.requireCompany(request, MANAGE), input)));
    }
}
