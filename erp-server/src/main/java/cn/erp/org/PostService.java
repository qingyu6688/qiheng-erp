package cn.erp.org;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.AuditService;
import cn.erp.shared.BusinessCommands;
import cn.erp.shared.Ids;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.org.OrgModels.*;
import static cn.erp.org.OrgSupport.*;

@Service
@Transactional(readOnly=true)
class PostService {
    private static final String SELECT="SELECT p.*,(SELECT COUNT(*) FROM org_user_post m WHERE m.tenant_id=p.tenant_id AND m.post_id=p.id) member_count FROM org_post p WHERE p.tenant_id=?";
    private final JdbcTemplate jdbc;
    private final OrgSupport access;
    private final BusinessCommands commands;
    private final AuditService audit;
    PostService(JdbcTemplate jdbc,OrgSupport access,BusinessCommands commands,AuditService audit) {this.jdbc=jdbc;this.access=access;this.commands=commands;this.audit=audit;}
    Page<Post> list(CompanyAccess actor,String keyword,String cursor,int limit) {
        access.require(actor,"org_post:view");long after=cursor(cursor,limit);String search=keyword(keyword);
        var values=rows(SELECT+" AND p.id>? AND (LOCATE(?,p.code)>0 OR LOCATE(?,p.name)>0) ORDER BY p.id LIMIT ?",actor.tenantId(),after,search,search,limit+1);
        return new Page<>(values.stream().limit(limit).toList(),values.size()>limit?values.get(limit-1).id():null);
    }
    Post get(CompanyAccess actor,long id) {access.require(actor,"org_post:view");return find(actor,id);}
    Post find(CompanyAccess actor,long id) {
        var values=rows(SELECT+" AND p.id=?",actor.tenantId(),id);if(values.isEmpty()) throw missing();return values.getFirst();
    }
    List<Post> options(CompanyAccess actor) { return rows(SELECT+" AND p.status='active' ORDER BY p.sort_no,p.id",actor.tenantId()); }
    @Transactional
    Post create(CompanyAccess actor,String key,PostInput input) {
        access.write(actor,"org_post:create");
        return commands.execute(actor,"org_post.create",key,input,201,()->{
            long id=Ids.next();
            jdbc.update("INSERT INTO org_post(id,tenant_id,code,name,sort_no,created_by,updated_by) VALUES(?,?,?,?,?,?,?)",id,actor.tenantId(),input.code().strip(),input.name().strip(),input.sortNo(),actor.userId(),actor.userId());
            return changed(actor,id,"org_post.create",null);
        },Post.class);
    }
    @Transactional
    Post edit(CompanyAccess actor,long id,String key,PostInput input) {
        access.write(actor,"org_post:update");var before=find(actor,id);
        return commands.execute(actor,"org_post.update",key,List.of(id,input),200,()->{
            version(input.version(),before.version());
            jdbc.update("UPDATE org_post SET code=?,name=?,sort_no=?,version=version+1,updated_by=? WHERE tenant_id=? AND id=?",input.code().strip(),input.name().strip(),input.sortNo(),actor.userId(),actor.tenantId(),id);
            return changed(actor,id,"org_post.update",before);
        },Post.class);
    }
    @Transactional
    Post status(CompanyAccess actor,long id,String action,String key,Version input) {
        access.write(actor,"org_post:update");var before=find(actor,id);
        return commands.execute(actor,"org_post."+action,key,List.of(id,input),200,()->{
            version(input.version(),before.version());
            if(!List.of("enable","disable").contains(action)) throw invalid("岗位状态操作无效");
            if(action.equals("disable") && before.memberCount()>0) throw dependency("请先移除所有公司的岗位任职，再停用岗位");
            jdbc.update("UPDATE org_post SET status=?,version=version+1,updated_by=? WHERE tenant_id=? AND id=?",action.equals("enable")?"active":"disabled",actor.userId(),actor.tenantId(),id);
            return changed(actor,id,"org_post."+action,before);
        },Post.class);
    }
    private Post changed(CompanyAccess actor,long id,String action,Post before) {var after=find(actor,id);audit.record(actor,action,"org_post",id,before,after);return after;}
    private List<Post> rows(String sql,Object... args) {return jdbc.query(sql,(r,n)->new Post(r.getString("id"),r.getString("code"),r.getString("name"),r.getInt("sort_no"),r.getString("status"),r.getLong("version"),r.getLong("member_count")),args);}
}
