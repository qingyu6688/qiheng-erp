package cn.erp.identity;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import cn.erp.shared.ApiException;

@Configuration(proxyBeanMethods = false)
class IdentitySecurity {
    static final String AUTH = "/api/v1/auth";
    private static final String HEALTH = "/actuator/health";
    private static final Set<String> PUBLIC_POSTS = Set.of(
            AUTH + "/login", AUTH + "/code/send", AUTH + "/code/login", AUTH + "/wechat/start", AUTH + "/ldap/login");
    private static final Set<String> PUBLIC_GETS = Set.of(HEALTH, AUTH + "/methods", AUTH + "/wechat/callback",
            AUTH + "/sso/callback");
    private static final Set<String> MFA_POSTS = Set.of(AUTH+"/mfa/setup",AUTH+"/mfa/activate",AUTH+"/mfa/verify");
    private static final RequestMatcher PUBLIC_FIRST_FACTOR = request ->
            ("POST".equals(request.getMethod()) && PUBLIC_POSTS.contains(request.getServletPath())) ||
            ("GET".equals(request.getMethod()) && (PUBLIC_GETS.contains(request.getServletPath())
                    || request.getServletPath().matches(AUTH+"/sso/(oidc|wecom|dingtalk|feishu)/start")));
    private static final RequestMatcher PUBLIC_AUTH = request -> PUBLIC_FIRST_FACTOR.matches(request)
            || ("GET".equals(request.getMethod()) && (AUTH+"/mfa/status").equals(request.getServletPath()))
            || ("POST".equals(request.getMethod()) && MFA_POSTS.contains(request.getServletPath()));

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CookieCsrfTokenRepository csrfTokens(@Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
        var tokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        tokens.setCookieCustomizer(cookie -> cookie.path("/").sameSite("Lax").secure(secure));
        return tokens;
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, AccessService access, SessionAuthority authority,CookieCsrfTokenRepository tokens,
                                    @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors)
            throws Exception {
        var csrf = new CsrfTokenRequestAttributeHandler();
        // Axios 把同域 Cookie 原值放入请求头，普通 GET 也需要下发令牌。
        csrf.setCsrfRequestAttributeName(null);
        http.csrf(c -> c.csrfTokenRepository(tokens).csrfTokenRequestHandler(csrf)
                        .ignoringRequestMatchers(PUBLIC_FIRST_FACTOR))
                .requestCache(c -> c.disable())
                .logout(c -> c.disable())
                .formLogin(c -> c.disable())
                .httpBasic(c -> c.disable())
                .authorizeHttpRequests(a -> a.requestMatchers(PUBLIC_AUTH).permitAll().anyRequest().authenticated())
                .exceptionHandling(h -> h.authenticationEntryPoint((req, res, ex) ->
                                errors.resolveException(req, res, null, SessionAccount.expired()))
                        .accessDeniedHandler((req, res, ex) -> errors.resolveException(req, res, null,
                                new ApiException(403, "FORBIDDEN", "请求校验失败，请刷新后重试"))))
                .addFilterBefore(new SessionFilter(access, authority,errors), AuthorizationFilter.class);
        return http.build();
    }

    private static class SessionFilter extends OncePerRequestFilter {
        private final AccessService access;
        private final HandlerExceptionResolver errors;
        private final SessionAuthority authority;

        SessionFilter(AccessService access, SessionAuthority authority,HandlerExceptionResolver errors) {
            this.access = access;
            this.errors = errors;
            this.authority=authority;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return PUBLIC_AUTH.matches(request);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws IOException, ServletException {
            try {
                if(req.getRequestedSessionId()!=null) authority.check(req.getRequestedSessionId());
                SessionAccount account = SessionAccount.read(req);
                var profile = access.current(req, account);
                req.setAttribute("erp.actor.tenant",account.tenantId());req.setAttribute("erp.actor.user",account.userId());
                authority.touch(req,account);
                if (profile.mustChangePassword() && !allowsForcedChange(req)) {
                    throw new ApiException(403, "PASSWORD_CHANGE_REQUIRED", "请先修改密码");
                }
                SecurityContextHolder.getContext().setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(account, null, List.of()));
            } catch (RuntimeException ex) {
                if (errors.resolveException(req, res, null, ex) == null) {
                    throw ex;
                }
                return;
            }
            chain.doFilter(req, res);
        }

        private boolean allowsForcedChange(HttpServletRequest req) {
            String path = req.getServletPath();
            return (AUTH + "/me").equals(path) || (AUTH + "/password").equals(path)
                    || (AUTH + "/logout").equals(path);
        }
    }
}
