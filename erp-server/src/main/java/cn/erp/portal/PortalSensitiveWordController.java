package cn.erp.portal;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cs/sensitive-words")
class PortalSensitiveWordController {
    private final AccessService access;
    private final PortalContentSecurity security;

    PortalSensitiveWordController(AccessService access,PortalContentSecurity security) {
        this.access=access;
        this.security=security;
    }

    @GetMapping
    ApiResponse<?> list(HttpServletRequest request,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="30") int limit) {
        return ApiResponse.success(security.list(access.requireCompany(request,"portal_sensitive_word:view"),cursor,limit));
    }

    @PostMapping
    ApiResponse<?> create(HttpServletRequest request,@RequestBody PortalContentSecurity.Input input) {
        return ApiResponse.success(security.save(access.requireCompany(request,"portal_sensitive_word:manage"),null,input));
    }

    @PutMapping("/{id}")
    ApiResponse<?> update(HttpServletRequest request,@PathVariable long id,@RequestBody PortalContentSecurity.Input input) {
        return ApiResponse.success(security.save(access.requireCompany(request,"portal_sensitive_word:manage"),id,input));
    }

    @DeleteMapping("/{id}")
    ApiResponse<?> delete(HttpServletRequest request,@PathVariable long id,@RequestParam int version) {
        return ApiResponse.success(security.delete(access.requireCompany(request,"portal_sensitive_word:manage"),id,version));
    }
}
