package cn.erp.org;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static cn.erp.org.OrgModels.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class OrganizationConcurrencyIT {
    @Autowired DepartmentService departments;
    @Autowired UserOrganizationService users;
    @Autowired JdbcTemplate jdbc;
    long tenant=Ids.next(),company=Ids.next(),second=Ids.next(),user=Ids.next();
    CompanyAccess actor,other;
    @BeforeEach void prepare() {
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'组织并发企业')",tenant,"CON-"+tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','甲公司'),(?,?,'B','乙公司')",company,tenant,second,tenant);
        jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,'member','任职人员','测试散列')",user,tenant);
        long role=Ids.next();
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'ORG','组织管理员')",role,tenant);
        jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",Ids.next(),tenant,user,role);
        for(long scope:List.of(company,second)) jdbc.update("INSERT INTO org_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)",Ids.next(),tenant,user,scope);
        var permissions=Set.of("org_department:create","org_department:update","org_department:view","user:update","user:view");
        actor=new CompanyAccess(tenant,user,company,true,Set.of(),permissions);
        other=new CompanyAccess(tenant,user,second,true,Set.of(),permissions);
    }
    @AfterEach void clean() {
        for(String table:List.of("org_user_post","org_user_department","org_user_company")) jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);
        for(long id:jdbc.queryForList("SELECT id FROM org_department WHERE tenant_id=? ORDER BY depth DESC",Long.class,tenant)) jdbc.update("DELETE FROM org_department WHERE id=?",id);
        for(String table:List.of("audit_log","idempotency_record","sys_user_role","sys_role","org_user_data_scope","sys_user","company")) jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);
        jdbc.update("DELETE FROM tenant WHERE id=?",tenant);
    }
    Department create(CompanyAccess scope,String code) {
        return departments.create(scope,"create-"+code,new DepartmentInput(code,code,null,null,0,null));
    }
    Assignment assignment(Department row) { return new Assignment(List.of(new Membership(Long.valueOf(row.id()),true)),List.of(),0L); }
    @Test void Y01_02_concurrentAssignmentsHaveOnePrimaryAndRejectStaleVersion() throws Exception {
        var a=create(actor,"A");var b=create(actor,"B");
        assertThat(race(()->users.assign(actor,user,"assign-a",assignment(a)),()->users.assign(actor,user,"assign-b",assignment(b))))
                .containsExactlyInAnyOrder(200,409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM org_user_department WHERE tenant_id=? AND is_primary=1",Long.class,tenant)).isEqualTo(1);
        assertThat(users.get(actor,user).version()).isEqualTo(1);
    }
    @Test void Y01_02_concurrentCompaniesHaveIndependentVersions() throws Exception {
        var a=create(actor,"A");var b=create(other,"B");
        assertThat(race(()->users.assign(actor,user,"assign-a",assignment(a)),()->users.assign(other,user,"assign-b",assignment(b))))
                .containsExactly(200,200);
        assertThat(users.get(actor,user).version()).isEqualTo(1);
        assertThat(users.get(other,user).version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM org_user_department WHERE tenant_id=? AND is_primary=1",Long.class,tenant)).isEqualTo(2);
    }
    @Test void Y01_01_concurrentOppositeMovesCannotCreateCycle() throws Exception {
        var a=create(actor,"A");var b=create(actor,"B");
        var results=race(()->departments.move(actor,Long.parseLong(a.id()),"move-a",new Move(Long.valueOf(b.id()),null,0L)),
                ()->departments.move(actor,Long.parseLong(b.id()),"move-b",new Move(Long.valueOf(a.id()),null,0L)));
        assertThat(results).contains(200);assertThat(results.stream().filter(value->value==200).count()).isEqualTo(1);
        assertThat(results).allMatch(value->value==200 || value==400 || value==409);
        assertThat(departments.tree(actor)).filteredOn(row->row.parentId().equals("0")).hasSize(1);
        assertThat(departments.tree(actor)).extracting(Department::depth).containsExactlyInAnyOrder(1,2);
    }
    private List<Integer> race(Supplier<?> first,Supplier<?> second) throws Exception {
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var one=executor.submit(()->invoke(start,first));var two=executor.submit(()->invoke(start,second));start.countDown();
            return List.of(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS));
        }
    }
    private int invoke(CountDownLatch start,Supplier<?> work) throws InterruptedException {
        if(!start.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("并发测试启动超时");
        try { work.get();return 200; } catch(ApiException error) { return error.getStatus(); }
    }
}
