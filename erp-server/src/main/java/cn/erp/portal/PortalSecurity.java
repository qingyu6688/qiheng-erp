package cn.erp.portal;

import cn.erp.shared.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration(proxyBeanMethods = false)
class PortalSecurity {
    static final String PREFIX = "/api/v1/portal";
    static final String SESSION_COOKIE = "ERPPORTALSESSION";
    static final String CSRF_COOKIE = "PORTAL-XSRF-TOKEN";
    static final String CSRF_HEADER = "X-PORTAL-XSRF-TOKEN";
    private static final Set<String> LOGIN_PATHS = Set.of("/auth/login", "/auth/code/send", "/auth/code/login");
    private static final Set<String> SCOPE_PARAMETERS = Set.of("tenantid", "tenantcode", "companyid", "companycode",
            "partnerid", "customerid", "contactid", "portaluserid", "userid");

    @Bean
    @Order(7)
    SecurityFilterChain portalSecurityChain(HttpSecurity http, PortalSessions sessions,
            @Value("${server.servlet.session.cookie.secure:true}") boolean secure,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) throws Exception {
        var tokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        tokens.setCookieName(CSRF_COOKIE);
        tokens.setHeaderName(CSRF_HEADER);
        tokens.setCookieCustomizer(cookie -> cookie.path("/").sameSite("Strict").secure(secure));
        var csrf = new CsrfTokenRequestAttributeHandler();
        csrf.setCsrfRequestAttributeName(null);
        http.securityMatcher(PREFIX, PREFIX + "/**")
                .csrf(configuration -> configuration.csrfTokenRepository(tokens).csrfTokenRequestHandler(csrf))
                .sessionManagement(configuration -> configuration.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(configuration -> configuration.disable())
                .requestCache(configuration -> configuration.disable())
                .httpBasic(configuration -> configuration.disable())
                .formLogin(configuration -> configuration.disable())
                .logout(configuration -> configuration.disable())
                .exceptionHandling(configuration -> configuration
                        .authenticationEntryPoint((req,res,error) -> errors.resolveException(req,res,null,PortalActor.expired()))
                        .accessDeniedHandler((req,res,error) -> errors.resolveException(req,res,null,
                                new ApiException(403,"PORTAL_CSRF_REQUIRED","请求验证已失效，请刷新后重试"))))
                .addFilterBefore(new PortalSessionFilter(sessions, errors), AuthorizationFilter.class)
                .authorizeHttpRequests(requests -> requests.requestMatchers(PortalSecurity::publicEndpoint).permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }

    private static boolean publicEndpoint(HttpServletRequest request) {
        String path = request.getServletPath();
        if ("GET".equals(request.getMethod())) return (PREFIX + "/auth/csrf").equals(path);
        if (!"POST".equals(request.getMethod()) || !path.startsWith(PREFIX)) return false;
        return LOGIN_PATHS.contains(path.substring(PREFIX.length()));
    }

    private static class PortalSessionFilter extends OncePerRequestFilter {
        private final PortalSessions sessions;
        private final HandlerExceptionResolver errors;

        PortalSessionFilter(PortalSessions sessions, HandlerExceptionResolver errors) {
            this.sessions = sessions;
            this.errors = errors;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            try {
                verifyScopeInput(request);
                if (!publicEndpoint(request)) {
                    PortalActor actor = sessions.authenticate(request);
                    request.setAttribute(PortalActor.ATTRIBUTE, actor);
                    SecurityContextHolder.getContext().setAuthentication(
                            UsernamePasswordAuthenticationToken.authenticated(actor, null, List.of()));
                }
            } catch (RuntimeException error) {
                SecurityContextHolder.clearContext();
                if (errors.resolveException(request, response, null, error) == null) throw error;
                return;
            }
            try {
                chain.doFilter(request, response);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }

        private void verifyScopeInput(HttpServletRequest request) {
            boolean hasScopeParameter = request.getParameterMap().keySet().stream().anyMatch(key ->
                    SCOPE_PARAMETERS.contains(key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT)));
            if (hasScopeParameter || request.getHeader("X-Company-Id") != null || request.getHeader("X-Partner-Id") != null)
                throw new ApiException(400,"PORTAL_SCOPE_INPUT","客户门户只使用当前账号绑定的数据范围");
        }
    }
}
