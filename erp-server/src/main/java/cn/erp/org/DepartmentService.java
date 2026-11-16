package cn.erp.org;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.AuditService;
import cn.erp.shared.BusinessCommands;
import cn.erp.shared.Ids;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.org.OrgModels.*;
import static cn.erp.org.OrgSupport.*;

@Service
@Transactional(readOnly=true)
class DepartmentService {
    private static final String SELECT="""
        SELECT d.*,u.display_name leader_name,
          (SELECT COUNT(*) FROM org_user_department m WHERE m.tenant_id=d.tenant_id AND m.company_id=d.company_id AND m.department_id=d.id) member_count
        FROM org_department d LEFT JOIN sys_user u ON u.tenant_id=d.tenant_id AND u.id=d.leader_user_id
        WHERE d.tenant_id=? AND d.company_id=?
        """;
    private final JdbcTemplate jdbc;
    private final OrgSupport access;
    private final BusinessCommands commands;
    private final AuditService audit;
    DepartmentService(JdbcTemplate jdbc,OrgSupport access,BusinessCommands commands,AuditService audit) {
        this.jdbc=jdbc;this.access=access;this.commands=commands;this.audit=audit;
    }
    List<Department> tree(CompanyAccess actor) {
        access.require(actor,"org_department:view");access.company(actor);
        return rows(SELECT+" ORDER BY d.sort_no,d.id",actor.tenantId(),actor.companyId());
    }
    Department get(CompanyAccess actor,long id) {
        access.require(actor,"org_department:view");access.company(actor);return find(actor,id);
    }
    Department find(CompanyAccess actor,long id) {
        var values=rows(SELECT+" AND d.id=?",actor.tenantId(),actor.companyId(),id);
        if(values.isEmpty()) throw missing();return values.getFirst();
    }
    List<Department> options(CompanyAccess actor) { return rows(SELECT+" AND d.status='active' ORDER BY d.sort_no,d.id",actor.tenantId(),actor.companyId()); }
    @Transactional
    Department create(CompanyAccess actor,String key,DepartmentInput input) {
        access.write(actor,"org_department:create");access.company(actor);
        return commands.execute(actor,"org_department.create",key,input,201,()->{
            if(jdbc.queryForObject("SELECT COUNT(*) FROM org_department WHERE tenant_id=? AND company_id=?",Long.class,actor.tenantId(),actor.companyId())>=MAX_DEPARTMENTS) throw dependency("每家公司最多1000个部门");
            Department parent=parentNode(actor,parent(input.parentId()));
            validateLeader(actor,input.leaderUserId());
            int depth=parent==null?1:parent.depth()+1;
            if(depth>MAX_DEPTH) throw dependency("部门层级最多20层");
            long id=Ids.next();String path=(parent==null?"/":parent.path())+id+"/";
            jdbc.update("""
                INSERT INTO org_department(id,tenant_id,company_id,parent_id,code,name,leader_user_id,sort_no,path,depth,created_by,updated_by)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,actor.tenantId(),actor.companyId(),nullableParent(input.parentId()),input.code().strip(),input.name().strip(),input.leaderUserId(),input.sortNo(),path,depth,actor.userId(),actor.userId());
            return changed(actor,id,"org_department.create",null);
        },Department.class);
    }
    @Transactional
    Department edit(CompanyAccess actor,long id,String key,DepartmentInput input) {
        access.write(actor,"org_department:update");access.company(actor);var before=find(actor,id);
        return commands.execute(actor,"org_department.update",key,List.of(id,input),200,()->{
            version(input.version(),before.version());
            if(parent(input.parentId())!=Long.parseLong(before.parentId())) throw invalid("请通过移动部门调整上级");
            validateLeader(actor,input.leaderUserId());
            jdbc.update("UPDATE org_department SET code=?,name=?,leader_user_id=?,sort_no=?,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",
                    input.code().strip(),input.name().strip(),input.leaderUserId(),input.sortNo(),actor.userId(),actor.tenantId(),actor.companyId(),id);
            return changed(actor,id,"org_department.update",before);
        },Department.class);
    }
    @Transactional
    Department move(CompanyAccess actor,long id,String key,Move input) {
        access.write(actor,"org_department:update");access.company(actor);var before=find(actor,id);
        return commands.execute(actor,"org_department.move",key,List.of(id,input),200,()->{
            version(input.version(),before.version());
            var nextParent=parentNode(actor,parent(input.parentId()));
            if(nextParent!=null && nextParent.path().startsWith(before.path())) throw invalid("不能将部门移入自身或下级部门");
            int depth=nextParent==null?1:nextParent.depth()+1;
            int delta=depth-before.depth();
            int maximum=jdbc.queryForObject("SELECT MAX(depth) FROM org_department WHERE tenant_id=? AND company_id=? AND path LIKE ?",Integer.class,actor.tenantId(),actor.companyId(),before.path()+"%");
            if(maximum+delta>MAX_DEPTH) throw dependency("移动后的部门层级超过20层");
            String prefix=(nextParent==null?"/":nextParent.path())+id+"/";
            jdbc.update("UPDATE org_department SET path=CONCAT(?,SUBSTRING(path,?)),depth=depth+?,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND path LIKE ?",
                    prefix,before.path().length()+1,delta,actor.userId(),actor.tenantId(),actor.companyId(),before.path()+"%");
            jdbc.update("UPDATE org_department SET parent_id=? WHERE tenant_id=? AND company_id=? AND id=?",nullableParent(input.parentId()),actor.tenantId(),actor.companyId(),id);
            reorder(actor,parent(input.parentId()),id,input.beforeId());
            return changed(actor,id,"org_department.move",before);
        },Department.class);
    }
    private void reorder(CompanyAccess actor,long parentId,long movingId,Long beforeId) {
        if(beforeId!=null) find(actor,beforeId);
        var siblings=new ArrayList<>(jdbc.queryForList("SELECT id FROM org_department WHERE tenant_id=? AND company_id=? AND COALESCE(parent_id,0)=? AND id<>? ORDER BY sort_no,id",Long.class,actor.tenantId(),actor.companyId(),parentId,movingId));
        int position=beforeId==null?siblings.size():siblings.indexOf(beforeId);
        if(position<0) throw invalid("排序参照部门不在同一上级下");
        siblings.add(position,movingId);
        for(int index=0;index<siblings.size();index++) jdbc.update("UPDATE org_department SET sort_no=?,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=? AND sort_no<>?",
                index,actor.userId(),actor.tenantId(),actor.companyId(),siblings.get(index),index);
    }
    @Transactional
    Department status(CompanyAccess actor,long id,String action,String key,Version input) {
        access.write(actor,"org_department:update");access.company(actor);var before=find(actor,id);
        return commands.execute(actor,"org_department."+action,key,List.of(id,input),200,()->{
            version(input.version(),before.version());
            if(action.equals("disable")) {
                long children=jdbc.queryForObject("SELECT COUNT(*) FROM org_department WHERE tenant_id=? AND company_id=? AND parent_id=?",Long.class,actor.tenantId(),actor.companyId(),id);
                if(children>0 || before.memberCount()>0) throw dependency("请先移出下级部门和成员，再停用部门");
            } else if(action.equals("enable")) { parentNode(actor,Long.parseLong(before.parentId())); }
            else throw invalid("部门状态操作无效");
            jdbc.update("UPDATE org_department SET status=?,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",action.equals("enable")?"active":"disabled",actor.userId(),actor.tenantId(),actor.companyId(),id);
            return changed(actor,id,"org_department."+action,before);
        },Department.class);
    }
    Page<User> members(CompanyAccess actor,long id,String cursor,int limit) {
        get(actor,id);long after=cursor(cursor,limit);
        var users=jdbc.query("""
            SELECT u.id,u.username,u.display_name,u.status FROM org_user_department m
            JOIN sys_user u ON u.tenant_id=m.tenant_id AND u.id=m.user_id
            WHERE m.tenant_id=? AND m.company_id=? AND m.department_id=? AND u.id>? ORDER BY u.id LIMIT ?
            """,(r,n)->new User(r.getString(1),r.getString(2),r.getString(3),r.getString(4)),actor.tenantId(),actor.companyId(),id,after,limit+1);
        return new Page<>(users.stream().limit(limit).toList(),users.size()>limit?users.get(limit-1).id():null);
    }
    private Department parentNode(CompanyAccess actor,long id) {
        if(id==0) return null;
        var node=find(actor,id);if(!node.status().equals("active")) throw dependency("上级部门已停用，请先启用");return node;
    }
    private void validateLeader(CompanyAccess actor,Long id) { if(id!=null) access.user(actor,id,true); }
    private Long nullableParent(Long id) { return parent(id)==0?null:id; }
    private Department changed(CompanyAccess actor,long id,String action,Department before) {
        var after=find(actor,id);audit.record(actor,action,"org_department",id,before,after);return after;
    }
    private List<Department> rows(String sql,Object... args) {
        return jdbc.query(sql,(r,n)->new Department(r.getString("id"),r.getString("parent_id")==null?"0":r.getString("parent_id"),r.getString("code"),r.getString("name"),r.getString("leader_user_id"),r.getString("leader_name"),r.getInt("sort_no"),r.getString("path"),r.getInt("depth"),r.getString("status"),r.getLong("version"),r.getLong("member_count")),args);
    }
}
