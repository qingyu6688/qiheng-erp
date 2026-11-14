package cn.erp.identity;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import cn.erp.shared.ApiException;

@Configuration(proxyBeanMethods = false)
class IdentitySecurity {
    static final String AUTH = "/api/v1/auth";
    private static final String HEALTH = "/actuator/health";

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
    SecurityFilterChain apiSecurity(HttpSecurity http, AccessService access, CookieCsrfTokenRepository tokens,
                                    @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors)
            throws Exception {
        var csrf = new CsrfTokenRequestAttributeHandler();
        // Axios 把同域 Cookie 原值放入请求头，普通 GET 也需要下发令牌。
        csrf.setCsrfRequestAttributeName(null);
        http.csrf(c -> c.csrfTokenRepository(tokens).csrfTokenRequestHandler(csrf)
                        .ignoringRequestMatchers(AUTH + "/login"))
                .requestCache(c -> c.disable())
                .logout(c -> c.disable())
                .formLogin(c -> c.disable())
                .httpBasic(c -> c.disable())
                .authorizeHttpRequests(a -> a.requestMatchers(HttpMethod.POST, AUTH + "/login").permitAll()
                        .requestMatchers(HttpMethod.GET, HEALTH).permitAll().anyRequest().authenticated())
                .exceptionHandling(h -> h.authenticationEntryPoint((req, res, ex) ->
                                errors.resolveException(req, res, null, SessionAccount.expired()))
                        .accessDeniedHandler((req, res, ex) -> errors.resolveException(req, res, null,
                                new ApiException(403, "FORBIDDEN", "请求校验失败，请刷新后重试"))))
                .addFilterBefore(new SessionFilter(access, errors), AuthorizationFilter.class);
        return http.build();
    }

    private static class SessionFilter extends OncePerRequestFilter {
        private final AccessService access;
        private final HandlerExceptionResolver errors;

        SessionFilter(AccessService access, HandlerExceptionResolver errors) {
            this.access = access;
            this.errors = errors;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            String path = request.getServletPath();
            return ("POST".equals(request.getMethod()) && (AUTH + "/login").equals(path))
                    || ("GET".equals(request.getMethod()) && HEALTH.equals(path));
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws IOException, ServletException {
            try {
                SessionAccount account = SessionAccount.read(req);
                var profile = access.current(req, account);
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
