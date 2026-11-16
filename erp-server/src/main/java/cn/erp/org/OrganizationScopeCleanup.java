package cn.erp.org;

import cn.erp.identity.CompanyAccess;
import cn.erp.identity.UserCompanyScopesChanged;
import cn.erp.shared.AuditService;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class OrganizationScopeCleanup {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    OrganizationScopeCleanup(JdbcTemplate jdbc,AuditService audit) {this.jdbc=jdbc;this.audit=audit;}

    @EventListener
    @Transactional(propagation=Propagation.MANDATORY)
    public void changed(UserCompanyScopesChanged event) {
        for(long company:event.removedCompanies().stream().sorted().toList()) clear(event,company);
    }
    private void clear(UserCompanyScopesChanged event,long company) {
        var actor=event.actor();long tenant=actor.tenantId(),user=event.userId();
        var departmentIds=jdbc.queryForList("SELECT department_id FROM org_user_department WHERE tenant_id=? AND company_id=? AND user_id=?",Long.class,tenant,company,user);
        var postIds=jdbc.queryForList("SELECT post_id FROM org_user_post WHERE tenant_id=? AND company_id=? AND user_id=?",Long.class,tenant,company,user);
        var leaderIds=jdbc.queryForList("SELECT id FROM org_department WHERE tenant_id=? AND company_id=? AND leader_user_id=?",Long.class,tenant,company,user);
        jdbc.update("DELETE FROM org_user_department WHERE tenant_id=? AND company_id=? AND user_id=?",tenant,company,user);
        jdbc.update("DELETE FROM org_user_post WHERE tenant_id=? AND company_id=? AND user_id=?",tenant,company,user);
        // 尚未保存过任职的用户也递增版本，恢复公司范围后，撤权前读到的版本0不能继续写入。
        jdbc.update("""
            INSERT INTO org_user_company(tenant_id,company_id,user_id,version,created_by,updated_by) VALUES(?,?,?,1,?,?)
            ON DUPLICATE KEY UPDATE version=version+1,updated_by=?
            """,tenant,company,user,actor.userId(),actor.userId(),actor.userId());
        jdbc.update("UPDATE org_department SET leader_user_id=NULL,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND leader_user_id=?",actor.userId(),tenant,company,user);
        var scope=new CompanyAccess(tenant,actor.userId(),company,true,java.util.Set.of(),actor.permissions());
        audit.record(scope,"user.organization_scope_removed","sys_user",user,
                Map.of("departmentIds",departmentIds,"postIds",postIds,"leaderDepartmentIds",leaderIds),Map.of("cleared",true));
    }
}
