package cn.erp.stocktransfer;

import cn.erp.ErpApplication;
import cn.erp.shared.Ids;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class StockTransferHttpIT extends StockTransferTestData {
    @LocalServerPort int port;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    private long role;
    private static final String PASSWORD="TransferTest!2026";
    private final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
    private final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();
    @BeforeEach void seedIdentity(){
        role=Ids.next();jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,'warehouse','调拨仓管',?)",user,tenant,passwords.encode(PASSWORD));
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'TRANSFER','调拨权限')",role,tenant);
        jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",Ids.next(),tenant,user,role);
        for(long permission:jdbc.queryForList("SELECT id FROM sys_permission WHERE code LIKE 'stock_transfer:%'",Long.class))jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) VALUES(?,?,?,?)",Ids.next(),tenant,role,permission);
        for(long id:List.of(company,otherCompany))jdbc.update("INSERT INTO org_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)",Ids.next(),tenant,user,id);
    }
    @Test void authenticationCsrfCurrentWarehouseScopeAndHistoryCannotBeBypassed() throws Exception {
        assertThat(http("GET","/stock-transfers",null,null,false,company).statusCode()).isEqualTo(401);login();String body=json.writeValueAsString(input("2",null));
        assertThat(http("POST","/stock-transfers",body,key(),false,company).statusCode()).isEqualTo(403);
        String createKey=key();var created=http("POST","/stock-transfers",body,createKey,true,company);assertThat(created.statusCode()).isEqualTo(201);
        String id=json.readTree(created.body()).path("data").path("id").asText();assertThat(json.readTree(created.body()).path("data").path("lines").get(0).has("costAmount")).isFalse();
        assertThat(http("GET","/stock-transfers/"+id,null,null,false,otherCompany).statusCode()).isEqualTo(404);
        String query="/command-results?operation=stock_transfer.create&key="+createKey;assertThat(http("GET",query,null,null,false,company).statusCode()).isEqualTo(200);
        jdbc.update("DELETE FROM org_user_data_scope WHERE tenant_id=? AND user_id=? AND company_id=?",tenant,user,company);
        jdbc.update("INSERT INTO org_user_data_scope(id,tenant_id,user_id,scope_type,company_id,warehouse_id) VALUES(?,?,?,'warehouse',?,?)",Ids.next(),tenant,user,company,from);
        assertThat(http("GET","/stock-transfers/"+id,null,null,false,company).statusCode()).isEqualTo(404);
        assertThat(http("GET",query,null,null,false,company).statusCode()).isEqualTo(404);
        assertThat(http("POST","/stock-transfers",body,createKey,true,company).statusCode()).isEqualTo(404);
    }
    @Test void validShipExposesCostsOnlyWithPermissionAndIllegalTransitionsAre409() throws Exception {
        login();var created=http("POST","/stock-transfers",json.writeValueAsString(input("2",null)),key(),true,company);assertThat(created.statusCode()).isEqualTo(201);String id=json.readTree(created.body()).path("data").path("id").asText();
        assertThat(http("POST","/stock-transfers/"+id+"/ship","{\"version\":0}",key(),true,company).statusCode()).isEqualTo(409);
        assertThat(http("POST","/stock-transfers/"+id+"/submit","{\"version\":0}",key(),true,company).statusCode()).isEqualTo(200);
        var shipped=http("POST","/stock-transfers/"+id+"/ship","{\"version\":1}",key(),true,company);assertThat(shipped.statusCode()).isEqualTo(200);
        assertThat(json.readTree(shipped.body()).path("data").path("events").get(0).has("costAmount")).isFalse();
        jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT ?,?,?,id FROM sys_permission WHERE code='cost:view'",Ids.next(),tenant,role);
        var visible=http("GET","/stock-transfers/"+id,null,null,false,company);assertThat(json.readTree(visible.body()).path("data").path("lines").get(0).path("costAmount").asText()).isEqualTo("0.67");
        assertThat(http("GET","/stock-transfers?limit=0",null,null,false,company).statusCode()).isEqualTo(400);
        jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM sys_permission WHERE code='stock_transfer:view')",tenant,role);
        assertThat(http("GET","/stock-transfers/"+id,null,null,false,company).statusCode()).isEqualTo(403);
    }
    private void login() throws Exception {assertThat(http("POST","/auth/login","{\"tenantCode\":\"TRANSFER"+tenant+"\",\"username\":\"warehouse\",\"password\":\""+PASSWORD+"\"}",null,false,company).statusCode()).isEqualTo(200);}
    private HttpResponse<String> http(String method,String path,String body,String key,boolean csrf,long company) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).header("X-Company-Id",Long.toString(company));if(body!=null)request.header("Content-Type","application/json");if(key!=null)request.header("Idempotency-Key",key);
        if(csrf)request.header("X-XSRF-TOKEN",cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst().orElseThrow());
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
}
