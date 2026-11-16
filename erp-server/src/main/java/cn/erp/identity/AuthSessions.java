package cn.erp.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.stereotype.Service;

@Service
class AuthSessions {
    private final AccessService access;
    private final CookieCsrfTokenRepository tokens;
    private final SessionAuthority authority;

    AuthSessions(AccessService access, CookieCsrfTokenRepository tokens,SessionAuthority authority) {
        this.access = access;
        this.tokens = tokens;
        this.authority=authority;
    }

    AccessService.UserView open(SessionAccount actor, HttpServletRequest request, HttpServletResponse response) {
        var existing = request.getSession(false);
        if (existing != null) {
            if(existing.getAttribute(SessionAccount.class.getName()) instanceof SessionAccount previous) authority.revoke(existing.getId(),previous.tenantId(),previous.userId());
            existing.invalidate();
        }
        actor.save(request);
        var profile = access.current(request, actor);
        authority.touch(request,actor);
        refreshCsrf(request, response);
        request.setAttribute("erp.login.actor",actor);
        return profile;
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
