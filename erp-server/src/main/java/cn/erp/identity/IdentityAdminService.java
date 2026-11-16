package cn.erp.identity;

import cn.erp.shared.ApiException;
import cn.erp.shared.AuditService;
import cn.erp.shared.BusinessCommands;
import cn.erp.shared.Ids;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.identity.AdminModels.*;
import static cn.erp.identity.OrganizationService.*;

@Service
@Transactional(readOnly=true)
class IdentityAdminService {
    private static final List<String> ADMIN_PERMISSIONS=List.of("user:update","user:assign_role","user:assign_scope","role:assign_permission");
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final AuditService audit;
    private final BusinessCommands commands;
    private final UserAccessMapper mapper;
    private final ApplicationEventPublisher events;

    IdentityAdminService(JdbcTemplate jdbc,PasswordEncoder passwords,AuditService audit,BusinessCommands commands,UserAccessMapper mapper,ApplicationEventPublisher events) {
        this.jdbc=jdbc;this.passwords=passwords;this.audit=audit;this.commands=commands;this.mapper=mapper;this.events=events;
    }

    Page<UserView> users(CompanyAccess actor,String keyword,String cursor,int limit) {
        var ids=pageIds(actor,"sys_user","username",keyword,cursor,limit);
        return new Page<>(ids.stream().limit(limit).map(id->user(actor,id,false)).toList(),
                ids.size()>limit?Long.toString(ids.get(limit-1)):null);
    }

    UserView user(CompanyAccess actor,long id,boolean lock) {
        var rows=jdbc.query("SELECT * FROM sys_user WHERE tenant_id=? AND id=?"+(lock?" FOR UPDATE":""),(r,n)->
                new UserView(r.getString("id"),r.getString("username"),r.getString("display_name"),
                        actor.hasPermission("user:view_mobile")?r.getString("mobile"):null,r.getString("status"),r.getLong("version"),
                        r.getBoolean("must_change_password"),roleIds(actor,id),scopes(actor,id)),actor.tenantId(),id);
        if(rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    @Transactional
    UserView createUser(CompanyAccess actor,String key,UserInput input) {
        CredentialsService.validateNewPassword(input.password());
        return commands.execute(actor,"user.create",key,input,201,()->{
            long id=Ids.next();
            jdbc.update("""
                    INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash,mobile,must_change_password,created_by,updated_by)
                    VALUES(?,?,?,?,?,?,1,?,?)
                    """,id,actor.tenantId(),input.username(),input.displayName(),passwords.encode(input.password()),input.mobile(),actor.userId(),actor.userId());
            return redact(userAfter(actor,id,"user.create",null));
        },UserView.class);
    }

    @Transactional
    UserView editUser(CompanyAccess actor,long id,UserEdit input) {
        var before=user(actor,id,true); version(input.version(),before.version());
        if(input.mobile()!=null&&!actor.hasPermission("user:view_mobile")) throw forbidden("没有修改用户手机号的权限");
        jdbc.update("UPDATE sys_user SET display_name=?,mobile=COALESCE(?,mobile),version=version+1,updated_by=? WHERE tenant_id=? AND id=?",
                input.displayName(),input.mobile(),actor.userId(),actor.tenantId(),id);
        return userAfter(actor,id,"user.update",before);
    }

    @Transactional
    UserView userCommand(CompanyAccess actor,long id,String action,String key,Version input) {
        return commands.execute(actor,"user."+action,key,List.of(id,input),200,()->{
            var prior=lockAdministrators(actor);
            var before=user(actor,id,true);version(input.version(),before.version());
            String status = switch(action) {
                case "disable" -> "disabled";
                case "unlock" -> before.status().equals("locked")?"active":before.status();
                default -> "active";
            };
            jdbc.update("""
                    UPDATE sys_user SET status=?,failed_login_count=0,locked_until=NULL,version=version+1,updated_by=?
                    WHERE tenant_id=? AND id=?
                    """,status,actor.userId(),actor.tenantId(),id);
            ensureAdministrators(actor,prior);
            return redact(userAfter(actor,id,"user."+action,before));
        },UserView.class);
    }

    @Transactional
    UserView resetPassword(CompanyAccess actor,long id,String key,ResetPassword input) {
        CredentialsService.validateNewPassword(input.password());
        lockAdministrators(actor);
        return commands.execute(actor,"user.reset_password",key,List.of(id,input),200,()->{
            var before=user(actor,id,true);version(input.version(),before.version());
            jdbc.update("""
                    UPDATE sys_user SET password_hash=?,must_change_password=1,password_changed_at=
                    GREATEST(UTC_TIMESTAMP(3),COALESCE(DATE_ADD(password_changed_at,INTERVAL 1000 MICROSECOND),UTC_TIMESTAMP(3))),
                    failed_login_count=0,locked_until=NULL,version=version+1,updated_by=? WHERE tenant_id=? AND id=?
                    """,passwords.encode(input.password()),actor.userId(),actor.tenantId(),id);
            return redact(userAfter(actor,id,"user.reset_password",before));
        },UserView.class);
    }

    @Transactional
    UserView assignRoles(CompanyAccess actor,long id,RoleAssignment input) {
        var prior=lockAdministrators(actor);
        var before=user(actor,id,true);version(input.version(),before.version());
        var ids=new LinkedHashSet<>(input.roleIds());
        for(String roleId:ids) {
            var role=role(actor,identifier(roleId),true);
            if(!role.status().equals("active")||!actor.permissions().containsAll(role.permissionCodes())) throw forbidden("只能分配自己拥有的有效角色权限");
        }
        jdbc.update("DELETE FROM sys_user_role WHERE tenant_id=? AND user_id=?",actor.tenantId(),id);
        for(String roleId:ids) jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id,created_by) VALUES(?,?,?,?,?)",
                Ids.next(),actor.tenantId(),id,identifier(roleId),actor.userId());
        touchUser(actor,id);ensureAdministrators(actor,prior);
        return userAfter(actor,id,"user.assign_role",before);
    }

    @Transactional
    UserView assignScopes(CompanyAccess actor,long id,ScopeAssignment input) {
        var prior=lockAdministrators(actor);
        var before=user(actor,id,true);version(input.version(),before.version());
        var scopes=new LinkedHashSet<>(input.scopes());
        for(DataScope scope:scopes) validateScope(actor,scope);
        jdbc.update("DELETE FROM sys_user_data_scope WHERE tenant_id=? AND user_id=?",actor.tenantId(),id);
        for(DataScope scope:scopes) jdbc.update("""
                INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id,warehouse_id,created_by) VALUES(?,?,?,?,?,?,?)
                """,Ids.next(),actor.tenantId(),id,scope.scopeType(),identifier(scope.companyId()),
                scope.warehouseId()==null?null:identifier(scope.warehouseId()),actor.userId());
        touchUser(actor,id);ensureAdministrators(actor,prior);
        var retainedCompanies=scopes.stream().map(DataScope::companyId).collect(java.util.stream.Collectors.toSet());
        var removedCompanies=before.dataScopes().stream().map(DataScope::companyId).filter(company->!retainedCompanies.contains(company))
                .map(Long::valueOf).collect(java.util.stream.Collectors.toSet());
        events.publishEvent(new UserCompanyScopesChanged(actor,id,removedCompanies));
        return userAfter(actor,id,"user.assign_scope",before);
    }

    Page<RoleView> roles(CompanyAccess actor,String keyword,String cursor,int limit) {
        var ids=pageIds(actor,"sys_role","code",keyword,cursor,limit);
        return new Page<>(ids.stream().limit(limit).map(id->role(actor,id,false)).toList(),
                ids.size()>limit?Long.toString(ids.get(limit-1)):null);
    }

    RoleView role(CompanyAccess actor,long id,boolean lock) {
        var rows=jdbc.query("SELECT * FROM sys_role WHERE tenant_id=? AND id=?"+(lock?" FOR UPDATE":""),(r,n)->
                new RoleView(r.getString("id"),r.getString("code"),r.getString("name"),r.getString("status"),
                        r.getLong("version"),jdbc.queryForList("""
                                SELECT p.code FROM sys_role_permission rp JOIN sys_permission p ON p.id=rp.permission_id
                                WHERE rp.tenant_id=? AND rp.role_id=? ORDER BY p.code
                                """,String.class,actor.tenantId(),id)),actor.tenantId(),id);
        if(rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    @Transactional
    RoleView createRole(CompanyAccess actor,String key,RoleInput input) {
        return commands.execute(actor,"role.create",key,input,201,()->{
            long id=Ids.next();
            jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name,created_by,updated_by) VALUES(?,?,?,?,?,?)",
                    id,actor.tenantId(),input.code(),input.name(),actor.userId(),actor.userId());
            return roleAfter(actor,id,"role.create",null);
        },RoleView.class);
    }

    @Transactional
    RoleView editRole(CompanyAccess actor,long id,RoleInput input) {
        var prior=lockAdministrators(actor);
        var before=role(actor,id,true);version(input.version(),before.version());
        if(!actor.permissions().containsAll(before.permissionCodes())) throw forbidden("不能修改超出自己权限的角色");
        jdbc.update("UPDATE sys_role SET code=?,name=?,status=?,version=version+1,updated_by=? WHERE tenant_id=? AND id=?",
                input.code(),input.name(),input.status()==null?before.status():input.status(),actor.userId(),actor.tenantId(),id);
        ensureAdministrators(actor,prior);
        return roleAfter(actor,id,"role.update",before);
    }

    @Transactional
    RoleView assignPermissions(CompanyAccess actor,long id,PermissionAssignment input) {
        var prior=lockAdministrators(actor);
        var before=role(actor,id,true);version(input.version(),before.version());
        var codes=new LinkedHashSet<>(input.permissionCodes());
        if(!actor.permissions().containsAll(codes)||!actor.permissions().containsAll(before.permissionCodes())) throw forbidden("只能配置自己拥有的权限");
        jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=?",actor.tenantId(),id);
        for(String code:codes) {
            var permissionIds=jdbc.queryForList("SELECT id FROM sys_permission WHERE code=?",Long.class,code);
            if(permissionIds.isEmpty()) throw new ApiException(400,"VALIDATION_FAILED","权限码不存在");
            jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id,created_by) VALUES(?,?,?,?,?)",
                    Ids.next(),actor.tenantId(),id,permissionIds.getFirst(),actor.userId());
        }
        jdbc.update("UPDATE sys_role SET version=version+1,updated_by=? WHERE tenant_id=? AND id=?",actor.userId(),actor.tenantId(),id);
        ensureAdministrators(actor,prior);
        return roleAfter(actor,id,"role.assign_permission",before);
    }

    List<Map<String,Object>> permissions() {
        return jdbc.query("SELECT id,code,name,perm_type FROM sys_permission ORDER BY sort_no,id",(r,n)->Map.<String,Object>of(
                "id",r.getString("id"),"code",r.getString("code"),"name",r.getString("name"),"permType",r.getString("perm_type"),"children",List.of()));
    }

    private List<Long> pageIds(CompanyAccess actor,String table,String codeColumn,String keyword,String cursor,int limit) {
        if(limit<1||limit>200||keyword!=null&&keyword.length()>100) throw new ApiException(400,"VALIDATION_FAILED","查询条件或条数有误");
        long after=0;
        if(cursor!=null) {
            try {after=Long.parseLong(cursor);} catch(NumberFormatException error) {throw new ApiException(400,"VALIDATION_FAILED","分页游标有误");}
            if(after<0) throw new ApiException(400,"VALIDATION_FAILED","分页游标有误");
        }
        String nameColumn=table.equals("sys_user")?"display_name":"name";
        String search=keyword==null?"":keyword;
        return jdbc.queryForList("SELECT id FROM "+table+" WHERE tenant_id=? AND id>? AND (LOCATE(?,"+codeColumn+")>0 OR LOCATE(?,"+nameColumn+")>0) ORDER BY id LIMIT ?",
                Long.class,actor.tenantId(),after,search,search,limit+1);
    }

    private List<String> roleIds(CompanyAccess actor,long id) {
        return jdbc.queryForList("SELECT CAST(role_id AS CHAR) FROM sys_user_role WHERE tenant_id=? AND user_id=? ORDER BY role_id",
                String.class,actor.tenantId(),id);
    }

    private List<DataScope> scopes(CompanyAccess actor,long id) {
        return jdbc.query("SELECT company_id,scope_type,warehouse_id FROM sys_user_data_scope WHERE tenant_id=? AND user_id=? ORDER BY company_id,warehouse_key",
                (r,n)->new DataScope(r.getString(1),r.getString(2),r.getString(3)),actor.tenantId(),id);
    }

    private void validateScope(CompanyAccess actor,DataScope scope) {
        if(scope.scopeType().equals("company") && scope.warehouseId()!=null || scope.scopeType().equals("warehouse")&&scope.warehouseId()==null)
            throw new ApiException(400,"VALIDATION_FAILED","仓库范围须指定仓库，公司范围不填写仓库");
        var owned=scopes(actor,actor.userId());
        boolean permitted=owned.stream().anyMatch(s->s.companyId().equals(scope.companyId()) &&
                (s.scopeType().equals("company") || scope.scopeType().equals("warehouse")&&Objects.equals(s.warehouseId(),scope.warehouseId())));
        if(!permitted) throw forbidden("只能分配自己拥有的数据范围");
        Long company=jdbc.queryForObject("SELECT COUNT(*) FROM company WHERE tenant_id=? AND id=? AND status='active'",Long.class,actor.tenantId(),scope.companyId());
        if(company==null||company==0) throw missing();
        if(scope.warehouseId()!=null && jdbc.queryForObject("SELECT COUNT(*) FROM warehouse WHERE tenant_id=? AND company_id=? AND id=? AND status='active'",
                Long.class,actor.tenantId(),scope.companyId(),scope.warehouseId())==0) throw missing();
    }

    private record AdministratorState(int total,Set<Long> companies) {}
    private AdministratorState lockAdministrators(CompanyAccess actor) {
        jdbc.queryForObject("SELECT id FROM tenant WHERE id=? FOR UPDATE",Long.class,actor.tenantId());
        requireUnrestrictedAdministration(actor);
        return new AdministratorState(administrators(actor),new OrgScopeResolver(jdbc).administrativeCompanies(actor.tenantId()));
    }

    private void requireUnrestrictedAdministration(CompanyAccess actor) {
        var companies=jdbc.queryForList("SELECT DISTINCT s.company_id FROM sys_user_data_scope s JOIN company c ON c.tenant_id=s.tenant_id AND c.id=s.company_id AND c.status='active' WHERE s.tenant_id=? AND s.user_id=?",Long.class,actor.tenantId(),actor.userId());
        var resolver=new OrgScopeResolver(jdbc);
        if(companies.stream().anyMatch(company->!resolver.resolve(actor.tenantId(),company,actor.userId()).allDepartments()))
            throw forbidden("用户角色、权限、数据范围及口令管理须由所授权公司均拥有全部部门范围的管理员处理");
    }

    private int administrators(CompanyAccess actor) {
        return (int)jdbc.queryForList("SELECT id FROM sys_user WHERE tenant_id=? AND status='active'",Long.class,actor.tenantId()).stream()
                .filter(id -> mapper.permissions(actor.tenantId(),id).containsAll(ADMIN_PERMISSIONS))
                .filter(id -> jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_data_scope WHERE tenant_id=? AND user_id=?",Long.class,actor.tenantId(),id)>0).count();
    }

    private void ensureAdministrators(CompanyAccess actor,AdministratorState before) {
        if(before.total()>0&&administrators(actor)==0) throw new ApiException(422,"DEPENDENCY_EXISTS","请保留至少一名有数据范围的有效管理员");
        if(!new OrgScopeResolver(jdbc).administrativeCompanies(actor.tenantId()).containsAll(before.companies())) throw new ApiException(422,"LAST_SCOPE_ADMIN","不能移除公司最后一位拥有全部部门范围的角色管理员");
    }

    private void touchUser(CompanyAccess actor,long id) {
        jdbc.update("UPDATE sys_user SET version=version+1,updated_by=? WHERE tenant_id=? AND id=?",actor.userId(),actor.tenantId(),id);
    }

    private UserView userAfter(CompanyAccess actor,long id,String action,UserView before) {
        var after=user(actor,id,false);
        audit.record(actor,action,"sys_user",id,redact(before),redact(after));return after;
    }

    private UserView redact(UserView view) {
        return view==null?null:new UserView(view.id(),view.username(),view.displayName(),null,view.status(),view.version(),
                view.mustChangePassword(),view.roleIds(),view.dataScopes());
    }

    private RoleView roleAfter(CompanyAccess actor,long id,String action,RoleView before) {
        var after=role(actor,id,false);
        audit.record(actor,action,"sys_role",id,before,after);return after;
    }

    private static long identifier(String value) {
        try {
            long id=Long.parseLong(value);
            if(id<=0) throw new NumberFormatException();
            return id;
        } catch(NumberFormatException error) {
            throw new ApiException(400,"VALIDATION_FAILED","资料编号无效");
        }
    }

    private static ApiException forbidden(String message) {return new ApiException(403,"FORBIDDEN",message);}
}
