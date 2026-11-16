package cn.erp.shared;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

/** 在追踪上下文仍有效时保存链路号，外层请求日志结束时上下文可能已经释放。 */
@Configuration(proxyBeanMethods=false)
class ApiTraceContext implements WebMvcConfigurer {
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
                String trace=MDC.get("trace_id");if(trace==null) trace=MDC.get("traceId");
                if(trace!=null&&trace.matches("[a-f0-9]{32}")) request.setAttribute("erp.api.trace_id",trace);return true;
            }
        });
    }
}
