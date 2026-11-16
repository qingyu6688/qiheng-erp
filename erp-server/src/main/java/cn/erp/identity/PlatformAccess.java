package cn.erp.identity;

import cn.erp.shared.ApiException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** 企业级配置管理不允许通过受限部门身份改变其他公司的安全配置。 */
public final class PlatformAccess {
    private PlatformAccess() {}
    public static void require(JdbcTemplate jdbc,CompanyAccess actor,String permission) {
        if(!actor.hasPermission(permission)) throw new ApiException(403,"FORBIDDEN","没有执行此操作的权限");
        var resolver=new OrgScopeResolver(jdbc);
        var companies=jdbc.queryForList("SELECT DISTINCT s.company_id FROM sys_user_data_scope s JOIN company c ON c.tenant_id=s.tenant_id AND c.id=s.company_id AND c.status='active' WHERE s.tenant_id=? AND s.user_id=?",Long.class,actor.tenantId(),actor.userId());
        if(companies.isEmpty()) throw new ApiException(403,"FORBIDDEN","平台管理需要至少一个有效的公司授权");
        for(long company:companies) {
            if(!resolver.resolve(actor.tenantId(),company,actor.userId()).allDepartments()) throw new ApiException(403,"FORBIDDEN","平台管理需要所授权公司的全部部门范围");
        }
    }
    public static void lock(JdbcTemplate jdbc,CompanyAccess actor,String permission) {
        if(jdbc.queryForList("SELECT id FROM tenant WHERE id=? AND status='active' FOR UPDATE",Long.class,actor.tenantId()).isEmpty()) throw new ApiException(404,"NOT_FOUND","企业不存在或不可用");
        require(jdbc,actor,permission);
    }
}
