package cn.erp.identity;

import cn.erp.shared.ApiException;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AlternativeLoginController {
    static final String ACCOUNT = "/api/v1/account/login-methods";
    private static final String AUTH = IdentitySecurity.AUTH;
    private static final String OTP_METHODS = "sms|email";
    private static final String PHONE = "(?:\\+86)?1[3-9][0-9]{9}";
    private static final String EMAIL = "[a-z0-9_+%~!#$&'*=/^?{}|.-]{1,64}@[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+";
    private static final Logger LOG = LoggerFactory.getLogger(AlternativeLoginController.class);

    private final LoginSettingsService settings;
    private final LoginIdentities identities;
    private final LoginCodeTokens codes;
    private final LoginCodeDelivery delivery;
    private final LoginCodeDispatch dispatch;
    private final AuthSessions sessions;
    private final LoginAudit audit;

    AlternativeLoginController(LoginSettingsService settings, LoginIdentities identities, LoginCodeTokens codes,
                               LoginCodeDelivery delivery, LoginCodeDispatch dispatch, AuthSessions sessions,LoginAudit audit) {
        this.settings = settings;
        this.identities = identities;
        this.codes = codes;
        this.delivery = delivery;
        this.dispatch = dispatch;
        this.sessions = sessions;
        this.audit=audit;
    }

    @GetMapping(AUTH + "/methods")
    ApiResponse<Map<String,Boolean>> methods(@RequestParam @Size(max=32, message="企业编码过长") String tenantCode) {
        return ApiResponse.success(settings.methods(tenantCode.strip()));
    }

    @PostMapping(value=AUTH + "/code/send", consumes=MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Void> send(@Valid @RequestBody SendCode input, HttpServletRequest request) {
        String destination = normalize(input.method(), input.destination());
        long tenantId = tenantId(input.tenantCode());
        provider(tenantId, input.method());
        codes.reserveSend(request.getRemoteAddr(), tenantId, input.method(), destination);
        // 登录入口等同返回，避免用渠道投递耗时判断账号是否存在。
        String method = input.method();
        dispatch.submit(() -> {
            var current = settings.provider(tenantId, method);
            var target = identities.target(tenantId, method, destination);
            if (current != null && target != null) {
                deliver(method, current, destination, loginScope(tenantId, method, destination, target, current), true);
            }
        });
        return ApiResponse.success(null);
    }

    @PostMapping(value=AUTH + "/code/login", consumes=MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<AccessService.UserView> login(@Valid @RequestBody LoginCode input, HttpServletRequest request,
                                             HttpServletResponse response) {
        request.setAttribute("erp.login.method",input.method());request.setAttribute("erp.login.tenant",input.tenantCode());
        String destination = normalize(input.method(), input.destination());
        long tenantId = tenantId(input.tenantCode());
        var provider = provider(tenantId, input.method());
        audit.attemptBinding(request,tenantId,input.method(),destination);
        codes.limitVerification(request.getRemoteAddr(), tenantId, input.method(), destination);
        var target = identities.target(tenantId, input.method(), destination);
        if (target == null || !codes.consume(loginScope(tenantId, input.method(), destination, target, provider), input.code())) {
            throw invalidCode();
        }
        var actor = identities.authenticate(tenantId, input.method(), destination, target);
        return ApiResponse.success(sessions.open(actor, request, response));
    }

    @GetMapping(ACCOUNT)
    ApiResponse<Map<String,Object>> bindings(HttpServletRequest request) {
        var actor = SessionAccount.read(request);
        var result = identities.bindings(request, actor);
        result.put("methods", settings.methods(identities.tenantCode(actor)));
        return ApiResponse.success(result);
    }

    @PostMapping(value=ACCOUNT + "/code/send", consumes=MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Void> sendBinding(@Valid @RequestBody SendBinding input, HttpServletRequest request) {
        String destination = normalize(input.method(), input.destination());
        var actor = identities.reauthenticate(request, input.password());
        var provider = provider(actor.tenantId(), input.method());
        codes.reserveSend(request.getRemoteAddr(), actor.tenantId(), input.method(), destination);
        deliver(input.method(), provider, destination,
                bindingScope(actor, request.getSession(false).getId(), input.method(), destination, provider), false);
        return ApiResponse.success(null);
    }

    @PutMapping(value=ACCOUNT + "/code", consumes=MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Void> bind(@Valid @RequestBody VerifyBinding input, HttpServletRequest request) {
        String destination = normalize(input.method(), input.destination());
        var actor = SessionAccount.read(request);
        var provider = provider(actor.tenantId(), input.method());
        codes.limitVerification(request.getRemoteAddr(), actor.tenantId(), input.method(), destination);
        if (!codes.consume(bindingScope(actor, request.getSession(false).getId(), input.method(), destination, provider),
                input.code())) {
            throw invalidCode();
        }
        identities.bind(actor, input.method(), destination);
        return ApiResponse.success(null);
    }

    @DeleteMapping(value=ACCOUNT + "/{method}", consumes=MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<Void> unbind(@PathVariable String method, @Valid @RequestBody Password input, HttpServletRequest request) {
        LoginIdentities.requireMethod(method);
        identities.unbind(identities.reauthenticate(request, input.password()), method);
        return ApiResponse.success(null);
    }

    private void deliver(String method, LoginSettingsService.Provider provider, String destination, String scope,
                         boolean publicLogin) {
        var issued = codes.issue(scope);
        try {
            delivery.send(method, provider, destination, issued.code());
        } catch (RuntimeException failure) {
            codes.cancel(scope, issued);
            // 公开入口不因投递失败暴露该地址是否已绑定，也不记录收件人或验证码。
            LOG.warn("登录验证码投递失败，渠道 {}", method);
            if (!publicLogin) {
                throw new ApiException(503, "LOGIN_DELIVERY_FAILED", "验证码暂时无法送达，请稍后再试");
            }
        }
    }

    private long tenantId(String code) {
        Long tenant = identities.tenantId(code.strip());
        if (tenant == null) {
            throw unavailable();
        }
        return tenant;
    }

    private LoginSettingsService.Provider provider(long tenantId, String method) {
        var provider = settings.provider(tenantId, method);
        if (provider == null) {
            throw unavailable();
        }
        return provider;
    }

    private static String loginScope(long tenantId, String method, String destination, LoginIdentities.Target target,
                                     LoginSettingsService.Provider provider) {
        return "login|" + tenantId + "|" + method + "|" + target.identityId() + "|" + target.passwordRevision()
                + "|" + provider.version() + "|" + destination;
    }

    private static String bindingScope(SessionAccount actor, String browserSession, String method, String destination,
                                       LoginSettingsService.Provider provider) {
        return "bind|" + actor.tenantId() + "|" + method + "|" + actor.userId() + "|" + actor.passwordRevision()
                + "|" + provider.version() + "|" + browserSession + "|" + destination;
    }

    static String normalize(String method, String destination) {
        String value = destination.strip();
        if ("sms".equals(method) && value.matches(PHONE)) {
            return value.startsWith("+86") ? value : "+86" + value;
        }
        if ("email".equals(method)) {
            value = value.toLowerCase(Locale.ROOT);
            int at = value.indexOf('@');
            if (value.matches(EMAIL) && !value.contains("..") && !value.startsWith(".") && value.charAt(at - 1) != '.') {
                return value;
            }
        }
        throw new ApiException(400, "VALIDATION_FAILED", "sms".equals(method) ? "请输入有效的中国大陆手机号" : "请输入有效的电子邮箱");
    }

    private static ApiException unavailable() {
        return new ApiException(409, "LOGIN_METHOD_DISABLED", "该企业未启用此登录方式");
    }

    private static ApiException invalidCode() {
        return new ApiException(401, "AUTH_FAILED", "验证码错误、已失效或该账号暂不可登录");
    }

    record SendCode(@NotBlank(message="请输入企业编码") @Size(max=32, message="企业编码过长") String tenantCode,
                    @NotBlank(message="请选择登录方式") @Pattern(regexp=OTP_METHODS, message="登录方式无效") String method,
                    @NotBlank(message="请输入手机号或邮箱") @Size(max=254, message="登录地址过长") String destination) {}
    record LoginCode(@NotBlank(message="请输入企业编码") @Size(max=32, message="企业编码过长") String tenantCode,
                     @NotBlank(message="请选择登录方式") @Pattern(regexp=OTP_METHODS, message="登录方式无效") String method,
                     @NotBlank(message="请输入手机号或邮箱") @Size(max=254, message="登录地址过长") String destination,
                     @NotBlank(message="请输入验证码") @Pattern(regexp="[0-9]{6}", message="验证码须为 6 位数字") String code) {}
    record SendBinding(@NotBlank(message="请选择登录方式") @Pattern(regexp=OTP_METHODS, message="登录方式无效") String method,
                       @NotBlank(message="请输入手机号或邮箱") @Size(max=254, message="登录地址过长") String destination,
                       @NotBlank(message="请输入当前密码") @Size(max=256, message="密码过长") String password) {}
    record VerifyBinding(@NotBlank(message="请选择登录方式") @Pattern(regexp=OTP_METHODS, message="登录方式无效") String method,
                         @NotBlank(message="请输入手机号或邮箱") @Size(max=254, message="登录地址过长") String destination,
                         @NotBlank(message="请输入验证码") @Pattern(regexp="[0-9]{6}", message="验证码须为 6 位数字") String code) {}
    record Password(@NotBlank(message="请输入当前密码") @Size(max=256, message="密码过长") String password) {}
}
