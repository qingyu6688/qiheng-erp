package cn.erp.observability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** 监控抓取使用独立凭据，避免建立业务会话。 */
@Configuration(proxyBeanMethods = false)
class ManagementSecurity {
    @Bean
    @Order(0)
    SecurityFilterChain metricsSecurity(HttpSecurity http,
            @Value("${ERP_METRICS_TOKEN:}") String token) throws Exception {
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        http.securityMatcher("/actuator/prometheus")
                .csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.disable())
                .authorizeHttpRequests(requests -> requests.anyRequest().access((authentication, context) -> {
                    String authorization = context.getRequest().getHeader("Authorization");
                    boolean allowed = !token.isBlank() && "GET".equals(context.getRequest().getMethod())
                            && authorization != null && MessageDigest.isEqual(expected,
                                    authorization.getBytes(StandardCharsets.UTF_8));
                    return new AuthorizationDecision(allowed);
                }))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, failure) ->
                                response.sendError(401, "监控凭据无效"))
                        .accessDeniedHandler((request, response, failure) -> response.sendError(403, "监控访问被拒绝")));
        return http.build();
    }
}
