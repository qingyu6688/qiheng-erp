package cn.erp.reversal;

import cn.erp.ErpApplication;
import cn.erp.inventory.InventoryModels;
import cn.erp.shared.Ids;
import java.net.*;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ReversalHttpIT extends ReversalTestData {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    private final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
    private final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();
    @Test void reversalAndHistoricResultRespectCurrentSourcePermissionWarehouseAndCompany() throws Exception {
        long source=stock("5","50");
        assertThat(http("GET","/document-reversals",null,null,false,company).statusCode()).isEqualTo(401);
        login("operator");String body=body("stock_adjustment",source),key=key();
        assertThat(http("POST","/document-reversals",body,key,false,company).statusCode()).isEqualTo(403);
        var created=http("POST","/document-reversals",body,key,true,company);assertThat(created.statusCode()).isEqualTo(201);
        String id=json.readTree(created.body()).path("data").path("id").asText(),result="/command-results?operation=reversal.create&key="+key;
        assertThat(created.body()).doesNotContain("inputTotalCost","unitCost","costAmount");
        assertThat(http("GET",result,null,null,false,company).statusCode()).isEqualTo(200);
        assertThat(http("GET","/document-reversals/"+id,null,null,false,otherCompany).statusCode()).isEqualTo(404);
        assertThat(http("GET","/document-reversals?limit=0",null,null,false,company).statusCode()).isEqualTo(400);
        jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM sys_permission WHERE code='stock_adjustment:view')",tenant,role);
        assertThat(http("GET","/document-reversals/"+id,null,null,false,company).statusCode()).isEqualTo(403);
        assertThat(http("GET",result,null,null,false,company).statusCode()).isEqualTo(403);
        jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT ?,?,?,id FROM sys_permission WHERE code='stock_adjustment:view'",Ids.next(),tenant,role);
        jdbc.update("DELETE FROM sys_user_data_scope WHERE tenant_id=? AND user_id=? AND company_id=? AND warehouse_id=?",tenant,user,company,warehouse);
        assertThat(http("GET","/document-reversals/"+id,null,null,false,company).statusCode()).isEqualTo(404);
        assertThat(http("GET",result,null,null,false,company).statusCode()).isEqualTo(404);
        assertThat(http("POST","/document-reversals",body,key,true,company).statusCode()).isEqualTo(404);
        assertThat(json.readTree(http("GET","/document-reversals",null,null,false,company).body()).path("data").path("items").size()).isZero();
        assertThat(inventory.balance(maker,new InventoryModels.Key(warehouse,sku)).onHand()).isEqualByComparingTo("5");
    }
    @Test void reviewerAndOriginalOperatorAreCheckedForHttpPostingAndReplay() throws Exception {
        long source=receipt("customer");login("operator");
        var created=http("POST","/document-reversals",body("receipt",source),key(),true,company);assertThat(created.statusCode()).isEqualTo(201);
        String id=json.readTree(created.body()).path("data").path("id").asText(),url="/document-reversals/"+id;
        assertThat(http("POST",url+"/post","{\"version\":0}",key(),true,company).statusCode()).isEqualTo(409);
        assertThat(http("POST",url+"/approve","{\"version\":0}",key(),true,company).statusCode()).isEqualTo(422);
        login("reviewer");assertThat(http("POST",url+"/approve","{\"version\":0}",key(),true,company).statusCode()).isEqualTo(200);
        String postKey=key();assertThat(http("POST",url+"/post","{\"version\":1}",postKey,true,company).statusCode()).isEqualTo(200);
        assertThat(http("POST",url+"/post","{\"version\":1}",postKey,true,company).statusCode()).isEqualTo(200);
        assertThat(http("POST",url+"/post","{\"version\":2}",key(),true,company).statusCode()).isEqualTo(409);
        login("operator");assertThat(http("GET","/command-results?operation=reversal.post&key="+postKey,null,null,false,company).statusCode()).isEqualTo(403);
        assertThat(value("SELECT balance FROM cash_account_balance WHERE cash_account_id=?",account)).isEqualByComparingTo("0");zero();
    }
    private String body(String type,long source){return "{\"sourceType\":\""+type+"\",\"sourceId\":\""+source+"\",\"businessDate\":\"2026-11-01\",\"reason\":\"冲销验收\"}";}
    private void login(String username) throws Exception {
        cookies.getCookieStore().removeAll();var response=http("POST","/auth/login","{\"tenantCode\":\"REV"+tenant+"\",\"username\":\""+username+"\",\"password\":\""+PASSWORD+"\"}",null,false,company);assertThat(response.statusCode()).isEqualTo(200);
    }
    private HttpResponse<String> http(String method,String path,String body,String key,boolean csrf,long company) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).header("X-Company-Id",Long.toString(company));
        if(body!=null)builder.header("Content-Type","application/json");if(key!=null)builder.header("Idempotency-Key",key);
        if(csrf)builder.header("X-XSRF-TOKEN",cookies.getCookieStore().getCookies().stream().filter(cookie->cookie.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst().orElseThrow());
        return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
}
