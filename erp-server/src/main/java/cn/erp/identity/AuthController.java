package cn.erp.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.erp.shared.ApiException;
import cn.erp.shared.ApiResponse;

@RestController
@RequestMapping(IdentitySecurity.AUTH)
class AuthController {
    private final CredentialsService credentials;
    private final AccessService access;
    private final LoginLimiter limiter;
    private final AuthSessions sessions;

    AuthController(CredentialsService credentials, AccessService access, LoginLimiter limiter,
                   AuthSessions sessions) {
        this.credentials = credentials;
        this.access = access;
        this.limiter = limiter;
        this.sessions = sessions;
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<AccessService.UserView> login(@Valid @RequestBody Login body, HttpServletRequest req,
                                              HttpServletResponse res) {
        req.setAttribute("erp.login.method","password");req.setAttribute("erp.login.tenant",body.tenantCode());req.setAttribute("erp.login.username",body.username());
        try {
            limiter.check(req.getRemoteAddr(), body.tenantCode(), body.username());
        } catch (ApiException ex) {
            if (ex.getStatus() == 429) {
                res.setHeader("Retry-After", "60");
            }
            throw ex;
        }
        SessionAccount actor;
        try {actor=credentials.authenticate(body.tenantCode(),body.username(),body.password());}
        catch(ApiException error) {req.setAttribute("erp.login.result",error.getErrorCode().equals("ACCOUNT_LOCKED")?"locked":"failed");throw error;}
        return ApiResponse.success(sessions.open(actor, req, res));
    }

    @GetMapping("/me")
    ApiResponse<AccessService.UserView> me(HttpServletRequest req) {
        return ApiResponse.success(access.current(req, SessionAccount.read(req)));
    }

    @PutMapping("/password")
    ApiResponse<Void> changePassword(@Valid @RequestBody PasswordChange body, HttpServletRequest req,
                                     HttpServletResponse res) {
        var actor = credentials.changePassword(SessionAccount.read(req), body.oldPassword(), body.newPassword());
        req.changeSessionId();
        actor.save(req);
        sessions.refreshCsrf(req, res);
        return ApiResponse.success(null);
    }

    @PostMapping("/logout")
    ApiResponse<Void> logout(HttpServletRequest req, HttpServletResponse res) {
        sessions.clear(req, res);
        return ApiResponse.success(null);
    }

    record Login(@NotBlank(message = "请输入企业编码") @Size(max = 32, message = "企业编码过长") String tenantCode,
                 @NotBlank(message = "请输入用户名") @Size(max = 64, message = "用户名过长") String username,
                 @NotBlank(message = "请输入密码") @Size(max = 256, message = "密码过长") String password) {}

    record PasswordChange(@NotBlank(message = "请输入原密码") @Size(max = 256, message = "原密码过长") String oldPassword,
                          @NotBlank(message = "请输入新密码") @Size(max = 72, message = "新密码过长") String newPassword) {}
}
