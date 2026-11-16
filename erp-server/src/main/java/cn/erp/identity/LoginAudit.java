package cn.erp.identity;

import cn.erp.shared.Ids;
import cn.erp.shared.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class LoginAudit {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(LoginAudit.class);
    private final JdbcTemplate jdbc;
    LoginAudit(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    void attemptBinding(HttpServletRequest request,long tenant,String method,String subject) {
        try {
            var users=jdbc.queryForList("SELECT user_id FROM user_login_identity WHERE tenant_id=? AND method=? AND subject=?",Long.class,tenant,method,subject);
            if(!users.isEmpty()) request.setAttribute("erp.login.attemptUser",users.getFirst());
        } catch(RuntimeException failure) {LOG.warn("登录尝试元数据查询失败，异常类型 {}",failure.getClass().getSimpleName());}
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void record(HttpServletRequest request,String method,String result) {
        long tenant=0,user=0;String username="未知账户";
        if(request.getAttribute("erp.login.actor") instanceof SessionAccount account) {tenant=account.tenantId();user=account.userId();}
        else if(request.getAttribute("erp.login.tenant") instanceof String code) {
            var tenants=jdbc.queryForList("SELECT id FROM tenant WHERE code=?",Long.class,code);if(!tenants.isEmpty()) tenant=tenants.getFirst();
            if(request.getAttribute("erp.login.username") instanceof String name) {
                var users=jdbc.queryForList("SELECT id FROM sys_user WHERE tenant_id=? AND username=?",Long.class,tenant,name);if(!users.isEmpty()) user=users.getFirst();
            }
        } else if(request.getAttribute("erp.login.tenantId") instanceof Long id) tenant=id;
        if(user==0&&request.getAttribute("erp.login.attemptUser") instanceof Long attempted) user=attempted;
        if(user>0) {
            var rows=jdbc.queryForList("SELECT username,COALESCE(locked_until>UTC_TIMESTAMP(3),FALSE) is_locked FROM sys_user WHERE tenant_id=? AND id=?",tenant,user);
            if(!rows.isEmpty()) {username=rows.getFirst().get("username").toString();if(result.equals("failed")&&(Boolean.TRUE.equals(rows.getFirst().get("is_locked"))||rows.getFirst().get("is_locked") instanceof Number n&&n.intValue()==1)) result="locked";}
            else user=0;
        }
        if(!result.equals("success")&&tenant>0&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM tenant WHERE id=? AND status<>'active') OR EXISTS(SELECT 1 FROM sys_user WHERE tenant_id=? AND id=? AND status<>'active')",Boolean.class,tenant,tenant,user))) result="disabled";
        jdbc.update("INSERT INTO sys_login_log(id,tenant_id,user_id,username,method,result,ip,user_agent,request_id) VALUES(?,?,?,?,?,?,?,?,?)",Ids.next(),tenant,user,username,method,result,SessionAuthority.safe(request.getRemoteAddr(),64),SessionAuthority.safe(request.getHeader("User-Agent"),255),RequestIds.current());
    }
}
