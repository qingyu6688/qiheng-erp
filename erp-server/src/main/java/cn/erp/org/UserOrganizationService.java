package cn.erp.org;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.AuditService;
import cn.erp.shared.BusinessCommands;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.org.OrgModels.*;
import static cn.erp.org.OrgSupport.*;

@Service
@Transactional(readOnly=true)
class UserOrganizationService {
    private final JdbcTemplate jdbc;
    private final OrgSupport access;
    private final DepartmentService departments;
    private final PostService posts;
    private final BusinessCommands commands;
    private final AuditService audit;
    UserOrganizationService(JdbcTemplate jdbc,OrgSupport access,DepartmentService departments,PostService posts,BusinessCommands commands,AuditService audit) {
        this.jdbc=jdbc;this.access=access;this.departments=departments;this.posts=posts;this.commands=commands;this.audit=audit;
    }
    UserOrganization get(CompanyAccess actor,long userId) {
        access.requireAny(actor,"user:view","user:update");access.company(actor);access.user(actor,userId,false);return snapshot(actor,userId);
    }
    AssignmentOptions options(CompanyAccess actor) {
        access.require(actor,"user:update");access.company(actor);return new AssignmentOptions(departments.options(actor),posts.options(actor));
    }
    Page<User> candidates(CompanyAccess actor,String keyword,String cursor,int limit) {
        access.requireAny(actor,"org_department:view","user:update");access.company(actor);
        long after=cursor(cursor,limit);String search=keyword(keyword);
        var values=jdbc.query("""
            SELECT u.id,u.username,u.display_name,u.status FROM sys_user u
            WHERE u.tenant_id=? AND u.id>? AND (LOCATE(?,u.username)>0 OR LOCATE(?,u.display_name)>0)
              AND EXISTS(SELECT 1 FROM sys_user_data_scope s WHERE s.tenant_id=u.tenant_id AND s.user_id=u.id AND s.company_id=?)
            ORDER BY u.id LIMIT ?
            """,(r,n)->new User(r.getString(1),r.getString(2),r.getString(3),r.getString(4)),actor.tenantId(),after,search,search,actor.companyId(),limit+1);
        return new Page<>(values.stream().limit(limit).toList(),values.size()>limit?values.get(limit-1).id():null);
    }
    @Transactional
    UserOrganization assign(CompanyAccess actor,long userId,String key,Assignment input) {
        access.write(actor,"user:update");access.company(actor);access.user(actor,userId,false);
        return commands.execute(actor,"user.assign_organization",key,List.of(userId,input),200,()->{
            validate(actor,input);
            if(!input.departments().isEmpty() || !input.postIds().isEmpty()) access.user(actor,userId,true);
            jdbc.update("""
                INSERT INTO org_user_company(tenant_id,company_id,user_id,created_by,updated_by) VALUES(?,?,?,?,?)
                ON DUPLICATE KEY UPDATE version=version
                """,actor.tenantId(),actor.companyId(),userId,actor.userId(),actor.userId());
            var before=snapshot(actor,userId);version(input.version(),before.version());
            jdbc.update("DELETE FROM org_user_department WHERE tenant_id=? AND company_id=? AND user_id=?",actor.tenantId(),actor.companyId(),userId);
            jdbc.update("DELETE FROM org_user_post WHERE tenant_id=? AND company_id=? AND user_id=?",actor.tenantId(),actor.companyId(),userId);
            for(var membership:input.departments()) jdbc.update("INSERT INTO org_user_department(tenant_id,company_id,user_id,department_id,is_primary) VALUES(?,?,?,?,?)",
                    actor.tenantId(),actor.companyId(),userId,membership.id(),membership.primary());
            for(long post:input.postIds()) jdbc.update("INSERT INTO org_user_post(tenant_id,company_id,user_id,post_id) VALUES(?,?,?,?)",actor.tenantId(),actor.companyId(),userId,post);
            jdbc.update("UPDATE org_user_company SET version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND user_id=?",actor.userId(),actor.tenantId(),actor.companyId(),userId);
            var after=snapshot(actor,userId);audit.record(actor,"user.assign_organization","sys_user",userId,before,after);return after;
        },UserOrganization.class);
    }
    private void validate(CompanyAccess actor,Assignment input) {
        var ids=new LinkedHashSet<Long>();
        for(var entry:input.departments()) if(!ids.add(entry.id())) throw invalid("部门不能重复");
        if(!input.departments().isEmpty() && input.departments().stream().filter(m->Boolean.TRUE.equals(m.primary())).count()!=1) throw invalid("请选择且只选择一个主部门");
        if(new LinkedHashSet<>(input.postIds()).size()!=input.postIds().size()) throw invalid("岗位不能重复");
        for(long id:ids) if(!departments.find(actor,id).status().equals("active")) throw dependency("所选部门已停用，请刷新选择");
        for(long id:input.postIds()) if(!posts.find(actor,id).status().equals("active")) throw dependency("所选岗位已停用，请刷新选择");
    }
    private UserOrganization snapshot(CompanyAccess actor,long userId) {
        var versions=jdbc.queryForList("SELECT version FROM org_user_company WHERE tenant_id=? AND company_id=? AND user_id=?",Long.class,actor.tenantId(),actor.companyId(),userId);
        var memberships=jdbc.query("SELECT department_id,is_primary FROM org_user_department WHERE tenant_id=? AND company_id=? AND user_id=? ORDER BY department_id",
                (r,n)->new MembershipView(r.getString(1),r.getBoolean(2)),actor.tenantId(),actor.companyId(),userId);
        var postIds=jdbc.queryForList("SELECT CAST(post_id AS CHAR) FROM org_user_post WHERE tenant_id=? AND company_id=? AND user_id=? ORDER BY post_id",String.class,actor.tenantId(),actor.companyId(),userId);
        return new UserOrganization(Long.toString(userId),Long.toString(actor.companyId()),versions.isEmpty()?0:versions.getFirst(),memberships,postIds);
    }
}
