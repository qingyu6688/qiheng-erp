package cn.erp.importing;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 后台任务不继承 HTTP 会话，逐行重新取得原操作人的有效授权。 */
@Service
class ImportAccess {
    private final JdbcTemplate jdbc;
    ImportAccess(JdbcTemplate jdbc){this.jdbc=jdbc;}
    static void permissions(CompanyAccess scope,String type,boolean cost) {
        ImportCsv.headers(type);
        String target=type.equals("opening_stock")?"stock_adjustment":type;
        if(!scope.hasPermission("import:"+type)||!scope.hasPermission(target+":create")
                ||(cost&&!scope.hasPermission("cost:view")))throw forbidden();
    }
    Set<Long> snapshot(CompanyAccess scope) {
        var ids=new HashSet<>(jdbc.queryForList("SELECT id FROM warehouse WHERE tenant_id=? AND company_id=? AND status='active'",Long.class,scope.tenantId(),scope.companyId()));
        ids.removeIf(id->!scope.allowsWarehouse(id));return Set.copyOf(ids);
    }
    CompanyAccess current(long tenant,long company,long user,Set<Long> snapshot,String type,boolean cost) {
        Long active=jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_user u JOIN tenant t ON t.id=u.tenant_id
                JOIN company c ON c.tenant_id=u.tenant_id AND c.id=? AND c.status='active'
                WHERE u.tenant_id=? AND u.id=? AND u.status='active' AND t.status='active' AND u.must_change_password=0
                AND EXISTS(SELECT 1 FROM sys_user_data_scope s WHERE s.tenant_id=u.tenant_id AND s.user_id=u.id AND s.company_id=c.id)
                """,Long.class,company,tenant,user);
        if(active==null||active!=1)throw forbidden();
        var permissions=new HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT p.code FROM sys_user_role ur
                JOIN sys_role r ON r.tenant_id=ur.tenant_id AND r.id=ur.role_id AND r.status='active'
                JOIN sys_role_permission rp ON rp.tenant_id=r.tenant_id AND rp.role_id=r.id
                JOIN sys_permission p ON p.id=rp.permission_id
                WHERE ur.tenant_id=? AND ur.user_id=?
                """,String.class,tenant,user));
        boolean all=jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_data_scope WHERE tenant_id=? AND user_id=? AND company_id=? AND scope_type='company'",Long.class,tenant,user,company)>0;
        var allowed=new HashSet<>(jdbc.queryForList("SELECT warehouse_id FROM sys_user_data_scope WHERE tenant_id=? AND user_id=? AND company_id=? AND warehouse_id IS NOT NULL",Long.class,tenant,user,company));
        if(all)allowed.addAll(snapshot);
        allowed.retainAll(snapshot);
        allowed.retainAll(jdbc.queryForList("SELECT id FROM warehouse WHERE tenant_id=? AND company_id=? AND status='active'",Long.class,tenant,company));
        var scope=new CompanyAccess(tenant,user,company,false,Set.copyOf(allowed),Set.copyOf(permissions));
        permissions(scope,type,cost);return scope;
    }
    static ApiException forbidden(){return new ApiException(403,"FORBIDDEN","当前账号、公司、数据范围或导入权限已失效");}
}
