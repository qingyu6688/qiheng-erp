package cn.erp.org;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class OrgSupport {
    static final int MAX_DEPTH=20;
    static final int MAX_DEPARTMENTS=1000;
    private final JdbcTemplate jdbc;
    OrgSupport(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    void require(CompanyAccess actor,String permission) {
        if (!actor.hasPermission(permission)) throw forbidden();
    }
    void requireAny(CompanyAccess actor,String... permissions) {
        if (List.of(permissions).stream().noneMatch(actor::hasPermission)) throw forbidden();
    }
    void company(CompanyAccess actor) {
        if (actor.companyId()==0 || jdbc.queryForObject("SELECT COUNT(*) FROM company WHERE tenant_id=? AND id=? AND status='active'",Long.class,actor.tenantId(),actor.companyId())==0) throw missing();
    }
    void write(CompanyAccess actor,String permission) {
        require(actor,permission);
        // 组织写入与既有角色、公司和数据范围管理共用企业屏障，防止任职与撤权交错。
        if (jdbc.queryForList("SELECT id FROM tenant WHERE id=? AND status='active' FOR UPDATE",Long.class,actor.tenantId()).isEmpty()) throw missing();
        if (actor.companyId()!=0) {
            if (jdbc.queryForList("SELECT id FROM company WHERE tenant_id=? AND id=? AND status='active' FOR UPDATE",Long.class,actor.tenantId(),actor.companyId()).isEmpty()) throw missing();
            if (!new cn.erp.identity.OrgScopeResolver(jdbc).resolve(actor.tenantId(),actor.companyId(),actor.userId()).allDepartments())
                throw new ApiException(403,"FORBIDDEN","组织任职及部门维护须由当前公司拥有全部部门范围的管理员处理");
        }
    }
    void user(CompanyAccess actor,long userId,boolean active) {
        String sql="SELECT COUNT(*) FROM sys_user u WHERE u.tenant_id=? AND u.id=?"+(active?" AND u.status='active'":"")
                +" AND EXISTS (SELECT 1 FROM org_user_data_scope s WHERE s.tenant_id=u.tenant_id AND s.user_id=u.id AND s.company_id=?)";
        if (jdbc.queryForObject(sql,Long.class,actor.tenantId(),userId,actor.companyId())==0) throw missing();
    }
    static void version(Long supplied,long actual) {
        if (supplied==null) throw invalid("请提供版本号");
        if (supplied!=actual) throw new ApiException(409,"VERSION_CONFLICT","资料已被修改，请刷新后重试");
    }
    static long parent(Long id) { return id==null?0:id; }
    static ApiException missing() { return new ApiException(404,"NOT_FOUND","资料不存在或无权访问"); }
    static ApiException forbidden() { return new ApiException(403,"FORBIDDEN","没有执行此操作的权限"); }
    static ApiException invalid(String message) { return new ApiException(400,"VALIDATION_FAILED",message); }
    static ApiException dependency(String message) { return new ApiException(422,"DEPENDENCY_EXISTS",message); }
    static long cursor(String cursor,int limit) {
        if (limit<1 || limit>200) throw invalid("每页条数须在1到200之间");
        try { long result=cursor==null?0:Long.parseLong(cursor);if (result<0) throw new NumberFormatException();return result; }
        catch (NumberFormatException error) { throw invalid("分页游标无效"); }
    }
    static String keyword(String keyword) {
        String value=keyword==null?"":keyword.strip();
        if(value.length()>100) throw invalid("搜索条件最多100字");return value;
    }
}
