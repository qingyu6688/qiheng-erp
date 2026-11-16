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
    private org.springframework.context.ApplicationEventPublisher events;
    @org.springframework.beans.factory.annotation.Autowired
    void setEvents(org.springframework.context.ApplicationEventPublisher events) {this.events=events;}
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
        String previousTrace=MDC.get("trace_id");
        try {
            chain.doFilter(request, response);
        } finally {
            String route=java.util.Objects.toString(request.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE),"未匹配路由");
            String method=java.util.Set.of("GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS","TRACE","CONNECT").contains(request.getMethod())?request.getMethod():"其他";
            long duration=(System.nanoTime()-start)/1_000_000;
            String trace=request.getAttribute("erp.api.trace_id") instanceof String value?value:MDC.get("trace_id");
            if(trace!=null) MDC.put("trace_id",trace);
            LOG.info("请求 {} {} {} 状态 {} 耗时 {} 毫秒", id, method,
                    route, response.getStatus(), duration);
            if(events!=null&&request.getRequestURI().startsWith("/api/")) {
                var failure=request.getAttribute("erp.api.failure") instanceof Exception error?error:null;
                var frames=failure==null?java.util.List.<String>of():java.util.Arrays.stream(failure.getStackTrace()).limit(20).map(StackTraceElement::toString).toList();
                try {events.publishEvent(new ApiObservation(number(request,"erp.actor.tenant"),number(request,"erp.actor.user"),number(request,"erp.actor.company"),method,route,response.getStatus(),duration,id,trace,failure==null?null:failure.getClass().getName(),frames));}
                catch(RuntimeException error) {LOG.warn("接口日志采集失败，异常类型 {}",error.getClass().getSimpleName());}
            }
            MDC.remove(KEY);
            if(previousTrace==null) MDC.remove("trace_id");else MDC.put("trace_id",previousTrace);
        }
    }
    private static long number(HttpServletRequest request,String key) {return request.getAttribute(key) instanceof Number value?value.longValue():0;}
}
