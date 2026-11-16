package cn.erp.exporting;

import cn.erp.identity.CompanyAccess;
import cn.erp.reporting.ReportType;
import cn.erp.shared.ApiException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ExportAccess {
    private final JdbcTemplate jdbc;
    public ExportAccess(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Scope(boolean allWarehouses,Set<Long> warehouseIds,boolean cost,cn.erp.shared.OrgScope organization) {
        public Scope { warehouseIds=Set.copyOf(warehouseIds);if(organization==null) organization=cn.erp.shared.OrgScope.all(); }
        public Scope(boolean allWarehouses,Set<Long> warehouseIds,boolean cost) {this(allWarehouses,warehouseIds,cost,cn.erp.shared.OrgScope.all());}
    }
    public Scope capture(CompanyAccess actor) {
        Set<Long> warehouses=actor.allWarehouses()?new HashSet<>(jdbc.queryForList("SELECT id FROM warehouse WHERE tenant_id=? AND company_id=?",Long.class,actor.tenantId(),actor.companyId())):actor.warehouseIds();
        return new Scope(actor.allWarehouses(),warehouses,actor.hasPermission("cost:view"),actor.orgScope());
    }
    public CompanyAccess current(long tenant,long company,long user) {
        Long count=jdbc.queryForObject("""
            SELECT COUNT(*) FROM sys_user u JOIN tenant t ON t.id=u.tenant_id JOIN company c ON c.tenant_id=t.id AND c.id=?
            WHERE u.tenant_id=? AND u.id=? AND u.status='active' AND u.must_change_password=0 AND t.status='active' AND c.status='active'
            """,Long.class,company,tenant,user);
        if(count==null || count==0) { throw forbidden(); }
        var scopes=jdbc.queryForList("SELECT scope_type,warehouse_id FROM sys_user_data_scope WHERE tenant_id=? AND user_id=? AND company_id=?",tenant,user,company);
        if(scopes.isEmpty()) { throw forbidden(); }
        boolean all=scopes.stream().anyMatch(s->s.get("scope_type").equals("company"));var warehouses=new HashSet<Long>();
        for(var row:scopes) { if(row.get("warehouse_id") instanceof Number n) { warehouses.add(n.longValue()); } }
        var permissions=jdbc.queryForList("""
            SELECT DISTINCT p.code FROM sys_user_role ur JOIN sys_role r ON r.tenant_id=ur.tenant_id AND r.id=ur.role_id AND r.status='active'
            JOIN sys_role_permission rp ON rp.tenant_id=r.tenant_id AND rp.role_id=r.id JOIN sys_permission p ON p.id=rp.permission_id
            WHERE ur.tenant_id=? AND ur.user_id=?
            """,String.class,tenant,user);
        return new CompanyAccess(tenant,user,company,all,warehouses,new HashSet<>(permissions),new cn.erp.identity.OrgScopeResolver(jdbc).resolve(tenant,company,user));
    }
    public CompanyAccess intersect(CompanyAccess current,Scope initial,ReportType type) {
        type.authorize(current,true);
        if(type.companyWide() && !initial.allWarehouses()) { throw forbidden(); }
        var warehouses=new HashSet<>(initial.warehouseIds());
        if(!current.allWarehouses()) { warehouses.retainAll(current.warehouseIds()); }
        var permissions=new HashSet<>(current.permissions());
        if(!initial.cost()) { permissions.remove("cost:view"); }
        if(type.costBearing() && initial.cost() && !current.hasPermission("cost:view")) { throw new ApiException(403,"FORBIDDEN","成本权限已变更，请恢复权限后重新导出"); }
        return new CompanyAccess(current.tenantId(),current.userId(),current.companyId(),type.companyWide(),warehouses,permissions,current.orgScope().intersect(initial.organization()));
    }
    public void covers(CompanyAccess current,Scope result,ReportType type) {
        type.authorize(current,true);
        if(!current.orgScope().covers(result.organization()) || type.companyWide() && !current.allWarehouses() || !current.allWarehouses() && !current.warehouseIds().containsAll(result.warehouseIds()) || result.cost() && !current.hasPermission("cost:view")) {
            throw new ApiException(403,"FORBIDDEN","权限或仓库范围已收窄，不能下载历史导出文件");
        }
    }
    private ApiException forbidden() { return new ApiException(403,"FORBIDDEN","导出发起人的账号、权限或公司范围已变更"); }
}
