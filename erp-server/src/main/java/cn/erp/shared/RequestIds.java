package cn.erp.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIds extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(RequestIds.class);
    private static final String KEY = "requestId";

    public static String current() {
        return MDC.get(KEY);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String id = UUID.randomUUID().toString();
        MDC.put(KEY, id);
        response.setHeader("X-Request-Id", id);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            LOG.info("请求 {} {} {} 状态 {} 耗时 {} 毫秒", id, request.getMethod(),
                    request.getRequestURI(), response.getStatus(), (System.nanoTime() - start) / 1_000_000);
            MDC.remove(KEY);
        }
    }
}
