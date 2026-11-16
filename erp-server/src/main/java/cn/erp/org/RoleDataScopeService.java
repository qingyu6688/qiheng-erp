package cn.erp.org;

import cn.erp.identity.CompanyAccess;
import cn.erp.identity.OrgScopeResolver;
import cn.erp.shared.*;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
class RoleDataScopeService {
    record Input(@NotNull(message="请选择部门范围") @Pattern(regexp="all|dept|dept_and_children|self|custom",message="部门范围无效") String scopeType,
                 @NotNull(message="请提供部门列表") @Size(max=1000,message="自定义部门最多1000个") List<@NotNull(message="部门不能为空") @Positive(message="部门编号无效") Long> departmentIds,
                 @NotNull(message="请提供版本号") @PositiveOrZero(message="版本号无效") Long version) {}
    record View(String roleId,String companyId,String scopeType,List<String> departmentIds,long version,List<OrgModels.Department> departments,boolean editable) {}
    private final JdbcTemplate jdbc;
    private final OrgSupport access;
    private final DepartmentService departments;
    private final OrgScopeResolver resolver;
    private final BusinessCommands commands;
    private final AuditService audit;
    RoleDataScopeService(JdbcTemplate jdbc,OrgSupport access,DepartmentService departments,OrgScopeResolver resolver,BusinessCommands commands,AuditService audit) {
        this.jdbc=jdbc;this.access=access;this.departments=departments;this.resolver=resolver;this.commands=commands;this.audit=audit;
    }
    View get(CompanyAccess actor,long role) {
        access.requireAny(actor,"role:view","role:assign_permission");access.company(actor);role(actor,role);
        var rows=jdbc.queryForList("SELECT scope_type,version FROM org_role_data_scope WHERE tenant_id=? AND company_id=? AND role_id=?",actor.tenantId(),actor.companyId(),role);
        var ids=jdbc.queryForList("SELECT CAST(department_id AS CHAR) FROM org_role_data_scope_dept WHERE tenant_id=? AND company_id=? AND role_id=? ORDER BY department_id",String.class,actor.tenantId(),actor.companyId(),role);
        var choices=new ArrayList<>(departments.options(actor));
        for(String id:ids) if(choices.stream().noneMatch(row->row.id().equals(id))) choices.add(departments.find(actor,Long.parseLong(id)));
        return new View(Long.toString(role),Long.toString(actor.companyId()),rows.isEmpty()?"all":rows.getFirst().get("scope_type").toString(),ids,rows.isEmpty()?0:((Number)rows.getFirst().get("version")).longValue(),choices,actor.hasPermission("role:assign_permission")&&actor.orgScope().allDepartments());
    }
    @Transactional
    View save(CompanyAccess actor,long role,String key,Input input) {
        access.write(actor,"role:assign_permission");access.company(actor);role(actor,role);
        if(!resolver.resolve(actor.tenantId(),actor.companyId(),actor.userId()).allDepartments()) throw new ApiException(403,"FORBIDDEN","部门数据范围须由当前公司拥有全部部门范围的角色管理员维护");
        var permissions=jdbc.queryForList("SELECT p.code FROM sys_role_permission rp JOIN sys_permission p ON p.id=rp.permission_id WHERE rp.tenant_id=? AND rp.role_id=?",String.class,actor.tenantId(),role);
        if(!actor.permissions().containsAll(permissions)) throw OrgSupport.forbidden();
        return commands.execute(actor,"role.assign_data_scope",key,List.of(role,input),200,()->{
            var before=get(actor,role);OrgSupport.version(input.version(),before.version());
            var unique=new LinkedHashSet<>(input.departmentIds());
            if(unique.size()!=input.departmentIds().size()) throw OrgSupport.invalid("自定义部门不能重复");
            if(!input.scopeType().equals("custom")&&!unique.isEmpty()) throw OrgSupport.invalid("只有自定义范围可以指定部门");
            for(long department:unique) if(!departments.find(actor,department).status().equals("active")) throw OrgSupport.dependency("自定义范围只能新增启用部门");
            jdbc.update("INSERT INTO org_role_data_scope(tenant_id,company_id,role_id,scope_type,version,created_by,updated_by) VALUES(?,?,?,?,1,?,?) ON DUPLICATE KEY UPDATE scope_type=?,version=version+1,updated_by=?",actor.tenantId(),actor.companyId(),role,input.scopeType(),actor.userId(),actor.userId(),input.scopeType(),actor.userId());
            jdbc.update("DELETE FROM org_role_data_scope_dept WHERE tenant_id=? AND company_id=? AND role_id=?",actor.tenantId(),actor.companyId(),role);
            for(long department:unique) jdbc.update("INSERT INTO org_role_data_scope_dept(tenant_id,company_id,role_id,department_id) VALUES(?,?,?,?)",actor.tenantId(),actor.companyId(),role,department);
            ensureAdministrator(actor);
            var after=get(actor,role);audit.record(actor,"role.assign_data_scope","sys_role",role,before,after);return after;
        },View.class);
    }
    private void role(CompanyAccess actor,long role) {
        if(jdbc.queryForObject("SELECT COUNT(*) FROM sys_role WHERE tenant_id=? AND id=?",Long.class,actor.tenantId(),role)==0) throw OrgSupport.missing();
    }
    private void ensureAdministrator(CompanyAccess actor) {
        if(!resolver.administrativeCompanies(actor.tenantId()).contains(actor.companyId()))
            throw new ApiException(422,"LAST_SCOPE_ADMIN","当前公司至少保留一位拥有全部部门范围的有效角色管理员");
    }
}
