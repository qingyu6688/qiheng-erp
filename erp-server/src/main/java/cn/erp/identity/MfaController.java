package cn.erp.identity;

import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import cn.erp.shared.ApiException;
import cn.erp.shared.ApiResponse;

@RestController
class MfaController {
    private static final String AUTH=IdentitySecurity.AUTH+"/mfa";
    private static final String ACCOUNT="/api/v1/account/mfa";
    private final MfaService mfa;
    private final AuthSessions sessions;
    private final LoginLimiter limiter;

    MfaController(MfaService mfa,AuthSessions sessions,LoginLimiter limiter) {
        this.mfa=mfa;
        this.sessions=sessions;
        this.limiter=limiter;
    }

    @GetMapping(AUTH+"/status")
    ApiResponse<Map<String,String>> status(HttpServletRequest request) {
        return ApiResponse.success(Map.of("step",mfa.pending(request).enrollmentId()==0?"enroll":"verify"));
    }

    @PostMapping(AUTH+"/setup")
    ApiResponse<MfaService.Setup> startEnrollment(HttpServletRequest request,HttpServletResponse response) {
        var actor=mfa.pending(request).actor();
        limited(actor,"setup");
        noStore(response);
        return ApiResponse.success(mfa.setup(request,actor));
    }

    @PostMapping(AUTH+"/activate")
    ApiResponse<Activated> finishEnrollment(@Valid @RequestBody Code input,HttpServletRequest request,HttpServletResponse response) {
        var attempt=mfa.pending(request);
        limited(attempt.actor(),"verify");
        auditAttempt(request,attempt);
        String sid=request.getSession(false).getId();
        String lease=mfa.lease(request);
        try {
            var enrolled=mfa.activate(request,mfa.pending(request).actor(),input.code());
            noStore(response);
            return ApiResponse.success(new Activated(sessions.openFull(enrolled.actor(),request,response),enrolled.recoveryCodes()));
        } finally {
            mfa.release(sid,lease);
        }
    }

    @PostMapping(AUTH+"/verify")
    ApiResponse<AccessService.UserView> verify(@Valid @RequestBody Code input,HttpServletRequest request,HttpServletResponse response) {
        var attempt=mfa.pending(request);
        limited(attempt.actor(),"verify");
        auditAttempt(request,attempt);
        String sid=request.getSession(false).getId();
        String lease=mfa.lease(request);
        try {
            var checked=mfa.verify(mfa.pending(request).actor(),input.code());
            return ApiResponse.success(sessions.openFull(checked,request,response));
        } finally {
            mfa.release(sid,lease);
        }
    }

    @GetMapping(ACCOUNT)
    ApiResponse<MfaService.State> self(HttpServletRequest request) {
        return ApiResponse.success(mfa.state(SessionAccount.read(request)));
    }

    @PostMapping(ACCOUNT+"/setup")
    ApiResponse<MfaService.Setup> setup(HttpServletRequest request,HttpServletResponse response) {
        var actor=SessionAccount.read(request);
        limited(actor,"setup");
        noStore(response);
        return ApiResponse.success(mfa.setup(request,actor));
    }

    @PostMapping(ACCOUNT+"/activate")
    ApiResponse<Map<String,List<String>>> activate(@Valid @RequestBody Code input,HttpServletRequest request,HttpServletResponse response) {
        var actor=SessionAccount.read(request);
        limited(actor,"verify");
        var enrolled=mfa.activate(request,actor,input.code());
        sessions.openFull(enrolled.actor(),request,response);
        noStore(response);
        return ApiResponse.success(Map.of("recoveryCodes",enrolled.recoveryCodes()));
    }

    @PostMapping(ACCOUNT+"/disable")
    ApiResponse<Void> disable(@Valid @RequestBody Code input,HttpServletRequest request,HttpServletResponse response) {
        var actor=SessionAccount.read(request);
        limited(actor,"verify");
        sessions.openFull(mfa.disable(actor,input.code()),request,response);
        return ApiResponse.success(null);
    }

    private void limited(SessionAccount actor,String action) {
        // 频次以用户而非来源 IP 限制，防止多出口并行试探同一验证码。
        limiter.check("mfa-"+action,Long.toString(actor.tenantId()),Long.toString(actor.userId()));
    }

    private void auditAttempt(HttpServletRequest request,MfaService.Pending attempt) {
        request.setAttribute("erp.login.method",attempt.method());
        request.setAttribute("erp.login.tenantId",attempt.actor().tenantId());
        request.setAttribute("erp.login.attemptUser",attempt.actor().userId());
    }

    private void noStore(HttpServletResponse response) {response.setHeader("Cache-Control","no-store");}

    static String step(ApiException error) {
        return switch(error.getErrorCode()) {
            case "MFA_REQUIRED" -> "verify";
            case "MFA_ENROLLMENT_REQUIRED" -> "enroll";
            default -> null;
        };
    }

    record Code(@NotBlank(message="请输入验证器验证码或恢复码") @Size(max=32,message="验证码长度过长") String code) {}
    record Activated(AccessService.UserView user,List<String> recoveryCodes) {
        @Override public String toString() {return "登录及验证器绑定结果";}
    }
}
