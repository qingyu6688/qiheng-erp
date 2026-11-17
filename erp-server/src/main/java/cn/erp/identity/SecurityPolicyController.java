package cn.erp.identity;

import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import cn.erp.shared.ApiResponse;

@RestController
class SecurityPolicyController {
    private static final String POLICY="/api/v1/sys/security-policy";
    private static final String USER="/api/v1/sys/users/{userId}";
    private static final String SSO="/api/v1/login-settings/sso";
    private final AccessService access;
    private final SecurityPolicyService policies;
    private final SsoSettings settings;
    private final SsoIdentities identities;
    private final SsoWebClient oauth;
    private final LdapLoginClient ldap;
    private final MfaService mfa;

    SecurityPolicyController(AccessService access,SecurityPolicyService policies,SsoSettings settings,SsoIdentities identities,
                             SsoWebClient oauth,LdapLoginClient ldap,MfaService mfa) {
        this.access=access;
        this.policies=policies;
        this.settings=settings;
        this.identities=identities;
        this.oauth=oauth;
        this.ldap=ldap;
        this.mfa=mfa;
    }

    @GetMapping(POLICY)
    ApiResponse<SecurityPolicyService.Policy> policy(HttpServletRequest request) {
        return ApiResponse.success(policies.view(policyActor(request)));
    }

    @PutMapping(POLICY)
    ApiResponse<SecurityPolicyService.Policy> policy(@RequestBody SecurityPolicyService.Policy update,HttpServletRequest request) {
        return ApiResponse.success(policies.save(policyActor(request),update));
    }

    @GetMapping(SSO)
    ApiResponse<List<SsoSettings.View>> providers(HttpServletRequest request) {
        return ApiResponse.success(settings.view(settingsActor(request)));
    }

    @PutMapping(SSO+"/{id}")
    ApiResponse<SsoSettings.View> provider(@PathVariable String id,@RequestBody SsoSettings.Update update,HttpServletRequest request) {
        return ApiResponse.success(settings.save(settingsActor(request),id,update));
    }

    @PostMapping(SSO+"/{id}/test")
    ApiResponse<Map<String,Boolean>> test(@PathVariable String id,HttpServletRequest request) {
        var provider=settings.testProvider(settingsActor(request),id);
        if ("ldap".equals(id)) ldap.test(provider); else oauth.test(provider);
        return ApiResponse.success(Map.of("ok",true));
    }

    @PutMapping(USER+"/login-identities/{method}")
    ApiResponse<Void> bind(@PathVariable String userId,@PathVariable String method,@Valid @RequestBody Binding input,HttpServletRequest request) {
        identities.bind(policyActor(request),SecurityPolicyService.validId(userId),method,input.subject());
        return ApiResponse.success(null);
    }

    @DeleteMapping(USER+"/login-identities/{method}")
    ApiResponse<Void> unbind(@PathVariable String userId,@PathVariable String method,HttpServletRequest request) {
        identities.unbind(policyActor(request),SecurityPolicyService.validId(userId),method);
        return ApiResponse.success(null);
    }

    @PostMapping(USER+"/mfa/reset")
    ApiResponse<Void> reset(@PathVariable String userId,HttpServletRequest request) {
        mfa.reset(policyActor(request),SecurityPolicyService.validId(userId));
        return ApiResponse.success(null);
    }

    private CompanyAccess policyActor(HttpServletRequest request) {return access.requireTenant(request,SecurityPolicyService.PERMISSION);}
    private CompanyAccess settingsActor(HttpServletRequest request) {return access.requireTenant(request,SsoSettings.PERMISSION);}

    record Binding(@NotBlank(message="请输入外部唯一标识") @Size(max=600,message="外部唯一标识过长") String subject) {
        @Override public String toString() {return "外部身份绑定";}
    }
}
