package cn.erp.identity;

import cn.erp.ErpApplication;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static cn.erp.identity.AdminModels.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
@Transactional
class IdentityAdministrationIT {
    @Autowired IdentityAdminService admin;
    @Autowired OrganizationService organization;
    @Autowired CredentialsService credentials;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;
    private CompanyAccess actor;
    private long tenant;
    private long user;
    private long company;
    private long role;

    @BeforeEach
    void prepare() {
        tenant=Ids.next();user=Ids.next();company=Ids.next();role=Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'权限测试企业')",tenant,"T"+tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'C','测试公司')",company,tenant);
        jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,'manager','管理员',?)",user,tenant,encoder.encode("sample-password"));
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'ADMIN','管理员')",role,tenant);
        jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",Ids.next(),tenant,user,role);
        for(Long id:jdbc.queryForList("SELECT id FROM sys_permission",Long.class)) jdbc.update(
                "INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) VALUES(?,?,?,?)",Ids.next(),tenant,role,id);
        jdbc.update("INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)",Ids.next(),tenant,user,company);
        actor=new CompanyAccess(tenant,user,0,true,Set.of(),Set.copyOf(jdbc.queryForList("SELECT code FROM sys_permission",String.class)));
    }

    @Test
    void companyAndPolicyChangesAreVersionedAndAudited() {
        var input=new CompanyInput("N","新公司",null,null,null,null);
        String key=UUID.randomUUID().toString();
        var created=organization.create(actor,key,input);
        assertThat(organization.create(actor,key,input).id()).isEqualTo(created.id());
        var policy=organization.policy(actor,Long.parseLong(created.id()),new PolicyInput("small",45,7,"1.50",true,true,0L));
        assertThat(policy.taxpayerType()).isEqualTo("small");
        assertThat(policy.version()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND action='company_policy.update'",Long.class,tenant)).isEqualTo(1L);
        assertThatThrownBy(()->organization.edit(actor,Long.parseLong(created.id()),new CompanyInput("N","旧版本",null,null,null,3L)))
                .isInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e).getStatus()).isEqualTo(409));
    }

    @Test
    void grantsCannotExceedOperatorsPermissionsOrDataScope() {
        var newUser=admin.createUser(actor,UUID.randomUUID().toString(),new UserInput("clerk","职员","temporary-password",null));
        long id=Long.parseLong(newUser.id());
        var limited=new CompanyAccess(tenant,user,0,true,Set.of(),Set.of("user:assign_role"));
        assertThatThrownBy(()->admin.assignRoles(limited,id,new RoleAssignment(List.of(Long.toString(role)),0L))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->admin.assignScopes(actor,id,new ScopeAssignment(List.of(new DataScope("999999","company",null)),0L)))
                .isInstanceOf(ApiException.class);
        var assigned=admin.assignScopes(actor,id,new ScopeAssignment(List.of(new DataScope(Long.toString(company),"company",null)),0L));
        assertThat(assigned.dataScopes()).hasSize(1);
    }

    @Test
    void lastAdministratorRemainsUsable() {
        assertThatThrownBy(()->admin.assignPermissions(actor,role,new PermissionAssignment(List.of("role:view"),0L)))
                .isInstanceOf(ApiException.class).hasMessageContaining("至少一名");
    }

    @Test
    void tenantIsolationAndSensitiveFieldsAreEnforced() {
        var target=admin.createUser(actor,UUID.randomUUID().toString(),new UserInput("phone","联系人","temporary-password","13800000000"));
        var limited=new CompanyAccess(tenant,user,0,true,Set.of(),Set.of("user:view"));
        assertThat(json.writeValueAsString(admin.user(limited,Long.parseLong(target.id()),false))).doesNotContain("mobile");
        var foreign=new CompanyAccess(Ids.next(),user,0,true,Set.of(),actor.permissions());
        assertThatThrownBy(()->admin.user(foreign,Long.parseLong(target.id()),false)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->organization.company(foreign,company,false)).isInstanceOf(ApiException.class);
    }

    @Test
    void auditPaginationHasNoDuplicateOrOutOfScopeRows() {
        long hiddenCompany=Ids.next();
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'HIDDEN','其他公司')",hiddenCompany,tenant);
        var visible=new HashSet<String>();
        for(int i=0;i<4;i++) {
            long id=Ids.next();
            visible.add(Long.toString(id));
            jdbc.update("INSERT INTO audit_log(id,tenant_id,company_id,action,object_type) VALUES(?,?,?,'visible','company')",
                    id,tenant,i==0?null:company);
        }
        jdbc.update("INSERT INTO audit_log(id,tenant_id,company_id,action,object_type) VALUES(?,?,?,'hidden_company','company')",
                Ids.next(),tenant,hiddenCompany);
        jdbc.update("INSERT INTO audit_log(id,tenant_id,action,object_type) VALUES(?,?,'hidden_tenant','company')",Ids.next(),Ids.next());
        var first=organization.auditLogs(actor,null,2);
        var second=organization.auditLogs(actor,first.nextCursor(),2);
        assertThat(first.items()).hasSize(2);
        assertThat(first.nextCursor()).isNotNull();
        assertThat(second.items()).hasSize(2);
        assertThat(second.nextCursor()).isNull();
        var found=new ArrayList<String>();
        first.items().forEach(row->found.add(row.get("id").toString()));
        second.items().forEach(row->found.add(row.get("id").toString()));
        assertThat(found).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(visible);
        assertThatThrownBy(()->organization.auditLogs(actor,"无效游标",2)).isInstanceOf(ApiException.class);
    }

    @Test
    void resetPasswordInvalidatesOldCredentialsAndRequiresChange() {
        var target=admin.createUser(actor,UUID.randomUUID().toString(),new UserInput("reset","重置用户","temporary-password",null));
        var changed=admin.resetPassword(actor,Long.parseLong(target.id()),UUID.randomUUID().toString(),new ResetPassword("replacement-password",0L));
        assertThat(changed.mustChangePassword()).isTrue();
        String code=jdbc.queryForObject("SELECT code FROM tenant WHERE id=?",String.class,tenant);
        var account=credentials.authenticate(code,"reset","replacement-password");
        assertThat(account.userId()).isEqualTo(Long.parseLong(target.id()));
        assertThat(jdbc.queryForObject("SELECT after_data FROM audit_log WHERE tenant_id=? AND action='user.reset_password'",String.class,tenant))
                .doesNotContain("replacement-password").doesNotContain("password_hash");
    }
}
