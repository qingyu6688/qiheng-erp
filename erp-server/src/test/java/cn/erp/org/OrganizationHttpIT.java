package cn.erp.org;

import cn.erp.shared.Ids;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrganizationHttpIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;
    long tenant=Ids.next(),company=Ids.next(),second=Ids.next(),foreignTenant=Ids.next(),foreignCompany=Ids.next();
    long manager=Ids.next(),member=Ids.next(),viewer=Ids.next(),adminRole=Ids.next(),viewerRole=Ids.next();
    String password=UUID.randomUUID().toString(),code="ORG-"+tenant;
    CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
    HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(10)).build();

    @BeforeEach void prepare() throws Exception {
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'组织HTTP企业'),(?,?,'其他企业')",tenant,code,foreignTenant,"OTHER-"+foreignTenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','甲公司'),(?,?,'B','乙公司'),(?,?,'C','外部公司')",company,tenant,second,tenant,foreignCompany,foreignTenant);
        for(long id:List.of(manager,member,viewer)) {
            String username=id==manager?"manager":id==member?"member":"viewer";
            jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash,must_change_password) VALUES(?,?,?,?,?,0)",id,tenant,username,username,encoder.encode(password));
            for(long current:List.of(company,second)) jdbc.update("INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)",Ids.next(),tenant,id,current);
        }
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'ADMIN','管理员'),(?,?,'VIEWER','只读')",adminRole,tenant,viewerRole,tenant);
        jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?),(?,?,?,?)",Ids.next(),tenant,manager,adminRole,Ids.next(),tenant,viewer,viewerRole);
        for(long permission:jdbc.queryForList("SELECT id FROM sys_permission",Long.class)) jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) VALUES(?,?,?,?)",Ids.next(),tenant,adminRole,permission);
        for(long permission:jdbc.queryForList("SELECT id FROM sys_permission WHERE code IN ('org_department:view','org_post:view','user:view')",Long.class)) jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) VALUES(?,?,?,?)",Ids.next(),tenant,viewerRole,permission);
        login("manager");
    }
    @AfterEach void clean() {
        for(String table:List.of("org_user_post","org_user_department","org_user_company")) jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);
        var departments=jdbc.queryForList("SELECT id FROM org_department WHERE tenant_id IN (?,?) ORDER BY depth DESC",Long.class,tenant,foreignTenant);
        for(long id:departments) jdbc.update("DELETE FROM org_department WHERE id=?",id);
        for(String table:List.of("org_post","audit_log","idempotency_record","sys_user_role","sys_role_permission","sys_role","sys_user_data_scope","sys_user")) jdbc.update("DELETE FROM "+table+" WHERE tenant_id IN (?,?)",tenant,foreignTenant);
        jdbc.update("DELETE FROM company WHERE tenant_id IN (?,?)",tenant,foreignTenant);jdbc.update("DELETE FROM tenant WHERE id IN (?,?)",tenant,foreignTenant);
    }
    void login(String username) throws Exception {
        cookies.getCookieStore().removeAll();
        call("POST","/auth/login",json.createObjectNode().put("tenantCode",code).put("username",username).put("password",password).toString(),company,200,false,null);
    }
    JsonNode create(long scope,String name,String key) throws Exception {
        return call("POST","/org/departments",json.createObjectNode().put("code",name).put("name",name).put("sortNo",0).toString(),scope,201,true,key).get("data");
    }
    String assignment(String department,String post,long version) {
        var body=json.createObjectNode().put("version",version);
        body.putArray("departments").addObject().put("id",department).put("primary",true);
        var posts=body.putArray("postIds");if(post!=null) posts.add(post);return body.toString();
    }
    @Test void Y01_04_httpRequiresCsrfPermissionCompanyAndVersion() throws Exception {
        call("POST","/org/departments","{\"code\":\"N\",\"name\":\"未校验\",\"sortNo\":0}",company,403,false,"csrf");
        var a=create(company,"A","create-a");
        assertThat(create(company,"A","create-a").get("id").asString()).isEqualTo(a.get("id").asString());
        call("POST","/org/departments","{\"code\":\"M\",\"name\":\"缺幂等键\",\"sortNo\":0}",company,400,true,null);
        call("POST","/org/departments","{\"code\":\"X\",\"name\":\"替换原请求\",\"sortNo\":0}",company,409,true,"create-a");
        var b=create(second,"B","create-b");
        call("GET","/org/departments/"+b.get("id").asString(),null,company,404,true,null);
        call("GET","/org/departments/"+a.get("id").asString(),null,foreignCompany,403,true,null);
        var invalid=json.createObjectNode().put("code","C").put("name","跨公司").put("sortNo",0).put("parentId",b.get("id").asString());
        call("POST","/org/departments",invalid.toString(),company,404,true,"cross-parent");
        call("POST","/org/departments/"+a.get("id").asString()+"/disable","{\"version\":99}",company,409,true,"stale");
        call("POST","/org/departments","{\"code\":\"BAD\",\"name\":\"\",\"sortNo\":0}",company,400,true,"invalid");
        login("viewer");
        call("GET","/org/departments/tree",null,company,200,true,null);
        call("POST","/org/departments","{\"code\":\"NO\",\"name\":\"越权\",\"sortNo\":0}",company,403,true,"forbidden");
        call("GET","/command-results?operation=org_department.create&key=create-a",null,company,403,true,null);
        call("PUT","/users/"+member+"/organization","{\"departments\":[],\"postIds\":[],\"version\":0}",company,403,true,"assign-forbidden");
        jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=?",tenant,viewerRole);
        call("GET","/org/departments/tree",null,company,403,true,null);
        call("GET","/org/posts",null,company,403,true,null);
    }
    @Test void Y01_02_companyAssignmentsAndRevocationCleanupAreAtomic() throws Exception {
        var a=create(company,"A","a");var b=create(second,"B","b");
        var post=call("POST","/org/posts","{\"code\":\"P\",\"name\":\"共享岗位\",\"sortNo\":0}",company,201,true,"p").get("data").get("id").asString();
        String path="/users/"+member+"/organization";
        call("PUT",path,assignment(a.get("id").asString(),post,0),company,200,true,"assign-a");
        call("PUT",path,assignment(b.get("id").asString(),post,0),second,200,true,"assign-b");
        var leader=json.createObjectNode().put("code","A").put("name","甲部门").put("sortNo",0).put("leaderUserId",member).put("version",0);
        call("PUT","/org/departments/"+a.get("id").asString(),leader.toString(),company,200,true,"leader");
        call("PUT","/users/"+member+"/data-scopes","{\"scopes\":[{\"companyId\":\""+second+"\",\"scopeType\":\"company\"}],\"version\":0}",company,200,true,null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM org_user_department WHERE tenant_id=? AND company_id=? AND user_id=?",Long.class,tenant,company,member)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM org_user_post WHERE tenant_id=? AND company_id=? AND user_id=?",Long.class,tenant,company,member)).isZero();
        assertThat(jdbc.queryForObject("SELECT leader_user_id FROM org_department WHERE id=?",Long.class,a.get("id").asString())).isNull();
        assertThat(jdbc.queryForObject("SELECT version FROM org_user_company WHERE tenant_id=? AND company_id=? AND user_id=?",Long.class,tenant,company,member)).isEqualTo(2);
        var remaining=call("GET",path,null,second,200,true,null).get("data");
        assertThat(remaining.get("departments").get(0).get("id").asString()).isEqualTo(b.get("id").asString());
        call("GET",path,null,company,404,true,null);
        call("GET","/command-results?operation=user.assign_organization&key=assign-a",null,company,404,true,null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND action='user.organization_scope_removed'",Long.class,tenant)).isEqualTo(1);
        call("PUT","/users/"+member+"/data-scopes","{\"scopes\":[{\"companyId\":\""+company+"\",\"scopeType\":\"company\"},{\"companyId\":\""+second+"\",\"scopeType\":\"company\"}],\"version\":1}",company,200,true,null);
        call("PUT",path,assignment(a.get("id").asString(),post,1),company,409,true,"before-revocation");
        assertThat(call("GET",path,null,second,200,true,null).get("data").get("version").asLong()).isEqualTo(1);
    }
    @Test void Y01_04_foreignTenantReferencesAndLeadersAreHidden() throws Exception {
        long foreign=Ids.next();
        jdbc.update("INSERT INTO org_department(id,tenant_id,company_id,code,name,path,depth) VALUES(?,?,?,'F','其他企业部门',?,1)",foreign,foreignTenant,foreignCompany,"/"+foreign+"/");
        call("GET","/org/departments/"+foreign,null,company,404,true,null);
        long foreignPost=Ids.next();
        jdbc.update("INSERT INTO org_post(id,tenant_id,code,name) VALUES(?,?,'F','外部岗位')",foreignPost,foreignTenant);
        call("GET","/org/posts/"+foreignPost,null,company,404,true,null);
        var postBody=json.createObjectNode().put("version",0);postBody.putArray("departments");postBody.putArray("postIds").add(foreignPost);
        call("PUT","/users/"+member+"/organization",postBody.toString(),company,404,true,"foreign-post");
        call("PUT","/users/"+member+"/organization",assignment(Long.toString(foreign),null,0),company,404,true,"foreign-member");
        jdbc.update("DELETE FROM sys_user_data_scope WHERE tenant_id=? AND user_id=? AND company_id=?",tenant,member,company);
        var body=json.createObjectNode().put("code","L").put("name","负责人越界").put("sortNo",0).put("leaderUserId",member);
        call("POST","/org/departments",body.toString(),company,404,true,"foreign-leader");
        call("PUT","/users/"+member+"/organization","{\"departments\":[],\"postIds\":[],\"version\":0}",company,404,true,"no-scope");
    }
    @Test void Y01_03_revocationInvalidatesInitiallyEmptyConfiguration() throws Exception {
        String path="/users/"+member+"/organization";
        assertThat(call("GET",path,null,company,200,true,null).get("data").get("version").asLong()).isZero();
        call("PUT","/users/"+member+"/data-scopes","{\"scopes\":[{\"companyId\":\""+second+"\",\"scopeType\":\"company\"}],\"version\":0}",company,200,true,null);
        call("PUT","/users/"+member+"/data-scopes","{\"scopes\":[{\"companyId\":\""+company+"\",\"scopeType\":\"company\"},{\"companyId\":\""+second+"\",\"scopeType\":\"company\"}],\"version\":1}",company,200,true,null);
        call("PUT",path,"{\"departments\":[],\"postIds\":[],\"version\":0}",company,409,true,"before-empty-revocation");
        assertThat(call("GET",path,null,company,200,true,null).get("data").get("version").asLong()).isEqualTo(1);
    }
    JsonNode call(String method,String path,String body,long scope,int expected,boolean csrf,String key) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type","application/json").header("X-Company-Id",Long.toString(scope));
        if(key!=null) request.header("Idempotency-Key",key);
        if(csrf) request.header("X-XSRF-TOKEN",cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst().orElseThrow());
        var response=client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        var result=json.readTree(response.body());
        assertThat(response.statusCode()).as(method+" "+path+" "+result.get("message")).isEqualTo(expected);return result;
    }
}
