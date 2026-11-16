package cn.erp.org;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.org.OrgModels.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
@Transactional
class OrganizationIT {
    @Autowired DepartmentService departments;
    @Autowired PostService posts;
    @Autowired UserOrganizationService users;
    @Autowired JdbcTemplate jdbc;
    long tenant,company,otherCompany,user;
    CompanyAccess actor,other;

    @BeforeEach void prepare() {
        tenant=Ids.next();company=Ids.next();otherCompany=Ids.next();user=Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'组织测试企业')",tenant,"O"+tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','甲公司'),(?,?,'B','乙公司')",company,tenant,otherCompany,tenant);
        jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,'member','任职人员','不可用于登录的测试散列')",user,tenant);
        for(long id:List.of(company,otherCompany)) jdbc.update("INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)",Ids.next(),tenant,user,id);
        var permissions=Set.of("org_department:view","org_department:create","org_department:update","org_post:view","org_post:create","org_post:update","user:view","user:update");
        actor=new CompanyAccess(tenant,user,company,true,Set.of(),permissions);
        other=new CompanyAccess(tenant,user,otherCompany,true,Set.of(),permissions);
    }
    Department create(String code,Long parent) {
        return departments.create(actor,"create-"+Ids.next(),new DepartmentInput(code,code,parent,null,0,null));
    }
    Post post() { return posts.create(actor,"post-"+Ids.next(),new PostInput("P","共享岗位",0,null)); }
    Assignment assignment(Department department,Post post,long version) {
        return new Assignment(List.of(new Membership(Long.valueOf(department.id()),true)),List.of(Long.valueOf(post.id())),version);
    }
    @Test void Y01_01_departmentTreeAndMovePreserveDescendantPaths() {
        var a=create("A",null);var b=create("B",Long.valueOf(a.id()));var c=create("C",Long.valueOf(b.id()));var root=create("R",null);
        var moved=departments.move(actor,Long.parseLong(b.id()),"move",new Move(Long.valueOf(root.id()),null,b.version()));
        assertThat(moved.path()).isEqualTo(root.path()+b.id()+"/");
        var child=departments.get(actor,Long.parseLong(c.id()));
        assertThat(child.path()).isEqualTo(moved.path()+c.id()+"/");assertThat(child.version()).isGreaterThan(c.version());
        assertThat(departments.tree(actor)).hasSize(4);
        assertThatThrownBy(()->departments.move(actor,Long.parseLong(root.id()),"cycle",new Move(Long.valueOf(c.id()),null,root.version())))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(400));
        assertThatThrownBy(()->departments.edit(actor,Long.parseLong(b.id()),"stale",new DepartmentInput("B","过期修改",Long.valueOf(root.id()),null,0,b.version())))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(409));
    }
    @Test void Y01_01_depthAndCompanyBoundariesAreEnforced() {
        Department current=create("N0",null);
        for(int i=1;i<20;i++) current=create("N"+i,Long.valueOf(current.id()));
        long parent=Long.parseLong(current.id());
        assertThatThrownBy(()->create("TOO-DEEP",parent)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(422));
        var foreign=departments.create(other,"other",new DepartmentInput("A","乙部门",null,null,0,null));
        assertThatThrownBy(()->departments.get(actor,Long.parseLong(foreign.id()))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(404));
        assertThatThrownBy(()->create("CROSS",Long.valueOf(foreign.id()))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(404));
    }
    @Test void Y01_02_primaryDepartmentsAndPostsAreIndependentPerCompany() {
        var a=create("A",null);var b=departments.create(other,"b",new DepartmentInput("A","乙公司部门",null,null,0,null));var p=post();
        var first=users.assign(actor,user,"assign-a",assignment(a,p,0));
        var second=users.assign(other,user,"assign-b",assignment(b,p,0));
        assertThat(first.version()).isEqualTo(1);assertThat(second.version()).isEqualTo(1);
        assertThat(users.assign(actor,user,"assign-a",assignment(a,p,0))).isEqualTo(first);
        users.assign(actor,user,"clear-a",new Assignment(List.of(),List.of(),first.version()));
        assertThat(users.get(other,user)).isEqualTo(second);
        assertThat(users.get(actor,user).departments()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT version FROM sys_user WHERE id=?",Long.class,user)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_data_scope WHERE tenant_id=? AND user_id=?",Long.class,tenant,user)).isEqualTo(2);
    }
    @Test void Y01_02_rejectsAmbiguousPrimaryDuplicateAndForeignReferences() {
        var a=create("A",null);var b=create("B",null);var p=post();
        var two=new Assignment(List.of(new Membership(Long.valueOf(a.id()),true),new Membership(Long.valueOf(b.id()),true)),List.of(),0L);
        assertThatThrownBy(()->users.assign(actor,user,"two",two)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(400));
        var duplicate=new Assignment(List.of(new Membership(Long.valueOf(a.id()),true),new Membership(Long.valueOf(a.id()),false)),List.of(),0L);
        assertThatThrownBy(()->users.assign(actor,user,"duplicate",duplicate)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->users.assign(other,user,"wrong-company",assignment(a,p,0))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(404));
        jdbc.update("DELETE FROM sys_user_data_scope WHERE tenant_id=? AND user_id=? AND company_id=?",tenant,user,company);
        assertThatThrownBy(()->users.assign(actor,user,"without-scope",assignment(a,p,0))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(404));
    }
    @Test void Y01_05_disabledAndReferencedObjectsCannotBeAssignedOrDisabled() {
        var a=create("A",null);var p=post();users.assign(actor,user,"assign",assignment(a,p,0));
        assertThatThrownBy(()->departments.status(actor,Long.parseLong(a.id()),"disable","disable-a",new Version(a.version())))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(422));
        assertThatThrownBy(()->posts.status(other,Long.parseLong(p.id()),"disable","disable-p",new Version(p.version())))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(422));
        users.assign(actor,user,"clear",new Assignment(List.of(),List.of(),1L));
        var disabled=departments.status(actor,Long.parseLong(a.id()),"disable","disable-free",new Version(a.version()));
        assertThat(disabled.status()).isEqualTo("disabled");
        assertThatThrownBy(()->users.assign(actor,user,"assign-disabled",assignment(disabled,p,2)))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(422));
    }
    @Test void Y01_04_servicesRejectMissingPermissionsAndDoNotExposeMobile() {
        var denied=new CompanyAccess(tenant,user,company,true,Set.of(),Set.of());
        assertThatThrownBy(()->departments.tree(denied)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(403));
        assertThatThrownBy(()->posts.list(denied,null,null,20)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(403));
        assertThatThrownBy(()->users.assign(denied,user,"forbidden",new Assignment(List.of(),List.of(),0L))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(403));
        assertThat(users.candidates(actor,null,null,20).items()).extracting(User::id).contains(Long.toString(user));
    }
}
