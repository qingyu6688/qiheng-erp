package cn.erp.org;

import cn.erp.shared.Ids;
import cn.erp.documents.ObjectFiles;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DepartmentScopeHttpIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;
    @MockitoSpyBean ObjectFiles files;
    @LocalServerPort int port;
    long tenant=Ids.next(),company=Ids.next(),second=Ids.next(),admin=Ids.next(),user=Ids.next(),adminRole=Ids.next(),role=Ids.next();
    long a=Ids.next(),b=Ids.next(),docA=Ids.next(),docB=Ids.next();
    String code="Y02-"+tenant,password=UUID.randomUUID().toString();
    CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
    HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(10)).build();
    @BeforeEach void prepare() throws Exception {
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'部门范围HTTP企业')",tenant,code);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','甲公司'),(?,?,'B','乙公司')",company,tenant,second,tenant);
        for(long id:List.of(admin,user)) {
            jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash,must_change_password) VALUES(?,?,?,?,?,0)",id,tenant,id==admin?"admin":"user","范围用户",encoder.encode(password));
            for(long scope:List.of(company,second)) jdbc.update("INSERT INTO org_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)",Ids.next(),tenant,id,scope);
        }
        for(long id:List.of(adminRole,role)) {
            jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,?,'范围测试角色')",id,tenant,"R"+id);
            jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",Ids.next(),tenant,id==adminRole?admin:user,id);
            for(long permission:jdbc.queryForList("SELECT id FROM sys_permission",Long.class)) jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) VALUES(?,?,?,?)",Ids.next(),tenant,id,permission);
        }
        for(long id:List.of(a,b)) jdbc.update("INSERT INTO org_department(id,tenant_id,company_id,code,name,path,depth) VALUES(?,?,?,?,?,?,1)",id,tenant,company,"D"+id,"部门"+id,"/"+id+"/");
        jdbc.update("INSERT INTO org_user_company(tenant_id,company_id,user_id) VALUES(?,?,?)",tenant,company,user);
        jdbc.update("INSERT INTO org_user_department(tenant_id,company_id,user_id,department_id,is_primary) VALUES(?,?,?,?,1)",tenant,company,user,a);
        for(long id:List.of(docA,docB)) jdbc.update("INSERT INTO purchase_requisition(id,tenant_id,company_id,requisition_no,requester_id,business_date,created_by,owner_department_id,owner_user_id) VALUES(?,?,?,?,?,CURRENT_DATE(),?,?,?)",id,tenant,company,"Q"+id,user,user,id==docA?a:b,user);
        login("admin");
    }
    @AfterEach void clean() {
        for(String storageKey:jdbc.queryForList("SELECT storage_key FROM attachment WHERE tenant_id=?",String.class,tenant)) files.delete(storageKey);
        jdbc.update("DELETE FROM file_ticket WHERE tenant_id=?",tenant);
        jdbc.update("DELETE FROM attachment WHERE tenant_id=?",tenant);
        for(String table:List.of("org_role_data_scope_dept","org_role_data_scope","org_user_department","org_user_company","purchase_requisition","org_department","audit_log","idempotency_record","sys_user_role","sys_role_permission","sys_role","org_user_data_scope","sys_user","company")) jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);
        jdbc.update("DELETE FROM tenant WHERE id=?",tenant);
    }
    void login(String name) throws Exception {cookies.getCookieStore().removeAll();call("POST","/auth/login",json.createObjectNode().put("tenantCode",code).put("username",name).put("password",password).toString(),company,200,false,null);}
    String input(String type,long version,long... ids) {var node=json.createObjectNode().put("scopeType",type).put("version",version);var values=node.putArray("departmentIds");for(long id:ids) values.add(Long.toString(id));return node.toString();}
    String path(long id) {return "/org/roles/"+id+"/data-scope";}
    @Test void Y02_06_roleScopesAreCompanyVersionedAndCannotEscalate() throws Exception {
        call("PUT",path(role),input("dept",0),company,403,false,"csrf");
        var saved=call("PUT",path(role),input("dept",0),company,200,true,"save").get("data");assertThat(saved.get("version").asLong()).isEqualTo(1);
        call("PUT",path(role),input("dept",0),company,200,true,"save");
        call("PUT",path(role),input("self",0),company,409,true,"stale");
        assertThat(call("GET",path(role),null,second,200,true,null).get("data").get("version").asLong()).isZero();
        call("PUT",path(role),input("custom",0,a),second,404,true,"foreign");
        call("PUT",path(role),input("self",0),second,200,true,"second-company");
        assertThat(call("GET",path(role),null,company,200,true,null).get("data").get("scopeType").asString()).isEqualTo("dept");
        call("GET","/purchase-requisitions/"+docA,null,second,404,true,null);
        login("user");call("PUT",path(role),input("all",1),company,403,true,"escalate");
        call("PUT","/users/"+user+"/roles","{\"roleIds\":[\""+adminRole+"\"],\"version\":0}",company,403,true,null);
        call("PUT","/users/"+user+"/organization","{\"departments\":[{\"id\":\""+b+"\",\"primary\":true}],\"postIds\":[],\"version\":0}",company,403,true,"escalate-membership");
        call("POST","/org/departments/"+b+"/move","{\"parentId\":\""+a+"\",\"version\":0}",company,403,true,"escalate-tree");
        call("GET","/purchase-requisitions/"+docA,null,company,200,true,null);
        call("GET","/purchase-requisitions/"+docB,null,company,404,true,null);
        call("POST","/purchase-requisitions/"+docB+"/submit","{\"version\":0}",company,404,true,"write-forbidden");
        call("GET","/print-data/purchase_requisition/"+docA,null,company,200,true,null);
        call("GET","/print-data/purchase_requisition/"+docB,null,company,404,true,null);
    }
    @Test void Y02_07_sameSessionRevocationFiltersListsDetailsAndBrowserCounts() throws Exception {
        jdbc.update("INSERT INTO idempotency_record(id,tenant_id,company_id,operation,idempotency_key,request_hash,operator_id,status,http_status,response_body,expire_at) VALUES(?,?,?,'purchase_requisition.create','old-request',?,?,'succeeded',201,?,DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY))",Ids.next(),tenant,company,"0".repeat(64),user,json.createObjectNode().put("id",Long.toString(docB)).put("documentNo","Q"+docB).toString());
        login("user");call("GET","/purchase-requisitions/"+docB,null,company,200,true,null);
        call("GET","/command-results?operation=purchase_requisition.create&key=old-request",null,company,200,true,null);
        jdbc.update("INSERT INTO org_role_data_scope(tenant_id,company_id,role_id,scope_type) VALUES(?,?,?,'dept')",tenant,company,role);
        call("GET","/purchase-requisitions/"+docB,null,company,404,true,null);
        call("GET","/command-results?operation=purchase_requisition.create&key=old-request",null,company,404,true,null);
        var rows=call("GET","/purchase-requisitions",null,company,200,true,null).get("data").get("items");assertThat(rows.size()).isEqualTo(1);assertThat(rows.get(0).get("id").asString()).isEqualTo(Long.toString(docA));
        var browser=call("GET","/document-browser/purchase-requisitions?withTotal=true",null,company,200,true,null).get("data");assertThat(browser.get("total").asLong()).isEqualTo(1);
    }
    @Test void Y02_08_lastUnrestrictedAdministratorIsPreserved() throws Exception {
        call("PUT",path(role),input("dept",0),company,200,true,"restrict-user");
        call("PUT",path(adminRole),input("self",0),company,422,true,"restrict-last-admin");
        call("PUT","/users/"+admin+"/roles","{\"roleIds\":[],\"version\":0}",company,422,true,null);
        assertThat(call("GET",path(adminRole),null,company,200,true,null).get("data").get("scopeType").asString()).isEqualTo("all");
    }
    @Test void Y02_10_attachmentBytesAreWithheldWhenScopeChangesDuringObjectRead() throws Exception {
        call("PUT",path(role),input("dept",0),company,200,true,"restrict-user");
        long attachment=Ids.next();String storageKey=tenant+"/"+company+"/"+attachment;
        byte[] pdf="%PDF-1.7\n部门范围附件\n%%EOF".getBytes(StandardCharsets.UTF_8);
        files.put(storageKey,pdf,"application/pdf");
        jdbc.update("INSERT INTO attachment(id,tenant_id,company_id,owner_type,owner_id,storage_key,file_name,content_type,size_bytes,sha256,created_by) VALUES(?,?,?,'purchase_requisition',?,?,'部门附件.pdf','application/pdf',?,?,?)",attachment,tenant,company,docA,storageKey,pdf.length,"0".repeat(64),user);
        login("user");
        call("GET","/attachments?ownerType=purchase_requisition&ownerId="+docB,null,company,404,true,null);
        var redirect=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/attachments/"+attachment+"/download")).header("X-Company-Id",Long.toString(company)).GET().build(),HttpResponse.BodyHandlers.discarding());
        assertThat(redirect.statusCode()).isEqualTo(302);
        doAnswer(invocation->{
            byte[] bytes=(byte[])invocation.callRealMethod();
            jdbc.update("UPDATE org_role_data_scope SET scope_type='custom',version=version+1 WHERE tenant_id=? AND company_id=? AND role_id=?",tenant,company,role);
            return bytes;
        }).when(files).read(storageKey);
        var content=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+redirect.headers().firstValue("location").orElseThrow())).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(content.statusCode()).as(content.body()).isEqualTo(404);
        assertThat(content.body()).doesNotContain("%PDF");
        call("GET","/attachments/"+attachment+"/download",null,company,404,true,null);
    }
    @Test void Y02_11_crossTenantRoleConfigurationIsRejected() throws Exception {
        long foreignTenant=Ids.next(),foreignRole=Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'范围外企业')",foreignTenant,"Y02-F-"+foreignTenant);
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'FOREIGN','范围外角色')",foreignRole,foreignTenant);
        try {
            call("GET",path(foreignRole),null,company,404,true,null);
            call("PUT",path(foreignRole),input("all",0),company,404,true,"foreign-tenant");
        } finally {
            jdbc.update("DELETE FROM sys_role WHERE id=?",foreignRole);
            jdbc.update("DELETE FROM tenant WHERE id=?",foreignTenant);
        }
    }
    JsonNode call(String method,String path,String body,long scope,int status,boolean csrf,String key) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json").header("X-Company-Id",Long.toString(scope));
        if(csrf) request.header("X-XSRF-TOKEN",cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("XSRF-TOKEN")).findFirst().orElseThrow().getValue());
        if(key!=null) request.header("Idempotency-Key",key);
        var response=client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(method+" "+path+": "+response.body()).isEqualTo(status);return json.readTree(response.body());
    }
}
