package cn.erp.identity;

import cn.erp.shared.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.stereotype.Service;

@Service
class AuthSessions {
    private final AccessService access;
    private final CookieCsrfTokenRepository tokens;
    private final SessionAuthority authority;
    private final SessionChecks checks;
    private final MfaService mfa;

    AuthSessions(AccessService access, CookieCsrfTokenRepository tokens,SessionAuthority authority,
                 SessionChecks checks,MfaService mfa) {
        this.access = access;
        this.tokens = tokens;
        this.authority=authority;
        this.checks=checks;
        this.mfa=mfa;
    }

    AccessService.UserView open(SessionAccount actor, HttpServletRequest request, HttpServletResponse response) {
        var state=checks.state(actor,false);
        if (state.required() || state.enrollmentId()!=0) {
            reset(request);
            var method=request.getAttribute("erp.login.method") instanceof String value?value:"password";
            mfa.challenge(request,actor,method,state.enrollmentId());
            refreshCsrf(request,response);
            request.setAttribute("erp.login.pending",Boolean.TRUE);
            if (state.enrollmentId()==0) throw new ApiException(403,"MFA_ENROLLMENT_REQUIRED","企业要求先绑定验证器");
            throw new ApiException(403,"MFA_REQUIRED","请使用验证器或恢复码完成二次验证");
        }
        return openFull(actor,request,response);
    }

    AccessService.UserView openFull(SessionAccount actor,HttpServletRequest request,HttpServletResponse response) {
        reset(request);
        actor.save(request);
        var profile = access.current(request, actor);
        authority.touch(request,actor);
        refreshCsrf(request, response);
        request.setAttribute("erp.login.actor",actor);
        return profile;
    }

    private void reset(HttpServletRequest request) {
        var existing = request.getSession(false);
        if (existing != null) {
            if(existing.getAttribute(SessionAccount.class.getName()) instanceof SessionAccount previous) authority.revoke(existing.getId(),previous.tenantId(),previous.userId());
            mfa.clearPending(request);
            existing.invalidate();
        }
        access.clearCache(request);
    }

    void refreshCsrf(HttpServletRequest request, HttpServletResponse response) {
        tokens.saveToken(tokens.generateToken(request), request, response);
    }

    void clear(HttpServletRequest request, HttpServletResponse response) {
        var account=SessionAccount.read(request);authority.revoke(request.getSession(false).getId(),account.tenantId(),account.userId());
        request.getSession(false).invalidate();
        tokens.saveToken(null, request, response);
    }
}
