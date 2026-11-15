package cn.erp.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.stereotype.Service;

@Service
class AuthSessions {
    private final AccessService access;
    private final CookieCsrfTokenRepository tokens;

    AuthSessions(AccessService access, CookieCsrfTokenRepository tokens) {
        this.access = access;
        this.tokens = tokens;
    }

    AccessService.UserView open(SessionAccount actor, HttpServletRequest request, HttpServletResponse response) {
        var existing = request.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
        actor.save(request);
        var profile = access.current(request, actor);
        refreshCsrf(request, response);
        return profile;
    }

    void refreshCsrf(HttpServletRequest request, HttpServletResponse response) {
        tokens.saveToken(tokens.generateToken(request), request, response);
    }

    void clear(HttpServletRequest request, HttpServletResponse response) {
        request.getSession(false).invalidate();
        tokens.saveToken(null, request, response);
    }
}
