package cn.erp.identity;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.slf4j.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE+20)
class LoginAuditFilter extends OncePerRequestFilter {
    private static final Logger LOG=LoggerFactory.getLogger(LoginAuditFilter.class);
    private final LoginAudit audit;
    LoginAuditFilter(LoginAudit audit) {this.audit=audit;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(request.getMethod().equals("POST")&&java.util.Set.of("/api/v1/auth/login","/api/v1/auth/code/login").contains(request.getRequestURI())||request.getMethod().equals("GET")&&request.getRequestURI().equals("/api/v1/auth/wechat/callback"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        try {chain.doFilter(request,response);} finally {
            String method=request.getAttribute("erp.login.method") instanceof String value?value:request.getRequestURI().contains("wechat")?"wechat":"password";
            if(!java.util.Set.of("password","sms","email","wechat").contains(method)) method="password";
            if(!Boolean.TRUE.equals(request.getAttribute("erp.login.binding"))) try {audit.record(request,method,request.getAttribute("erp.login.actor")!=null?"success":request.getAttribute("erp.login.result") instanceof String value?value:"failed");}
            catch(RuntimeException failure) {LOG.warn("登录日志写入失败，异常类型 {}",failure.getClass().getSimpleName());}
        }
    }
}
